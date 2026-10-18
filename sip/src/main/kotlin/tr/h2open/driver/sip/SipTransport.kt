package tr.h2open.driver.sip

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Carries SIP messages as text frames; one connection per [SipUserAgent]. */
interface SipTransport {
    /** `WS` or `WSS`, for Via headers. */
    val viaTransport: String
    val connected: StateFlow<Boolean>
    /** Every text frame received, in order. Completes when the connection ends. */
    val incoming: Flow<String>
    suspend fun connect()
    fun send(message: String): Boolean
    fun close()
}

/**
 * SIP over WebSocket (RFC 7118) with OkHttp. WebSocket pings keep NAT and the
 * VPS proxy from dropping an idle connection.
 */
class WebSocketSipTransport(
    private val url: String,
    client: OkHttpClient = OkHttpClient(),
) : SipTransport {
    private val http = client.newBuilder()
        .pingInterval(25, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()
    private var socket: WebSocket? = null
    private var frames = Channel<String>(Channel.UNLIMITED)
    private val _connected = MutableStateFlow(false)

    override val viaTransport = if (url.startsWith("wss", ignoreCase = true)) "WSS" else "WS"
    override val connected: StateFlow<Boolean> = _connected
    override val incoming: Flow<String> get() = frames.receiveAsFlow()

    override suspend fun connect() {
        if (_connected.value) return
        frames = Channel(Channel.UNLIMITED)
        val opened = CompletableDeferred<Unit>()
        val request = Request.Builder().url(url).header("Sec-WebSocket-Protocol", "sip").build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _connected.value = true
                opened.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                frames.trySend(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                ended()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = ended()

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                opened.completeExceptionally(IOException("WebSocket to $url failed: ${t.message}", t))
                ended()
            }

            private fun ended() {
                _connected.value = false
                frames.close()
            }
        })
        opened.await()
    }

    override fun send(message: String): Boolean = socket?.send(message) ?: false

    override fun close() {
        socket?.close(1000, "bye")
        socket = null
        _connected.value = false
        frames.close()
    }
}
