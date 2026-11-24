package com.danieldk.splatevpn.vpn

import com.danieldk.splatevpn.data.TorConfigParser
import com.danieldk.splatevpn.data.TorSettings
import com.danieldk.splatevpn.data.torBridgeTransport
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.InputStreamReader

class TorTop100BenchmarkTest {
    private lateinit var rawTop100: String

    @Before
    fun setUp() {
        val stream = javaClass.classLoader?.getResourceAsStream("tor_top100.txt")
            ?: error("tor_top100.txt not found in test resources")
        rawTop100 = InputStreamReader(stream, Charsets.UTF_8).use { it.readText() }
    }

    @Test
    fun parseAll100BridgesWithoutLoss() {
        val imported = TorConfigParser.parseSubscription(rawTop100)
        assertEquals(100, imported.bridges.size)
        assertEquals(0, imported.skippedBridges)
        assertTrue(imported.useBridges == true)

        val settings = TorSettings(useBridges = true, bridges = imported.bridges)
        val validated = TorConfigParser.validate(settings)
        assertEquals(100, validated.bridges.size)
    }

    @Test
    fun verifyClassificationAndAddressExtractionFor100Bridges() {
        val imported = TorConfigParser.parseSubscription(rawTop100)
        val grouped = imported.bridges.groupBy(::torBridgeTransport)

        assertTrue("Should contain Vanilla bridges", grouped.containsKey("vanilla") && grouped["vanilla"]!!.isNotEmpty())
        assertTrue("Should contain obfs4 bridges", grouped.containsKey("obfs4") && grouped["obfs4"]!!.isNotEmpty())
        assertTrue("Should contain WebTunnel bridges", grouped.containsKey("webtunnel") && grouped["webtunnel"]!!.isNotEmpty())

        imported.bridges.forEach { bridge ->
            val address = TorConfigParser.extractBridgeAddress(bridge)
            assertNotNull("Bridge address must be extractable: $bridge", address)
            val (host, port) = address!!
            assertTrue("Port must be valid: $port", port in 1..65535)
            assertTrue("Host must not be blank", host.isNotBlank())
            assertFalse("IPv6 brackets must be stripped: $host", host.startsWith("[") || host.endsWith("]"))

            if (torBridgeTransport(bridge) == "webtunnel") {
                val sni = TorConfigParser.extractBridgeSni(bridge)
                assertNotNull("WebTunnel bridge must have an extractable SNI / domain: $bridge", sni)
                assertTrue("SNI must be non-empty", sni!!.isNotBlank())
            }
        }
    }

    @Test
    fun benchmarkRankingPreservesTransportDiversityUnderHarshCensorship() {
        val imported = TorConfigParser.parseSubscription(rawTop100)
        val settings = TorSettings(useBridges = true, bridges = imported.bridges)

        // Simulate Russian TSPU / DPI environment:
        // 75 bridges completely blocked (timeout / packet drop = -1L)
        // 15 bridges throttled / high jitter (1500ms..4000ms)
        // 10 bridges active and fast (50ms..320ms)
        val simulatedDelays = mutableMapOf<String, Long>()

        imported.bridges.forEachIndexed { index, bridge ->
            simulatedDelays[bridge] = when {
                index < 10 -> 50L + index * 25L // 50ms, 75ms, ..., 275ms (Fastest)
                index < 25 -> 1500L + index * 100L // 1500ms..3900ms (High latency)
                else -> -1L // 75 bridges blocked / unreachable
            }
        }

        val plan = TorBridgeSelection.plan(settings, delays = simulatedDelays)
        assertTrue(plan.automatic)
        assertEquals(TorBridgeSelection.MAX_ATTEMPTS, plan.candidates.size)

        // Assert candidate 1 is the fastest bridge overall (50ms)
        val topBridge = plan.candidates[0].bridge
        assertNotNull(topBridge)
        assertEquals(50L, simulatedDelays[topBridge])

        val transports = settings.bridges.map(::torBridgeTransport).distinct()
        assertEquals(transports.size, plan.candidates.take(transports.size).map { it.transport }.distinct().size)
        // TCP reachability does not prove that a transport survives the bridge handshake.
        // Keep the fastest candidates within each transport, including a slower fallback.
        for ((transport, candidates) in plan.candidates.groupBy { it.transport }) {
            val available = settings.bridges.filter { torBridgeTransport(it) == transport }
                .sortedBy { simulatedDelays[it]?.takeIf { delay -> delay > 0 } ?: Long.MAX_VALUE }
            assertEquals(available.take(candidates.size), candidates.map { it.bridge })
        }
    }

