package com.danieldk.splatevpn.vpn

import com.google.gson.JsonParser
import com.google.gson.JsonObject
import com.danieldk.splatevpn.data.*
import org.junit.Assert.*
import org.junit.Test

class AiDnsResolverRoutingTest {
    private val generator = XrayConfigGenerator()
    private val node = ServerNode("fixture", "fixture", "vless", "vpn.example", 443, uuid = "11111111-2222-3333-4444-555555555555", security = "tls")
    private fun route(root: JsonObject, host: String) = root.getAsJsonObject("routing").getAsJsonArray("rules").map { it.asJsonObject }
        .first { rule -> rule.getAsJsonArray("domain")?.any { it.asString == "full:$host" } == true }["outboundTag"].asString
    @Test fun fragmentStrategyCoversResolverTlsInProbeAndMainConfig() {
        val probe = JsonParser.parseString(generator.generateAiDnsProbeConfig("dns_ai", true, 25001)).asJsonObject
        assertEquals("provider-probe", route(probe, "dns.dns-ai.ru"))
        val outbound = probe.getAsJsonArray("outbounds").first { it.asJsonObject["tag"].asString == "provider-probe" }.asJsonObject
        assertEquals("tlshello", outbound.getAsJsonObject("settings").getAsJsonObject("fragment")["packets"].asString)
        val main = JsonParser.parseString(generator.generateConfig(node, bypassAi = true, aiRoutingMode = "dns_ai", aiDnsProvider = "dns_ai", aiTlsFragment = true)).asJsonObject
        assertEquals("ai-direct", route(main, "dns.dns-ai.ru"))
    }
    @Test fun vpnDnsTransportNeverBecomesDirectBecauseFragmentIsEnabled() {
        val root = JsonParser.parseString(generator.generateConfig(node, bypassAi = true, aiRoutingMode = "dns_vpn", aiDnsProvider = "dns_ai", aiTlsFragment = true)).asJsonObject
        assertEquals("proxy", route(root, "dns.dns-ai.ru"))
        val direct = JsonParser.parseString(generator.generateAiDnsProbeConfig("dns_ai", false, 25001)).asJsonObject
        assertEquals("dns-probe", route(direct, "dns.dns-ai.ru"))
    }
}
