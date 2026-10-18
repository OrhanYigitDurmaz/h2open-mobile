package tr.h2open.driver.sip

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Runs against the Asterisk in asterisk-test/ (`./run.sh`, then
 * `H2O_SIP_WS=ws://localhost:8088/ws ../gradlew test`). Skipped otherwise.
 */
class AsteriskTest {
    private val url = System.getenv("H2O_SIP_WS").orEmpty()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val agents = mutableListOf<SipUserAgent>()

    @Before fun needsAsterisk() = assumeTrue("H2O_SIP_WS not set", url.isNotBlank())

    @After fun tearDown() = runBlocking {
        agents.forEach { runCatching { it.close() } }
        scope.cancel()
    }

    private fun agent(user: String, password: String = "secret$user") =
        SipUserAgent(SipAccount(user, password, "localhost", displayName = "Şoför $user"), WebSocketSipTransport(url), scope)
            .also { agents += it }

    private suspend inline fun <reified T : CallState> SipCall.await(millis: Long = 10_000): T =
        withTimeout(millis) { state.first { it is T || (it is CallState.Ended && T::class != CallState.Ended::class) } }
            .let { it as? T ?: throw AssertionError("expected ${T::class.simpleName}, got $it") }

    @Test fun registersAndUnregisters() = runBlocking {
        val ua = agent("1001")
        ua.register()
        val state = ua.registration.value
        assertTrue("$state", state is RegistrationState.Registered && state.expiresSeconds > 0)
        ua.unregister()
        assertEquals(RegistrationState.Unregistered, ua.registration.value)
    }

    @Test fun wrongPasswordFails() = runBlocking {
        val ua = agent("1001", "nope")
        val error = runCatching { ua.register() }.exceptionOrNull()
        assertTrue("$error", error is SipException)
        assertTrue("${ua.registration.value}", ua.registration.value is RegistrationState.Failed)
    }

    @Test fun registersWebRtcEndpoint() = runBlocking {
        val ua = agent("1003")
        ua.register()
        assertTrue("${ua.registration.value}", ua.registration.value is RegistrationState.Registered)
    }

    @Test fun callAnsweredThenHangUp() = runBlocking {
        val ua = agent("1001")
        ua.register()
        val call = ua.call("600", sdp(40000))
        val connected = call.await<CallState.Connected>()
        assertTrue(connected.remoteSdp, connected.remoteSdp.contains("PCMA/8000"))
        call.hangup()
        assertEquals(CallState.Ended(EndReason.Local), call.state.value)
    }

    @Test fun farEndHangsUp() = runBlocking {
        val ua = agent("1002")
        ua.register()
        val call = ua.call("600", sdp(40010))
        call.await<CallState.Connected>()
        assertEquals(EndReason.Remote, call.await<CallState.Ended>().reason)
    }

    @Test fun busy() = runBlocking {
        val ua = agent("1001")
        ua.register()
        val call = ua.call("601", sdp(40020))
        assertEquals(EndReason.Busy, call.await<CallState.Ended>().reason)
    }

    @Test fun cancelWhileRinging() = runBlocking {
        val ua = agent("1001")
        ua.register()
        val call = ua.call("602", sdp(40030))
        call.await<CallState.Ringing>()
        call.hangup()
        assertEquals(CallState.Ended(EndReason.Local), call.state.value)
        // The 487 arrives and the call is forgotten; a new call still works.
        val again = ua.call("600", sdp(40040))
        again.await<CallState.Connected>()
        again.hangup()
    }

    @Test fun incomingCallAnsweredAndHungUpByCaller() = runBlocking {
        val driver = agent("1001")
        val office = agent("1002")
        driver.register()
        office.register()
        val outgoing = office.call("1001", sdp(40050))
        val incoming = withTimeout(10_000) { driver.incomingCalls.first() }
        assertTrue(incoming.state.value is CallState.Incoming)
        assertEquals("1002", incoming.remoteUser)
        assertEquals("Şoför 1002", incoming.remoteDisplayName)
        outgoing.await<CallState.Ringing>()
        incoming.answer(sdp(40060))
        outgoing.await<CallState.Connected>()
        outgoing.hangup()
        assertEquals(EndReason.Remote, incoming.await<CallState.Ended>().reason)
    }

    @Test fun incomingCallHungUpByDriver() = runBlocking {
        val driver = agent("1001")
        val office = agent("1002")
        driver.register()
        office.register()
        val outgoing = office.call("1001", sdp(40070))
        val incoming = withTimeout(10_000) { driver.incomingCalls.first() }
        incoming.answer(sdp(40080))
        outgoing.await<CallState.Connected>()
        incoming.hangup()
        assertEquals(EndReason.Remote, outgoing.await<CallState.Ended>().reason)
    }

    @Test fun incomingCallCancelledByCaller() = runBlocking {
        val driver = agent("1001")
        val office = agent("1002")
        driver.register()
        office.register()
        val outgoing = office.call("1001", sdp(40090))
        val incoming = withTimeout(10_000) { driver.incomingCalls.first() }
        outgoing.await<CallState.Ringing>()
        outgoing.hangup()
        assertEquals(EndReason.CallerCancelled, incoming.await<CallState.Ended>().reason)
    }

    @Test fun incomingCallDeclined() = runBlocking {
        val driver = agent("1001")
        val office = agent("1002")
        driver.register()
        office.register()
        val outgoing = office.call("1001", sdp(40100))
        val incoming = withTimeout(10_000) { driver.incomingCalls.first() }
        incoming.reject()
        val ended = outgoing.await<CallState.Ended>()
        assertTrue("$ended", ended.reason != EndReason.Local)
    }

    private fun sdp(port: Int) = listOf(
        "v=0",
        "o=- $port 1 IN IP4 127.0.0.1",
        "s=-",
        "c=IN IP4 127.0.0.1",
        "t=0 0",
        "m=audio $port RTP/AVP 8 101",
        "a=rtpmap:8 PCMA/8000",
        "a=rtpmap:101 telephone-event/8000",
        "a=sendrecv",
        "",
    ).joinToString("\r\n")
}