    @Test
    fun benchmarkFullBlackoutFallback() {
        val imported = TorConfigParser.parseSubscription(rawTop100)
        val settings = TorSettings(useBridges = true, bridges = imported.bridges)

        // All 100 bridges are reported unreachable
        val blackoutDelays = imported.bridges.associateWith { -1L }

        val plan = TorBridgeSelection.plan(settings, delays = blackoutDelays)
        assertTrue(plan.automatic)
        assertEquals(TorBridgeSelection.MAX_ATTEMPTS, plan.candidates.size)

        // Even in complete blackout, candidates are valid and alternate transports
        val transports = plan.candidates.map { it.transport }.distinct()
        assertTrue("Should diversify transports during blackout", transports.size >= 2)
    }

    @Test
    fun pinnedBridgeOverridesBenchmarkResults() {
        val imported = TorConfigParser.parseSubscription(rawTop100)
        val pinnedBridge = imported.bridges[42]
        val settings = TorSettings(useBridges = true, bridges = imported.bridges, selectedBridge = pinnedBridge)

        // Pinned bridge has high latency, while other bridges have ultra-low latency
        val delays = imported.bridges.associateWith { if (it == pinnedBridge) 2500L else 40L }

        val plan = TorBridgeSelection.plan(settings, delays = delays)
        assertFalse(plan.automatic)
        assertEquals(1, plan.candidates.size)
        assertEquals(pinnedBridge, plan.candidates.single().bridge)
    }

    @Test
    fun verifyOptimizedTorrcPerformanceDirectives() {
        val imported = TorConfigParser.parseSubscription(rawTop100)
        val settings = TorSettings(
            useBridges = true,
            bridges = imported.bridges.take(5),
            optimisticData = true,
            circuitBuildTimeout = 15,
            maxCircuitDirtiness = 300
        )
        val transports = mapOf("obfs4" to 41100, "webtunnel" to 41101, "snowflake" to 41102)
        val torrc = TorConfigParser.buildTorrc(settings, transports)

        // Verify speed & anti-censorship parameters
        assertTrue("OptimisticData must be enabled", torrc.contains("OptimisticData 1\n"))
        assertTrue("NumEntryGuards must be set to 4", torrc.contains("NumEntryGuards 4\n"))
        assertTrue("NumDirectoryGuards must be set to 3", torrc.contains("NumDirectoryGuards 3\n"))
        assertTrue("MaxCircuitDirtiness must be 300s", torrc.contains("MaxCircuitDirtiness 300\n"))
        assertTrue("KeepalivePeriod must be 60s", torrc.contains("KeepalivePeriod 60\n"))
        assertTrue("ConnectionPadding must be enabled", torrc.contains("ConnectionPadding 1\n"))
        assertTrue("ReducedConnectionPadding must be enabled", torrc.contains("ReducedConnectionPadding 1\n"))
        assertTrue("EnforceDistinctSubnets must be 0", torrc.contains("EnforceDistinctSubnets 0\n"))
    }

    @Test
    fun verifySnowflakeBridgeSupport() {
        val snowflakeBridge = "snowflake 192.0.2.3:1 2B280B23E1107BB62ABFC40DDCC8224815F83E7A url=https://snowflake-broker.torproject.net.global.prod.fastly.net/ front=cdn.sstatic.net ice=stun:stun.l.google.com:19302,stun:stun.voip.blackberry.com:3478 utls-imitate=hellorandomizedalpn"
        val validated = TorConfigParser.validateBridge(snowflakeBridge)
        assertEquals(snowflakeBridge, validated)
        assertEquals("snowflake", torBridgeTransport(snowflakeBridge))

        val sni = TorConfigParser.extractBridgeSni(snowflakeBridge)
        assertEquals("cdn.sstatic.net", sni)

        val address = TorConfigParser.extractBridgeAddress(snowflakeBridge)
        assertNotNull(address)
        assertEquals("192.0.2.3", address!!.first)
        assertEquals(1, address.second)

        val settings = TorSettings(useBridges = true, bridges = listOf(snowflakeBridge))
        val torrc = TorConfigParser.buildTorrc(settings, mapOf("snowflake" to 42000))
        assertTrue(torrc.contains("ClientTransportPlugin snowflake socks5 127.0.0.1:42000\n"))
        assertTrue(torrc.contains("Bridge $snowflakeBridge\n"))
    }

    @Test
    fun verifyCustomBridgeSniExtraction() {
        val bridgeWithSni = "webtunnel 192.0.2.1:443 1111111111111111111111111111111111111111 sni=fastly.com url=https://cdn-45.triplebit.dev/secret"
        assertEquals("fastly.com", TorConfigParser.extractBridgeSni(bridgeWithSni))

        val bridgeWithFront = "webtunnel 192.0.2.1:443 1111111111111111111111111111111111111111 front=cloudflare.net url=https://cdn-45.triplebit.dev/secret"
        assertEquals("cloudflare.net", TorConfigParser.extractBridgeSni(bridgeWithFront))

        val bridgeWithUrlOnly = "webtunnel 192.0.2.1:443 1111111111111111111111111111111111111111 url=https://custom-domain.org/path"
        assertEquals("custom-domain.org", TorConfigParser.extractBridgeSni(bridgeWithUrlOnly))
    }
}
