package com.danieldk.splatevpn.data

import com.danieldk.splatevpn.vpn.XrayConfigGenerator
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class InternationalRoutingTest {
    @Test fun regionUsesCountryIndependentlyOfLanguage() {
        assertEquals(RoutingRegion.CHINA, RoutingRegion.suggested(Locale.forLanguageTag("en-CN")))
        assertEquals(RoutingRegion.INTERNATIONAL, RoutingRegion.suggested(Locale.forLanguageTag("ru-US")))
        assertEquals(RoutingRegion.INTERNATIONAL, RoutingRegion.suggested(Locale("fa")))
        assertEquals(RoutingRegion.IRAN, RoutingRegion.suggested(Locale.forLanguageTag("fa-IR")))
    }
    @Test fun bootstrapDnsIsLimitedToVpnServerHostname() {
        val node = ServerNode(id = "bootstrap", name = "test", protocol = "vless", address = "vpn.example.com", port = 443,
            uuid = "11111111-2222-3333-4444-555555555555")
        val config = JsonParser.parseString(XrayConfigGenerator().generateConfig(node, mode = "global", routingRegion = "cn", bypassAi = false)).asJsonObject
        val bootstrap = config.getAsJsonObject("dns").getAsJsonArray("servers").first().asJsonObject
        assertEquals("localhost", bootstrap["address"].asString)
        assertTrue(bootstrap["skipFallback"].asBoolean)
        assertEquals(listOf("full:vpn.example.com"), bootstrap.getAsJsonArray("domains").map { it.asString })
        val probe = XrayConfigGenerator().generateTestConfig(node, 25001, routingRegion = "cn")
        assertFalse(probe.contains("77.88.8.8"))
    }
    @Test fun eachRegionHasItsOwnLocalRulesAndNoRussianDnsOutsideRussia() {
        for (region in RoutingRegion.entries) {
            val json = JsonParser.parseString(XrayConfigGenerator().generateConfig(null, mode = "smart", routingRegion = region.id, bypassAi = false)).asJsonObject
            val rules = json.getAsJsonObject("routing").getAsJsonArray("rules").map { it.asJsonObject }
            val local = rules.filter { it["outboundTag"]?.asString == "direct" }.flatMap { it.getAsJsonArray("domain")?.map { d -> d.asString }.orEmpty() }
            assertTrue(local.containsAll(region.domains))
            if (region != RoutingRegion.RUSSIA) {
                assertFalse(local.contains("geosite:category-ru"))
                assertFalse(json.getAsJsonObject("dns").toString().contains("77.88.8.8"))
            }
        }
    }
    @Test fun fullTunnelDoesNotInheritRegionalDnsOrCatchallUdpBypass() {
        for (region in RoutingRegion.entries) {
            val config = JsonParser.parseString(XrayConfigGenerator().generateConfig(null, mode = "global", routingRegion = region.id, bypassAi = false)).asJsonObject
            assertFalse(config.getAsJsonObject("dns").toString().contains("77.88.8.8"))
            val rules = config.getAsJsonObject("routing").getAsJsonArray("rules").map { it.asJsonObject }
            assertFalse(rules.any { it["network"]?.asString == "udp" && it["outboundTag"]?.asString == "direct" })
            assertEquals("tcp,udp", rules.last()["network"].asString)
        }
    }
    @Test fun oldProfileRestoresRussianRegionEvenOnChineseDevice() {
        val restored = NetworkPreferences.apply(mapOf("routing_region" to "cn"), mapOf("routing_mode" to "smart_ru"))
        assertEquals("ru", restored["routing_region"])
        assertEquals("smart", restored["routing_mode"])
    }
    @Test fun temporaryOverridePrecedesPermanentRule() {
        val config = JsonParser.parseString(XrayConfigGenerator().generateConfig(null,
            customRoutes = listOf("full:example.com" to "proxy", "domain:example.com" to "direct"))).asJsonObject
        val rules = config.getAsJsonObject("routing").getAsJsonArray("rules").map { it.asJsonObject }
        val temporary = rules.indexOfFirst { it.getAsJsonArray("domain")?.toString() == "[\"full:example.com\"]" }
        val permanent = rules.indexOfFirst { it.getAsJsonArray("domain")?.toString() == "[\"domain:example.com\"]" }
        assertTrue(temporary >= 0 && permanent > temporary)
    }
}
