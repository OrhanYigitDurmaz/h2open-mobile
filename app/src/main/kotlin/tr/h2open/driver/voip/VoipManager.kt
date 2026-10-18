package tr.h2open.driver.voip

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import tr.h2open.driver.H2oApplication
import tr.h2open.driver.api.ApiException
import tr.h2open.driver.sip.CallState
import tr.h2open.driver.sip.EndReason
import tr.h2open.driver.sip.RegistrationState
import tr.h2open.driver.sip.SipAccount
import tr.h2open.driver.sip.SipCall
import tr.h2open.driver.sip.SipUserAgent
import tr.h2open.driver.sip.WebSocketSipTransport
import java.io.IOException

enum class CallPhase { Connecting, Ringing, Incoming, Active, Ended }

/** Why a call could not be made, beyond what SIP says. */
enum class CallFailure { NotSetUp, Network, Other }

/** What the call screen and the notification show. */
data class CallInfo(
    val number: String,
    val name: String?,
    val incoming: Boolean,
    val phase: CallPhase,
    val connectedAtMillis: Long? = null,
    val muted: Boolean = false,
    val route: AudioRoute = AudioRoute.Earpiece,
    val routes: List<AudioRoute> = emptyList(),
    val endReason: EndReason? = null,
    val failure: CallFailure? = null,
) {
    val isLive: Boolean get() = phase != CallPhase.Ended
}

/** A push said the PBX is about to ring us: we are registering for it. */
data class Wake(val caller: String, val callerName: String?)

class VoipUnavailableException : Exception("Telephony is not configured on the server")

/**
 * Calls over the office PBX: gets short-lived SIP credentials from Odoo,
 * registers only while a call or a push wake-up needs it, and pairs each
 * SIP call with a WebRTC audio session. One call at a time.
 */
