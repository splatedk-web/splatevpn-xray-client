package com.danieldk.splatevpn.vpn

import com.danieldk.splatevpn.data.ServerNode
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class TorRoutingPolicyTest {
    private val tor = ServerNode("splate-tor", "Tor", "socks", "127.0.0.1", 9050,
        encryption = "", security = "none")
    private val generator = XrayConfigGenerator()

    private fun apply(input: String): JsonObject =
        JsonParser.parseString(TorRoutingPolicy.apply(input)).asJsonObject

    @Test fun removesRegionalCustomAndSmartDnsBypassesWhileKeepingAdBlocks() {
        val generated = generator.generateConfig(tor, mode = "smart_ru", bypassAi = true,
            aiRoutingMode = "dns_ai", enableMux = true, customRoutes = listOf("domain:example.com" to "direct"))
        val original = JsonParser.parseString(generated).asJsonObject
        val adRules = original.getAsJsonObject("routing").getAsJsonArray("rules")
            .filter { it.asJsonObject["outboundTag"]?.asString == "block" && it.asJsonObject.has("domain") }
        assertTrue("Fixture must include a domain block to preserve", adRules.isNotEmpty())

        val config = apply(generated)
        val outbounds = config.getAsJsonArray("outbounds").map { it.asJsonObject }
        assertEquals(setOf("socks", "dns", "blackhole"), outbounds.map { it["protocol"].asString }.toSet())
        assertEquals(setOf("proxy", "dns-out", "block"), outbounds.map { it["tag"].asString }.toSet())
        assertEquals("proxy", outbounds.first()["tag"].asString)
        assertEquals("127.0.0.1", outbounds.first().getAsJsonObject("settings")
            .getAsJsonArray("servers")[0].asJsonObject["address"].asString)
        assertEquals("AsIs", outbounds.first()["targetStrategy"].asString)
        assertFalse("Tor does not support Xray multiplexing", outbounds.first().has("mux"))

        val routing = config.getAsJsonObject("routing")
        val rules = routing.getAsJsonArray("rules")
        assertEquals("AsIs", routing["domainStrategy"].asString)
        assertTrue(rules.all { it.asJsonObject["outboundTag"].asString in setOf("proxy", "dns-out", "block") })
        adRules.forEach { ad -> assertTrue("Domain block was lost", rules.any { it == ad }) }
        assertEquals(original.getAsJsonObject("dns")["hosts"], config.getAsJsonObject("dns")["hosts"])
    }

    @Test fun interceptsDnsBeforeRejectingAllOtherUdpAndProxyingTcp() {
        val config = apply(generator.generateConfig(tor, blockQuic = false))
        val rules = config.getAsJsonObject("routing").getAsJsonArray("rules").map { it.asJsonObject }
        val dns = rules.indexOfFirst { it["port"]?.asString == "53" && it["outboundTag"]?.asString == "dns-out" }
        val udp = rules.indexOfFirst { it["network"]?.asString == "udp" && it["outboundTag"]?.asString == "block" }
        val tcp = rules.indexOfFirst { it["network"]?.asString == "tcp" && it["outboundTag"]?.asString == "proxy" }
        assertEquals(0, dns)
        assertTrue("DNS must be handled before the UDP rejection", udp > dns)
        assertTrue("TCP must use the Tor SOCKS outbound", tcp > udp)
        assertEquals(rules.lastIndex, tcp)
        assertFalse(rules.any { it["network"]?.asString?.contains("udp") == true && it["outboundTag"]?.asString == "proxy" })
    }

    @Test fun forcesFakeDnsHostnameRecoveryEvenWhenSniffingWasDisabled() {
        val config = apply(generator.generateConfig(tor, enableSniffing = false, routeOnlySniffing = true,
            isFakeDns = false, adBlocker = false, bypassAi = false, shareLan = true,
            lanAccess = LanAccess("192.168.43.1", "splate", "test-password")))
        val dns = config.getAsJsonObject("dns")
        assertEquals(listOf("fakedns", "https://1.1.1.1/dns-query"), dns.getAsJsonArray("servers").map { it.asString })
        assertEquals("UseIPv4", dns["queryStrategy"].asString)
        assertEquals("198.18.0.0/15", config.getAsJsonArray("fakedns")[0].asJsonObject["ipPool"].asString)
        val inbounds = config.getAsJsonArray("inbounds").map { it.asJsonObject }
        assertEquals(3, inbounds.size)
        inbounds.forEach { inbound ->
            val sniffing = inbound.getAsJsonObject("sniffing")
            assertTrue(sniffing["enabled"].asBoolean)
            assertFalse("Tor must receive the recovered hostname rather than the fake IP", sniffing["routeOnly"].asBoolean)
            assertTrue(sniffing.getAsJsonArray("destOverride").any { it.asString == "fakedns" })
        }
        assertEquals("127.0.0.1", inbounds.first()["listen"].asString)
        inbounds.drop(1).forEach { inbound ->
            assertEquals("192.168.43.1", inbound["listen"].asString)
            assertEquals("test-password", inbound.getAsJsonObject("settings")
                .getAsJsonArray("accounts")[0].asJsonObject["pass"].asString)
        }
    }

    @Test fun refusesToTreatAnOrdinaryVlessOutboundAsTor() {
        val vless = ServerNode("vless", "VPN", "vless", "example.com", 443,
            uuid = "00000000-0000-0000-0000-000000000001", security = "tls")
        assertThrows(IllegalArgumentException::class.java) { apply(generator.generateConfig(vless)) }
    }
}
