package tr.h2open.driver.voip

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import tr.h2open.driver.H2oApplication
import tr.h2open.driver.sip.EndReason
import java.util.concurrent.Executor

/** The call system creates the connection of each call we hand it ([TelecomCalls]) here. */
class H2oConnectionService : ConnectionService() {
    private val voip get() = (application as H2oApplication).voip

    override fun onCreateOutgoingConnection(account: PhoneAccountHandle?, request: ConnectionRequest): Connection =
        create(request, incoming = false)

    override fun onCreateIncomingConnection(account: PhoneAccountHandle?, request: ConnectionRequest): Connection =
        create(request, incoming = true)

    override fun onCreateOutgoingConnectionFailed(account: PhoneAccountHandle?, request: ConnectionRequest?) {
        Log.w(TAG, "The call system could not take the outgoing call; it goes on without it")
    }

    override fun onCreateIncomingConnectionFailed(account: PhoneAccountHandle?, request: ConnectionRequest?) {
        Log.w(TAG, "The call system could not take the incoming call; it goes on without it")
    }

    private fun create(request: ConnectionRequest, incoming: Boolean): Connection {
        val extras = request.extras
        val name = extras?.getBundle(
            if (incoming) TelecomManager.EXTRA_INCOMING_CALL_EXTRAS else TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS,
        )?.getString(TelecomCalls.EXTRA_NAME) ?: extras?.getString(TelecomCalls.EXTRA_NAME)
        val connection = VoipConnection(voip, incoming)
        connection.setAddress(request.address, TelecomManager.PRESENTATION_ALLOWED)
        if (!name.isNullOrBlank()) connection.setCallerDisplayName(name, TelecomManager.PRESENTATION_ALLOWED)
        if (voip.attach(connection)) {
            connection.start()
        } else {
            // The call ended (or another one started) before the call system got back to us.
            connection.setDisconnected(DisconnectCause(DisconnectCause.CANCELED))
            connection.destroy()
        }
        return connection
    }

    private companion object {
        const val TAG = "H2Open.Telecom"
    }
}

/**
 * The call system's side of one call. It mirrors [VoipManager.call] into
 * the call system, and passes on what the call system asks: answer, hang up,
 * hold for a phone call, change the audio route.
 */
