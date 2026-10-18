package tr.h2open.driver

import android.app.Application
import android.os.Build
import tr.h2open.driver.api.H2oApi
import tr.h2open.driver.api.UnauthorizedException
import tr.h2open.driver.data.Session
import tr.h2open.driver.data.SessionStore

class H2oApplication : Application() {
    lateinit var sessions: SessionStore
        private set

    private var cachedApi: Pair<String, H2oApi>? = null

    override fun onCreate() {
        super.onCreate()
        sessions = SessionStore(this)
    }

    /** API client for the signed-in server. */
    fun api(): H2oApi {
        val url = sessions.session.value?.serverUrl ?: sessions.lastServerUrl
        return apiFor(url)
    }

    fun apiFor(serverUrl: String): H2oApi {
        cachedApi?.let { (url, api) -> if (url == serverUrl) return api }
        return H2oApi(serverUrl, { sessions.session.value?.token }).also { cachedApi = serverUrl to it }
    }

    suspend fun signIn(serverUrl: String, login: String, password: String): Session {
        val url = H2oApi.normalizeBaseUrl(serverUrl)
        val api = H2oApi(url, { null })
        val result = api.login(login.trim(), password, deviceName)
        if (!result.user.isDriver) {
            // Leave the fresh token unused; it expires on its own.
            throw NotADriverException()
        }
        return Session(url, result.token, result.user).also(sessions::save)
    }

    /** Signs out on the server too when it can be reached; the local session goes either way. */
    suspend fun signOut() {
        runCatching { api().logout() }
        sessions.clear()
    }

    /** Called when the server rejects the token: back to the login screen. */
    fun onUnauthorized(error: Throwable) {
        if (error is UnauthorizedException) sessions.clear()
    }

    val deviceName: String get() = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
}

class NotADriverException : Exception("This account is not a driver")
