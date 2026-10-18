package tr.h2open.driver.sip

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class EndReason {
    /** We hung up, cancelled or declined. */
    Local,
    /** The far end hung up. */
    Remote,
    /** The caller gave up before we answered. */
    CallerCancelled,
    Busy,
    Declined,
    NoAnswer,
    NotFound,
    ConnectionLost,
    Failed,
}

sealed interface CallState {
    /** INVITE sent, nothing back yet. */
    data object Calling : CallState
    /** The far end is ringing; [earlyMediaSdp] when it sent a 183 with SDP. */
    data class Ringing(val earlyMediaSdp: String? = null) : CallState
    /** Someone is calling us; answer with an SDP answer to [remoteSdp]. */
    data class Incoming(val remoteSdp: String) : CallState
    data class Connected(val remoteSdp: String) : CallState
    data class Ended(val reason: EndReason, val code: Int? = null) : CallState
}

/**
 * One INVITE dialog, outgoing or incoming. Every method runs on the agent's
 * loop, so the dialog state below is only ever touched by one coroutine.
 */
class SipCall private constructor(
    private val ua: SipUserAgent,
    val callId: String,
    val isOutgoing: Boolean,
    /** The far end's URI, e.g. `sip:600@pbx`. */
    val remoteUri: String,
    val remoteDisplayName: String?,
    initial: CallState,
) {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<CallState> = _state.asStateFlow()

    /** The extension or number on the other side. */
    val remoteUser: String get() = SipMessage.userOf(remoteUri)

    private val localTag = SipMessage.randomToken()
    private var remoteTag: String? = null
    private lateinit var localHeader: String   // our From (UAC) or To (UAS), with tag
    private lateinit var remoteHeader: String  // the other one
    private var remoteTarget: String = remoteUri
    private var routeSet: List<String> = emptyList()
    private var localCSeq = 0
    private var localSdp: String = ""

    // UAC
    private lateinit var invite: SipRequest
    private var invitePending: SipUserAgent.Pending? = null
    private var provisionalSeen = false
    private var cancelRequested = false
    private var cancelSent = false
    private var lastAck: SipRequest? = null

    // UAS
    private var inviteReceived: SipRequest? = null
    private var acked = false

    val isEnded: Boolean get() = _state.value is CallState.Ended

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /** Answers an incoming call with [sdpAnswer]. */
    suspend fun answer(sdpAnswer: String) = withContext(ua.loop) {
        val request = inviteReceived ?: error("Only incoming calls can be answered")
        val offer = (_state.value as? CallState.Incoming)?.remoteSdp ?: return@withContext
        localSdp = sdpAnswer
        ua.send(ua.response(request, 200, "OK", localTag).apply {
            addHeader("Contact", "<${ua.contactUri}>")
            addHeader("Allow", SipUserAgent.ALLOW)
            addHeader("Content-Type", "application/sdp")
            body = sdpAnswer
        })
        _state.value = CallState.Connected(offer)
        ua.scope.launch {
            delay(SipUserAgent.TIMER_F)
            if (!acked && _state.value is CallState.Connected) {
                runCatching { sendBye() }
                finish(CallState.Ended(EndReason.Failed, 408))
            }
        }
    }

    /** Declines an incoming call that has not been answered yet. */
    suspend fun reject(code: Int = 603, reason: String = "Decline") = withContext(ua.loop) {
        val request = inviteReceived ?: error("Only incoming calls can be rejected")
        if (_state.value !is CallState.Incoming) return@withContext
        ua.respond(request, code, reason, localTag)
        finish(CallState.Ended(EndReason.Local, code))
    }

    /** Hangs up, cancels or declines, whatever fits the call's state. */
    suspend fun hangup() = withContext(ua.loop) { hangupInternal() }

    // ------------------------------------------------------------------
    // Outgoing (UAC)
    // ------------------------------------------------------------------

    internal suspend fun runInvite() {
        var authorization: Pair<String, String>? = null
        for (attempt in 0..1) {
            invite = buildInvite(authorization)
            val pending = try {
                ua.start(invite)
            } catch (e: SipException) {
                return finish(CallState.Ended(EndReason.ConnectionLost))
            }
            invitePending = pending
            val final = try {
                awaitInviteFinal(pending)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SipException) {
                ua.endTransaction(pending)
                return finish(CallState.Ended(if (e.code == 408) EndReason.NoAnswer else EndReason.ConnectionLost, e.code))
            } catch (e: Exception) {
                // The channel was closed under us: connection lost, or a CANCEL went unanswered.
                ua.endTransaction(pending)
                return finish(CallState.Ended(if (cancelRequested) EndReason.Local else EndReason.ConnectionLost))
            }
            invitePending = null
            ua.endTransaction(pending)

            if (final.isSuccess) {
                remoteTag = final.toTag
                remoteHeader = final.header("To").orEmpty()
                final.header("Contact")?.let { remoteTarget = SipMessage.uriOf(it) }
                routeSet = final.headerValues("Record-Route").reversed()
                sendAck(final)
                if (cancelRequested || isEnded) {
                    // Answered while we were hanging up.
                    runCatching { sendBye() }
                    return finish(CallState.Ended(EndReason.Local))
                }
                _state.value = CallState.Connected(final.body)
                return
            }

            ackFailure(final)
            if ((final.code == 401 || final.code == 407) && authorization == null && !cancelRequested) {
                authorization = try {
                    ua.answerChallenge(final, "INVITE", remoteUri)
                } catch (e: SipException) {
                    return finish(CallState.Ended(EndReason.Failed, final.code))
                }
                provisionalSeen = false
                continue
            }
            return finish(CallState.Ended(endReasonFor(final.code), final.code))
        }
        finish(CallState.Ended(EndReason.Failed, 401))
    }

    private suspend fun awaitInviteFinal(pending: SipUserAgent.Pending): SipResponse {
        while (true) {
            val response = if (provisionalSeen) {
                pending.responses.receive()
            } else {
                // Timer B: give up when nothing at all comes back.
                withTimeoutOrNull(SipUserAgent.TIMER_F) { pending.responses.receive() }
                    ?: throw SipException("No answer from the PBX", 408)
            }
            if (!response.isProvisional) return response
            if (response.code == 100) continue
            provisionalSeen = true
            if (cancelRequested && !cancelSent) sendCancel()
            if (!isEnded && response.code in 180..183) {
                _state.value = CallState.Ringing(response.body.ifBlank { null })
            }
        }
    }

    private fun buildInvite(authorization: Pair<String, String>?): SipRequest {
        if (!::localHeader.isInitialized) {
            localHeader = "${ua.localIdentity};tag=$localTag"
            remoteHeader = "<$remoteUri>"
        }
        return SipRequest("INVITE", remoteUri).apply {
            addHeader("Via", ua.via())
            addHeader("Max-Forwards", "70")
            addHeader("From", localHeader)
            addHeader("To", remoteHeader)
            addHeader("Call-ID", this@SipCall.callId)
            addHeader("CSeq", "${++localCSeq} INVITE")
            addHeader("Contact", "<${ua.contactUri}>")
            addHeader("Allow", SipUserAgent.ALLOW)
            addHeader("Supported", "outbound")
            addHeader("User-Agent", "H2Open Driver")
            authorization?.let { (name, value) -> addHeader(name, value) }
            addHeader("Content-Type", "application/sdp")
            body = localSdp
        }
    }

    /** RFC 3261 §9.1: same Request-URI, top Via, Call-ID, From, To and CSeq number as the INVITE. */
    private fun sendCancel() {
        cancelSent = true
        val cancel = SipRequest("CANCEL", invite.uri).apply {
            addHeader("Via", invite.headerValues("Via").first())
            addHeader("Max-Forwards", "70")
            addHeader("From", invite.header("From").orEmpty())
            addHeader("To", invite.header("To").orEmpty())
            addHeader("Call-ID", this@SipCall.callId)
            addHeader("CSeq", "${invite.cseq} CANCEL")
            addHeader("User-Agent", "H2Open Driver")
        }
        runCatching { ua.send(cancel) }
        // If the PBX never answers the CANCEL with a 487, stop waiting.
        val pending = invitePending
        ua.scope.launch {
            delay(SipUserAgent.TIMER_F)
            if (pending != null && invitePending === pending) ua.endTransaction(pending)
        }
    }

    /** ACK for a non-2xx final: same branch as the INVITE (§17.1.1.3). */
    private fun ackFailure(response: SipResponse) {
        val ack = SipRequest("ACK", invite.uri).apply {
            addHeader("Via", invite.headerValues("Via").first())
            addHeader("Max-Forwards", "70")
            addHeader("From", invite.header("From").orEmpty())
            addHeader("To", response.header("To").orEmpty())
            addHeader("Call-ID", this@SipCall.callId)
            addHeader("CSeq", "${invite.cseq} ACK")
        }
        runCatching { ua.send(ack) }
    }

    /** ACK for a 2xx: a new transaction inside the dialog (§13.2.2.4). */
    private fun sendAck(response: SipResponse) {
        val ack = inDialog("ACK", cseq = response.cseq)
        lastAck = ack
        runCatching { ua.send(ack) }
    }

    internal fun reAck(response: SipResponse) {
        if (isOutgoing && response.cseq == invite.cseq) lastAck?.let { runCatching { ua.send(it) } }
    }

    // ------------------------------------------------------------------
    // In-dialog requests
    // ------------------------------------------------------------------

    private fun inDialog(method: String, cseq: Int = ++localCSeq) = SipRequest(method, remoteTarget).apply {
        addHeader("Via", ua.via())
        addHeader("Max-Forwards", "70")
        routeSet.forEach { addHeader("Route", it) }
        addHeader("From", localHeader)
        addHeader("To", remoteHeader)
        addHeader("Call-ID", this@SipCall.callId)
        addHeader("CSeq", "$cseq $method")
        addHeader("User-Agent", "H2Open Driver")
    }

    private suspend fun sendBye() {
        val pending = ua.start(inDialog("BYE"))
        withTimeoutOrNull(BYE_TIMEOUT) { with(ua) { runCatching { pending.awaitFinal() } } }
        ua.endTransaction(pending)
    }

    internal suspend fun hangupInternal() {
        when (_state.value) {
            is CallState.Ended -> return
            is CallState.Calling, is CallState.Ringing -> {
                cancelRequested = true
                _state.value = CallState.Ended(EndReason.Local)
                if (provisionalSeen && !cancelSent) sendCancel()
                // runInvite sees the 487 (or a racing 200, which it ACKs and BYEs) and forgets the call.
            }
            is CallState.Incoming -> {
                inviteReceived?.let { ua.respond(it, 603, "Decline", localTag) }
                finish(CallState.Ended(EndReason.Local, 603))
            }
            is CallState.Connected -> {
                _state.value = CallState.Ended(EndReason.Local)
                runCatching { sendBye() }
                ua.forget(this)
            }
        }
    }

    // ------------------------------------------------------------------
    // Requests from the far end
    // ------------------------------------------------------------------

    /** Sends 180 Ringing for an incoming INVITE. */
    internal fun ringing() {
        val request = inviteReceived ?: return
        ua.respond(request, 180, "Ringing", localTag) { addHeader("Contact", "<${ua.contactUri}>") }
    }

    internal fun onAck(request: SipRequest) {
        if (request.cseq == inviteReceived?.cseq) acked = true
    }

    /**
     * A re-INVITE (session refresh, hold, direct media). The audio path
     * stays as negotiated, so we answer with the SDP we already gave.
     */
    internal fun onReInvite(request: SipRequest) {
        request.header("Contact")?.let { remoteTarget = SipMessage.uriOf(it) }
        if (isEnded) return ua.respond(request, 481, "Call/Transaction Does Not Exist")
        ua.respond(request, 200, "OK") {
            addHeader("Contact", "<${ua.contactUri}>")
            addHeader("Allow", SipUserAgent.ALLOW)
            if (localSdp.isNotEmpty()) {
                addHeader("Content-Type", "application/sdp")
                body = localSdp
            }
        }
    }

    internal fun onRemoteBye() = finish(CallState.Ended(EndReason.Remote))

    internal fun onRemoteCancel() {
        val request = inviteReceived ?: return
        if (_state.value !is CallState.Incoming) return
        ua.respond(request, 487, "Request Terminated", localTag)
        finish(CallState.Ended(EndReason.CallerCancelled, 487))
    }

    internal fun onTransportLost() = finish(CallState.Ended(EndReason.ConnectionLost))

    private fun finish(ended: CallState.Ended) {
        if (!isEnded) _state.value = ended
        ua.forget(this)
    }

    internal companion object {
        const val BYE_TIMEOUT = 5_000L

        fun endReasonFor(code: Int) = when (code) {
            486, 600 -> EndReason.Busy
            603 -> EndReason.Declined
            408, 480 -> EndReason.NoAnswer
            404, 484, 604 -> EndReason.NotFound
            487 -> EndReason.Local
            else -> EndReason.Failed
        }

        fun outgoing(ua: SipUserAgent, uri: String, sdpOffer: String) =
            SipCall(ua, SipMessage.randomToken(24), true, uri, null, CallState.Calling).apply { localSdp = sdpOffer }

        fun incoming(ua: SipUserAgent, request: SipRequest): SipCall {
            val from = request.header("From").orEmpty()
            return SipCall(
                ua, request.callId, false, SipMessage.uriOf(from), SipMessage.displayNameOf(from),
                CallState.Incoming(request.body),
            ).apply {
                inviteReceived = request
                remoteTag = request.fromTag
                remoteHeader = from
                localHeader = request.header("To").orEmpty().let { if (SipMessage.tagOf(it) == null) "$it;tag=$localTag" else it }
                request.header("Contact")?.let { remoteTarget = SipMessage.uriOf(it) }
                routeSet = request.headerValues("Record-Route")
            }
        }
    }
}
