package tr.h2open.driver.voip

import android.content.Context
import android.media.AudioAttributes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One WebRTC factory for the process; audio only. */
class WebRtcMedia(context: Context) {
    private val appContext = context.applicationContext

    private val factory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions(),
        )
        val audio = JavaAudioDeviceModule.builder(appContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .createAudioDeviceModule()
        PeerConnectionFactory.builder().setAudioDeviceModule(audio).createPeerConnectionFactory()
    }

    fun newSession(): MediaSession = MediaSession(factory)
}

class MediaException(message: String) : Exception(message)

/**
 * One call's peer connection. SIP carries whole SDPs (no trickle ICE), so
 * every description we hand out waits for candidate gathering to finish.
 * There are no STUN or TURN servers: the PBX's VPS has a public address and
 * its ICE candidate is the only one that matters.
 */
class MediaSession internal constructor(private val factory: PeerConnectionFactory) {
    private val gathered = CompletableDeferred<Unit>()

    private val observer = object : PeerConnection.Observer {
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) gathered.complete(Unit)
        }
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceCandidate(candidate: IceCandidate) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: org.webrtc.DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
    }

    private val connection: PeerConnection = factory.createPeerConnection(
        PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
            keyType = PeerConnection.KeyType.ECDSA
        },
        observer,
    ) ?: throw MediaException("Could not create the peer connection")

    private val source = factory.createAudioSource(MediaConstraints())
    private val track = factory.createAudioTrack("audio0", source).also { connection.addTrack(it, listOf("h2o")) }
    private var remoteSet = false
    private var closed = false

    /** Our SDP offer for an outgoing call. */
    suspend fun createOffer(): String {
        val offer = create { connection.createOffer(it, audioConstraints()) }
        setLocal(offer)
        return localSdp()
    }

    /** Our SDP answer to an incoming call's [remoteOffer]. */
    suspend fun createAnswer(remoteOffer: String): String {
        setRemote(SessionDescription(SessionDescription.Type.OFFER, remoteOffer))
        val answer = create { connection.createAnswer(it, audioConstraints()) }
        setLocal(answer)
        return localSdp()
    }

    /** The far end's answer to our offer; a 183's early media and the 200's are the same SDP. */
    suspend fun setRemoteAnswer(sdp: String) {
        if (remoteSet || sdp.isBlank()) return
        setRemote(SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    var muted: Boolean = false
        set(value) {
            field = value
            applyTracks()
        }

    /** On hold (a phone call took over): neither side is heard until it is taken off hold. */
    var held: Boolean = false
        set(value) {
            field = value
            applyTracks()
        }

    private fun applyTracks() {
        if (closed) return
        track.setEnabled(!muted && !held)
        connection.receivers.forEach { it.track()?.setEnabled(!held) }
    }

    fun close() {
        if (closed) return
        closed = true
        // Disposing the connection also disposes its senders' tracks (as AppRTC does).
        connection.dispose()
        source.dispose()
    }

    private suspend fun localSdp(): String {
        withTimeoutOrNull(GATHER_TIMEOUT) { gathered.await() }
        return connection.localDescription?.description ?: throw MediaException("No local description")
    }

    private suspend fun setLocal(description: SessionDescription) = set { connection.setLocalDescription(it, description) }

    private suspend fun setRemote(description: SessionDescription) {
        set { connection.setRemoteDescription(it, description) }
        remoteSet = true
    }

    private fun audioConstraints() = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
    }

    private suspend fun create(block: (SdpObserver) -> Unit): SessionDescription = suspendCancellableCoroutine { cont ->
        block(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) = cont.resume(description)
            override fun onCreateFailure(error: String?) = cont.resumeWithException(MediaException("SDP: $error"))
            override fun onSetSuccess() = Unit
            override fun onSetFailure(error: String?) = Unit
        })
    }

    private suspend fun set(block: (SdpObserver) -> Unit): Unit = suspendCancellableCoroutine { cont ->
        block(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) = Unit
            override fun onCreateFailure(error: String?) = Unit
            override fun onSetSuccess() = cont.resume(Unit)
            override fun onSetFailure(error: String?) = cont.resumeWithException(MediaException("SDP: $error"))
        })
    }

    private companion object {
        const val GATHER_TIMEOUT = 3_000L
    }
}
