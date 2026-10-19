package tr.h2open.driver

import android.app.Application
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import tr.h2open.driver.api.H2oApi
import tr.h2open.driver.api.UnauthorizedException
import tr.h2open.driver.data.Connectivity
import tr.h2open.driver.data.CrashReporter
import tr.h2open.driver.data.ErrorLog
import tr.h2open.driver.data.Session
import tr.h2open.driver.data.SessionStore
import tr.h2open.driver.push.OrderNotifications
import tr.h2open.driver.push.Push
import tr.h2open.driver.voip.VoipManager

class H2oApplication : Application() {
    lateinit var sessions: SessionStore
        private set

    private var cachedApi: Pair<String, H2oApi>? = null
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Whether the phone is online; the app shows a bar and disables its actions when it is not. */
    lateinit var connectivity: Connectivity
        private set

    /** Fires when the phone gets back online: screens reload what may have changed meanwhile. */
    val backOnlineEvents: Flow<Unit> get() = connectivity.online.drop(1).filter { it }.map { }

    /** Calls over the office PBX. */
    val voip: VoipManager by lazy { VoipManager(this) }

    private val ordersChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Fires when a push says an order was placed or taken: the Orders tab reloads. */
    val ordersChangedEvents: SharedFlow<Unit> = ordersChanged.asSharedFlow()

    fun onOrdersChanged() {
        ordersChanged.tryEmit(Unit)
    }

    private val tripChanged = MutableSharedFlow<Long>(extraBufferCapacity = 1)

    /** Fires with the trip id when the office added or removed a stop on a trip in progress: the trip screens reload. */
    val tripChangedEvents: SharedFlow<Long> = tripChanged.asSharedFlow()

    fun onTripChanged(tripId: Long) {
        tripChanged.tryEmit(tripId)
    }

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        sessions = SessionStore(this)
        connectivity = Connectivity(this, appScope)
        Push.init(this)
        OrderNotifications.createChannel(this)
        // The token may have changed while the app was not running.
        if (sessions.session.value != null) registerPush()
        // Crash reports saved on the phone go to Odoo whenever the app is signed in and online.
        appScope.launch {
            combine(connectivity.online, sessions.session) { online, session -> online && session != null }
                .distinctUntilChanged()
                .filter { it }
                .collect { CrashReporter.sendSaved(this@H2oApplication, api()) }
        }
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
            runCatching { api.logout() }.onFailure { ErrorLog.log("Sign out (not a driver)", it) }
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
                .onFailure { ErrorLog.log("Push: register device", it) }
        }
    }

    /** Signs out on the server too when it can be reached; the local session goes either way. */
    suspend fun signOut() {
        voip.shutdown()
        runCatching { api().logout() }.onFailure { ErrorLog.log("Sign out", it) }
        sessions.clear()
    }

    /** Logs a failed [what] (see [ErrorLog]) and signs out when the server no longer knows the session. */
    fun onError(what: String, error: Throwable) {
        ErrorLog.log(what, error)
        onUnauthorized(error)
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
