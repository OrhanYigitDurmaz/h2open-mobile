package tr.h2open.driver.api

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * An error answer from Odoo. [status] follows h2o_driver: 400 bad input,
 * 401 signed out, 403 not a driver, 404 someone else's record, 409 order already taken, 422 wrong state.
 */
open class ApiException(val status: Int, message: String) : IOException(message)

/** The token is missing, expired or revoked: the driver has to sign in again. */
class UnauthorizedException(message: String) : ApiException(401, message)

/**
 * Client for the H2Open app API (`/h2o/api/v1/...`). Bodies are plain JSON
 * (Odoo `json2` routes); every route but [login] needs the bearer token.
 *
 * Tokens live in [tokens]. The access token is refreshed shortly before it
 * expires, and once more when a route answers 401; when the refresh itself is
 * refused, [tokens] is cleared and [UnauthorizedException] tells the app to
 * show the login screen. Only one refresh runs at a time: the server treats a
 * second use of a rotated refresh token as theft.
 */
class H2oApi(
    baseUrl: String,
    private val tokens: TokenStore,
    client: OkHttpClient = defaultClient(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val base: HttpUrl = normalizeBaseUrl(baseUrl).toHttpUrl()
    private val http = client
    private val refreshLock = Mutex()

    // ------------------------------------------------------------------
    // Session (h2o_base)
    // ------------------------------------------------------------------

    /** Signs in and saves the new tokens in [tokens]. */
    suspend fun login(login: String, password: String, deviceName: String): UserProfile {
        val body = JSON.encodeToString(LoginRequest.serializer(), LoginRequest(login, password, deviceName))
        val text = send(Request.Builder().url(url("login")).post(body.toRequestBody(JSON_MEDIA)), bearer = null)
        val user = JSON.decodeFromString(LoginResponse.serializer(), text).user
        tokens.save(JSON.decodeFromString(TokenResponse.serializer(), text).toTokens(clock()))
        return user
    }

    /** Ends this device's session on the server. [tokens] is left for the caller to clear. */
    suspend fun logout() {
        post("logout", JsonObject(emptyMap()), JsonObject.serializer(), JsonObject.serializer())
    }

    suspend fun me(): UserProfile = get("me", UserProfile.serializer())

    // ------------------------------------------------------------------
    // Trips and stops (h2o_driver)
    // ------------------------------------------------------------------

    /** Trips of the signed-in driver on [date] (`YYYY-MM-DD`), today when null. */
    suspend fun trips(date: String? = null): List<Trip> =
        get("driver/trips", ListSerializer(Trip.serializer()), query = date?.let { mapOf("date" to it) } ?: emptyMap())

    suspend fun trip(tripId: Long): Trip = get("driver/trips/$tripId", Trip.serializer())

    suspend fun startTrip(tripId: Long): Trip = postEmpty("driver/trips/$tripId/start", Trip.serializer())

    suspend fun closeTrip(tripId: Long): Trip = postEmpty("driver/trips/$tripId/close", Trip.serializer())

    suspend fun deliver(stopId: Long, request: DeliverRequest): Stop =
        post("driver/stops/$stopId/deliver", request, DeliverRequest.serializer(), Stop.serializer())

    suspend fun fail(stopId: Long, reason: String, note: String? = null): Stop =
        post("driver/stops/$stopId/fail", FailRequest(reason, note), FailRequest.serializer(), Stop.serializer())

    suspend fun skip(stopId: Long): Stop = postEmpty("driver/stops/$stopId/skip", Stop.serializer())

    suspend fun reorder(tripId: Long, stopIds: List<Long>): Trip =
        post("driver/stops/reorder", ReorderRequest(tripId, stopIds), ReorderRequest.serializer(), Trip.serializer())

    /** Orders waiting for a driver, by delivery date, the driver's own zones first. */
    suspend fun openOrders(): List<OpenOrder> = get("driver/orders", ListSerializer(OpenOrder.serializer()))

    /**
     * Takes an open order: it becomes a stop on the driver's trip of today.
     * Throws [ApiException] 409 when another driver took it first.
     */
    suspend fun claimOrder(orderId: Long): ClaimResult = postEmpty("driver/orders/$orderId/claim", ClaimResult.serializer())

    /** Gives back an order the driver took ([Stop.claimed]); it is open again for every driver. */
    suspend fun releaseStop(stopId: Long): Trip = postEmpty("driver/stops/$stopId/release", Trip.serializer())

    suspend fun position(tripId: Long, latitude: Double, longitude: Double) {
        post("driver/position", PositionRequest(latitude, longitude, tripId), PositionRequest.serializer(), JsonObject.serializer())
    }

    // ------------------------------------------------------------------
    // Push (h2o_push)
    // ------------------------------------------------------------------

    suspend fun registerPush(fcmToken: String, deviceName: String) {
        post("push/register", PushRegisterRequest(fcmToken, deviceName = deviceName),
            PushRegisterRequest.serializer(), JsonObject.serializer())
    }

    suspend fun unregisterPush(fcmToken: String) {
        post("push/unregister", PushUnregisterRequest(fcmToken), PushUnregisterRequest.serializer(), JsonObject.serializer())
    }

    // ------------------------------------------------------------------
    // Telephony (h2o_telephony)
    // ------------------------------------------------------------------

    suspend fun sipCredentials(): SipCredentials = postEmpty("sip/credentials", SipCredentials.serializer())

    // ------------------------------------------------------------------
    // Plumbing
    // ------------------------------------------------------------------

    private suspend fun <T> get(path: String, out: KSerializer<T>, query: Map<String, String> = emptyMap()): T {
        val url = url(path).newBuilder().apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        return execute(Request.Builder().url(url).get(), out)
    }

    private suspend fun <T> postEmpty(path: String, out: KSerializer<T>): T =
        post(path, JsonObject(emptyMap()), JsonObject.serializer(), out)

    private suspend fun <B, T> post(path: String, body: B, inSer: KSerializer<B>, out: KSerializer<T>): T {
        val json = JSON.encodeToString(inSer, body).toRequestBody(JSON_MEDIA)
        return execute(Request.Builder().url(url(path)).post(json), out)
    }

    private fun url(path: String): HttpUrl = base.resolve("h2o/api/v1/$path")!!

    /** An authenticated call: refreshes ahead of expiry, and once more on a 401. */
    private suspend fun <T> execute(builder: Request.Builder, out: KSerializer<T>): T {
        var current = tokens.load() ?: throw UnauthorizedException("Not signed in")
        val expiresAt = current.accessExpiresAtMillis
        if (expiresAt != null && expiresAt - clock() < EARLY_REFRESH_MILLIS) {
            current = refresh(current.accessToken)
        }
        val text = try {
            send(builder, current.accessToken)
        } catch (e: UnauthorizedException) {
            // Revoked or expired early (server restart, clock): one refresh, one retry.
            send(builder, refresh(current.accessToken).accessToken)
        }
        return JSON.decodeFromString(out, text)
    }

    /**
     * Swaps the refresh token for a new pair, unless another call already did
     * since [staleAccess] was read. Clears [tokens] when the server refuses.
     */
    private suspend fun refresh(staleAccess: String): Tokens = refreshLock.withLock {
        val current = tokens.load() ?: throw UnauthorizedException("Not signed in")
        if (current.accessToken != staleAccess) return@withLock current
        val refreshToken = current.refreshToken ?: run {
            tokens.clear()
            throw UnauthorizedException("Session expired")
        }
        val body = JSON.encodeToString(RefreshRequest.serializer(), RefreshRequest(refreshToken))
        val text = try {
            send(Request.Builder().url(url("token/refresh")).post(body.toRequestBody(JSON_MEDIA)), bearer = null)
        } catch (e: UnauthorizedException) {
            tokens.clear()
            throw e
        }
        JSON.decodeFromString(TokenResponse.serializer(), text).toTokens(clock()).also(tokens::save)
    }

    private suspend fun send(builder: Request.Builder, bearer: String?): String {
        builder.header("Accept", "application/json")
        if (bearer != null) builder.header("Authorization", "Bearer $bearer")
        val response = http.newCall(builder.build()).await()
        val text = response.body
        if (!response.isSuccessful) {
            val message = errorMessage(text) ?: response.message.ifBlank { "HTTP ${response.code}" }
            throw if (response.code == 401) UnauthorizedException(message) else ApiException(response.code, message)
        }
        return text
    }

    companion object {
        val JSON = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            coerceInputValues = true
        }
        private val JSON_MEDIA = "application/json".toMediaType()
        /** Refresh this long before the access token runs out, so a call never starts with a dying token. */
        private const val EARLY_REFRESH_MILLIS = 5 * 60 * 1000L

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS) // signatures and photos
            .build()

        /** `odoo.example.com` → `https://odoo.example.com/`; an explicit `http://` is kept. */
        fun normalizeBaseUrl(raw: String): String {
            var url = raw.trim()
            val lower = url.lowercase()
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) url = "https://$url"
            return if (url.endsWith("/")) url else "$url/"
        }

        /** Odoo's json2 errors carry a `message`; werkzeug errors may be HTML or plain text. */
        internal fun errorMessage(body: String): String? {
            if (body.isBlank()) return null
            val obj = runCatching { JSON.parseToJsonElement(body) as? JsonObject }.getOrNull()
            if (obj != null) {
                val message = (obj["message"] as? JsonPrimitive)?.contentOrNull
                    ?: ((obj["error"] as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull
                    ?: (obj["description"] as? JsonPrimitive)?.contentOrNull
                return message?.let(::stripWerkzeugPrefix)
            }
            if (body.trimStart().startsWith("<")) {
                // werkzeug HTML page: the description is in the first <p>.
                return Regex("<p>(.*?)</p>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)?.trim()
            }
            return body.trim().take(300)
        }

        /** "422 Unprocessable Entity: Trip T001 can't be closed" → "Trip T001 can't be closed". */
        private fun stripWerkzeugPrefix(message: String): String =
            message.replace(Regex("^\\d{3} [A-Za-z ]+: "), "")
    }
}

/** A finished response with its body already read. */
private class HttpResult(val code: Int, val message: String, val body: String) {
    val isSuccessful get() = code in 200..299
}

/**
 * Runs the call and reads the whole body on OkHttp's thread: the caller is
 * often the main thread, where Android forbids the socket reads a body
 * larger than the first packet needs.
 */
private suspend fun Call.await(): HttpResult = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            val result = try {
                response.use { HttpResult(it.code, it.message, it.body.string()) }
            } catch (e: IOException) {
                return onFailure(call, e)
            }
            cont.resume(result)
        }
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
