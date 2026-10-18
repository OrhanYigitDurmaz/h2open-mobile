package tr.h2open.driver.sip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SipMessageTest {

    private val invite = listOf(
        "INVITE sip:1001@x.invalid;transport=ws SIP/2.0",
        "Via: SIP/2.0/WSS 10.0.0.1:8089;rport;branch=z9hG4bKabc",
        "v: SIP/2.0/WSS 10.0.0.2;branch=z9hG4bKdef",
        "From: \"Ayşe Yılmaz\" <sip:+905551112233@pbx>;tag=from1",
        "t: <sip:1001@pbx>",
        "i: call-1",
        "CSeq: 102 INVITE",
        "Record-Route: <sip:a;lr>, <sip:b;lr>",
        "Subject: long",
        "  folded",
        "Content-Type: application/sdp",
        "l: 7",
        "",
        "v=0\r\nxyz",
    ).joinToString("\r\n")

    @Test fun parsesRequestWithCompactAndFoldedHeaders() {
        val m = SipMessage.parse(invite) as SipRequest
        assertEquals("INVITE", m.method)
        assertEquals("sip:1001@x.invalid;transport=ws", m.uri)
        assertEquals("call-1", m.callId)
        assertEquals(102, m.cseq)
        assertEquals("INVITE", m.cseqMethod)
        assertEquals("from1", m.fromTag)
        assertNull(m.toTag)
        assertEquals("z9hG4bKabc", m.branch)
        assertEquals(2, m.headerValues("Via").size)
        assertEquals(listOf("<sip:a;lr>", "<sip:b;lr>"), m.headerValues("Record-Route"))
        assertEquals("long folded", m.header("subject"))
        assertEquals("v=0\r\nxy", m.body)
        assertEquals("Ayşe Yılmaz", SipMessage.displayNameOf(m.header("From")!!))
        assertEquals("+905551112233", SipMessage.userOf(SipMessage.uriOf(m.header("From")!!)))
    }

    @Test fun parsesResponse() {
        val m = SipMessage.parse("SIP/2.0 180 Ringing\r\nTo: <sip:600@pbx>;tag=t9\r\nCSeq: 1 INVITE\r\n\r\n") as SipResponse
        assertEquals(180, m.code)
        assertEquals("Ringing", m.reason)
        assertTrue(m.isProvisional)
        assertEquals("t9", m.toTag)
        assertEquals("", m.body)
    }

    @Test fun encodeRoundTripsWithByteContentLength() {
        val r = SipRequest("MESSAGE", "sip:1@pbx").apply {
            addHeader("Call-ID", "c")
            addHeader("Content-Length", "999")
            body = "ğüş"
        }
        val text = r.encode()
        assertTrue(text, text.contains("Content-Length: 6\r\n\r\nğüş"))
        assertEquals("ğüş", SipMessage.parse(text).body)
    }

    @Test fun splitsListsOutsideQuotesAndBrackets() {
        assertEquals(
            listOf("\"a, b\" <sip:x>", "<sip:y;p=1,2>", "z"),
            SipMessage.splitList("\"a, b\" <sip:x>, <sip:y;p=1,2>, z"),
        )
    }

    @Test fun paramsAfterUri() {
        val contact = "<sip:1001@x.invalid;transport=ws>;expires=300"
        assertEquals("300", SipMessage.param(contact, "expires"))
        assertNull(SipMessage.param(contact, "transport"))
        assertEquals("sip:1001@x.invalid;transport=ws", SipMessage.uriOf(contact))
    }
}