class VoipManager(private val app: H2oApplication) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Mutex()
    private val media by lazy { WebRtcMedia(app) }
    private val audio by lazy { CallAudio(app) }
    private val http = OkHttpClient()

    private var agent: SipUserAgent? = null
    private var credentialsExpireAt = 0L
    private var incomingJob: Job? = null
    private var idleJob: Job? = null

    private var sipCall: SipCall? = null
    private var session: MediaSession? = null
    private var callJob: Job? = null

    private val _call = MutableStateFlow<CallInfo?>(null)
    val call: StateFlow<CallInfo?> = _call.asStateFlow()

    private val _wake = MutableStateFlow<Wake?>(null)
    val wake: StateFlow<Wake?> = _wake.asStateFlow()

    private val busy: Boolean get() = sipCall != null || _call.value?.isLive == true

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    /** Calls [number] through the PBX. */
    fun startCall(number: String, name: String?) {
        if (busy) return
        val target = number.filter { it.isDigit() || it == '+' }
        if (target.isEmpty()) return
        _call.value = CallInfo(target, name, incoming = false, phase = CallPhase.Connecting)
        CallService.start(app)
        callJob = scope.launch {
            try {
                val ua = registered()
                val media = media.newSession().also { session = it }
                val offer = media.createOffer()
                val call = ua.call(target, offer)
                sipCall = call
                follow(call)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Call to $target failed", e)
                end(EndReason.Failed, failureOf(e))
            }
        }
    }

    fun answer() {
        val call = sipCall ?: return
        val offer = (call.state.value as? CallState.Incoming)?.remoteSdp ?: return
        update { copy(phase = CallPhase.Connecting) }
        scope.launch {
            try {
                val media = media.newSession().also { session = it }
                call.answer(media.createAnswer(offer))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Answering failed", e)
                end(EndReason.Failed, CallFailure.Other)
            }
        }
    }

    /** Hangs up, cancels or declines. */
    fun hangup() {
        val call = sipCall
        if (call == null) {
            if (_call.value?.isLive == true) end(EndReason.Local)
            return
        }
        scope.launch { runCatching { call.hangup() } }
    }

    fun toggleMute() {
        val media = session ?: return
        media.muted = !media.muted
        update { copy(muted = media.muted) }
    }

    fun selectRoute(route: AudioRoute) {
        audio.select(route)
        update { copy(route = audio.route, routes = audio.available()) }
    }

    /** A push said the PBX wants to ring this phone: register so it can. */
    fun onWake(caller: String, callerName: String?) {
        if (busy) return
        val wake = Wake(caller, callerName?.ifBlank { null })
        _wake.value = wake
        CallService.start(app)
        scope.launch {
            try {
                registered()
                // The INVITE follows the registration; onIncoming clears the wake.
                withTimeoutOrNull(WAKE_TIMEOUT) { _wake.first { it !== wake } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not register for an incoming call", e)
            }
            if (_wake.value === wake) _wake.value = null
            scheduleIdle()
        }
    }

    /** The caller hung up (or someone else answered) before the INVITE reached us. */
    fun onWakeCancelled() {
        _wake.value = null
        scheduleIdle()
    }

    /** Signing out: end any call and drop the registration. */
    fun shutdown() {
        _wake.value = null
        if (_call.value?.isLive == true) end(EndReason.Local)
        _call.value = null
        scope.launch {
            lock.withLock {
                agent?.let { runCatching { it.close() } }
                agent = null
            }
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private suspend fun registered(): SipUserAgent = lock.withLock {
        idleJob?.cancel()
        val current = agent
        if (current != null && current.registration.value is RegistrationState.Registered &&
            (sipCall != null || System.currentTimeMillis() < credentialsExpireAt - CREDENTIALS_MARGIN)
        ) {
            return current
        }
        agent = null
        current?.let { runCatching { it.close() } }

        val credentials = app.api().sipCredentials()
        val url = credentials.wssUrl?.ifBlank { null } ?: throw VoipUnavailableException()
        val domain = credentials.domain?.ifBlank { null } ?: throw VoipUnavailableException()
        val account = SipAccount(
            credentials.username, credentials.password, domain,
            displayName = app.sessions.session.value?.user?.name,
        )
        val ua = SipUserAgent(account, WebSocketSipTransport(url, http), scope, userAgentName = "H2Open Driver Android")
        try {
            ua.register()
        } catch (e: Exception) {
            runCatching { ua.close() }
            throw e
        }
        credentialsExpireAt = credentials.expiresAtMillis ?: (System.currentTimeMillis() + 60 * 60_000L)
        agent = ua
        incomingJob?.cancel()
        incomingJob = scope.launch { ua.incomingCalls.collect { onIncoming(it) } }
        ua
    }

    private fun onIncoming(call: SipCall) {
        if (busy) {
            scope.launch { runCatching { call.reject(486, "Busy Here") } }
            return
        }
        val wake = _wake.value
        _wake.value = null
        sipCall = call
        _call.value = CallInfo(
            number = call.remoteUser,
            name = call.remoteDisplayName ?: wake?.callerName,
            incoming = true,
            phase = CallPhase.Incoming,
        )
        CallService.start(app)
        callJob = scope.launch { follow(call) }
    }

    /** Mirrors the SIP call into [call] and the audio session until it ends. */
    private suspend fun follow(call: SipCall) {
        var ended: CallState.Ended? = null
        try {
            call.state.first { state ->
                when (state) {
                    CallState.Calling, is CallState.Incoming -> Unit
                    is CallState.Ringing -> {
                        state.earlyMediaSdp?.let {
                            session?.setRemoteAnswer(it)
                            audio.start()
                        }
                        update { copy(phase = CallPhase.Ringing) }
                    }
                    is CallState.Connected -> {
                        if (call.isOutgoing) session?.setRemoteAnswer(state.remoteSdp)
                        audio.start()
                        update {
                            copy(
                                phase = CallPhase.Active,
                                connectedAtMillis = System.currentTimeMillis(),
                                route = audio.route,
                                routes = audio.available(),
                            )
                        }
                    }
                    is CallState.Ended -> ended = state
                }
                state is CallState.Ended
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Call media failed", e)
            end(EndReason.Failed, CallFailure.Other)
            return
        }
        end(ended?.reason ?: EndReason.Failed)
    }

    private fun end(reason: EndReason, failure: CallFailure? = null) {
        val call = sipCall
        sipCall = null
        session?.close()
        session = null
        audio.stop()
        if (call != null && !call.isEnded) scope.launch { runCatching { call.hangup() } }
        _call.value?.takeIf { it.isLive }?.let { info ->
            _call.value = info.copy(phase = CallPhase.Ended, endReason = reason, failure = failure)
            val endedInfo = _call.value
            scope.launch {
                delay(ENDED_LINGER)
                if (_call.value === endedInfo) _call.value = null
            }
        }
        scheduleIdle()
        val job = callJob
        callJob = null
        job?.cancel()
    }

    private fun scheduleIdle() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_TIMEOUT)
            lock.withLock {
                if (busy || _wake.value != null) return@withLock
                agent?.let { runCatching { it.close() } }
                agent = null
                incomingJob?.cancel()
            }
        }
    }

    private inline fun update(change: CallInfo.() -> CallInfo) {
        _call.value?.takeIf { it.isLive }?.let { _call.value = it.change() }
    }

    private fun failureOf(e: Exception) = when {
        e is VoipUnavailableException -> CallFailure.NotSetUp
        e is ApiException && e.status == 404 -> CallFailure.NotSetUp
        e is IOException -> CallFailure.Network
        else -> CallFailure.Other
    }

    private companion object {
        const val TAG = "H2oVoip"
        const val CREDENTIALS_MARGIN = 10 * 60_000L
        const val WAKE_TIMEOUT = 30_000L
        const val IDLE_TIMEOUT = 60_000L
        const val ENDED_LINGER = 2_000L
    }
}
