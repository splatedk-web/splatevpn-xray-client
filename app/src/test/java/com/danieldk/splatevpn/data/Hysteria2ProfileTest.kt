package com.danieldk.splatevpn.data

import com.danieldk.splatevpn.vpn.XrayConfigGenerator
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class Hysteria2ProfileTest {
    @Test fun aliasesIpv6AndEscapedCredentialsRoundTrip() {
        val node = requireNotNull(LinkParser.parse("hy2://a%3Ab%2Bc@[::1]/?sni=test.example#Test%20node"))
        assertEquals("a:b+c", node.uuid)
        assertEquals("::1", node.address)
        assertEquals(443, node.port)
        val updated = node.copy(uuid = "changed+password", name = "Updated")
        val restored = requireNotNull(LinkParser.parse(LinkParser.toShareLink(updated)))
        assertEquals(updated.uuid, restored.uuid)
        assertEquals(updated.name, restored.name)
        assertEquals(node.sni, restored.sni)
    }
    @Test fun subscriptionKeepsHysteriaLinksAndMetadata() {
        val raw = "hy2://first@one.example:443,8443\nhysteria2://second@two.example:8443"
        for (payload in listOf(raw, java.util.Base64.getEncoder().encodeToString(raw.toByteArray()))) {
            val nodes = LinkParser.parseSubscription(payload, "sub", "Test")
            assertEquals(2, nodes.size)
            assertTrue(nodes.all { it.protocol == "hysteria2" && it.subId == "sub" })
            assertTrue(Hysteria2Profile.share(nodes.first()).contains(":443,8443/"))
        }
    }
    @Test fun rejectsUnsupportedOrDamagedParametersInsteadOfSilentlyDroppingThem() {
        listOf("?insecure=1", "?pinSHA256=bad", "?obfs=salamander", "?obfs=unknown")
            .forEach { assertNull(Hysteria2Profile.parse("hy2://pass@vpn.example/$it")) }
        assertNull(Hysteria2Profile.parse("hy2://pass@vpn.example:70000"))
        assertNotNull(Hysteria2Profile.parse("hy2://pass@vpn.example/?insecure=1&pinSHA256=${"ab".repeat(32)}"))
    }
    @Test fun authorityHoppingSupportsIpv6ListsRangesAndCompactNormalization() {
        for (host in listOf("vpn.example", "127.0.0.1", "[::1]")) {
            val node = requireNotNull(Hysteria2Profile.parse("hy2://p%3Aass@$host:20000-50000,443,8443-8445,8444/?sni=test.example#Hopping"))
            assertEquals(443, node.port)
            assertEquals(host.removeSurrounding("[", "]"), node.address)
            val outbound = Hysteria2Profile.outbound(node)
            val settings = outbound.getAsJsonObject("streamSettings").getAsJsonObject("finalmask")
                .getAsJsonArray("udp")[0].asJsonObject.getAsJsonObject("settings")
            assertEquals("443,8443-8445,20000-50000", settings["remotePorts"].asString)
            assertEquals(30, settings["interval"].asInt)
            assertEquals("intervalLocal,intervalRemote", settings["mode"].asString)
            val shared = Hysteria2Profile.share(node.copy(name = "Changed", uuid = "new+pass"))
            val restored = requireNotNull(Hysteria2Profile.parse(shared))
            assertEquals("new+pass", restored.uuid)
            assertEquals("Changed", restored.name)
            assertEquals(settings, Hysteria2Profile.outbound(restored).getAsJsonObject("streamSettings")
                .getAsJsonObject("finalmask").getAsJsonArray("udp")[0].asJsonObject.getAsJsonObject("settings"))
        }
    }
    @Test fun legacyHoppingAliasesExportAsMultiPortAuthority() {
        for (alias in listOf("mport", "ports")) {
            val node = requireNotNull(Hysteria2Profile.parse("hy2://pass@[::1]:443/?$alias=21000-21005,23000&hop-interval=5s"))
            assertEquals(21000, node.port)
            val shared = Hysteria2Profile.share(node)
            assertTrue(shared, shared.contains("@[::1]:21000-21005,23000/"))
            assertFalse(shared, shared.contains("$alias="))
            val restored = requireNotNull(LinkParser.parse(shared))
            assertEquals(Hysteria2Profile.outbound(node), Hysteria2Profile.outbound(restored))
        }
    }
    @Test fun hoppingAndSalamanderUseTheSameTransportForConnectionAndProbe() {
        val node = requireNotNull(Hysteria2Profile.parse("hy2://pass@vpn.example:443,8443/?hopInterval=6&obfs=salamander&obfs-password=secret"))
        val generator = XrayConfigGenerator()
        for (raw in listOf(generator.generateConfig(node), generator.generateTestConfig(node, 24991))) {
            val proxy = JsonParser.parseString(raw).asJsonObject.getAsJsonArray("outbounds")
                .first { it.asJsonObject["tag"].asString == "proxy" }.asJsonObject
            val masks = proxy.getAsJsonObject("streamSettings").getAsJsonObject("finalmask").getAsJsonArray("udp")
            assertEquals(listOf("salamander", "udphop"), masks.map { it.asJsonObject["type"].asString })
            assertEquals(6, masks[1].asJsonObject.getAsJsonObject("settings")["interval"].asInt)
        }
    }
    @Test fun rejectsDamagedOrConflictingPortPoolsAndIntervals() {
        for (pool in listOf("", "0", "65536", "443,", "443,,8443", "9000-8000", "1-65536", "-1", "443-", "abc", "1-2-3")) {
            assertNull(pool, Hysteria2Profile.parse("hy2://pass@vpn.example:$pool/"))
            assertNull(pool, Hysteria2Profile.parse("hy2://pass@vpn.example/?mport=$pool"))
        }
        for (query in listOf("mport=443,8443&ports=443,9443", "hop-interval=4", "hop-interval=0", "hopInterval=bad",
            "hopInterval=5&hop-interval=6", "hop-interval=99999999999999999")) {
            assertNull(query, Hysteria2Profile.parse("hy2://pass@vpn.example:443,8443/?$query"))
        }
        assertNull(Hysteria2Profile.parse("hy2://pass@vpn.example:443,8443/?mport=443,9443"))
        assertNotNull(Hysteria2Profile.parse("hy2://pass@vpn.example:443,8443/?mport=8443,443"))
    }
    @Test fun singlePortDoesNotEnableHoppingAndEditsStillUpdateExport() {
        val node = requireNotNull(Hysteria2Profile.parse("hy2://pass@vpn.example:443/?mport=8443"))
        assertEquals(8443, node.port)
        assertFalse(Hysteria2Profile.outbound(node).getAsJsonObject("streamSettings").has("finalmask"))
        val restored = requireNotNull(Hysteria2Profile.parse(Hysteria2Profile.share(node.copy(port = 9443))))
        assertEquals(9443, restored.port)
    }
    @Test fun explicitPortEditOverridesImportedHoppingPool() {
        val node = requireNotNull(Hysteria2Profile.parse("hy2://pass@vpn.example:443,8443/?hop-interval=5"))
        val edited = node.copy(port = 9443)
        val shared = Hysteria2Profile.share(edited)
        val restored = requireNotNull(Hysteria2Profile.parse(shared))
        assertEquals(9443, restored.port)
        assertFalse(Hysteria2Profile.outbound(edited).getAsJsonObject("streamSettings").has("finalmask"))
        assertEquals(Hysteria2Profile.outbound(edited), Hysteria2Profile.outbound(restored))
    }
    @Test fun protocolChangeDoesNotReusePreviousProtocolParameters() {
        val node = requireNotNull(LinkParser.parse("vless://key@vpn.example:443/?type=tcp&security=tls&sni=old.example"))
            .copy(protocol = "hysteria2", uuid = "pass", sni = "new.example")
        val restored = requireNotNull(Hysteria2Profile.parse(Hysteria2Profile.share(node)))
        assertEquals(Hysteria2Profile.outbound(node), Hysteria2Profile.outbound(restored))
        assertFalse(Hysteria2Profile.share(node).contains("type="))
    }
    @Test fun productionAndProbeUseNativeQuicAuthAndSalamanderWithoutTcpFragmentOrMux() {
        val node = requireNotNull(LinkParser.parse("hy2://a+b@vpn.example:443/?sni=tls.example&obfs=salamander&obfs-password=x%2By"))
        val generator = XrayConfigGenerator()
        for (raw in listOf(generator.generateConfig(node), generator.generateTestConfig(node, 24991, enableMux = true, enableTlsFragment = true))) {
            val proxy = JsonParser.parseString(raw).asJsonObject.getAsJsonArray("outbounds")
                .first { it.asJsonObject["tag"].asString == "proxy" }.asJsonObject
            assertEquals("hysteria", proxy["protocol"].asString)
            assertEquals(2, proxy.getAsJsonObject("settings")["version"].asInt)
            val stream = proxy.getAsJsonObject("streamSettings")
            assertEquals("hysteria", stream["network"].asString)
            assertEquals("a+b", stream.getAsJsonObject("hysteriaSettings")["auth"].asString)
            assertEquals("tls.example", stream.getAsJsonObject("tlsSettings")["serverName"].asString)
            assertEquals("x+y", stream.getAsJsonObject("finalmask").getAsJsonArray("udp")[0].asJsonObject
                .getAsJsonObject("settings")["password"].asString)
            assertFalse(proxy.has("mux"))
            assertFalse(stream.has("sockopt"))
        }
    }
}
