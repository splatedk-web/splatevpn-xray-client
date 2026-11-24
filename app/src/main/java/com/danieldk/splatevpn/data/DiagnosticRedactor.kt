package com.danieldk.splatevpn.data

/** Output-only redaction: the on-device troubleshooting log is left intact. */
internal object DiagnosticRedactor {
    private val authorization = Regex("""(?i)(?:proxy-)?authorization["']?\s*[:=]\s*["']?(?:Basic|Bearer)\s+[^\s"',;]+""")
    private val links = Regex("""(?i)\b(?:vless|vmess|trojan|ss|socks5?|awg|vpn|hy2|hysteria2|https?)://[^\s<>"']+""")
    private val credentials = Regex("""(?i)["']?(?:uuid|username|user|password|pass|token|secret|authorization|private_?key|preshared_?key|header_?protection_?key|publickey|pbk|sid)["']?\s*[:=]\s*(?:"[^"]*"|'[^']*'|[^\s,;]+)""")
    private val realityKeys = Regex("""(?i)(["']?(?:mldsa65Verify|mldsa65Seed|pqv)["']?\s*[:=]\s*)(?:"[^"]*"|'[^']*'|[^\s,;]+)""")
    private val uuid = Regex("""\b[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}\b""")
    private val ipv4 = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")
    private val ipv6 = Regex("""(?<![\w:])(?:[0-9a-fA-F]{0,4}:){2,}[0-9a-fA-F:.]*(?:%[\w]+)?""")
    private val paths = Regex("""/data/(?:user/\d+|data)/[^\s"']+|/storage/emulated/\d+/[^\s"']+""")

    fun redact(text: String, privateValues: Collection<String> = emptyList()): String {
        var result = links.replace(text, "[link hidden]")
        result = authorization.replace(result, "[authorization hidden]")
        result = realityKeys.replace(result) { it.groupValues[1] + "[key hidden]" }
        result = credentials.replace(result, "[credentials hidden]")
        result = uuid.replace(result, "[UUID hidden]")
        result = ipv6.replace(result) { match ->
            // Clock timestamps have only two colons and a fractional suffix; do not mistake them for IPv6.
            val value = match.value
            if (value.count { it == ':' } >= 3 || "::" in value) "[IPv6 hidden]" else value
        }
        result = ipv4.replace(result) { if (it.value == "127.0.0.1") it.value else "[IP hidden]" }
        result = paths.replace(result, "[local path hidden]")
        privateValues.asSequence().filter { it.length >= 4 }.distinct().sortedByDescending { it.length }.forEach {
            result = result.replace(it, "[hidden]", ignoreCase = true)
        }
        return result
    }
}
