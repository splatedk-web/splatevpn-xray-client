package com.danieldk.splatevpn.vpn

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/** Domain filtering only: never block shared VK/YouTube API or video CDN domains.
 * RU additions checked against https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt
 * on 2026-10-17. Keep this list limited to dedicated advertising/measurement hosts.
 */
internal object AdBlockingPolicy {
    val domains = listOf(
        "an.yandex.ru", "ads.yandex.ru", "adv.yandex.ru", "adfox.ru", "banners.adfox.net",
        "adfox.yandex.ru", "awaps.yandex.ru", "awaps.yandex.net", "bs.yandex.ru",
        "direct.yandex.ru", "adsdk.yandex.ru", "adfstat.yandex.ru", "amc.yandex.ru",
        "banners.mobile.yandex.net", "banners-slb.mobile.yandex.net", "offerwall.yandex.net",
        "yandexadexchange.net", "easy-monetization.s3.yandex.net",
        "metrika.yandex.ru", "mc.yandex.ru", "mc.yandex.com", "mc.yandex.md",
        "mc.webvisor.com", "mc.webvisor.org", "webvisor.com", "yandexmetrica.com",
        "appmetrica.yandex.net", "appmetrica.yandex.com", "analytics.mobile.yandex.net",
        "target.my.com", "ad.mail.ru", "alt-ad.mail.ru", "an.mail.ru", "rs.mail.ru",
        "r0.mail.ru", "relap.mail.ru", "media-advcycle.imgsmail.ru", "r.my.com", "alt-r.my.com",
        "top-fwz1.mail.ru", "top-staging.mail.ru", "1l-hit.mail.ru", "media-stat.mail.ru",
        "stat.radar.imgsmail.ru", "tracker-api.my.com", "stats.vk-portal.net",
        "adriver.ru", "begun.ru", "tns-counter.ru", "mediascope.net", "rutarget.ru",
        "recreativ.ru", "sape.ru", "marketgid.com", "moevideo.biz", "traffmag.com",
        "luckyads.pro", "advertis.ru", "kadam.net", "slickjump.com",
        "mdp-appconf-row.heytapdl.com", "mdp-appconf-sg.heytapdl.com",
        // Dedicated ads/telemetry, verified against HaGeZi PRO and its MIUI issue #9311.
        // Do not block BugSnag/Sentry dashboards or shared business APIs just to score 100%.
        "ads-api.tiktok.com", "ads-sg.tiktok.com", "log.byteoversea.com",
        "udcm.yahoo.com", "log.fc.yahoo.com", "gemini.yahoo.com",
        "adtech.yahooinc.com", "tracking.rus.miui.com"
    )
    val matchers get() = listOf("geosite:category-ads-all") + domains.map { "domain:$it" }
    private fun matchers(extended: String?, allow: List<String>): List<String> = when {
        // Retain the existing global coverage. With exceptions, only the compiled base can
        // guarantee that an allowed child will not match an opaque legacy geosite parent.
        extended != null -> listOf(extended) + if (allow.isEmpty()) listOf("geosite:category-ads-all") else emptyList()
        allow.isEmpty() -> matchers
        // The old geosite category cannot express exceptions: prefer usable sites in fallback mode.
        else -> domains.filter { domain -> allow.none { domain == it || domain.endsWith(".$it") || it.endsWith(".$domain") } }.map { "domain:$it" }
    }

    fun addDnsHosts(hosts: JsonObject, extended: String? = null, allow: List<String> = emptyList()) {
        // Both address families; evaluated before upstream DNS and FakeDNS.
        matchers(extended, allow).forEach { matcher ->
            hosts.add(matcher, JsonArray().apply { add("0.0.0.0"); add("::") })
        }
    }

    fun rule(outboundTag: String, extended: String? = null, allow: List<String> = emptyList()) = JsonObject().apply {
        addProperty("type", "field")
        addProperty("outboundTag", outboundTag)
        add("domain", JsonArray().apply { matchers(extended, allow).forEach { add(it) } })
    }

    fun enableSniffing(inbound: JsonObject) {
        val sniffing = inbound.get("sniffing")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: JsonObject().also { inbound.add("sniffing", it) }
        sniffing.addProperty("enabled", true)
        sniffing.addProperty("metadataOnly", false)
        // Imported profiles may depend on FakeDNS or other existing overrides.
        val overrides = sniffing.get("destOverride")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: JsonArray().also { sniffing.add("destOverride", it) }
        listOf("http", "tls", "quic").forEach { protocol ->
            if (overrides.none { it.asString == protocol }) overrides.add(protocol)
        }
    }

    fun applyToImported(config: JsonObject, inbound: JsonObject, extended: String? = null, allow: List<String> = emptyList()) {
        fun obj(parent: JsonObject, key: String) = parent.get(key)?.takeIf { it.isJsonObject }?.asJsonObject
            ?: JsonObject().also { parent.add(key, it) }
        val outbounds = config.get("outbounds")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: JsonArray().also { config.add("outbounds", it) }
        // Xray's implicit default is freedom. Do not turn an omitted default into blackhole.
        if (outbounds.size() == 0) outbounds.add(JsonObject().apply { addProperty("protocol", "freedom") })
        fun uniqueTag(base: String): String {
            val used = outbounds.mapNotNull { it.asJsonObject.get("tag")?.asString }.toSet()
            return generateSequence(base) { "$it-1" }.first { it !in used }
        }
        val blockTag = uniqueTag("splate-ad-block")
        outbounds.add(JsonObject().apply { addProperty("tag", blockTag); addProperty("protocol", "blackhole") })
        val dnsTag = uniqueTag("splate-ad-dns")
        outbounds.add(JsonObject().apply { addProperty("tag", dnsTag); addProperty("protocol", "dns") })
        val dns = obj(config, "dns")
        addDnsHosts(obj(dns, "hosts"), extended, allow)
        enableSniffing(inbound)
        if (inbound.get("tag")?.asString.isNullOrBlank()) inbound.addProperty("tag", "splate-managed-socks")
        val routing = obj(config, "routing")
        val oldRules = routing.get("rules")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
        routing.add("rules", JsonArray().apply {
            rule(blockTag, extended, allow).takeIf { it.getAsJsonArray("domain").size() > 0 }?.let { add(it) }
            add(JsonObject().apply {
                addProperty("type", "field")
                add("inboundTag", JsonArray().apply { add(inbound.get("tag").asString) })
                addProperty("port", "53")
                addProperty("outboundTag", dnsTag)
            })
            oldRules.forEach { add(it) }
        })
    }
}
