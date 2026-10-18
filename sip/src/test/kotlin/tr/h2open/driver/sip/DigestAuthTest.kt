package tr.h2open.driver.sip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DigestAuthTest {

    /** RFC 2617 §3.5. */
    @Test fun rfc2617Example() {
        val challenge = DigestAuth.parseChallenge(
            "Digest realm=\"testrealm@host.com\", qop=\"auth,auth-int\", " +
                "nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\", opaque=\"5ccc069c403ebaf9f0171e9517f40e41\"",
        )
        val value = DigestAuth.authorization(
            challenge, "Mufasa", "Circle Of Life", "GET", "/dir/index.html", nonceCount = 1, cnonce = "0a4f113b",
        )
        assertTrue(value, value.contains("response=\"6629fae49393a05397450978507c4ef1\""))
        assertTrue(value, value.contains("qop=auth, nc=00000001, cnonce=\"0a4f113b\""))
        assertTrue(value, value.contains("opaque=\"5ccc069c403ebaf9f0171e9517f40e41\""))
    }

    @Test fun withoutQop() {
        val challenge = DigestAuth.parseChallenge("Digest realm=\"asterisk\",nonce=\"n1\"")
        val value = DigestAuth.authorization(challenge, "1001", "pw", "REGISTER", "sip:pbx")
        val ha1 = DigestAuth.md5("1001:asterisk:pw")
        val ha2 = DigestAuth.md5("REGISTER:sip:pbx")
        assertTrue(value, value.contains("response=\"${DigestAuth.md5("$ha1:n1:$ha2")}\""))
        assertEquals(false, value.contains("qop"))
    }
}
