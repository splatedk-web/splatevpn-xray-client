package com.danieldk.splatevpn.vpn

import com.danieldk.splatevpn.data.ServerNode
import com.danieldk.splatevpn.data.SubscriptionEntity
import com.danieldk.splatevpn.data.SubscriptionRoutingRules
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class SubscriptionRoutingRulesIntegrationTest {
    private val node = ServerNode("node", "Test", "trojan", "vpn.example", 443, uuid = "test-password", security = "tls", subId = "source")
    private val source = """{
      "inbounds":[{"listen":"0.0.0.0","port":60000,"protocol":"socks"}],
      "dns":{"servers":["http://remote-dns.invalid/dns-query"]},
      "outbounds":[
        {"protocol":"trojan","tag":"remote","settings":{"servers":[{"address":"vpn.example","port":443,"password":"test-password"}]},"streamSettings":{"network":"tcp","security":"tls"}},
        {"tag":"local","protocol":"freedom"}
      ],
      "routing":{"rules":[{"type":"field","domain":["full:example.com"],"outboundTag":"local"}]}
    }"""

    @Test fun explicitUserRulesAndDnsSafetyRemainAboveApprovedSubscriptionRules() {
        val parsed = SubscriptionRoutingRules.parse(source)
        assertEquals(0, parsed.skipped)
        val imported = SubscriptionRoutingRules.rules(parsed.json)
        val before = imported.map { it.toString() }
        val generated = JsonParser.parseString(XrayConfigGenerator().generateConfig(node,
            customRoutes = listOf("full:example.com" to "block"), subscriptionRules = imported)).asJsonObject
        val rules = generated.getAsJsonObject("routing").getAsJsonArray("rules").map { it.asJsonObject }
        fun routeIndex(target: String) = rules.indexOfFirst {
            it["outboundTag"]?.asString == target && it.getAsJsonArray("domain")?.any { domain -> domain.asString == "full:example.com" } == true
        }
        val user = routeIndex("block")
        val subscription = routeIndex("direct")
        assertTrue(user >= 0)
        assertTrue(subscription > user)
        val interceptedDns = rules.indexOfFirst { it["outboundTag"]?.asString == "dns-out" }
        assertTrue(interceptedDns in 0 until subscription)
        val ipv6Block = rules.indexOfFirst { it["outboundTag"]?.asString == "block" && it.getAsJsonArray("ip")?.any { ip -> ip.asString == "::/0" } == true }
        assertTrue(ipv6Block in 0 until subscription)
        assertEquals(before, imported.map { it.toString() })
    }

    @Test fun optedInRulesDoNotReintroduceRemoteListenersOrDnsFromTheFullProfile() {
        val parsed = SubscriptionRoutingRules.parse(source)
        val subscription = SubscriptionEntity("source", "Source", "https://example.com/sub", useSubscriptionRules = true, importedRulesJson = parsed.json)
        val active = SubscriptionRoutingRules.activeFor(node, listOf(subscription))
        val generated = JsonParser.parseString(XrayConfigGenerator().generateConfig(node.copy(fullConfigJson = source),
            subscriptionRules = active)).asJsonObject
        assertFalse(generated.toString().contains("remote-dns.invalid"))
        assertFalse(generated.getAsJsonArray("inbounds").any { it.asJsonObject["listen"]?.asString == "0.0.0.0" })
        val rules = generated.getAsJsonObject("routing").getAsJsonArray("rules").map { it.asJsonObject }
        assertTrue(rules.any { it == active.first() })
        assertEquals("proxy", generated.getAsJsonArray("outbounds")[0].asJsonObject["tag"].asString)
        val disabled = SubscriptionRoutingRules.activeFor(node, listOf(subscription.copy(useSubscriptionRules = false)))
        assertTrue(disabled.isEmpty())
    }
}
