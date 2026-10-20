package com.danieldk.splatevpn.vpn

import com.danieldk.splatevpn.data.ServerNode
import com.danieldk.splatevpn.SplateConstants
import com.google.gson.JsonParser
import com.danieldk.splatevpn.data.SmartDnsProviders
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class XrayConfigGeneratorTest {

    private lateinit var generator: XrayConfigGenerator

    @Before
    fun setUp() {
        generator = XrayConfigGenerator()
    }

    @Test
    fun autoDnsProbeUsesOnlyItsLoopbackPortAndSelectedResolver() {
        SmartDnsProviders.all.forEach { provider ->
            listOf(false, true).forEach { fragment ->
                val json = JsonParser.parseString(generator.generateAiDnsProbeConfig(provider.key, fragment, 29081)).asJsonObject
                val inbound = json.getAsJsonArray("inbounds").single().asJsonObject
                assertEquals("127.0.0.1", inbound.get("listen").asString)
                assertEquals(29081, inbound.get("port").asInt)
                assertFalse(inbound.getAsJsonObject("settings").get("udp").asBoolean)
                val dns = json.getAsJsonObject("dns")
                assertTrue(dns.get("disableFallbackIfMatch").asBoolean)
                val servers = dns.getAsJsonArray("servers")
                assertEquals(2, servers.size())
                val server = servers.first().asJsonObject
                assertEquals(provider.dohUrl, server.get("address").asString)
                assertTrue(server.get("skipFallback").asBoolean)
                assertTrue(server.getAsJsonArray("domains").any { it.asString == "full:gemini.google.com" })
                assertEquals("https://1.1.1.1/dns-query", servers.last().asJsonObject.get("address").asString)
                val rules = json.getAsJsonObject("routing").getAsJsonArray("rules").map { it.asJsonObject }
                assertEquals("dns-probe", rules.first().get("outboundTag").asString)
                assertEquals("ai-probe", rules[1].get("outboundTag").asString)
                assertEquals("dns-probe", rules.last().get("outboundTag").asString)
                val outbounds = json.getAsJsonArray("outbounds").map { it.asJsonObject }
                val ai = outbounds.first { it.get("tag").asString == "ai-probe" }
                assertEquals(fragment, ai.getAsJsonObject("settings").has("fragment"))
                assertEquals("ForceIPv4", ai.getAsJsonObject("streamSettings").getAsJsonObject("sockopt").get("domainStrategy").asString)
                val doh = outbounds.first { it.get("tag").asString == "dns-probe" }
                assertFalse(doh.has("settings"))
                assertFalse(json.toString().contains("\"tag\":\"proxy\""))
            }
        }
    }

    @Test
    fun eachSmartDnsProvider_isScopedBootstrappedAndRoutedDirectly() {
        SmartDnsProviders.all.forEach { provider ->
            val config = JsonParser.parseString(generator.generateConfig(null, aiDnsProvider = provider.key)).asJsonObject
            val dns = config.getAsJsonObject("dns")
            val scoped = dns.getAsJsonArray("servers").first {
                it.isJsonObject && it.asJsonObject.getAsJsonArray("domains")?.any { d -> d.asString == "full:gemini.google.com" } == true
            }.asJsonObject
            assertEquals(provider.key, provider.dohUrl, scoped.get("address").asString)
            assertTrue(scoped.get("skipFallback").asBoolean)
            assertTrue(dns.get("disableFallbackIfMatch").asBoolean)
            assertEquals(provider.bootstrapIpv4, dns.getAsJsonObject("hosts").getAsJsonArray(provider.host).map { it.asString })
            val rule = config.getAsJsonObject("routing").getAsJsonArray("rules").map { it.asJsonObject }.first {
                it.getAsJsonArray("domain")?.any { d -> d.asString == "full:${provider.host}" } == true
            }
            assertEquals("direct", rule.get("outboundTag").asString)
            assertFalse(rule.has("ip"))
            if (provider.key != "dns_ai") assertFalse(config.toString().contains("dns.dns-ai.ru"))
        }
    }

    @Test
    fun generalAndAiSmartDns_areIndependentAndBothBootstrapped() {
        val config = JsonParser.parseString(generator.generateConfig(null, dnsProvider = "geohide", aiDnsProvider = "malw_gateway")).asJsonObject
        val dns = config.getAsJsonObject("dns")
        assertTrue(dns.getAsJsonObject("hosts").has("geohide.ru"))
        assertTrue(dns.getAsJsonObject("hosts").has("5u35p8m9i7.cloudflare-gateway.com"))
        val servers = dns.getAsJsonArray("servers")
        assertTrue(servers.any { it.isJsonPrimitive && it.asString == "https://geohide.ru/dns-query" })
        assertTrue(servers.any { it.isJsonObject && it.asJsonObject.get("address").asString == "https://5u35p8m9i7.cloudflare-gateway.com/dns-query" })
        val domains = config.getAsJsonObject("routing").getAsJsonArray("rules").map { it.asJsonObject }.first {
            it.getAsJsonArray("domain")?.any { d -> d.asString == "full:geohide.ru" } == true
        }.getAsJsonArray("domain").map { it.asString }
        assertTrue(domains.contains("full:5u35p8m9i7.cloudflare-gateway.com"))
    }

    @Test
    fun aiVpnMode_doesNotInheritAnySmartDnsRelay() {
        SmartDnsProviders.all.forEach { provider ->
            val config = JsonParser.parseString(generator.generateConfig(null, dnsProvider = provider.key,
                aiRoutingMode = "vpn", aiDnsProvider = "malw_gateway")).asJsonObject
            val dns = config.getAsJsonObject("dns")
            val aiDns = dns.getAsJsonArray("servers").first {
                it.isJsonObject && it.asJsonObject.getAsJsonArray("domains")?.any { d -> d.asString == "full:gemini.google.com" } == true
            }.asJsonObject
            assertEquals(provider.key, "1.1.1.1", aiDns.get("address").asString)
            val aiRoute = config.getAsJsonObject("routing").getAsJsonArray("rules").map { it.asJsonObject }.first {
                it.get("network")?.asString != "udp" && it.getAsJsonArray("domain")?.any { d -> d.asString == "full:gemini.google.com" } == true
            }
            assertEquals("proxy", aiRoute.get("outboundTag").asString)
        }
    }

    @Test
    fun smartDnsPreset_honorsExplicitProxyOverrides() {
        SmartDnsProviders.all.forEach { provider ->
            val config = JsonParser.parseString(generator.generateConfig(null, dnsProvider = provider.key,
                aiDnsProvider = provider.key, customRoutes = listOf("domain:chatgpt.com" to "proxy"))).asJsonObject
            val firstMatchedDns = config.getAsJsonObject("dns").getAsJsonArray("servers").filter { it.isJsonObject }.map { it.asJsonObject }.first {
                it.getAsJsonArray("domains")?.any { d -> d.asString in listOf("domain:chatgpt.com", "full:chatgpt.com") } == true
            }
            assertEquals(provider.key, "1.1.1.1", firstMatchedDns.get("address").asString)
        }
    }

    @Test
    fun disabledAiBypass_doesNotInstallSelectedAiDns() {
        SmartDnsProviders.all.forEach { provider ->
            val config = generator.generateConfig(null, bypassAi = false, aiDnsProvider = provider.key)
            assertFalse(provider.key, config.contains(provider.host))
        }
    }

    @Test
    fun smartDnsDefaultsToCompatibleTlsWithoutSystemDnsFallback() {
        val json = JsonParser.parseString(generator.generateConfig(null, aiDnsProvider = "malw")).asJsonObject
        val outbound = json.getAsJsonArray("outbounds").first { it.asJsonObject.get("tag").asString == "ai-direct" }.asJsonObject
        assertFalse(outbound.getAsJsonObject("settings").has("fragment"))
        assertEquals("ForceIPv4", outbound.getAsJsonObject("streamSettings").getAsJsonObject("sockopt").get("domainStrategy").asString)
    }

    @Test
    fun optionalAiFragmentationNeverFragmentsDohConnections() {
        val json = JsonParser.parseString(generator.generateConfig(null, aiDnsProvider = "malw_gateway", aiTlsFragment = true)).asJsonObject
        val rule = json.getAsJsonObject("routing").getAsJsonArray("rules").map { it.asJsonObject }.first {
            it.getAsJsonArray("domain")?.any { d -> d.asString == "full:5u35p8m9i7.cloudflare-gateway.com" } == true
        }
        assertEquals("direct", rule.get("outboundTag").asString)
        val outbound = json.getAsJsonArray("outbounds").first { it.asJsonObject.get("tag").asString == "ai-direct" }.asJsonObject
        assertTrue(outbound.getAsJsonObject("settings").has("fragment"))
    }

    @Test
    fun aiBypass_keepsSmartDnsSeparateFromVpnDomains() {
        val json = JsonParser.parseString(generator.generateConfig(node = null, bypassAi = true)).asJsonObject
        val dns = json.getAsJsonObject("dns")
        val smartDns = dns.getAsJsonArray("servers").first {
            it.isJsonObject && it.asJsonObject.get("address").asString == "https://dns.dns-ai.ru/dns-query"
        }.asJsonObject
        val domains = smartDns.getAsJsonArray("domains").map { it.asString }
        assertTrue(domains.contains("full:gemini.google.com"))
        assertTrue(domains.contains("full:robinfrontend-pa.googleapis.com"))
        assertTrue(domains.contains("full:chatgpt.com"))
        assertTrue(domains.contains("full:openai.com"))
        assertTrue(domains.contains("full:claude.ai"))
        assertTrue(domains.contains("full:perplexity.ai"))
        assertFalse(domains.contains("domain:bing.com"))
        assertTrue(smartDns.get("skipFallback").asBoolean)
        assertTrue(dns.get("disableFallbackIfMatch").asBoolean)
        assertEquals(listOf("194.87.196.13", "186.246.49.127"),
            dns.getAsJsonObject("hosts").getAsJsonArray("dns.dns-ai.ru").map { it.asString })

        val rules = json.getAsJsonObject("routing").getAsJsonArray("rules").map { it.asJsonObject }
        fun routeFor(domain: String) = rules.first { rule ->
            rule.get("network")?.asString != "udp" &&
                rule.getAsJsonArray("domain")?.any { it.asString == "full:$domain" } == true
        }.get("outboundTag").asString
        assertEquals("direct", routeFor("dns.dns-ai.ru"))
        assertEquals("ai-direct", routeFor("gemini.google.com"))
        assertEquals("ai-direct", routeFor("robinfrontend-pa.googleapis.com"))
        assertEquals("ai-direct", routeFor("chatgpt.com"))
        assertEquals("ai-direct", routeFor("claude.ai"))
        assertEquals("ai-direct", routeFor("perplexity.ai"))
        assertFalse(json.toString().contains("xbox-dns.ru"))
        assertFalse(json.toString().contains("87.228.47.0/24"))
    }

    @Test
    fun testGenerateConfig_adBlockerEnabledAndDisabled() {
        val jsonWithAdsBlocked = JsonParser.parseString(generator.generateConfig(node = null, adBlocker = true)).asJsonObject
        val rulesBlocked = jsonWithAdsBlocked.getAsJsonObject("routing").getAsJsonArray("rules").map { it.asJsonObject }
        val blockRule = rulesBlocked.firstOrNull { rule ->
            rule.get("outboundTag")?.asString == "block" &&
                rule.getAsJsonArray("domain")?.any { it.asString == "geosite:category-ads-all" } == true
        }
        assertNotNull("Block rule for ads must be present when adBlocker is true", blockRule)
        val blockedDomains = blockRule!!.getAsJsonArray("domain").map { it.asString }
        assertTrue(blockedDomains.contains("domain:an.yandex.ru"))
        assertTrue(blockedDomains.contains("domain:adfox.yandex.ru"))
        assertTrue(blockedDomains.contains("domain:top-fwz1.mail.ru"))

        val jsonWithoutAdsBlocked = JsonParser.parseString(generator.generateConfig(node = null, adBlocker = false)).asJsonObject
        val rulesNotBlocked = jsonWithoutAdsBlocked.getAsJsonObject("routing").getAsJsonArray("rules").map { it.asJsonObject }
        val absentBlockRule = rulesNotBlocked.firstOrNull { rule ->
            rule.get("outboundTag")?.asString == "block" &&
                rule.getAsJsonArray("domain")?.any { it.asString == "geosite:category-ads-all" } == true
        }
        assertNull("Block rule for ads must be absent when adBlocker is false", absentBlockRule)
    }

    @Test
    fun testGenerateConfig_comssDnsProvider() {
        val json = JsonParser.parseString(generator.generateConfig(node = null, dnsProvider = "comss")).asJsonObject
        val dns = json.getAsJsonObject("dns")
        val servers = dns.getAsJsonArray("servers").map { if (it.isJsonObject) it.asJsonObject.get("address").asString else it.asString }
        assertTrue(servers.contains("https://dns.dns-ai.ru/dns-query"))
    }

    @Test
    fun adBlocking_filtersDnsBeforeUpstreamsAndForcesDomainDetection() {
        for (fakeDns in listOf(false, true)) {
            val config = JsonParser.parseString(generator.generateConfig(null, adBlocker = true,
                bypassAi = false, enableSniffing = false, isFakeDns = fakeDns, blockQuic = false)).asJsonObject
            val hosts = config.getAsJsonObject("dns").getAsJsonObject("hosts")
            listOf("geosite:category-ads-all", "domain:adsdk.yandex.ru", "domain:banners.mobile.yandex.net",
                "domain:r.my.com", "domain:alt-ad.mail.ru").forEach {
                assertEquals(listOf("0.0.0.0", "::"), hosts.getAsJsonArray(it).map { address -> address.asString })
            }
            val sniffing = config.getAsJsonArray("inbounds")[0].asJsonObject.getAsJsonObject("sniffing")
            assertTrue(sniffing.get("enabled").asBoolean)
            assertTrue(sniffing.getAsJsonArray("destOverride").any { it.asString == "quic" })
            val rules = config.getAsJsonObject("routing").getAsJsonArray("rules")
            assertTrue(rules[0].asJsonObject.getAsJsonArray("domain").any { it.asString == "domain:adsdk.yandex.ru" })
        }
        val disabled = JsonParser.parseString(generator.generateConfig(null, adBlocker = false,
            bypassAi = false, enableSniffing = false)).asJsonObject
        assertFalse(disabled.getAsJsonObject("dns").getAsJsonObject("hosts").has("domain:adsdk.yandex.ru"))
        assertFalse(disabled.getAsJsonArray("inbounds")[0].asJsonObject.has("sniffing"))
    }

    @Test
    fun adBlocking_neverBlocksSharedContentDomains() {
        val matchers = AdBlockingPolicy.matchers
        listOf("yandex.ru", "yandex.net", "yastatic.net", "vk.com", "vk.ru", "api.vk.com",
            "vk-portal.net", "imgsmail.ru", "youtube.com", "googlevideo.com", "ytimg.com", "tiktok.com", "yahoo.com", "miui.com").forEach { content ->
            assertFalse("Shared content domain $content must remain available", matchers.any {
                it == "domain:$content" || (it.startsWith("domain:") && content.endsWith("." + it.removePrefix("domain:")))
            })
        }
    }

    @Test fun extendedAdFilterIsUsedForBothDnsAndRoutesAndFallbackExceptionsCannotBlockAllTraffic() {
        val config = JsonParser.parseString(generator.generateConfig(null, adBlockMatcher = "ext:test-ads.dat:ads", adBlockAllowlist = listOf("adsdk.yandex.ru"))).asJsonObject
        val hosts = config.getAsJsonObject("dns").getAsJsonObject("hosts")
        assertTrue(hosts.has("ext:test-ads.dat:ads"))
        assertFalse(hosts.has("geosite:category-ads-all"))
        val adRule = config.getAsJsonObject("routing").getAsJsonArray("rules").first {
            it.asJsonObject.getAsJsonArray("domain")?.any { domain -> domain.asString == "ext:test-ads.dat:ads" } == true
        }.asJsonObject
        assertEquals(1, adRule.getAsJsonArray("domain").size())
        val fallback = JsonParser.parseString(generator.generateConfig(null, adBlockAllowlist = AdBlockingPolicy.domains)).asJsonObject
        fallback.getAsJsonObject("routing").getAsJsonArray("rules").forEach {
            val domain = it.asJsonObject.getAsJsonArray("domain")
            if (domain != null) assertTrue("An empty domain condition must never become an unconditional block", domain.size() > 0)
        }
        assertFalse(fallback.getAsJsonObject("dns").getAsJsonObject("hosts").has("geosite:category-ads-all"))
    }

    @Test
    fun adBlocking_importedProfilesKeepDefaultOutboundButCannotBypassFilter() {
        val raw = """{"inbounds":[{"protocol":"socks","tag":"custom-in","sniffing":{"enabled":false,"destOverride":["fakedns"]}}],
            "outbounds":[{"protocol":"freedom","tag":"splate-ad-block"}],
            "dns":{"hosts":{"example.com":"203.0.113.10"}},
            "routing":{"domainStrategy":"AsIs","rules":[{"type":"field","network":"tcp,udp","outboundTag":"splate-ad-block"}]}}"""
        val node = ServerNode("import", "Imported", "vless", "example.com", 443, fullConfigJson = raw)
        val config = JsonParser.parseString(generator.generateConfig(node, adBlocker = true)).asJsonObject
        val outbounds = config.getAsJsonArray("outbounds")
        assertEquals("freedom", outbounds[0].asJsonObject.get("protocol").asString)
        val rules = config.getAsJsonObject("routing").getAsJsonArray("rules")
        assertEquals("splate-ad-block-1", rules[0].asJsonObject.get("outboundTag").asString)
        assertEquals("53", rules[1].asJsonObject.get("port").asString)
        assertEquals("splate-ad-block", rules[2].asJsonObject.get("outboundTag").asString)
        assertEquals("203.0.113.10", config.getAsJsonObject("dns").getAsJsonObject("hosts").get("example.com").asString)
        assertTrue(config.getAsJsonArray("inbounds")[0].asJsonObject.getAsJsonObject("sniffing")
            .getAsJsonArray("destOverride").any { it.asString == "fakedns" })
        val disabled = JsonParser.parseString(generator.generateConfig(node, adBlocker = false)).asJsonObject
        assertEquals(1, disabled.getAsJsonArray("outbounds").size())
        assertEquals(1, disabled.getAsJsonObject("routing").getAsJsonArray("rules").size())
        assertFalse(disabled.getAsJsonObject("dns").getAsJsonObject("hosts").has("domain:adsdk.yandex.ru"))
        val implicitDefault = JsonParser.parseString(generator.generateConfig(node.copy(fullConfigJson = "{}"))).asJsonObject
        assertEquals("freedom", implicitDefault.getAsJsonArray("outbounds")[0].asJsonObject.get("protocol").asString)
    }

    @Test
    fun aiBypass_disabledDoesNotInstallSmartDnsOrDirectAiRoute() {
        val json = JsonParser.parseString(generator.generateConfig(node = null, bypassAi = false)).asJsonObject
        assertFalse(json.toString().contains("dns.dns-ai.ru"))
        assertFalse(json.getAsJsonObject("dns").has("disableFallbackIfMatch"))
        val rules = json.getAsJsonObject("routing").getAsJsonArray("rules")
        assertFalse(rules.any { it.asJsonObject.getAsJsonArray("domain")?.any {
            domain -> domain.asString == "full:gemini.google.com"
        } == true })
    }

    @Test
    fun aiSmartDns_fragmentsDirectTlsEvenWithoutServerTlsAndBlocksQuic() {
        val json = JsonParser.parseString(generator.generateConfig(null, blockQuic = false, aiTlsFragment = true)).asJsonObject
        val outbound = json.getAsJsonArray("outbounds").first { it.asJsonObject.get("tag").asString == "ai-direct" }.asJsonObject
        assertEquals("tlshello", outbound.getAsJsonObject("settings").getAsJsonObject("fragment").get("packets").asString)
        assertEquals("ForceIPv4", outbound.getAsJsonObject("streamSettings").getAsJsonObject("sockopt").get("domainStrategy").asString)
        val rules = json.getAsJsonObject("routing").getAsJsonArray("rules").map { it.asJsonObject }
        val dohRule = rules.first { it.getAsJsonArray("domain")?.any { d -> d.asString == "full:dns.dns-ai.ru" } == true }
        assertFalse("A hostname request has no resolved IP at routing time", dohRule.has("ip"))
        val quicRule = rules.first { it.get("network")?.asString == "udp" && it.get("port")?.asString == "443" }
        assertEquals("block", quicRule.get("outboundTag").asString)
        assertTrue(quicRule.getAsJsonArray("domain").any { it.asString == "full:gemini.google.com" })
    }

    @Test
    fun aiVpnMode_usesOrdinaryDnsAndProxyEvenWithDnsAiSelected() {
        val json = JsonParser.parseString(generator.generateConfig(null, aiRoutingMode = "vpn", dnsProvider = "dns_ai")).asJsonObject
        val rules = json.getAsJsonObject("routing").getAsJsonArray("rules").map { it.asJsonObject }
        val aiRule = rules.first { it.getAsJsonArray("domain")?.any { d -> d.asString == "full:gemini.google.com" } == true }
        assertEquals("proxy", aiRule.get("outboundTag").asString)
        val dns = json.getAsJsonObject("dns").getAsJsonArray("servers").first {
            it.isJsonObject && it.asJsonObject.getAsJsonArray("domains")?.any { d -> d.asString == "full:gemini.google.com" } == true
        }.asJsonObject
        assertEquals("1.1.1.1", dns.get("address").asString)
    }

    @Test
    fun customAiProxyOverride_usesRemoteDnsBeforeSmartDns() {
        val json = JsonParser.parseString(generator.generateConfig(null, customRoutes = listOf("domain:chatgpt.com" to "proxy"))).asJsonObject
        val matchedDns = json.getAsJsonObject("dns").getAsJsonArray("servers").filter { it.isJsonObject }.map { it.asJsonObject }.first {
            it.getAsJsonArray("domains")?.any { d -> d.asString in listOf("domain:chatgpt.com", "full:chatgpt.com") } == true
        }
        assertEquals("1.1.1.1", matchedDns.get("address").asString)
    }

    @Test
    fun fakeDns_enablesDestinationRecoveryDespiteConflictingSniffingSettings() {
        val json = JsonParser.parseString(generator.generateConfig(null, isFakeDns = true,
            enableSniffing = false, routeOnlySniffing = true, shareLan = true)).asJsonObject
        json.getAsJsonArray("inbounds").forEach {
            val sniffing = it.asJsonObject.getAsJsonObject("sniffing")
            assertTrue(sniffing.get("enabled").asBoolean)
            assertFalse(sniffing.get("routeOnly").asBoolean)
            assertTrue(sniffing.getAsJsonArray("destOverride").any { override -> override.asString == "fakedns" })
        }
    }

    @Test
    fun virtualDns_interceptsQueriesBeforeRejectingOpportunisticTls() {
        val json = JsonParser.parseString(generator.generateConfig(node = null, isFakeDns = true)).asJsonObject
        val rules = json.getAsJsonObject("routing").getAsJsonArray("rules").map { it.asJsonObject }
        val interceptIndex = rules.indexOfFirst { it.get("outboundTag").asString == "dns-out" }
        val tlsIndex = rules.indexOfFirst { rule ->
            rule.get("port")?.asString == "853" &&
                rule.getAsJsonArray("ip")?.any { it.asString == SplateConstants.TUN_DNS_ADDRESS } == true
        }
        assertTrue(interceptIndex >= 0)
        assertTrue(tlsIndex > interceptIndex)
        assertEquals("53", rules[interceptIndex].get("port").asString)
        assertEquals("block", rules[tlsIndex].get("outboundTag").asString)
    }

    @Test
    fun testGenerateConfig_nullNode_createsFreedomConfig() {
        val configJson = generator.generateConfig(node = null)
        val json = JsonParser.parseString(configJson).asJsonObject

        assertTrue(json.has("outbounds"))
        val outbounds = json.getAsJsonArray("outbounds")
        assertTrue(outbounds.size() > 0)
        val direct = outbounds.first { it.asJsonObject.get("tag").asString == "direct" }.asJsonObject
        assertEquals("freedom", direct.get("protocol").asString)
    }

    @Test
    fun testGenerateConfig_vlessReality_correctSettings() {
        val node = ServerNode(
            id = "test-node-1",
            name = "Test VLESS Reality",
            protocol = "vless",
            address = "vpn.example.com",
            port = 443,
            uuid = "11111111-2222-3333-4444-555555555555",
            flow = "xtls-rprx-vision",
            encryption = "none",
            network = "tcp",
            security = "reality",
            sni = "reality.example.com",
            fingerprint = "chrome",
            publicKey = "abcdef0123456789",
            shortId = "abcd"
        )

        val configJson = generator.generateConfig(node = node, mode = "smart_ru")
        val json = JsonParser.parseString(configJson).asJsonObject

        val outbounds = json.getAsJsonArray("outbounds")
        val proxyOutbound = outbounds[0].asJsonObject
        assertEquals("vless", proxyOutbound.get("protocol").asString)
        assertEquals("proxy", proxyOutbound.get("tag").asString)

        val streamSettings = proxyOutbound.getAsJsonObject("streamSettings")
        assertEquals("reality", streamSettings.get("security").asString)
        assertEquals("tcp", streamSettings.get("network").asString)

        val realitySettings = streamSettings.getAsJsonObject("realitySettings")
        assertEquals("reality.example.com", realitySettings.get("serverName").asString)
        assertEquals("abcdef0123456789", realitySettings.get("publicKey").asString)
        assertEquals("abcd", realitySettings.get("shortId").asString)
    }

    @Test
    fun testGenerateConfig_ipAddressDoesNotSetVerifyPeerCertByName() {
        // Fix verification: if address is IP and sni is empty, verifyPeerCertByName must NOT be set to IP
        val node = ServerNode(
            id = "test-node-ip",
            name = "Test IP Node",
            protocol = "vless",
            address = "192.168.1.100",
            port = 443,
            uuid = "11111111-2222-3333-4444-555555555555",
            security = "tls",
            sni = ""
        )

        val configJson = generator.generateConfig(node = node)
        val json = JsonParser.parseString(configJson).asJsonObject
        val proxyOutbound = json.getAsJsonArray("outbounds")[0].asJsonObject
        val streamSettings = proxyOutbound.getAsJsonObject("streamSettings")
        val tlsSettings = streamSettings.getAsJsonObject("tlsSettings")

        // Should NOT contain verifyPeerCertByName because address is an IP and sni is empty
        assertFalse(
            "verifyPeerCertByName should not be set for raw IP addresses",
            tlsSettings.has("verifyPeerCertByName")
        )
    }

    @Test
    fun testGenerateConfig_blockQuicAndIpv6Settings() {
        val node = ServerNode(
            id = "test-node-quic",
            name = "Test Quic",
            protocol = "trojan",
            address = "trojan.example.com",
            port = 443,
            uuid = "password123",
            security = "tls"
        )

        val configJson = generator.generateConfig(
            node = node,
            blockQuic = true,
            blockIpv6 = true,
            dnsProvider = "cloudflare"
        )
        val json = JsonParser.parseString(configJson).asJsonObject

        // Check routing rules for quic blocking
        val routing = json.getAsJsonObject("routing")
        val rules = routing.getAsJsonArray("rules")
        val hasBlockRule = rules.any { rule ->
            val r = rule.asJsonObject
            r.has("outboundTag") && r.get("outboundTag").asString == "block" &&
                    r.has("port") && r.get("port").asString == "443" &&
                    r.has("network") && r.get("network").asString.contains("udp")
        }
        assertTrue("Routing rules should block QUIC (udp/443)", hasBlockRule)

        // Check DNS queryStrategy for blockIpv6
        val dns = json.getAsJsonObject("dns")
        assertEquals("UseIPv4", dns.get("queryStrategy").asString)
    }

    @Test
    fun testGenerateConfig_dohAndRoutingStrategy() {
        val node = ServerNode(
            id = "test-node-doh",
            name = "Test DoH Node",
            protocol = "vless",
            address = "node.myserver.com",
            port = 443,
            uuid = "11111111-2222-3333-4444-555555555555",
            security = "reality",
            sni = "yahoo.com",
            publicKey = "abcdef0123456789"
        )

        val configJson = generator.generateConfig(node = node, dnsProvider = "google")
        val json = JsonParser.parseString(configJson).asJsonObject

        // 1. Verify routing domainStrategy is IPIfNonMatch
        val routing = json.getAsJsonObject("routing")
        assertEquals("IPIfNonMatch", routing.get("domainStrategy").asString)

        // 2. Verify dns servers contain DNS endpoint
        val dns = json.getAsJsonObject("dns")
        val servers = dns.getAsJsonArray("servers")
        val hasDns = servers.any { it.isJsonPrimitive && (it.asString == "8.8.8.8" || it.asString == "https://8.8.8.8/dns-query") }
        assertTrue("DNS servers should contain DNS endpoint", hasDns)

        // 3. Verify node address rule routes direct
        val rules = routing.getAsJsonArray("rules")
        val hasNodeRule = rules.any { rule ->
            val r = rule.asJsonObject
            r.has("outboundTag") && r.get("outboundTag").asString == "direct" &&
                    r.has("domain") && r.getAsJsonArray("domain").any { it.asString == "domain:node.myserver.com" }
        }
        assertTrue("Routing rules should route node.address direct", hasNodeRule)
    }

    @Test
    fun testGenerateConfig_tlsFragmentation() {
        val node = ServerNode(
            id = "test-frag",
            name = "Fragment Server",
            protocol = "vless",
            address = "node.frag.com",
            port = 443,
            security = "tls"
        )

        val configJson = generator.generateConfig(node = node, enableTlsFragment = true)
        val json = JsonParser.parseString(configJson).asJsonObject

        val outbounds = json.getAsJsonArray("outbounds")
        val hasFragmentOutbound = outbounds.any {
            val o = it.asJsonObject
            o.has("tag") && o.get("tag").asString == "fragment"
        }
        assertTrue("Should contain fragment outbound when enabled", hasFragmentOutbound)

        val proxyOutbound = outbounds.first { it.asJsonObject.get("tag").asString == "proxy" }.asJsonObject
        val streamSettings = proxyOutbound.getAsJsonObject("streamSettings")
        val sockopt = streamSettings.getAsJsonObject("sockopt")
        assertEquals("fragment", sockopt.get("dialerProxy").asString)
    }

    @Test
    fun testGenerateTestConfig_validStructureAndTypes() {
        val node = ServerNode(
            id = "test-node-testconfig",
            name = "Test Node",
            protocol = "vless",
            address = "test.server.io",
            port = 443,
            uuid = "12345678-1234-1234-1234-123456789abc",
            security = "reality",
            publicKey = "abcdef",
            sni = "microsoft.com"
        )

        val configJson = generator.generateTestConfig(
            node = node,
            socksPort = 25001,
            enableTlsFragment = true
        )
        val json = JsonParser.parseString(configJson).asJsonObject

        // 1. Inbounds check
        val inbounds = json.getAsJsonArray("inbounds")
        val socksIn = inbounds[0].asJsonObject
        assertEquals("socks-test", socksIn.get("tag").asString)
        assertEquals(25001, socksIn.get("port").asInt)
        assertEquals("127.0.0.1", socksIn.get("listen").asString)

        // 2. Outbounds check
        val outbounds = json.getAsJsonArray("outbounds")
        assertTrue(outbounds.any { it.asJsonObject.get("tag").asString == "proxy" })
        assertTrue(outbounds.any { it.asJsonObject.get("tag").asString == "direct" })
        assertTrue(outbounds.any { it.asJsonObject.get("tag").asString == "dns-out" })
        assertTrue(outbounds.any { it.asJsonObject.get("tag").asString == "fragment" })

        // 3. DNS check
        val dns = json.getAsJsonObject("dns")
        val servers = dns.getAsJsonArray("servers")
        assertTrue(servers.size() > 0)
        // All servers must be valid string primitives, NOT objects with nested properties
        assertTrue("DNS servers must be strings", servers.all { it.isJsonPrimitive && it.asJsonPrimitive.isString })

        // 4. Routing check: ensure port and network are strings, not arrays
        val routing = json.getAsJsonObject("routing")
        val rules = routing.getAsJsonArray("rules")
        for (r in rules) {
            val rule = r.asJsonObject
            if (rule.has("port")) {
                assertTrue("Port in routing rule must be a string primitive", rule.get("port").isJsonPrimitive && rule.get("port").asJsonPrimitive.isString)
            }
            if (rule.has("network")) {
                assertTrue("Network in routing rule must be a string primitive", rule.get("network").isJsonPrimitive && rule.get("network").asJsonPrimitive.isString)
            }
        }
    }
}