class VoipConnection(private val voip: VoipManager, val incoming: Boolean) : Connection() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { main.post(it) }
    private var endpoints: List<Any> = emptyList()
    private var ended = false

    var route: AudioRoute = AudioRoute.Earpiece
        private set
    var routes: List<AudioRoute> = emptyList()
        private set

    init {
        connectionProperties = PROPERTY_SELF_MANAGED
        connectionCapabilities = CAPABILITY_HOLD or CAPABILITY_SUPPORT_HOLD or CAPABILITY_MUTE
        audioModeIsVoip = true
        if (incoming) setRinging() else setDialing()
    }

    /** Starts mirroring the call, once [VoipManager] has taken this connection. */
    fun start() {
        scope.launch {
            voip.call.collect { call ->
                when {
                    call == null -> end(DisconnectCause(DisconnectCause.LOCAL))
                    call.phase == CallPhase.Active && state != STATE_ACTIVE && state != STATE_HOLDING -> setActive()
                    call.phase == CallPhase.Ended -> end(disconnectCause(call))
                }
            }
        }
    }

    override fun onAnswer() = voip.answer()

    override fun onAnswer(videoState: Int) = voip.answer()

    override fun onReject() = voip.hangup()

    override fun onDisconnect() = voip.hangup()

    override fun onAbort() = voip.hangup()

    /** A phone call was answered: ours waits, unheard, until it is over. */
    override fun onHold() {
        setOnHold()
        voip.setHeld(true)
    }

    override fun onUnhold() {
        setActive()
        voip.setHeld(false)
    }

    override fun onShowIncomingCallUi() {
        // CallService already shows the ringing notification and the call screen.
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun onAvailableCallEndpointsChanged(availableEndpoints: List<CallEndpoint>) {
        endpoints = availableEndpoints
        routes = availableEndpoints.mapNotNull { routeOf(it.endpointType) }.distinct().sortedBy { it.ordinal }
        voip.onRoutesChanged()
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun onCallEndpointChanged(callEndpoint: CallEndpoint) {
        routeOf(callEndpoint.endpointType)?.let { route = it }
        voip.onRoutesChanged()
    }

    // Before Android 14 the call system reports routes this way only.
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onCallAudioStateChanged(state: CallAudioState) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val mask = state.supportedRouteMask
        routes = listOfNotNull(
            AudioRoute.Earpiece.takeIf { mask and (CallAudioState.ROUTE_EARPIECE or CallAudioState.ROUTE_WIRED_HEADSET) != 0 },
            AudioRoute.Speaker.takeIf { mask and CallAudioState.ROUTE_SPEAKER != 0 },
            AudioRoute.Bluetooth.takeIf { mask and CallAudioState.ROUTE_BLUETOOTH != 0 },
        )
        route = when (state.route) {
            CallAudioState.ROUTE_SPEAKER -> AudioRoute.Speaker
            CallAudioState.ROUTE_BLUETOOTH -> AudioRoute.Bluetooth
            else -> AudioRoute.Earpiece
        }
        voip.onRoutesChanged()
    }

    /** Asks the call system to move the audio to [target]. */
    fun select(target: AudioRoute) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            selectEndpoint(target)
        } else {
            @Suppress("DEPRECATION")
            setAudioRoute(
                when (target) {
                    AudioRoute.Speaker -> CallAudioState.ROUTE_SPEAKER
                    AudioRoute.Bluetooth -> CallAudioState.ROUTE_BLUETOOTH
                    AudioRoute.Earpiece -> CallAudioState.ROUTE_WIRED_OR_EARPIECE
                },
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun selectEndpoint(target: AudioRoute) {
        val available = endpoints.filterIsInstance<CallEndpoint>().filter { routeOf(it.endpointType) == target }
        // A wired headset before the earpiece, as CallAudio does.
        val endpoint = available.firstOrNull { it.endpointType == CallEndpoint.TYPE_WIRED_HEADSET } ?: available.firstOrNull() ?: return
        // If the change fails, the route shown stays what the call system reports.
        requestCallEndpointChange(endpoint, mainExecutor) { }
    }

    private fun end(cause: DisconnectCause) {
        if (ended) return
        ended = true
        setDisconnected(cause)
        destroy()
        scope.cancel()
        voip.detach(this)
    }

    private fun disconnectCause(call: CallInfo): DisconnectCause = DisconnectCause(
        when {
            call.failure != null -> DisconnectCause.ERROR
            else -> when (call.endReason) {
                EndReason.Local -> if (incoming && call.connectedAtMillis == null) DisconnectCause.REJECTED else DisconnectCause.LOCAL
                EndReason.Remote -> DisconnectCause.REMOTE
                EndReason.CallerCancelled -> DisconnectCause.MISSED
                EndReason.Busy -> DisconnectCause.BUSY
                EndReason.Declined, EndReason.NoAnswer, EndReason.NotFound -> DisconnectCause.REMOTE
                EndReason.ConnectionLost, EndReason.Failed, null -> DisconnectCause.ERROR
            }
        },
    )

    private companion object {
        @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        fun routeOf(type: Int): AudioRoute? = when (type) {
            CallEndpoint.TYPE_EARPIECE, CallEndpoint.TYPE_WIRED_HEADSET -> AudioRoute.Earpiece
            CallEndpoint.TYPE_SPEAKER -> AudioRoute.Speaker
            CallEndpoint.TYPE_BLUETOOTH -> AudioRoute.Bluetooth
            else -> null
        }
    }
}
