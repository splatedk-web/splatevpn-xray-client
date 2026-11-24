package com.danieldk.splatevpn.vpn

import com.danieldk.splatevpn.data.DiagnosticRedactor
import com.danieldk.splatevpn.data.RoutingRegion
import com.danieldk.splatevpn.data.ServerNode
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class AnonymityAndLeakSecurityTest {

    @Test fun publicPlaintextVlessKeepsItsRealAddressForCoreSecurityValidation() {
        val node = vlessServer.copy(security = "none", encryption = "none")
        for (json in listOf(generator.generateConfig(node), generator.generateTestConfig(node, 25001))) {
            val config = JsonParser.parseString(json).asJsonObject
            val proxy = config.getAsJsonArray("outbounds").first().asJsonObject
            assertEquals(node.address, proxy.getAsJsonObject("settings").getAsJsonArray("vnext")[0]
                .asJsonObject["address"].asString)
            assertFalse(config.getAsJsonObject("dns").getAsJsonObject("hosts").has("server.local"))
        }
    }

    @Test fun leakCheckSitesHaveNoSpecialRoutingPrivileges() {
        val sites = listOf("2ip.ru", "2ip.io", "browserleaks.com", "dnsleaktest.com", "ipleak.net", "whoer.net")
        for (mode in listOf("smart", "smart_ru", "global")) {
            val config = JsonParser.parseString(generator.generateConfig(vlessServer, mode = mode, bypassAi = false)).asJsonObject
            val domains = config.getAsJsonObject("routing").getAsJsonArray("rules").flatMap {
                it.asJsonObject.getAsJsonArray("domain")?.map { d -> d.asString }.orEmpty()
            }
            assertFalse("Checkers must follow ordinary rules in $mode", domains.any { d -> sites.any { d.endsWith(it) } })
        }
    }

    @Test fun userCanStillExplicitlyRouteACheckSite() {
        val config = JsonParser.parseString(generator.generateConfig(vlessServer, mode = "smart",
            customRoutes = listOf("full:2ip.ru" to "direct"))).asJsonObject
        val rule = config.getAsJsonObject("routing").getAsJsonArray("rules").map { it.asJsonObject }.first {
            it.getAsJsonArray("domain")?.any { d -> d.asString == "full:2ip.ru" } == true
        }
        assertEquals("direct", rule["outboundTag"].asString)
    }

    private val generator = XrayConfigGenerator()
    private val vlessServer = ServerNode(
        id = "srv-secret-01",
        name = "My-Secret-VPS-Germany",
        protocol = "vless",
        address = "194.87.123.45",
        port = 443,
        uuid = "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d",
        security = "reality",
        sni = "yahoo.com",
        publicKey = "xK9_secret_reality_public_key_here_123456",
        shortId = "1a2b3c4d",
        rawLink = "vless://a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d@194.87.123.45:443?security=reality&sni=yahoo.com&pbk=xK9_secret_reality_public_key_here_123456&sid=1a2b3c4d#My-Secret-VPS-Germany"
    )

    private val torServer = ServerNode(
        id = "splate-tor",
        name = "Tor",
        protocol = "socks",
        address = "127.0.0.1",
        port = 9050,
        security = "none"
    )

    @Test
    fun testDiagnosticRedactorMasksAllSensitiveData() {
        val rawLog = """
            [12:00:01.123] [INFO/Xray]: Connecting to server My-Secret-VPS-Germany at 194.87.123.45:443
            [12:00:01.124] [INFO/Xray]: Config path: /data/user/0/com.danieldk.splatevpn/files/config.json
            [12:00:01.125] [INFO/Xray]: Outbound link: vless://a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d@194.87.123.45:443?pbk=xK9_secret_reality_public_key_here_123456
            [12:00:01.126] [INFO/Xray]: Client UUID: a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d, pbk: "xK9_secret_reality_public_key_here_123456", sid: 1a2b3c4d
            [12:00:01.127] [INFO/Xray]: Local proxy active on tcp 127.0.0.1:10808, client IPv6 2001:0db8:85a3:0000:0000:8a2e:0370:7334
        """.trimIndent()

        val redacted = DiagnosticRedactor.redact(
            rawLog,
            listOf(vlessServer.name, vlessServer.address, vlessServer.uuid, vlessServer.publicKey, vlessServer.shortId)
        )

        // 1. External IPv4 must be completely masked
        assertFalse("Real server IPv4 must not leak", redacted.contains("194.87.123.45"))
        assertTrue("Server IPv4 should be replaced with [IP hidden]", redacted.contains("[IP hidden]"))

        // 2. Local 127.0.0.1 must be preserved for loopback diagnostics
        assertTrue("Loopback 127.0.0.1 should remain visible for local debugging", redacted.contains("127.0.0.1:10808"))

        // 3. IPv6 addresses must be masked
        assertFalse("Client IPv6 must not leak", redacted.contains("2001:0db8:85a3:0000:0000:8a2e:0370:7334"))
        assertTrue("IPv6 should be replaced with [IPv6 hidden]", redacted.contains("[IPv6 hidden]"))

        // 4. UUID must be masked
        assertFalse("Client UUID must not leak", redacted.contains("a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d"))

        // 5. Reality public keys must be masked
        assertFalse("Reality public key must not leak", redacted.contains("xK9_secret_reality_public_key_here_123456"))

        // 6. Complete subscription and outbound links must be masked
        assertFalse("Raw config link must not leak", redacted.contains("vless://"))
        assertTrue("Links must be replaced with [link hidden]", redacted.contains("[link hidden]"))

        // 7. Internal application paths must be masked
        assertFalse("Filesystem path must not leak", redacted.contains("/data/user/0/com.danieldk.splatevpn"))
        assertTrue("Internal paths must be replaced with [local path hidden]", redacted.contains("[local path hidden]"))

        // 8. Server name must be masked
        assertFalse("Server name must not leak", redacted.contains("My-Secret-VPS-Germany"))
    }

    @Test
    fun testGlobalModeContainsNoDirectDnsOrRegionalBypasses() {
        val configJson = generator.generateConfig(
            vlessServer,
            mode = "global",
            routingRegion = "ru",
            dnsProvider = "cloudflare",
            isFakeDns = true,
            bypassAi = false,
            blockIpv6 = true,
            blockQuic = true
        )
        val config = JsonParser.parseString(configJson).asJsonObject

        // DNS verification: Global mode must NOT route any DNS to Yandex or local ISP
        val dns = config.getAsJsonObject("dns")
        val dnsServers = dns.getAsJsonArray("servers")
        dnsServers.forEach { serverElem ->
            if (serverElem.isJsonObject) {
                val serverObj = serverElem.asJsonObject
                val address = serverObj.get("address")?.asString.orEmpty()
                assertFalse(
                    "Global mode must never send queries to Yandex DNS 77.88.8.8",
                    address.contains("77.88.8.8")
                )
            }
        }

        // Routing rules verification: Global mode must NOT contain direct bypass for ru domains or geoip:ru
        val rules = config.getAsJsonObject("routing").getAsJsonArray("rules")
        for (ruleElem in rules) {
            val rule = ruleElem.asJsonObject
            val outbound = rule.get("outboundTag")?.asString.orEmpty()
            if (outbound == "direct") {
                val domains = rule.getAsJsonArray("domain")?.map { it.asString }.orEmpty()
                val ips = rule.getAsJsonArray("ip")?.map { it.asString }.orEmpty()

                assertFalse("Direct rule must not bypass Russian domains in Global mode", domains.any { it.contains("geosite:ru") || it.contains("domain:ru") })
                assertFalse("Direct rule must not bypass Russian IPs in Global mode", ips.any { it.contains("geoip:ru") })
            }
        }
    }

    @Test
    fun testSmartModeRuDnsUsesSkipFallbackToPreventDomainLeaks() {
        val configJson = generator.generateConfig(
            vlessServer,
            mode = "smart",
            routingRegion = "ru",
            dnsProvider = "google",
            isFakeDns = true,
            bypassAi = false
        )
        val config = JsonParser.parseString(configJson).asJsonObject
        val dnsServers = config.getAsJsonObject("dns").getAsJsonArray("servers")

        var foundYandexServer = false
        dnsServers.forEach { serverElem ->
            if (serverElem.isJsonObject) {
                val serverObj = serverElem.asJsonObject
                if (serverObj.get("address")?.asString?.contains("77.88.8.8") == true) {
                    foundYandexServer = true
                    assertTrue(
                        "Yandex DNS rule for Russian domains must have skipFallback=true so foreign/blocked queries never leak to Yandex",
                        serverObj.get("skipFallback")?.asBoolean == true
                    )
                    assertTrue("Yandex DNS must be restricted to regional domains only", serverObj.has("domains"))
                }
            }
        }
        assertTrue("Smart mode with Russia region should configure regional DNS", foundYandexServer)
    }

    @Test
    fun testTorRoutingStrictlyBlocksUdpToPreventWebRtcLeaks() {
        val baseConfig = generator.generateConfig(torServer, mode = "global")
        val torConfigJson = TorRoutingPolicy.apply(baseConfig)
        val torConfig = JsonParser.parseString(torConfigJson).asJsonObject

        val outbounds = torConfig.getAsJsonArray("outbounds").map { it.asJsonObject }
        val tags = outbounds.map { it["tag"].asString }.toSet()
        assertEquals("Tor must only contain proxy, dns-out and block outbounds", setOf("proxy", "dns-out", "block"), tags)
        assertFalse("Tor must NEVER contain a direct outbound", tags.contains("direct"))

        val rules = torConfig.getAsJsonObject("routing").getAsJsonArray("rules").map { it.asJsonObject }

        // All UDP traffic must be blackholed to prevent WebRTC STUN leaks
        val udpRule = rules.firstOrNull { it.get("network")?.asString == "udp" }
        assertNotNull("Tor must have a dedicated UDP rule", udpRule)
        assertEquals("Tor must blackhole all UDP packets", "block", udpRule?.get("outboundTag")?.asString)

        // All TCP traffic must go through the Tor SOCKS proxy
        val tcpRule = rules.firstOrNull { it.get("network")?.asString == "tcp" }
        assertNotNull("Tor must have a dedicated TCP rule", tcpRule)
        assertEquals("Tor must route all TCP traffic to proxy", "proxy", tcpRule?.get("outboundTag")?.asString)
    }

    @Test
    fun testIpv6BlackholeRulePreventsIpv6LeaksWhenBlocked() {
        val configJson = generator.generateConfig(vlessServer, blockIpv6 = true)
        val config = JsonParser.parseString(configJson).asJsonObject
        val rules = config.getAsJsonObject("routing").getAsJsonArray("rules").map { it.asJsonObject }

        val ipv6BlockRule = rules.firstOrNull { rule ->
            rule.getAsJsonArray("ip")?.any { it.asString == "::/0" } == true
        }
        assertNotNull("Must contain an explicit IPv6 ::/0 rule", ipv6BlockRule)
        assertEquals("IPv6 ::/0 must be routed to block (blackhole)", "block", ipv6BlockRule?.get("outboundTag")?.asString)
    }
}
