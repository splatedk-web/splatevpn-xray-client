package com.danieldk.splatevpn.vpn

import com.danieldk.splatevpn.data.AdvancedRouteRule
import com.danieldk.splatevpn.data.AdvancedRoutingRules
import com.danieldk.splatevpn.data.NetworkPreferences
import com.danieldk.splatevpn.data.ServerNode
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class AdvancedRoutingIntegrationTest {
    private val node = ServerNode("test", "Test", "vless", "203.0.113.1", 443, uuid = "key")
    @Test fun advancedRulesFollowExplicitOverridesAndPrecedeSubscriptionAndDefaults() {
        val rule = AdvancedRouteRule("logical", "Shop", suffixes = listOf("ozon.ru"), keywords = listOf("ozon"), outbound = "block")
        val subscription = JsonParser.parseString("""{"type":"field","domain":["domain:ozon.ru"],"outboundTag":"direct"}""").asJsonObject
        val root = JsonParser.parseString(XrayConfigGenerator().generateConfig(node, customRoutes = listOf("full:safe.ozon.ru" to "proxy"),
            advancedRules = listOf(rule), subscriptionRules = listOf(subscription))).asJsonObject
        val rules = root.getAsJsonObject("routing").getAsJsonArray("rules").map { it.asJsonObject }
        val explicit = rules.indexOfFirst { it.getAsJsonArray("domain")?.any { selector -> selector.asString == "full:safe.ozon.ru" } == true }
        val advanced = rules.indexOfFirst { it["ruleTag"]?.asString == "adv-rule-logical" }
        val remote = rules.indexOfFirst { it == subscription }
        assertTrue(explicit >= 0 && advanced > explicit && remote > advanced)
        assertEquals("block", rules[advanced]["outboundTag"].asString)
        assertEquals("IPIfNonMatch", root.getAsJsonObject("routing")["domainStrategy"].asString)
    }
    @Test fun ipConditionsResolveOnDemandAndSniffingCannotSilentlyDisableDomainRules() {
        val rule = AdvancedRouteRule("logical", "Match", suffixes = listOf("example.com"), ips = listOf("192.0.2.0/24"))
        val root = JsonParser.parseString(XrayConfigGenerator().generateConfig(node, advancedRules = listOf(rule))).asJsonObject
        assertEquals("IPOnDemand", root.getAsJsonObject("routing")["domainStrategy"].asString)
        assertTrue(runCatching { XrayConfigGenerator().generateConfig(node, enableSniffing = false, advancedRules = listOf(rule)) }.isFailure)
        val preferences = mapOf("advanced_routes_v1" to AdvancedRoutingRules.encode(listOf(rule)))
        assertEquals(preferences, NetworkPreferences.capture(preferences))
        NetworkPreferences.validate(preferences)
        assertTrue(runCatching { NetworkPreferences.validate(mapOf("advanced_routes_v1" to "not-json")) }.isFailure)
    }

    @Test fun ruleTargetingSpecificServerCompilesDedicatedOutboundAndDirectAddress() {
        val dedicatedServer = ServerNode("dedicated-1", "Dedicated Node", "trojan", "198.51.100.22", 8443, uuid = "secret-pass")
        val rule = AdvancedRouteRule(
            id = "custom-dest",
            name = "Specific Service",
            suffixes = listOf("myservice.org"),
            outbound = "proxy",
            targetServerId = "dedicated-1"
        )
        val root = JsonParser.parseString(XrayConfigGenerator().generateConfig(
            node = node,
            advancedRules = listOf(rule),
            allAvailableServers = listOf(node, dedicatedServer)
        )).asJsonObject

        val outbounds = root.getAsJsonArray("outbounds").map { it.asJsonObject }
        val dedicatedOutbound = outbounds.firstOrNull { it["tag"]?.asString == "proxy-server-dedicated-1" }
        assertNotNull("Must generate dedicated proxy outbound for targeted server", dedicatedOutbound)
        assertEquals("trojan", dedicatedOutbound!!["protocol"]?.asString)

        val routingRules = root.getAsJsonObject("routing").getAsJsonArray("rules").map { it.asJsonObject }
        val ruleEntry = routingRules.firstOrNull { it["ruleTag"]?.asString == "adv-rule-custom-dest" }
        assertNotNull("Advanced rule must be compiled into routing rules", ruleEntry)
        assertEquals("proxy-server-dedicated-1", ruleEntry!!["outboundTag"]?.asString)

        val loopPreventionRule = routingRules.firstOrNull {
            it["outboundTag"]?.asString == "direct" &&
                it.getAsJsonArray("ip")?.any { ip -> ip.asString == "198.51.100.22" } == true
        }
        assertNotNull("Direct rule must exist for target server address to prevent proxy loops", loopPreventionRule)
    }

    @Test fun targetServerIdRoundTripSerialization() {
        val rule = AdvancedRouteRule(
            id = "roundtrip",
            name = "Custom Rule",
            domains = listOf("domain.com"),
            outbound = "proxy",
            targetServerId = "srv-12345"
        )
        val encoded = AdvancedRoutingRules.encode(listOf(rule))
        val decoded = AdvancedRoutingRules.decode(encoded)
        assertEquals(1, decoded.size)
        assertEquals("srv-12345", decoded.first().targetServerId)
        assertEquals("proxy", decoded.first().outbound)
    }
}
