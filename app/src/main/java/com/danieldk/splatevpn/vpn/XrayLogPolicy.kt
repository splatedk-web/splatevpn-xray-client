package com.danieldk.splatevpn.vpn

/** Keep transport failures from info logging without flooding the UI with packet traces. */
internal class XrayLogPolicy(private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private data class Seen(var at: Long, var suppressed: Int = 0)
    private val recent = LinkedHashMap<String, Seen>()
    private val prefix = Regex("^\\d{4}/\\d{2}/\\d{2} \\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?\\s*")
    private val requestId = Regex("\\[\\d+]\\s*")
    private val dnsQuestion = Regex("(?i)(failed to retrieve response for )[^\\s]+")
    private val sessionId = Regex("(?i)\\b[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}\\b")
    private val localPort = Regex("(tcp [^\\s:]+):\\d+(->)")

    @Synchronized fun select(line: String): String? {
        val lower = line.lowercase()
        // Negative DNS answers (often AAAA on IPv4-only routing) are not transport failures.
        if ("[info]" in lower && ("app/dns:" in lower || "failed to resolve ip" in lower) &&
            listOf("record not found", "rcode: 3", "features/dns: empty response").any { it in lower }) return null
        if ("[info]" in lower && "proxy/dns: rejected type typehttps query" in lower) return null
        if ("[info]" in lower && !listOf("failed", "failure", "timeout", "timed out", "refused", "rejected",
                "closed pipe", "broken pipe", "reset by peer", "unexpected eof", "reading config").any { it in lower }) return null
        val normalized = localPort.replace(sessionId.replace(requestId.replace(prefix.replace(line, ""), ""), "<session>"), "$1:<port>$2")
        val key = dnsQuestion.replace(normalized, "$1<domain>")
        val now = clockMs()
        val previous = recent[key]
        if (previous != null && now - previous.at < 5000) {
            previous.suppressed++
            return null
        }
        val repeats = previous?.suppressed ?: 0
        recent.remove(key)
        recent[key] = Seen(now)
        while (recent.size > 256) recent.remove(recent.keys.first())
        return if (repeats == 0) line else "$line [suppressed $repeats similar messages]"
    }
}
