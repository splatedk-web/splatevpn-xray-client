package com.danieldk.splatevpn.data

import java.util.Locale

/** Region describes local routing exceptions, never the location of the VPN server. */
enum class RoutingRegion(val id: String, val domains: List<String>, val geoIp: String?) {
    RUSSIA("ru", listOf("geosite:category-ru", "geosite:tld-ru", "geosite:yandex", "geosite:vk", "geosite:mailru", "domain:ru", "domain:su", "domain:xn--p1ai"), "geoip:ru"),
    CHINA("cn", listOf("geosite:cn", "geosite:geolocation-cn", "geosite:tld-cn"), "geoip:cn"),
    IRAN("ir", listOf("geosite:category-ir"), "geoip:ir"),
    INTERNATIONAL("international", emptyList(), null);

    companion object {
        fun fromId(id: String?): RoutingRegion = entries.firstOrNull { it.id == id } ?: INTERNATIONAL
        fun suggested(locale: Locale): RoutingRegion = when (locale.country.uppercase(Locale.ROOT)) {
            "RU" -> RUSSIA
            "CN" -> CHINA
            "IR" -> IRAN
            else -> INTERNATIONAL
        }
        fun language(locale: Locale): String = when (locale.language) {
            "ru" -> "ru"
            "zh" -> "zh-Hans"
            "fa" -> "fa"
            else -> "en"
        }
    }
}
