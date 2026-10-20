package com.danieldk.splatevpn.data

import java.util.Locale

data class SmartDnsProvider(
    val key: String,
    val label: String,
    val host: String,
    val bootstrapIpv4: List<String>
) {
    val dohUrl: String get() = "https://$host/dns-query"
}

/** Explicit presets, checked against operator documentation on 2026-10-16. */
object SmartDnsProviders {
    val all = listOf(
        SmartDnsProvider("dns_ai", "DNS-AI", "dns.dns-ai.ru", listOf("194.87.196.13", "186.246.49.127")),
        SmartDnsProvider("geohide", "GeoHide RU", "geohide.ru", listOf("159.194.200.33", "193.233.112.67", "193.233.112.88", "193.233.112.68")),
        SmartDnsProvider("geohide_eu", "GeoHide EU", "eu.geohide.ru", listOf("5.175.161.3", "5.175.161.40", "45.13.237.213")),
        SmartDnsProvider("malw", "MALW", "dns.malw.link", listOf("164.5.224.81", "179.254.166.171")),
        SmartDnsProvider("malw_gateway", "MALW · CF", "5u35p8m9i7.cloudflare-gateway.com", listOf("162.159.36.20", "162.159.36.5")),
        SmartDnsProvider("zapret_dns", "Zapret DNS", "dns.zapret.moe", listOf("83.217.211.149")),
        SmartDnsProvider("comss_smart", "Comss.one", "dns.comss.one", listOf("195.133.25.16", "83.220.169.155", "212.109.195.93"))
    )

    // Preserve previous migration of legacy settings; the restored Comss preset has its own key.
    fun find(key: String): SmartDnsProvider? {
        val normalized = key.lowercase(Locale.ROOT).let { if (it == "comss" || it == "xbox") "dns_ai" else it }
        return all.firstOrNull { it.key == normalized }
    }

    fun forAi(key: String): SmartDnsProvider = find(key) ?: all.first()
}
