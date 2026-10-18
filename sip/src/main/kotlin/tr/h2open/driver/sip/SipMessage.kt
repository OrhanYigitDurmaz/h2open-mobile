package tr.h2open.driver.sip

import java.security.SecureRandom

/**
 * A SIP request or response (RFC 3261), as text over a WebSocket (RFC 7118).
 * Headers keep their order; lookups ignore case and accept compact forms.
 */
sealed class SipMessage(
    val headers: MutableList<Pair<String, String>> = mutableListOf(),
    var body: String = "",
) {
    /** First value of [name], with comma-joined values of the same header left joined. */
    fun header(name: String): String? = headers.firstOrNull { matches(it.first, name) }?.second

    /** Every value of [name], with comma-separated lists split (Via, Route, Record-Route, ...). */
    fun headerValues(name: String): List<String> =
        headers.filter { matches(it.first, name) }.flatMap { splitList(it.second) }

    fun setHeader(name: String, value: String) {
        headers.removeAll { matches(it.first, name) }
        headers.add(name to value)
    }

    fun addHeader(name: String, value: String) {
        headers.add(name to value)
    }

    val callId: String get() = header("Call-ID").orEmpty()
    val cseq: Int get() = header("CSeq")?.trim()?.substringBefore(' ')?.toIntOrNull() ?: 0
    val cseqMethod: String get() = header("CSeq")?.trim()?.substringAfter(' ')?.trim()?.uppercase().orEmpty()
    val fromTag: String? get() = header("From")?.let(::tagOf)
    val toTag: String? get() = header("To")?.let(::tagOf)
    /** Branch of the top Via: the transaction this message belongs to. */
    val branch: String? get() = headerValues("Via").firstOrNull()?.let { param(it, "branch") }

    protected abstract fun startLine(): String

    fun encode(): String {
        val bytes = body.toByteArray(Charsets.UTF_8).size
        val sb = StringBuilder(startLine()).append("\r\n")
        headers.filterNot { matches(it.first, "Content-Length") }.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        sb.append("Content-Length: ").append(bytes).append("\r\n\r\n").append(body)
        return sb.toString()
    }

    override fun toString() = encode()

    companion object {
        private val COMPACT = mapOf(
            "v" to "via", "f" to "from", "t" to "to", "i" to "call-id", "m" to "contact",
            "l" to "content-length", "c" to "content-type", "k" to "supported", "s" to "subject",
            "e" to "content-encoding", "o" to "event", "u" to "allow-events",
        )

        private fun canonical(name: String): String = name.trim().lowercase().let { COMPACT[it] ?: it }

        private fun matches(a: String, b: String) = canonical(a) == canonical(b)

        /** Splits on commas outside quotes and angle brackets. */
        fun splitList(value: String): List<String> {
            val parts = mutableListOf<String>()
            var depth = 0
            var quoted = false
            val current = StringBuilder()
            var i = 0
            while (i < value.length) {
                val c = value[i]
                when {
                    quoted && c == '\\' && i + 1 < value.length -> { current.append(c).append(value[i + 1]); i++ }
                    c == '"' -> { quoted = !quoted; current.append(c) }
                    !quoted && c == '<' -> { depth++; current.append(c) }
                    !quoted && c == '>' -> { depth--; current.append(c) }
                    !quoted && depth == 0 && c == ',' -> { parts.add(current.toString().trim()); current.clear() }
                    else -> current.append(c)
                }
                i++
            }
            if (current.isNotBlank()) parts.add(current.toString().trim())
            return parts
        }

        /** `"Ali" <sip:1001@x>;tag=abc` → `abc`. */
        fun tagOf(nameAddr: String): String? = param(nameAddr.substringAfterLast('>'), "tag")

        /** A `;name=value` parameter (after the URI when there are angle brackets). */
        fun param(value: String, name: String): String? {
            val tail = if ('>' in value) value.substringAfterLast('>') else value
            return tail.split(';').drop(1).map { it.trim() }
                .firstOrNull { it.substringBefore('=').trim().equals(name, ignoreCase = true) }
                ?.substringAfter('=', "")?.trim()?.trim('"')
        }

        /** `"Ali" <sip:1001@x;transport=ws>;expires=60` → `sip:1001@x;transport=ws`. */
        fun uriOf(nameAddr: String): String {
            val v = nameAddr.trim()
            return if ('<' in v) v.substringAfter('<').substringBefore('>') else v.substringBefore(';').trim()
        }

        /** `sip:1001@pbx.example;transport=ws` → `1001`. */
        fun userOf(uri: String): String = uri.substringAfter(':').substringBefore('@').substringBefore(';')

        /** `"Ayşe" <sip:+905...@x>` → `Ayşe`, or null. */
        fun displayNameOf(nameAddr: String): String? {
            val v = nameAddr.trim()
            if (!v.contains('<')) return null
            return v.substringBefore('<').trim().trim('"').ifBlank { null }
        }

        fun parse(text: String): SipMessage {
            val headEnd = text.indexOf("\r\n\r\n").let { if (it < 0) text.indexOf("\n\n") else it }
            val head = if (headEnd < 0) text else text.substring(0, headEnd)
            var body = if (headEnd < 0) "" else text.substring(headEnd).trimStart('\r', '\n')
            val lines = head.split(Regex("\r?\n"))
            val start = lines.first().trim()
            val message: SipMessage = if (start.startsWith("SIP/2.0 ")) {
                val rest = start.removePrefix("SIP/2.0 ")
                SipResponse(rest.substringBefore(' ').toInt(), rest.substringAfter(' ', ""))
            } else {
                val parts = start.split(' ')
                require(parts.size >= 3 && parts[2] == "SIP/2.0") { "Not a SIP message: $start" }
                SipRequest(parts[0].uppercase(), parts[1])
            }
            var last: Pair<String, String>? = null
            for (line in lines.drop(1)) {
                if (line.isEmpty()) continue
                if ((line[0] == ' ' || line[0] == '\t') && last != null) {
                    // Folded header line.
                    val folded = last.first to (last.second + " " + line.trim())
                    message.headers[message.headers.size - 1] = folded
                    last = folded
                    continue
                }
                val colon = line.indexOf(':')
                if (colon <= 0) continue
                last = line.substring(0, colon).trim() to line.substring(colon + 1).trim()
                message.headers.add(last)
            }
            message.header("Content-Length")?.trim()?.toIntOrNull()?.let { length ->
                val bytes = body.toByteArray(Charsets.UTF_8)
                if (bytes.size > length) body = String(bytes, 0, length, Charsets.UTF_8)
            }
            message.body = body
            return message
        }

        private val random = SecureRandom()
        private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

        fun randomToken(length: Int = 10): String = buildString {
            repeat(length) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        }

        /** RFC 3261 branch: magic cookie plus something unique. */
        fun newBranch(): String = "z9hG4bK" + randomToken(16)
    }
}

class SipRequest(val method: String, var uri: String) : SipMessage() {
    override fun startLine() = "$method $uri SIP/2.0"
}

class SipResponse(val code: Int, val reason: String) : SipMessage() {
    override fun startLine() = "SIP/2.0 $code $reason"
    val isProvisional get() = code in 100..199
    val isSuccess get() = code in 200..299
}
