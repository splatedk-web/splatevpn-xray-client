package com.danieldk.splatevpn.vpn

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Tor has no UDP relay. Resolve names inside the tunnel and never inherit direct routes. */
internal object TorRoutingPolicy {
    fun apply(configJson: String): String {
        val config = JsonParser.parseString(configJson).asJsonObject
        val outbounds = config.getAsJsonArray("outbounds")
        val proxy = outbounds.first { it.asJsonObject.get("tag")?.asString == "proxy" }.asJsonObject
        require(proxy.get("protocol")?.asString == "socks")
        proxy.addProperty("targetStrategy", "AsIs")
        proxy.remove("mux")
        config.add("outbounds", JsonArray().apply {
            add(proxy)
            add(JsonObject().apply { addProperty("tag", "dns-out"); addProperty("protocol", "dns") })
            add(JsonObject().apply { addProperty("tag", "block"); addProperty("protocol", "blackhole") })
        })
        config.getAsJsonObject("dns").apply {
            add("servers", JsonArray().apply { add("fakedns"); add("https://1.1.1.1/dns-query") })
            addProperty("queryStrategy", "UseIPv4")
        }
        // FakeDNS preserves hostnames, including .onion, until the SOCKS request reaches Tor.
        config.add("fakedns", JsonArray().apply {
            add(JsonObject().apply { addProperty("ipPool", "198.18.0.0/15"); addProperty("poolSize", 65535) })
        })
        config.getAsJsonArray("inbounds").forEach { element ->
            element.asJsonObject.add("sniffing", JsonObject().apply {
                addProperty("enabled", true)
                addProperty("routeOnly", false)
                add("destOverride", JsonArray().apply { add("fakedns"); add("http"); add("tls") })
            })
        }
        val rules = JsonArray()
        rules.add(JsonObject().apply {
            addProperty("type", "field"); addProperty("port", "53"); addProperty("outboundTag", "dns-out")
        })
        // Preserve explicit ad blocking without retaining regional, SmartDNS or custom bypasses.
        config.getAsJsonObject("routing").getAsJsonArray("rules").forEach { element ->
            val rule = element.asJsonObject
            if (rule.get("outboundTag")?.asString == "block" && rule.has("domain")) rules.add(rule.deepCopy())
        }
        rules.add(JsonObject().apply {
            addProperty("type", "field"); addProperty("network", "udp"); addProperty("outboundTag", "block")
        })
        rules.add(JsonObject().apply {
            addProperty("type", "field"); addProperty("network", "tcp"); addProperty("outboundTag", "proxy")
        })
        config.add("routing", JsonObject().apply {
            addProperty("domainStrategy", "AsIs"); add("rules", rules)
        })
        return config.toString()
    }
}
