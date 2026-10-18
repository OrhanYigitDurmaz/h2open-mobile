package tr.h2open.driver

import android.app.Application
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import tr.h2open.driver.api.H2oApi
import tr.h2open.driver.api.UnauthorizedException
import tr.h2open.driver.data.Session
import tr.h2open.driver.data.SessionStore
import tr.h2open.driver.push.Push
import tr.h2open.driver.voip.VoipManager

class H2oApplication : Application() {
    lateinit var sessions: SessionStore
        private set

    private var cachedApi: Pair<String, H2oApi>? = null
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Calls over the office PBX. */
    val voip: VoipManager by lazy { VoipManager(this) }

    override fun onCreate() {
        super.onCreate()
        sessions = SessionStore(this)
        Push.init(this)
        // The token may have changed while the app was not running.
        if (sessions.session.value != null) registerPush()
    }

    /** API client for the signed-in server. */
    fun api(): H2oApi {
        val url = sessions.session.value?.serverUrl ?: sessions.lastServerUrl
        return apiFor(url)
    }

    @Synchronized
    fun apiFor(serverUrl: String): H2oApi {
        cachedApi?.let { (url, api) -> if (url == serverUrl) return api }
        return H2oApi(serverUrl, sessions).also { cachedApi = serverUrl to it }
    }

    suspend fun signIn(serverUrl: String, login: String, password: String): Session {
        val url = H2oApi.normalizeBaseUrl(serverUrl)
        val api = apiFor(url)
        val user = api.login(login.trim(), password, deviceName)
        if (!user.isDriver) {
            // Not a driver: end the session the login just opened.
            runCatching { api.logout() }
            sessions.clear()
            throw NotADriverException()
        }
        return Session(url, user).also {
            sessions.signedIn(it)
            // A new session has no push device yet (h2o_push ties devices to sessions).
            registerPush()
        }
    }

    /** Tells Odoo where to push incoming calls; [token] when FCM just handed out a new one. */
    /** Without [installationId], asks FCM for it; the answer calls back here with it. */
    fun registerPush(installationId: String? = null) {
        if (!Push.configured) return
        if (installationId == null) {
            if (sessions.session.value != null) Push.register(this)
            return
        }
        appScope.launch {
            if (sessions.session.value == null) return@launch
            runCatching { api().registerPush(installationId, deviceName) }
                .onFailure { Log.w("H2oPush", "push/register failed", it) }
        }
    }

    /** Signs out on the server too when it can be reached; the local session goes either way. */
    suspend fun signOut() {
        voip.shutdown()
        runCatching { api().logout() }
        sessions.clear()
    }

    /** Called when the server rejects the token: back to the login screen. */
    fun onUnauthorized(error: Throwable) {
        if (error is UnauthorizedException) {
            voip.shutdown()
            sessions.clear()
        }
    }

    val deviceName: String get() = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
}

class NotADriverException : Exception("This account is not a driver")
