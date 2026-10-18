package tr.h2open.driver.sip

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/** Who the driver's phone is on the PBX; from `POST /h2o/api/v1/sip/credentials`. */
data class SipAccount(
    val username: String,
    val password: String,
    /** SIP domain (realm host), e.g. `pbx.h2open.com.tr`. */
    val domain: String,
    val displayName: String? = null,
    val authUsername: String = username,
)

sealed interface RegistrationState {
    data object Unregistered : RegistrationState
    data object Registering : RegistrationState
    data class Registered(val expiresSeconds: Int) : RegistrationState
    data class Failed(val reason: String, val code: Int? = null) : RegistrationState
}

class SipException(message: String, val code: Int? = null) : IOException(message)

/**
 * A small SIP user agent for one account over one WebSocket: registration,
 * outgoing and incoming calls with SDP offer/answer, cancel, hang up.
 * Media is someone else's job (WebRTC on Android): the agent only carries
 * the SDP the caller gives it and hands back the far end's.
 *
 * Every state change runs on one coroutine at a time ([loop]), so dialogs
 * and transactions need no locks. Over a reliable transport there are no
 * retransmissions to do (RFC 3261 §17), only timeouts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SipUserAgent(
    private val account: SipAccount,
    private val transport: SipTransport,
    parent: CoroutineScope,
    private val userAgentName: String = "H2Open Driver",
    private val registerExpires: Int = 600,
) {
    internal val loop = Dispatchers.Default.limitedParallelism(1)
    internal val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]) + loop)

    private val _registration = MutableStateFlow<RegistrationState>(RegistrationState.Unregistered)
    val registration: StateFlow<RegistrationState> = _registration.asStateFlow()

    private val _incomingCalls = MutableSharedFlow<SipCall>(extraBufferCapacity = 4)
    /** New calls from the PBX, in [CallState.Incoming]. */
    val incomingCalls: SharedFlow<SipCall> = _incomingCalls.asSharedFlow()

    /** RFC 7118 §5: a made-up host for our Via and Contact. */
    internal val localHost = SipMessage.randomToken(12) + ".invalid"
    internal val contactUri = "sip:${account.username}@$localHost;transport=ws"
    internal val aor = "sip:${account.username}@${account.domain}"
    internal val localIdentity: String
        get() = account.displayName?.let { "\"${it.replace("\"", "")}\" <$aor>" } ?: "<$aor>"

    private val registerCallId = SipMessage.randomToken(20)
    private var registerCSeq = 0
    private var nonceCount = 0
    private var refreshJob: Job? = null
    private var readerJob: Job? = null

    /** Open client transactions by Via branch. */
    private val transactions = HashMap<String, Channel<SipResponse>>()
    /** Calls by Call-ID. */
    internal val calls = HashMap<String, SipCall>()

    // ------------------------------------------------------------------
    // Registration
    // ------------------------------------------------------------------

    /** Connects if needed and registers; keeps refreshing until [unregister] or [close]. */
    suspend fun register() = withContext(loop) {
        ensureConnected()
        _registration.value = RegistrationState.Registering
        try {
            val expires = sendRegister(registerExpires)
            _registration.value = RegistrationState.Registered(expires)
            scheduleRefresh(expires)
        } catch (e: SipException) {
            _registration.value = RegistrationState.Failed(e.message ?: "Registration failed", e.code)
            throw e
        }
    }

    suspend fun unregister() = withContext(loop) {
        refreshJob?.cancel()
        if (transport.connected.value) runCatching { sendRegister(0) }
        _registration.value = RegistrationState.Unregistered
    }

    /** Hangs up every call, unregisters and closes the connection. */
    suspend fun close() {
        withContext(loop) {
            calls.values.toList().forEach { runCatching { it.hangupInternal() } }
            refreshJob?.cancel()
            if (transport.connected.value) runCatching { withTimeoutOrNull(3_000) { sendRegister(0) } }
            _registration.value = RegistrationState.Unregistered
            transport.close()
        }
        scope.cancel()
    }

    private fun scheduleRefresh(expires: Int) {
        refreshJob?.cancel()
        if (expires <= 0) return
        refreshJob = scope.launch {
            delay((expires * 0.85).toLong().coerceAtLeast(5) * 1000)
            runCatching { register() }
        }
    }

    /** Sends REGISTER (answering one digest challenge) and returns the granted expiry. */
    private suspend fun sendRegister(expires: Int): Int {
        val uri = "sip:${account.domain}"
        var authorization: Pair<String, String>? = null
        repeat(2) {
            val request = SipRequest("REGISTER", uri).apply {
                addHeader("Via", via())
                addHeader("Max-Forwards", "70")
                addHeader("From", "$localIdentity;tag=${SipMessage.randomToken()}")
                addHeader("To", "<$aor>")
                addHeader("Call-ID", registerCallId)
                addHeader("CSeq", "${++registerCSeq} REGISTER")
                addHeader("Contact", "<$contactUri>;expires=$expires")
                addHeader("Expires", expires.toString())
                addHeader("Allow", ALLOW)
                addHeader("Supported", "path, outbound")
                addHeader("User-Agent", userAgentName)
                authorization?.let { (name, value) -> addHeader(name, value) }
            }
            val response = start(request).awaitFinal()
            when {
                response.isSuccess -> {
                    val granted = response.headerValues("Contact")
                        .firstOrNull { SipMessage.uriOf(it).contains(localHost) }
                        ?.let { SipMessage.param(it, "expires")?.toIntOrNull() }
                        ?: response.header("Expires")?.trim()?.toIntOrNull()
                        ?: expires
                    return granted
                }
                (response.code == 401 || response.code == 407) && authorization == null -> {
                    authorization = answerChallenge(response, "REGISTER", uri)
                }
                else -> throw SipException("REGISTER: ${response.code} ${response.reason}", response.code)
            }
        }
        throw SipException("REGISTER: credentials refused", 401)
    }

    internal fun answerChallenge(response: SipResponse, method: String, uri: String): Pair<String, String> {
        val proxy = response.code == 407
        val header = response.header(if (proxy) "Proxy-Authenticate" else "WWW-Authenticate")
            ?: throw SipException("$method: ${response.code} without a challenge", response.code)
        val value = DigestAuth.authorization(
            DigestAuth.parseChallenge(header), account.authUsername, account.password, method, uri, ++nonceCount,
        )
        return (if (proxy) "Proxy-Authorization" else "Authorization") to value
    }

    // ------------------------------------------------------------------
    // Calls
    // ------------------------------------------------------------------

    /**
     * Calls [target] (an extension, a number, or a full SIP URI) with
     * [sdpOffer]. Returns at once; follow [SipCall.state].
     */
    suspend fun call(target: String, sdpOffer: String): SipCall = withContext(loop) {
        ensureConnected()
        val uri = if (target.startsWith("sip:")) target else "sip:$target@${account.domain}"
        val call = SipCall.outgoing(this@SipUserAgent, uri, sdpOffer)
        calls[call.callId] = call
        scope.launch { call.runInvite() }
        call
    }

    // ------------------------------------------------------------------
    // Transport and transactions
    // ------------------------------------------------------------------

    private suspend fun ensureConnected() {
        if (transport.connected.value && readerJob?.isActive == true) return
        transport.connect()
        readerJob?.cancel()
        readerJob = scope.launch {
            transport.incoming.collect { text ->
                val message = runCatching { SipMessage.parse(text) }.getOrNull() ?: return@collect
                when (message) {
                    is SipResponse -> onResponse(message)
                    is SipRequest -> onRequest(message)
                }
            }
            onDisconnected()
        }
    }

    private fun onDisconnected() {
        transactions.values.forEach { it.close(SipException("Connection lost")) }
        transactions.clear()
        calls.values.toList().forEach { it.onTransportLost() }
        refreshJob?.cancel()
        if (_registration.value is RegistrationState.Registered || _registration.value is RegistrationState.Registering) {
            _registration.value = RegistrationState.Failed("Connection lost")
        }
    }

    internal fun via(branch: String = SipMessage.newBranch()) =
        "SIP/2.0/${transport.viaTransport} $localHost;branch=$branch;rport"

    internal fun send(message: SipMessage) {
        if (!transport.send(message.encode())) throw SipException("Not connected")
    }

    /** A client transaction, keyed by Via branch and method (a CANCEL shares its INVITE's branch). */
    internal class Pending(val key: String, val responses: Channel<SipResponse>)

    /** Sends [request] and returns its responses' channel (closed after the final one). */
    internal fun start(request: SipRequest): Pending {
        val key = "${request.branch ?: error("request without Via branch")} ${request.method}"
        val channel = Channel<SipResponse>(Channel.UNLIMITED)
        transactions[key] = channel
        try {
            send(request)
        } catch (e: SipException) {
            transactions.remove(key)
            throw e
        }
        return Pending(key, channel)
    }

    /** Waits for the final response, ignoring provisional ones. */
    internal suspend fun Pending.awaitFinal(timeoutMillis: Long = TIMER_F): SipResponse = try {
        withTimeout(timeoutMillis) {
            var response: SipResponse
            do {
                response = responses.receive()
            } while (response.isProvisional)
            response
        }
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        transactions.remove(key)
        throw SipException("No answer from the PBX", 408)
    } catch (e: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
        throw SipException("Connection lost")
    }

    internal fun endTransaction(pending: Pending) {
        transactions.remove(pending.key)?.close()
    }

    private fun onResponse(response: SipResponse) {
        val key = "${response.branch ?: return} ${response.cseqMethod}"
        val channel = transactions[key] ?: run {
            // A 2xx retransmission for an INVITE we already acknowledged: ACK again.
            if (response.isSuccess && response.cseqMethod == "INVITE") calls[response.callId]?.reAck(response)
            return
        }
        channel.trySend(response)
        if (!response.isProvisional && response.cseqMethod != "INVITE") {
            transactions.remove(key)
            channel.close()
        }
    }

    private suspend fun onRequest(request: SipRequest) {
        val call = calls[request.callId]
        when (request.method) {
            "INVITE" -> if (call == null || request.toTag == null) {
                if (call != null) return // retransmitted initial INVITE
                val incoming = SipCall.incoming(this, request)
                calls[incoming.callId] = incoming
                respond(request, 100, "Trying")
                incoming.ringing()
                _incomingCalls.emit(incoming)
            } else {
                call.onReInvite(request)
            }
            "ACK" -> call?.onAck(request)
            "BYE" -> {
                respond(request, if (call != null) 200 else 481, if (call != null) "OK" else "Call/Transaction Does Not Exist")
                call?.onRemoteBye()
            }
            "CANCEL" -> {
                respond(request, if (call != null) 200 else 481, if (call != null) "OK" else "Call/Transaction Does Not Exist")
                call?.onRemoteCancel()
            }
            "OPTIONS" -> respond(request, 200, "OK") {
                addHeader("Allow", ALLOW)
                addHeader("Accept", "application/sdp")
            }
            "NOTIFY", "INFO", "MESSAGE", "UPDATE" -> respond(request, 200, "OK")
            else -> respond(request, 405, "Method Not Allowed") { addHeader("Allow", ALLOW) }
        }
    }

    /** A response to [request] with its Via, From, To, Call-ID and CSeq. */
    internal fun response(request: SipRequest, code: Int, reason: String, toTag: String? = null): SipResponse =
        SipResponse(code, reason).apply {
            request.headers.filter { it.first.equals("Via", true) || it.first.equals("v", true) }.forEach { addHeader("Via", it.second) }
            request.header("Record-Route")?.let { if (code in 101..299) request.headerValues("Record-Route").forEach { rr -> addHeader("Record-Route", rr) } }
            addHeader("From", request.header("From").orEmpty())
            val to = request.header("To").orEmpty()
            addHeader("To", if (toTag != null && SipMessage.tagOf(to) == null) "$to;tag=$toTag" else to)
            addHeader("Call-ID", request.callId)
            addHeader("CSeq", request.header("CSeq").orEmpty())
            addHeader("User-Agent", userAgentName)
        }

    internal fun respond(request: SipRequest, code: Int, reason: String, toTag: String? = null, extra: SipResponse.() -> Unit = {}) {
        runCatching { send(response(request, code, reason, toTag).apply(extra)) }
    }

    internal fun forget(call: SipCall) {
        calls.remove(call.callId)
    }

    internal companion object {
        const val ALLOW = "INVITE, ACK, CANCEL, BYE, OPTIONS, NOTIFY, INFO, MESSAGE, UPDATE"
        /** RFC 3261 timer F / B: 64 × T1. */
        const val TIMER_F = 32_000L
    }
}
