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

    @Test fun realDelayNeverBypassesTheCandidateWhenTheHttpTargetSharesItsAddress() {
        val candidates = listOf(
            ServerNode("ip", "IP endpoint", "vless", "192.0.2.25", 443,
                uuid = "11111111-2222-3333-4444-555555555555", security = "tls"),
            ServerNode("dns", "Domain endpoint", "vless", "vpn.example", 443,
                uuid = "11111111-2222-3333-4444-555555555555", security = "tls"),
            ServerNode("plain", "Plain endpoint", "vless", "vpn.example", 8080,
                uuid = "11111111-2222-3333-4444-555555555555"),
            ServerNode("quic", "Hysteria endpoint", "hysteria2", "127.0.0.1", 443,
                uuid = "test-password", security = "tls")
        )
        for (node in candidates) {
            val config = JsonParser.parseString(generator.generateTestConfig(node, 25001)).asJsonObject
            val rules = config.getAsJsonObject("routing").getAsJsonArray("rules").map { it.asJsonObject }
            val directRules = rules.filter { it["outboundTag"]?.asString == "direct" }
            assertTrue("Direct traffic must be limited to DNS bootstrap for ${node.address}",
                directRules.all { it["port"]?.asString == "53" && !it.has("domain") })
            assertEquals("proxy", rules.last()["outboundTag"].asString)
            assertEquals("tcp,udp", rules.last()["network"].asString)
            assertFalse("Imported transport must not replace the probe with a direct outbound",
                config.getAsJsonArray("outbounds").first { it.asJsonObject["tag"]?.asString == "proxy" }
                    .asJsonObject["protocol"].asString == "freedom")
        }
    }

    @Test fun diagnosticLoggingKeepsTransportFailuresVisibleWithoutChangingNormalLogging() {
        val normal = JsonParser.parseString(generator.generateConfig(null)).asJsonObject
        val diagnostic = JsonParser.parseString(generator.generateConfig(null, diagnosticLogging = true)).asJsonObject
        assertEquals("warning", normal.getAsJsonObject("log")["loglevel"].asString)
        assertEquals("info", diagnostic.getAsJsonObject("log")["loglevel"].asString)
    }
    @Test fun importedTcpRealityRetainsInfoLevelTransportFailures() {
        val raw = """{"outbounds":[{"tag":"remote","protocol":"vless","settings":{"vnext":[{"address":"vpn.example","port":443,"users":[{"id":"11111111-2222-3333-4444-555555555555","encryption":"none"}]}]},"streamSettings":{"network":"tcp","security":"reality","realitySettings":{"serverName":"example.com","publicKey":"fixture-key","shortId":"abcd","fingerprint":"chrome"}}}]}"""
        val node = com.danieldk.splatevpn.data.LinkParser.parseSubscription(raw).single()
        val config = JsonParser.parseString(generator.generateConfig(node)).asJsonObject
        assertEquals("info", config.getAsJsonObject("log")["loglevel"].asString)
        val imported = config.getAsJsonArray("outbounds")[0].asJsonObject.getAsJsonObject("streamSettings")
        assertEquals("tcp", imported["network"].asString)
        assertEquals("fixture-key", imported.getAsJsonObject("realitySettings")["publicKey"].asString)
    }
    @Test fun tcpPathsAndHostsDoNotEnableHttpCamouflageWithoutAnExplicitHeaderType() {
        val link = "vless://11111111-2222-3333-4444-555555555555@vpn.example:443?security=reality&pbk=fixture&type=tcp"
        for (suffix in listOf("", "&path=%2F", "&path=%2Fmobile&host=example.com&headerType=none")) {
            val node = requireNotNull(com.danieldk.splatevpn.data.LinkParser.parse(link + suffix))
            for (json in listOf(generator.generateConfig(node), generator.generateTestConfig(node, 25001))) {
                val stream = JsonParser.parseString(json).asJsonObject.getAsJsonArray("outbounds")[0].asJsonObject
                    .getAsJsonObject("streamSettings")
                assertFalse("Unexpected HTTP camouflage for $suffix", stream.has("tcpSettings"))
            }
        }
        val http = requireNotNull(com.danieldk.splatevpn.data.LinkParser.parse(link + "&headerType=http&path=%2F"))
        val stream = JsonParser.parseString(generator.generateConfig(http)).asJsonObject.getAsJsonArray("outbounds")[0].asJsonObject
            .getAsJsonObject("streamSettings")
        assertEquals("http", stream.getAsJsonObject("tcpSettings").getAsJsonObject("header")["type"].asString)
        for (headerType in listOf("http", "none")) {
            val raw = """{"v":"2","ps":"VMess","add":"vpn.example","port":"443","id":"11111111-2222-3333-4444-555555555555","net":"tcp","type":"$headerType","path":"/","host":"example.com","tls":"tls"}"""
            val encoded = java.util.Base64.getEncoder().encodeToString(raw.toByteArray())
            val vmess = requireNotNull(com.danieldk.splatevpn.data.LinkParser.parse("vmess://$encoded"))
            val vmessStream = JsonParser.parseString(generator.generateConfig(vmess)).asJsonObject.getAsJsonArray("outbounds")[0]
                .asJsonObject.getAsJsonObject("streamSettings")
            assertEquals(headerType == "http", vmessStream.has("tcpSettings"))
        }
    }

    @Test fun privateDnsCheckerDoesNotResolveEveryProviderUsingTheCurrentlySelectedDns() {
        val config = JsonParser.parseString(generator.generateConfig(null, dnsProbePort = SplateConstants.DNS_CHECK_SOCKS_PORT)).asJsonObject
        val checker = config.getAsJsonArray("inbounds").first { it.asJsonObject["tag"].asString == "dns-check-in" }.asJsonObject
        assertEquals("127.0.0.1", checker["listen"].asString)
        assertEquals(SplateConstants.DNS_CHECK_SOCKS_PORT, checker["port"].asInt)
        assertFalse(checker.has("sniffing"))
        assertFalse(checker.getAsJsonObject("settings")["udp"].asBoolean)
        val probeRoute = config.getAsJsonObject("routing").getAsJsonArray("rules").first().asJsonObject
        assertEquals(listOf("dns-check-in"), probeRoute.getAsJsonArray("inboundTag").map { it.asString })
        assertEquals("dns-check-proxy", probeRoute["outboundTag"].asString)
        val probeOutbound = config.getAsJsonArray("outbounds").first { it.asJsonObject["tag"].asString == "dns-check-proxy" }.asJsonObject
        assertEquals("AsIs", probeOutbound["targetStrategy"].asString)
        val intercept = config.getAsJsonObject("routing").getAsJsonArray("rules").first {
            it.asJsonObject["outboundTag"].asString == "dns-out"
        }.asJsonObject
        assertEquals(listOf("socks-in"), intercept.getAsJsonArray("inboundTag").map { it.asString })
        assertThrows(IllegalArgumentException::class.java) { generator.generateConfig(null, dnsProbePort = SplateConstants.XRAY_SOCKS_PORT) }
    }

    @Test fun dnsDiagnosticsAndCustomBootstrapRetainTheEffectiveImportedProxyChain() {
        val raw = """{"outbounds":[
            {"tag":"entry","protocol":"socks","settings":{"servers":[{"address":"127.0.0.2","port":10400}]},"proxySettings":{"tag":"hop"}},
            {"tag":"hop","protocol":"socks","settings":{"servers":[{"address":"127.0.0.3","port":10401}]}}
        ]}"""
        val node = ServerNode("dns-chain", "DNS chain", "socks", "127.0.0.2", 10400, fullConfigJson = raw)
        val config = JsonParser.parseString(generator.generateConfig(node, dnsProvider = "custom",
            customDohUrl = "https://resolver.example/dns-query", dnsProbePort = SplateConstants.DNS_CHECK_SOCKS_PORT)).asJsonObject
        val outbounds = config.getAsJsonArray("outbounds").map { it.asJsonObject }.associateBy { it["tag"].asString }
        val primary = outbounds.getValue("proxy")
        assertEquals("import-hop", primary.getAsJsonObject("proxySettings")["tag"].asString)
        for (tag in listOf("dns-check-proxy", "dns-bootstrap")) {
            assertEquals(primary.deepCopy().apply { addProperty("tag", tag); addProperty("targetStrategy", "AsIs") }, outbounds.getValue(tag))
        }
    }

    @Test fun optionalRealitySignatureVerificationIsPreservedInGeneratedCoreConfig() {
        val key = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(1952) { (it % 251).toByte() })
        val node = requireNotNull(com.danieldk.splatevpn.data.LinkParser.parse(
            "vless://00000000-0000-0000-0000-000000000000@vpn.example:443?security=reality&type=xhttp&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&pqv=$key"))
        fun reality(rawLink: String) = JsonParser.parseString(generator.generateConfig(node.copy(rawLink = rawLink)))
            .asJsonObject.getAsJsonArray("outbounds").first { it.asJsonObject["tag"].asString == "proxy" }
            .asJsonObject.getAsJsonObject("streamSettings").getAsJsonObject("realitySettings")
        assertEquals(key, reality(node.rawLink)["mldsa65Verify"].asString)
        assertFalse(reality("").has("mldsa65Verify"))
    }

    @Test fun realityVerificationNormalizesLinksAndImportedJsonButRejectsDamagedKeys() {
        val bytes = ByteArray(1952) { (it % 251).toByte() }
        val canonical = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val standard = java.util.Base64.getEncoder().encodeToString(bytes)
        val raw = "vless://00000000-0000-0000-0000-000000000000@vpn.example:443?security=reality&type=xhttp&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&pqv="
        val node = requireNotNull(com.danieldk.splatevpn.data.LinkParser.parse(raw + standard))
        fun key(config: String) = JsonParser.parseString(config).asJsonObject.getAsJsonArray("outbounds")
            .first { it.asJsonObject["tag"].asString == "proxy" }.asJsonObject
            .getAsJsonObject("streamSettings").getAsJsonObject("realitySettings")["mldsa65Verify"].asString
        assertEquals(canonical, key(generator.generateConfig(node)))
        assertEquals(canonical, key(generator.generateTestConfig(node, 25001)))
        val imported = JsonParser.parseString(generator.generateConfig(node)).asJsonObject
        val reality = imported.getAsJsonArray("outbounds").first { it.asJsonObject["tag"].asString == "proxy" }
            .asJsonObject.getAsJsonObject("streamSettings").getAsJsonObject("realitySettings")
        reality.addProperty("mldsa65Verify", standard)
        val jsonNode = node.copy(rawLink = "", fullConfigJson = imported.toString())
        assertEquals(canonical, key(generator.generateConfig(jsonNode)))
        assertEquals(canonical, key(generator.generateTestConfig(jsonNode, 25001)))
        reality.addProperty("mldsa65Verify", "truncated-key")
        assertThrows(IllegalArgumentException::class.java) { generator.generateConfig(jsonNode.copy(fullConfigJson = imported.toString())) }
        assertThrows(IllegalArgumentException::class.java) { generator.generateConfig(node.copy(rawLink = raw + "truncated-key")) }
    }

    @Test fun ordinaryProxyDoesNotRequireDnsBeforeTransportButCustomResolverStillApplies() {
        val ordinary = JsonParser.parseString(generator.generateConfig(null)).asJsonObject
        assertEquals("AsIs", ordinary.getAsJsonArray("outbounds").first {
            it.asJsonObject["tag"].asString == "proxy"
        }.asJsonObject["targetStrategy"].asString)
        assertFalse(ordinary.getAsJsonArray("outbounds").any { it.asJsonObject["tag"].asString == "dns-bootstrap" })
        val custom = JsonParser.parseString(generator.generateConfig(null, dnsProvider = "custom",
            customDohUrl = "https://resolver.example/dns-query")).asJsonObject
        assertEquals("ForceIP", custom.getAsJsonArray("outbounds").first {
            it.asJsonObject["tag"].asString == "proxy"
        }.asJsonObject["targetStrategy"].asString)
        val bootstrap = custom.getAsJsonArray("outbounds").first {
            it.asJsonObject["tag"].asString == "dns-bootstrap"
        }.asJsonObject
        assertEquals("AsIs", bootstrap["targetStrategy"].asString)
        val rule = custom.getAsJsonObject("routing").getAsJsonArray("rules").first {
            it.asJsonObject["outboundTag"].asString == "dns-bootstrap"
        }.asJsonObject
        assertEquals(listOf("full:resolver.example"), rule.getAsJsonArray("domain").map { it.asString })
        val numeric = JsonParser.parseString(generator.generateConfig(null, dnsProvider = "custom",
            customDohUrl = "https://1.1.1.1/dns-query")).asJsonObject
        assertFalse(numeric.getAsJsonArray("outbounds").any { it.asJsonObject["tag"].asString == "dns-bootstrap" })
    }

    @Test fun xhttpAllowlistedSniDoesNotReplaceServerAddressOrRequireExternalDns() {
        val node = requireNotNull(com.danieldk.splatevpn.data.LinkParser.parse(
            "vless://11111111-2222-3333-4444-555555555555@192.0.2.25:443?type=xhttp&security=tls&sni=max.ru&host=origin.example&path=%2Fmobile&mode=packet-up"))
        for (raw in listOf(generator.generateConfig(node, mode = "global", bypassAi = false), generator.generateTestConfig(node, 25001))) {
            val config = JsonParser.parseString(raw).asJsonObject
            val proxy = config.getAsJsonArray("outbounds").first { it.asJsonObject["tag"].asString == "proxy" }.asJsonObject
            assertEquals("AsIs", proxy["targetStrategy"].asString)
            assertEquals("192.0.2.25", proxy.getAsJsonObject("settings").getAsJsonArray("vnext")[0].asJsonObject["address"].asString)
            val stream = proxy.getAsJsonObject("streamSettings")
            assertEquals("max.ru", stream.getAsJsonObject("tlsSettings")["serverName"].asString)
            assertEquals("/mobile", stream.getAsJsonObject("xhttpSettings")["path"].asString)
            assertEquals("packet-up", stream.getAsJsonObject("xhttpSettings")["mode"].asString)
            assertEquals("origin.example", stream.getAsJsonObject("xhttpSettings")["host"].asString)
            assertFalse(config.getAsJsonObject("dns").getAsJsonArray("servers").any {
                it.isJsonObject && it.asJsonObject["address"]?.asString == "localhost"
            })
        }
    }

    @Test fun selectedDnsNeverAddsOtherProvidersAsGeneralFallbacks() {
        val providers = mapOf("cloudflare" to "https://1.1.1.1/dns-query", "google" to "https://8.8.8.8/dns-query",
            "quad9" to "https://9.9.9.9/dns-query", "adguard" to "https://94.140.14.14/dns-query",
            "yandex" to "tcp://77.88.8.8:53", "custom" to "https://resolver.example/dns-query")
        for ((provider, address) in providers) {
            for (aiMode in listOf("vpn", "dns_ai", "dns_vpn")) {
                val dns = JsonParser.parseString(generator.generateConfig(null, dnsProvider = provider,
                    customDohUrl = "https://resolver.example/dns-query", aiRoutingMode = aiMode)).asJsonObject.getAsJsonObject("dns")
                val servers = dns.getAsJsonArray("servers")
                val general = servers.filter { it.isJsonPrimitive }.map { it.asString }
                val expected = if (address.contains("://")) listOf(address) else listOf(address, "tcp://$address:53")
                assertEquals("$provider/$aiMode", expected, general)
                val scoped = servers.filter { it.isJsonObject }.map { it.asJsonObject }
                assertTrue(scoped.isNotEmpty())
                assertTrue(scoped.all { it.get("skipFallback").asBoolean && it.getAsJsonArray("domains").size() > 0 })
                assertTrue(dns.get("disableFallbackIfMatch").asBoolean)
            }
        }
    }

    @Test
    fun tunneledSmartDnsKeepsRelayResolutionAndUsesSelectedTransport() {
        SmartDnsProviders.all.forEach { provider ->
            listOf("xhttp", "tcp", "ws", "grpc").forEach { transport ->
                val node = ServerNode("test", "test", "vless", "vpn.example.com", 443,
                    uuid = "11111111-2222-3333-4444-555555555555", network = transport, security = "tls")
                val json = JsonParser.parseString(generator.generateConfig(node, aiRoutingMode = "dns_vpn",
                    aiDnsProvider = provider.key, aiTlsFragment = true, blockQuic = false)).asJsonObject
                val relay = json.getAsJsonArray("outbounds").first { it.asJsonObject["tag"].asString == "ai-direct" }.asJsonObject
                val socket = relay.getAsJsonObject("streamSettings").getAsJsonObject("sockopt")
                assertEquals("proxy", socket["dialerProxy"].asString)
                assertEquals("ForceIPv4", socket["domainStrategy"].asString)
                assertFalse(relay.getAsJsonObject("settings").has("fragment"))
                val rules = json.getAsJsonObject("routing").getAsJsonArray("rules").map { it.asJsonObject }
                val doh = rules.first { it.getAsJsonArray("domain")?.any { d -> d.asString == "full:${provider.host}" } == true }
                assertEquals("proxy", doh["outboundTag"].asString)
                val ai = rules.first { it["network"]?.asString != "udp" &&
                    it.getAsJsonArray("domain")?.any { d -> d.asString == "full:gemini.google.com" } == true }
                assertEquals("ai-direct", ai["outboundTag"].asString)
                assertTrue(rules.any { it["network"]?.asString == "udp" && it["port"]?.asString == "443" &&
                    it["outboundTag"]?.asString == "block" })
                val proxy = json.getAsJsonArray("outbounds").first { it.asJsonObject["tag"].asString == "proxy" }.asJsonObject
                assertEquals(transport, proxy.getAsJsonObject("streamSettings")["network"].asString)
                assertFalse(proxy.getAsJsonObject("streamSettings").getAsJsonObject("sockopt").has("dialerProxy"))
            }
        }
    }

    @Test
    fun tunneledAiDnsDoesNotTunnelAnUnrelatedGeneralSmartDns() {
        val json = JsonParser.parseString(generator.generateConfig(null, aiRoutingMode = "dns_vpn",
            aiDnsProvider = "geohide", dnsProvider = "malw")).asJsonObject
        val rules = json.getAsJsonObject("routing").getAsJsonArray("rules").map { it.asJsonObject }
        fun route(host: String) = rules.first {
            it.getAsJsonArray("domain")?.any { d -> d.asString == "full:$host" } == true
        }["outboundTag"].asString
        assertEquals("proxy", route("geohide.ru"))
        assertEquals("direct", route("dns.malw.link"))
        val disabled = JsonParser.parseString(generator.generateConfig(null, aiRoutingMode = "dns_vpn", bypassAi = false)).asJsonObject
        assertFalse(disabled.getAsJsonArray("outbounds").any { it.asJsonObject["tag"].asString == "ai-direct" })
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
                assertEquals(if (fragment) "provider-probe" else "dns-probe", rules.first().get("outboundTag").asString)
                assertEquals("ai-probe", rules[1].get("outboundTag").asString)
                assertEquals("dns-probe", rules.last().get("outboundTag").asString)
                val outbounds = json.getAsJsonArray("outbounds").map { it.asJsonObject }
                val ai = outbounds.first { it.get("tag").asString == "ai-probe" }
                assertEquals(fragment, ai.getAsJsonObject("settings").has("fragment"))
                assertEquals("ForceIPv4", ai.getAsJsonObject("streamSettings").getAsJsonObject("sockopt").get("domainStrategy").asString)
                val doh = outbounds.first { it.get("tag").asString == "dns-probe" }
                assertFalse(doh.has("settings"))
                val providerOut = outbounds.firstOrNull { it.get("tag").asString == "provider-probe" }
                assertEquals(fragment, providerOut != null)
                if (fragment) assertTrue(requireNotNull(providerOut).getAsJsonObject("settings").has("fragment"))
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
            assertEquals(provider.key, "https://1.1.1.1/dns-query", aiDns.get("address").asString)
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
            assertEquals(provider.key, "https://1.1.1.1/dns-query", firstMatchedDns.get("address").asString)
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
    fun optionalAiFragmentationIncludesSelectedDohConnections() {
        val json = JsonParser.parseString(generator.generateConfig(null, aiDnsProvider = "malw_gateway", aiTlsFragment = true)).asJsonObject
        val rule = json.getAsJsonObject("routing").getAsJsonArray("rules").map { it.asJsonObject }.first {
            it.getAsJsonArray("domain")?.any { d -> d.asString == "full:5u35p8m9i7.cloudflare-gateway.com" } == true
        }
        assertEquals("ai-direct", rule.get("outboundTag").asString)
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
        assertEquals(listOf("194.87.196.13", "45.151.102.219"),
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
    fun importedProfilesUseManagedDnsRoutesAndListeners() {
        val raw = """{"inbounds":[{"protocol":"http","listen":"0.0.0.0","port":8888}],
            "outbounds":[{"protocol":"trojan","tag":"custom","settings":{"servers":[{"address":"example.com","port":443,"password":"test-password"}]},"streamSettings":{"network":"tcp","security":"tls"}}],
            "dns":{"servers":["evil.example"]},"routing":{"rules":[{"outboundTag":"direct"}]},
            "log":{"access":"/arbitrary/path"}}"""
        val node = ServerNode("import", "Imported", "trojan", "example.com", 443, uuid = "test-password", fullConfigJson = raw)
        val config = JsonParser.parseString(generator.generateConfig(node, mode = "global", bypassAi = false, blockQuic = true)).asJsonObject
        assertEquals("trojan", config.getAsJsonArray("outbounds")[0].asJsonObject["protocol"].asString)
        assertEquals("proxy", config.getAsJsonArray("outbounds")[0].asJsonObject["tag"].asString)
        assertEquals(1, config.getAsJsonArray("inbounds").size())
        assertEquals("127.0.0.1", config.getAsJsonArray("inbounds")[0].asJsonObject["listen"].asString)
        assertFalse(config.toString().contains("evil.example"))
        assertFalse(config.toString().contains("/arbitrary/path"))
        assertTrue(config.getAsJsonObject("routing").getAsJsonArray("rules").any {
            it.asJsonObject["network"]?.asString == "udp" && it.asJsonObject["port"]?.asString == "443" && it.asJsonObject["outboundTag"]?.asString == "block"
        })
        val probe = JsonParser.parseString(generator.generateTestConfig(node, 25001)).asJsonObject
        assertEquals(1, probe.getAsJsonArray("inbounds").size())
        assertEquals(25001, probe.getAsJsonArray("inbounds")[0].asJsonObject["port"].asInt)
        assertEquals("none", probe.getAsJsonObject("log")["loglevel"].asString)
    }

    @Test
    fun aiBypass_disabledDoesNotInstallSmartDnsOrDirectAiRoute() {
        val json = JsonParser.parseString(generator.generateConfig(node = null, bypassAi = false)).asJsonObject
        assertFalse(json.toString().contains("dns.dns-ai.ru"))
        assertTrue(json.getAsJsonObject("dns").get("disableFallbackIfMatch").asBoolean)
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
        assertEquals("https://1.1.1.1/dns-query", dns.get("address").asString)
    }

    @Test
    fun customAiProxyOverride_usesRemoteDnsBeforeSmartDns() {
        val json = JsonParser.parseString(generator.generateConfig(null, customRoutes = listOf("domain:chatgpt.com" to "proxy"))).asJsonObject
        val matchedDns = json.getAsJsonObject("dns").getAsJsonArray("servers").filter { it.isJsonObject }.map { it.asJsonObject }.first {
            it.getAsJsonArray("domains")?.any { d -> d.asString in listOf("domain:chatgpt.com", "full:chatgpt.com") } == true
        }
        assertEquals("https://1.1.1.1/dns-query", matchedDns.get("address").asString)
    }

    @Test
    fun fakeDns_enablesDestinationRecoveryDespiteConflictingSniffingSettings() {
        val json = JsonParser.parseString(generator.generateConfig(null, isFakeDns = true,
            enableSniffing = false, routeOnlySniffing = true, shareLan = true, lanAccess = LanAccess("192.168.43.1", "splate", "test-password"))).asJsonObject
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
    fun testGenerateConfig_vlessReality_withoutFlow_doesNotForceVision() {
        val node = ServerNode(
            id = "test-node-no-flow",
            name = "Test VLESS Reality No Flow",
            protocol = "vless",
            address = "vpn.example.com",
            port = 443,
            uuid = "11111111-2222-3333-4444-555555555555",
            flow = "",
            encryption = "none",
            network = "tcp",
            security = "reality",
            sni = "reality.example.com",
            fingerprint = "chrome",
            publicKey = "abcdef0123456789",
            shortId = "abcd"
        )

        val prodConfig = JsonParser.parseString(generator.generateConfig(node = node)).asJsonObject
        val prodUser = prodConfig.getAsJsonArray("outbounds")[0].asJsonObject
            .getAsJsonObject("settings").getAsJsonArray("vnext")[0].asJsonObject
            .getAsJsonArray("users")[0].asJsonObject
        assertFalse("VLESS Reality node without flow must NOT have xtls-rprx-vision forced", prodUser.has("flow"))

        val testConfig = JsonParser.parseString(generator.generateTestConfig(node = node, socksPort = 25001)).asJsonObject
        val testUser = testConfig.getAsJsonArray("outbounds")[0].asJsonObject
            .getAsJsonObject("settings").getAsJsonArray("vnext")[0].asJsonObject
            .getAsJsonArray("users")[0].asJsonObject
        assertFalse("VLESS Reality test config without flow must NOT have xtls-rprx-vision forced", testUser.has("flow"))
    }

    @Test
    fun testGenerateConfig_vlessReality_withExplicitFlow_preservesFlow() {
        val node = ServerNode(
            id = "test-node-explicit-flow",
            name = "Test VLESS Reality Explicit Flow",
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

        val prodConfig = JsonParser.parseString(generator.generateConfig(node = node)).asJsonObject
        val prodUser = prodConfig.getAsJsonArray("outbounds")[0].asJsonObject
            .getAsJsonObject("settings").getAsJsonArray("vnext")[0].asJsonObject
            .getAsJsonArray("users")[0].asJsonObject
        assertEquals("xtls-rprx-vision", prodUser.get("flow").asString)

        val testConfig = JsonParser.parseString(generator.generateTestConfig(node = node, socksPort = 25001)).asJsonObject
        val testUser = testConfig.getAsJsonArray("outbounds")[0].asJsonObject
            .getAsJsonObject("settings").getAsJsonArray("vnext")[0].asJsonObject
            .getAsJsonArray("users")[0].asJsonObject
        assertEquals("xtls-rprx-vision", testUser.get("flow").asString)
    }

    @Test
    fun testGenerateConfig_ipAddressDoesNotSetVerifyPeerCertByName() {
        // Fix verification: if address is IP, verifyPeerCertByName must NOT be set to IP (even with non-empty SNI)
        val nodeWithEmptySni = ServerNode(
            id = "test-node-ip",
            name = "Test IP Node",
            protocol = "vless",
            address = "192.168.1.100",
            port = 443,
            uuid = "11111111-2222-3333-4444-555555555555",
            security = "tls",
            sni = ""
        )

        val configJson1 = generator.generateConfig(node = nodeWithEmptySni)
        val json1 = JsonParser.parseString(configJson1).asJsonObject
        val proxyOutbound1 = json1.getAsJsonArray("outbounds")[0].asJsonObject
        val tlsSettings1 = proxyOutbound1.getAsJsonObject("streamSettings").getAsJsonObject("tlsSettings")

        assertFalse(
            "verifyPeerCertByName should not be set for raw IP addresses with empty SNI",
            tlsSettings1.has("verifyPeerCertByName")
        )

        val nodeWithSni = ServerNode(
            id = "test-node-ip-sni",
            name = "Test IP Node with SNI",
            protocol = "vless",
            address = "192.168.1.100",
            port = 443,
            uuid = "11111111-2222-3333-4444-555555555555",
            security = "tls",
            sni = "server.example.com"
        )

        val configJson2 = generator.generateConfig(node = nodeWithSni)
        val json2 = JsonParser.parseString(configJson2).asJsonObject
        val proxyOutbound2 = json2.getAsJsonArray("outbounds")[0].asJsonObject
        val tlsSettings2 = proxyOutbound2.getAsJsonObject("streamSettings").getAsJsonObject("tlsSettings")

        assertFalse(
            "verifyPeerCertByName should not be set for raw IP addresses even when SNI is present",
            tlsSettings2.has("verifyPeerCertByName")
        )
        assertEquals("server.example.com", tlsSettings2.get("serverName").asString)
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
        // Bootstrap is a scoped object; general resolvers remain strings.
        assertTrue(servers.first().isJsonObject)
        assertEquals("localhost", servers.first().asJsonObject["address"].asString)
        assertTrue(servers.drop(1).all { it.isJsonPrimitive && it.asJsonPrimitive.isString })

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
