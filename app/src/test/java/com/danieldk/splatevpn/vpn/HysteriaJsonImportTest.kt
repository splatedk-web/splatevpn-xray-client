package com.danieldk.splatevpn.vpn

import com.danieldk.splatevpn.data.LinkParser
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class HysteriaJsonImportTest {
    private fun profile(version: Int = 2) = """{"remarks":"Netherlands","inbounds":[],"outbounds":[{"tag":"proxy","protocol":"hysteria","settings":{"address":"hy.example","port":443,"version":$version},"streamSettings":{"network":"hysteria","security":"tls","hysteriaSettings":{"version":$version,"auth":"test-password"},"tlsSettings":{"serverName":"tls.example","alpn":["h3"],"echConfigList":"test-ech"},"finalmask":{"udp":[{"type":"salamander","settings":{"password":"test-obfs"}}]}}}]}"""

    @Test fun mixedSubscriptionKeepsVlessAndNativeHysteria2() {
        val vless = """{"outbounds":[{"protocol":"vless","settings":{"address":"v.example","port":443,"id":"test-id"},"streamSettings":{"network":"tcp"}}]}"""
        val nodes = LinkParser.parseSubscription("[$vless,${profile()}]", "sub", "WTPN")
        assertEquals(listOf("vless", "hysteria2"), nodes.map { it.protocol })
        val node = nodes.last()
        assertEquals("test-password", node.uuid)
        assertEquals("hysteria", node.network)
        assertEquals("tls.example", node.sni)
        assertEquals("Netherlands", node.name)
        assertEquals("sub", node.subId)
        val config = JsonParser.parseString(XrayConfigGenerator().generateConfig(node,
            enableMux = true, enableTlsFragment = true)).asJsonObject
        val outbound = config.getAsJsonArray("outbounds")[0].asJsonObject
        assertEquals("hysteria", outbound["protocol"].asString)
        assertFalse(outbound.has("mux"))
        val stream = outbound.getAsJsonObject("streamSettings")
        assertEquals("test-password", stream.getAsJsonObject("hysteriaSettings")["auth"].asString)
        assertEquals("test-ech", stream.getAsJsonObject("tlsSettings")["echConfigList"].asString)
        assertEquals("test-obfs", stream.getAsJsonObject("finalmask").getAsJsonArray("udp")[0].asJsonObject
            .getAsJsonObject("settings")["password"].asString)
        assertFalse(stream.getAsJsonObject("sockopt")?.has("dialerProxy") == true)
    }

    @Test fun legacyHysteriaIsNotMisidentifiedAsHysteria2() {
        assertTrue(LinkParser.parseSubscription(profile(1)).isEmpty())
    }

    @Test fun unrelatedJsonDialectIsNotImportedWithEmptyCredentials() {
        assertTrue(LinkParser.parseSubscription("""{"type":"hysteria2","server":"test.example","server_port":443,"password":"secret"}""").isEmpty())
    }
}
