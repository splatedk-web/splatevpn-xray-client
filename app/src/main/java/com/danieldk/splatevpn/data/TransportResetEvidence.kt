package com.danieldk.splatevpn.data

/** A closed local stream, DNS failure or direct website is not evidence about the VPN server. */
internal fun isRelevantProxyReset(line: String): Boolean {
    val lower = line.lowercase()
    return "connection reset" in lower &&
        ("proxy/vless/outbound:" in lower || "proxy/vmess/outbound:" in lower ||
            "proxy/trojan:" in lower || "proxy/shadowsocks:" in lower) &&
        "proxy/freedom:" !in lower && "app/dns:" !in lower &&
        !Regex("(?:127\\.0\\.0\\.1|\\[::1]):\\d+->").containsMatchIn(lower)
}
