package com.danieldk.splatevpn.vpn

import com.danieldk.splatevpn.data.AiDnsVpnRoute
import com.danieldk.splatevpn.data.LinkParser
import com.danieldk.splatevpn.data.ServerNode
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class AiDnsVpnProbeConfigTest {
    private val generator = XrayConfigGenerator()
    private val node = ServerNode("vpn-node", "test", "vless", "vpn.example", 443,
        uuid = "11111111-2222-3333-4444-555555555555", security = "tls", sni = "vpn.example")

    @Test fun dnsAndSmartDnsRelayUseTheSelectedVpnWithoutDirectFallback() {
        val config = JsonParser.parseString(generator.generateAiDnsProbeConfig("dns_ai", fragment = true,
            socksPort = 23456, viaVpn = true, vpnRoute = AiDnsVpnRoute(node))).asJsonObject
        val outbounds = config.getAsJsonArray("outbounds").map { it.asJsonObject }.associateBy { it["tag"].asString }
        assertEquals("vless", outbounds.getValue("proxy")["protocol"].asString)
        val relay = outbounds.getValue("ai-probe")
        assertEquals("ForceIPv4", relay.getAsJsonObject("streamSettings").getAsJsonObject("sockopt")["domainStrategy"].asString)
        assertEquals("proxy", relay.getAsJsonObject("streamSettings").getAsJsonObject("sockopt")["dialerProxy"].asString)
        assertFalse(relay.getAsJsonObject("settings").has("fragment"))
        val rules = config.getAsJsonObject("routing").getAsJsonArray("rules").map { it.asJsonObject }
        assertEquals("proxy", rules.last()["outboundTag"].asString)
        assertTrue(rules.none { it["outboundTag"].asString in setOf("direct", "dns-probe") })
        assertEquals("proxy", rules.first()["outboundTag"].asString)
        val dns = config.getAsJsonObject("dns")
        assertTrue(dns["disableFallbackIfMatch"].asBoolean)
        val local = dns.getAsJsonArray("servers").filter { it.isJsonObject && it.asJsonObject["address"]?.asString == "localhost" }
        assertEquals(1, local.size)
        assertEquals(listOf("full:vpn.example"), local.single().asJsonObject.getAsJsonArray("domains").map { it.asString })
        assertEquals("127.0.0.1", config.getAsJsonArray("inbounds")[0].asJsonObject["listen"].asString)
    }

    @Test fun missingVpnProfileCannotGenerateADirectProbe() {
        assertThrows(IllegalArgumentException::class.java) {
            generator.generateAiDnsProbeConfig("dns_ai", false, 23456, viaVpn = true)
        }
    }

    @Test fun capturedTransportOptionsAreUsedForTheVpnOutbound() {
        val route = AiDnsVpnRoute(node.copy(network = "xhttp"), xhttpMode = "stream-one", tlsFragment = true,
            tlsFragmentLength = "51-52", mux = false)
        val config = JsonParser.parseString(generator.generateAiDnsProbeConfig("malw", false, 23456,
            viaVpn = true, vpnRoute = route)).asJsonObject
        val outbounds = config.getAsJsonArray("outbounds").map { it.asJsonObject }.associateBy { it["tag"].asString }
        assertEquals("stream-one", outbounds.getValue("proxy").getAsJsonObject("streamSettings")
            .getAsJsonObject("xhttpSettings")["mode"].asString)
        assertEquals("51-52", outbounds.getValue("fragment").getAsJsonObject("settings")
            .getAsJsonObject("fragment")["length"].asString)
    }

    @Test fun importedJsonKeepsItsReachableChainWhileAppOwnsDnsRoutingAndListeners() {
        val raw = """{"inbounds":[{"listen":"0.0.0.0","port":5555,"protocol":"socks"}],"dns":{"servers":["untrusted-resolver.invalid"]},"routing":{"rules":[{"type":"field","network":"tcp,udp","outboundTag":"unused"}]},"outbounds":[{"tag":"original","protocol":"trojan","settings":{"address":"vpn.example","port":443,"password":"example-password"},"streamSettings":{"network":"tcp","security":"tls","sockopt":{"dialerProxy":"hop"}}},{"tag":"hop","protocol":"socks","settings":{"servers":[{"address":"hop.example","port":1080}]}},{"tag":"unused","protocol":"freedom"}]}"""
        val imported = LinkParser.parseSubscription(raw).single()
        val config = JsonParser.parseString(generator.generateAiDnsProbeConfig("dns_ai", false, 23456,
            viaVpn = true, vpnRoute = AiDnsVpnRoute(imported))).asJsonObject
        val outbounds = config.getAsJsonArray("outbounds").map { it.asJsonObject }.associateBy { it["tag"].asString }
        assertEquals("trojan", outbounds.getValue("proxy")["protocol"].asString)
        assertEquals("import-hop", outbounds.getValue("proxy").getAsJsonObject("streamSettings")
            .getAsJsonObject("sockopt")["dialerProxy"].asString)
        assertEquals("socks", outbounds.getValue("import-hop")["protocol"].asString)
        assertFalse(outbounds.containsKey("unused"))
        assertEquals("proxy", outbounds.getValue("ai-probe").getAsJsonObject("streamSettings")
            .getAsJsonObject("sockopt")["dialerProxy"].asString)
        assertFalse(config.getAsJsonObject("dns").toString().contains("untrusted-resolver.invalid"))
        assertTrue(config.getAsJsonObject("dns").toString().contains("dns.dns-ai.ru"))
        assertFalse(config.getAsJsonObject("routing").toString().contains("unused"))
        val inbound = config.getAsJsonArray("inbounds").single().asJsonObject
        assertEquals("127.0.0.1", inbound["listen"].asString)
        assertEquals(23456, inbound["port"].asInt)
    }
}
