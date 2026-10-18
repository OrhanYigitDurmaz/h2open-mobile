package tr.h2open.driver.api

import kotlinx.coroutines.suspendCancellableCoroutine
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
 * 401 signed out, 403 not a driver, 404 someone else's record, 422 wrong state.
 */
open class ApiException(val status: Int, message: String) : IOException(message)

/** The token is missing, expired or revoked: the driver has to sign in again. */
class UnauthorizedException(message: String) : ApiException(401, message)

/**
 * Client for the H2Open app API (`/h2o/api/v1/...`). Bodies are plain JSON
 * (Odoo `json2` routes); every route but [login] needs the bearer token.
 */
class H2oApi(
    baseUrl: String,
    private val token: () -> String?,
    client: OkHttpClient = defaultClient(),
) {
    private val base: HttpUrl = normalizeBaseUrl(baseUrl).toHttpUrl()
    private val http = client

    // ------------------------------------------------------------------
    // Session (h2o_base)
    // ------------------------------------------------------------------

    suspend fun login(login: String, password: String, deviceName: String): LoginResponse =
        post("login", LoginRequest(login, password, deviceName), LoginRequest.serializer(), LoginResponse.serializer(), auth = false)

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
    // Plumbing
    // ------------------------------------------------------------------

    private suspend fun <T> get(path: String, out: KSerializer<T>, query: Map<String, String> = emptyMap()): T {
        val url = url(path).newBuilder().apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        return execute(Request.Builder().url(url).get(), out, auth = true)
    }

    private suspend fun <T> postEmpty(path: String, out: KSerializer<T>): T =
        post(path, JsonObject(emptyMap()), JsonObject.serializer(), out)

    private suspend fun <B, T> post(path: String, body: B, inSer: KSerializer<B>, out: KSerializer<T>, auth: Boolean = true): T {
        val json = JSON.encodeToString(inSer, body).toRequestBody(JSON_MEDIA)
        return execute(Request.Builder().url(url(path)).post(json), out, auth)
    }

    private fun url(path: String): HttpUrl = base.resolve("h2o/api/v1/$path")!!

    private suspend fun <T> execute(builder: Request.Builder, out: KSerializer<T>, auth: Boolean): T {
        builder.header("Accept", "application/json")
        if (auth) {
            val bearer = token() ?: throw UnauthorizedException("Not signed in")
            builder.header("Authorization", "Bearer $bearer")
        }
        val response = http.newCall(builder.build()).await()
        val text = response.use { it.body?.string().orEmpty() }
        if (!response.isSuccessful) {
            val message = errorMessage(text) ?: response.message.ifBlank { "HTTP ${response.code}" }
            throw if (response.code == 401) UnauthorizedException(message) else ApiException(response.code, message)
        }
        return JSON.decodeFromString(out, text)
    }

    companion object {
        val JSON = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            coerceInputValues = true
        }
        private val JSON_MEDIA = "application/json".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS) // signatures and photos
            .build()

        /**
         * `odoo.example.com` → `https://odoo.example.com/`. Without a scheme, a
         * local address (`192.168.12.1:8069`, `localhost`, `*.local`) gets
         * `http://`: a development Odoo on the LAN has no certificate.
         */
        fun normalizeBaseUrl(raw: String): String {
            var url = raw.trim()
            val lower = url.lowercase()
            url = when {
                lower.startsWith("http://") || lower.startsWith("https://") -> url
                isLocalHost(lower.substringBefore('/').substringBefore(':')) -> "http://$url"
                else -> "https://$url"
            }
            return if (url.endsWith("/")) url else "$url/"
        }

        private fun isLocalHost(host: String): Boolean {
            if (host == "localhost" || host.endsWith(".local")) return true
            val parts = host.split('.').map { it.toIntOrNull() ?: return false }
            if (parts.size != 4 || parts.any { it !in 0..255 }) return false
            val (a, b) = parts
            return a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b in 16..31) ||
                (a == 100 && b in 64..127) // Tailscale / CGNAT
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

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = cont.resume(response)
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
