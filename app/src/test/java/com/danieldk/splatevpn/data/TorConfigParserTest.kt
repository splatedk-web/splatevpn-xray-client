package com.danieldk.splatevpn.data

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class TorConfigParserTest {
    private val fingerprint = "A".repeat(40)
    private val otherFingerprint = "B".repeat(40)
    private val cert = Base64.getEncoder().withoutPadding().encodeToString(ByteArray(52) { it.toByte() })
    private val vanilla = "198.51.100.2:443 $fingerprint"
    private val obfs4 = "obfs4 198.51.100.3:8443 $fingerprint cert=$cert iat-mode=0"

    @Test fun defaultTop100KeepsExistingBridgesAndStaysRemovedAfterReload() {
        val custom = TorBridgeSubscription("https://example.org/bridges", listOf(vanilla))
        val legacy = TorSettings(useBridges = true).withBridgeSources(listOf(custom), listOf(obfs4))
            .copy(useBridges = true, selectedBridge = vanilla)
        val initialized = legacy.withDefaultBridgeSubscription()
        assertEquals(listOf(TorBridgeSubscription(TOR_TOP100_SUBSCRIPTION, emptyList()), custom), initialized.bridgeSubscriptions)
        assertEquals(legacy.bridges, initialized.bridges)
        assertEquals(vanilla, initialized.selectedBridge)
        val removed = initialized.withBridgeSources(listOf(custom))
        val gson = com.google.gson.Gson()
        val loaded = TorConfigParser.validate(gson.fromJson(gson.toJson(removed), TorSettings::class.java))
        assertEquals(removed, loaded.withDefaultBridgeSubscription())
        assertEquals(listOf(obfs4), loaded.manualBridges())
    }

    @Test fun legacySubscriptionMigratesAndSourcesCanBeRefreshedIndependently() {
        val firstUrl = "https://one.example/bridges"
        val secondUrl = "https://two.example/bridges"
        val otherVanilla = "198.51.100.4:443 $otherFingerprint"
        val oldSettings = TorSettings(useBridges = true, bridges = listOf(vanilla), subscriptionUrl = firstUrl,
            selectedBridge = vanilla, autoUpdate = true)
        val gson = com.google.gson.Gson()
        val legacyJson = gson.toJsonTree(oldSettings).asJsonObject.apply {
            remove("bridgeSubscriptions")
            remove("manualBridgeEntries")
        }
        val migrated = TorConfigParser.validate(gson.fromJson(legacyJson, TorSettings::class.java))
        assertEquals(listOf(TorBridgeSubscription(firstUrl, listOf(vanilla))), migrated.bridgeSubscriptions)
        assertTrue(migrated.autoUpdate)
        val combined = migrated.withBridgeSources(
            migrated.bridgeSubscriptions + TorBridgeSubscription(secondUrl, listOf(obfs4)),
            manual = listOf(obfs4)
        )
        val refreshed = TorConfigParser.validate(combined.withBridgeSources(listOf(
            TorBridgeSubscription(firstUrl, listOf(otherVanilla)),
            combined.bridgeSubscriptions[1]
        )))
        assertEquals(setOf(obfs4, otherVanilla), refreshed.bridges.toSet())
        assertEquals("", refreshed.selectedBridge)
        val loaded = TorConfigParser.validate(gson.fromJson(gson.toJson(refreshed), TorSettings::class.java))
        assertEquals(listOf(obfs4), loaded.withBridgeSources(emptyList()).bridges)
    }

    @Test fun acceptsBridgeDbListAndDeduplicates() {
        val result = TorConfigParser.parse("# BridgeDB\nBridge $vanilla\n$obfs4\n$vanilla\n")
        assertEquals(listOf(vanilla, obfs4), result.bridges)
    }

    @Test fun parsesTorrcRelaySelectionWithoutExecutingOtherDirectives() {
        val result = TorConfigParser.parse("UseBridges 1\nBridge $obfs4\nEntryNodes \u0024$fingerprint\nExitNodes $otherFingerprint\nExcludeNodes $fingerprint\nStrictNodes 1")
        assertEquals(true, result.useBridges)
        assertEquals(true, result.strictNodes)
        assertEquals("\u0024$fingerprint", result.entryNodes)
        assertEquals(otherFingerprint, result.exitNodes)
    }

    @Test fun acceptsJsonBridgeListAndSafeTorrc() {
        val result = TorConfigParser.parse("""{"bridges":["$obfs4"],"exitNodes":"$otherFingerprint","strictNodes":true}""")
        assertEquals(listOf(obfs4), result.bridges)
        assertEquals(otherFingerprint, result.exitNodes)
        assertEquals(true, result.strictNodes)
        assertEquals(listOf(vanilla), TorConfigParser.parse("""["$vanilla"]""").bridges)
    }

    @Test fun normalizesGitHubFileLinkToRawContent() {
        assertEquals("https://raw.githubusercontent.com/owner/repo/main/bridges/torrc.txt",
            TorConfigParser.normalizeSubscriptionUrl("https://github.com/owner/repo/blob/main/bridges/torrc.txt?raw=true"))
        assertEquals("https://example.com/tor.txt?token=opaque", TorConfigParser.normalizeSubscriptionUrl("https://example.com/tor.txt?token=opaque"))
    }

    @Test fun rejectsGithubHtmlDirectoryAndInsecureSources() {
        rejects { TorConfigParser.normalizeSubscriptionUrl("https://github.com/owner/repo") }
        rejects { TorConfigParser.normalizeSubscriptionUrl("http://example.com/tor.txt") }
        rejects { TorConfigParser.normalizeSubscriptionUrl("https://user:password@example.com/tor.txt") }
        rejects { TorConfigParser.parse("<html><body>not raw GitHub content</body></html>") }
    }

    @Test fun rejectsInjectedTorrcAndExecutableTransportOptions() {
        listOf("SocksPort 0.0.0.0:9050", "DataDirectory /sdcard/tor", "ClientTransportPlugin obfs4 exec /bin/sh", "HiddenServiceDir /tmp/onion", "%include /sdcard/torrc").forEach { directive ->
            rejects { TorConfigParser.parse("Bridge $vanilla\n$directive") }
        }
        rejects { TorConfigParser.parse("""{"bridges":["$vanilla"],"SocksPort":"0.0.0.0:9050"}""") }
        rejects { TorConfigParser.validateBridge("$vanilla\nSocksPort 0.0.0.0:9050") }
        rejects { TorConfigParser.validateNodes("$fingerprint\nSocksPort 1") }
    }

    @Test fun rejectsBrokenAndUnsupportedBridgeTransports() {
        listOf("obfs4 198.51.100.1:443 $fingerprint cert=invalid iat-mode=0",
            "obfs4 198.51.100.1:443 $fingerprint cert=$cert iat-mode=9",
            "meek 192.0.2.3:1 $fingerprint", "999.1.2.3:443 $fingerprint", "198.51.100.1:0 $fingerprint",
            "example.com:443 $fingerprint", "198.51.100.1:443 INVALID").forEach { line ->
            rejects { TorConfigParser.validateBridge(line) }
        }
    }

    @Test fun acceptsIpv6AndWebtunnel() {
        val ipv6 = "[2001:db8::2]:443 $fingerprint"
        assertEquals(ipv6, TorConfigParser.validateBridge(ipv6))
        val web = "webtunnel 192.0.2.3:1 $fingerprint url=https://bridge.example.com/secret ver=0.0.1"
        assertEquals(web, TorConfigParser.validateBridge(web))
        val snowflake = "snowflake 192.0.2.3:1 $fingerprint url=https://broker.example.com/ front=example.com"
        assertEquals(snowflake, TorConfigParser.validateBridge(snowflake))
    }

    @Test fun buildsOnlyLoopbackProxyAndStartedTransports() {
        val settings = TorSettings(useBridges = true, bridges = listOf(obfs4), exitNodes = otherFingerprint)
        val config = TorConfigParser.buildTorrc(settings, mapOf("obfs4" to 12345))
        assertTrue(config.contains("SocksPort 127.0.0.1:auto IsolateSOCKSAuth"))
        assertTrue(config.contains("OptimisticData 1"))
        assertTrue(config.contains("NumEntryGuards 4"))
        assertTrue(config.contains("LearnCircuitBuildTimeout 0"))
        assertTrue(config.contains("CircuitBuildTimeout 15"))
        assertTrue(config.contains("KeepalivePeriod 60"))
        assertTrue(config.contains("ClientTransportPlugin obfs4 socks5 127.0.0.1:12345"))
        assertTrue(config.contains("Bridge $obfs4\n"))
        assertTrue(config.contains("ExitNodes $otherFingerprint\n"))
        assertFalse(config.contains("exec"))
        rejects { TorConfigParser.buildTorrc(settings, emptyMap()) }
        rejects { TorConfigParser.validate(TorSettings(useBridges = true)) }
    }

    @Test fun rejectsOversizedImportsAndCountrySelectorsWithoutGeoip() {
        rejects { TorConfigParser.parse(" ".repeat(TorConfigParser.MAX_BYTES + 1)) }
        rejects { TorConfigParser.parse((0..TorConfigParser.MAX_BRIDGES).joinToString("\n") { vanilla }) }
        rejects { TorConfigParser.validateNodes("{de}") }
    }

    @Test fun appliesCircuitBuildTimeoutAndCustomBridgeSniSafely() {
        val web = "webtunnel 192.0.2.3:1 $fingerprint url=https://bridge.example.com/secret ver=0.0.1"
        val snowflake = "snowflake 192.0.2.4:1 $fingerprint url=https://broker.example.com/ fronts=old1.example,old2.example"
        val settings = TorSettings(
            useBridges = true,
            bridges = listOf(web, snowflake, obfs4),
            circuitBuildTimeout = 25,
            customBridgeSni = "cdn.custom.example"
        )
        val config = TorConfigParser.buildTorrc(settings, mapOf("webtunnel" to 10001, "snowflake" to 10002, "obfs4" to 10003))
        assertTrue(config.contains("LearnCircuitBuildTimeout 0\n"))
        assertTrue(config.contains("CircuitBuildTimeout 25\n"))
        assertTrue(config.contains("Bridge $web servername=cdn.custom.example\n"))
        assertTrue(config.contains("Bridge snowflake 192.0.2.4:1 $fingerprint url=https://broker.example.com/ front=cdn.custom.example\n"))
        assertTrue(config.contains("Bridge $obfs4\n"))

        assertEquals("old1.example", TorConfigParser.extractBridgeSni(snowflake))
        assertEquals("cdn.custom.example", TorConfigParser.extractBridgeSni("$web servername=cdn.custom.example"))

        rejects { TorConfigParser.validate(settings.copy(customBridgeSni = "bad host")) }
        rejects { TorConfigParser.validate(settings.copy(customBridgeSni = "evil.com\nSocksPort 0.0.0.0:9050")) }
    }

    private fun rejects(block: () -> Any?) {
        try { block(); fail("Expected invalid Tor configuration to be rejected") }
        catch (_: IllegalArgumentException) { }
    }
}
