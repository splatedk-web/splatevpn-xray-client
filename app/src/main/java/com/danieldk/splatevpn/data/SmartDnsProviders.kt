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

/** Operator endpoints reviewed on 2026-11-12; current reachability is checked on the user's route. */
object SmartDnsProviders {
    val all = listOf(
        SmartDnsProvider("dns_ai", "DNS-AI", "dns.dns-ai.ru", listOf("194.87.196.13", "45.151.102.219")),
        SmartDnsProvider("geohide", "GeoHide RU", "geohide.ru", listOf("159.194.200.33", "193.233.112.67", "193.233.112.88", "193.233.112.68")),
        SmartDnsProvider("geohide_eu", "GeoHide EU", "eu.geohide.ru", listOf("5.175.161.3", "5.175.161.40", "45.13.237.213")),
        SmartDnsProvider("malw", "MALW", "dns.malw.link", listOf("164.5.224.81", "179.254.166.171")),
        SmartDnsProvider("malw_gateway", "MALW · CF", "5u35p8m9i7.cloudflare-gateway.com", listOf("162.159.36.20", "162.159.36.5")),
        SmartDnsProvider("zapret_dns", "Zapret DNS", "dns.zapret.moe", listOf("83.217.211.149")),
        SmartDnsProvider("dagami", "Dagami", "dns.dagami.ru", listOf("48.102.213.179")),
        SmartDnsProvider("wlrus", "WLRus", "dns.wlrus.cfd", listOf("95.85.240.254", "138.124.254.134")),
        SmartDnsProvider("geodema", "Geodema · Beta", "dns.geodema.love", listOf("179.236.130.11", "179.236.130.10"))
    )

    // Migrate removed legacy presets (Comss / Xbox) safely to DNS-AI.
    fun find(key: String): SmartDnsProvider? {
        val normalized = key.lowercase(Locale.ROOT).let {
            if (it == "comss" || it == "comss_smart" || it == "xbox") "dns_ai" else it
        }
        return all.firstOrNull { it.key == normalized }
    }

    fun forAi(key: String): SmartDnsProvider = find(key) ?: all.first()
}
