package tr.h2open.driver.sip

import java.security.MessageDigest

/** HTTP digest (RFC 2617, MD5) as Asterisk asks for it on REGISTER and INVITE. */
object DigestAuth {

    /** `Digest realm="asterisk",nonce="…",qop="auth"` → its parameters. */
    fun parseChallenge(header: String): Map<String, String> {
        val params = header.trim().removePrefix("Digest").trim()
        return SipMessage.splitList(params).associate { part ->
            val key = part.substringBefore('=').trim().lowercase()
            key to part.substringAfter('=', "").trim().trim('"')
        }
    }

    /**
     * The `Authorization` (or `Proxy-Authorization`) value answering
     * [challenge] for [method] on [uri].
     */
    fun authorization(
        challenge: Map<String, String>,
        username: String,
        password: String,
        method: String,
        uri: String,
        nonceCount: Int = 1,
        cnonce: String = SipMessage.randomToken(16),
    ): String {
        val realm = challenge["realm"].orEmpty()
        val nonce = challenge["nonce"].orEmpty()
        val algorithm = challenge["algorithm"]
        require(algorithm == null || algorithm.equals("MD5", ignoreCase = true)) { "Unsupported digest algorithm $algorithm" }
        val qop = challenge["qop"]?.split(',')?.map { it.trim() }?.firstOrNull { it == "auth" }
        val ha1 = md5("$username:$realm:$password")
        val ha2 = md5("$method:$uri")
        val nc = "%08x".format(nonceCount)
        val response = if (qop != null) md5("$ha1:$nonce:$nc:$cnonce:$qop:$ha2") else md5("$ha1:$nonce:$ha2")
        return buildString {
            append("Digest username=\"$username\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", response=\"$response\"")
            if (algorithm != null) append(", algorithm=$algorithm")
            challenge["opaque"]?.let { append(", opaque=\"$it\"") }
            if (qop != null) append(", qop=$qop, nc=$nc, cnonce=\"$cnonce\"")
        }
    }

    internal fun md5(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
