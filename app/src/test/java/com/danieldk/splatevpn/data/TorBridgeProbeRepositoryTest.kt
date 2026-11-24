package com.danieldk.splatevpn.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TorBridgeProbeRepositoryTest {
    @Test fun firstProbeBatchCoversTransportsEvenWhenSubscriptionIsGrouped() {
        val bridges = (1..100).map { "vanilla-$it" } + listOf("webtunnel first", "webtunnel second", "obfs4 only")
        val ordered = torBridgeProbeOrder(bridges, 0)
        assertEquals(setOf("vanilla", "webtunnel", "obfs4"), ordered.take(3).map(::torBridgeTransport).toSet())
        assertEquals(bridges.toSet(), ordered.toSet())
        assertNotEquals(ordered.first(), torBridgeProbeOrder(bridges, 1).first())
    }

    @Test fun webTunnelProbeUsesTheRealUrlPort() = runBlocking {
        java.net.ServerSocket(0).use { server ->
            val bridge = "webtunnel 192.0.2.1:443 ${"A".repeat(40)} url=https://127.0.0.1:${server.localPort}/bridge"
            assertTrue(TorBridgeProbeRepository().probeSingleBridge(bridge, 500).second > 0)
        }
    }

    @Test fun invalidWebTunnelPortCannotAbortTheWholeBridgeScan() = runBlocking {
        val invalid = "webtunnel 192.0.2.1:443 ${"A".repeat(40)} url=https://example.test:99999/bridge"
        assertEquals(-1L, TorBridgeProbeRepository().probeSingleBridge(invalid, 100).second)
    }

    @Test fun largeScanReturnsPartialResultsWithinItsOverallBudget() = runBlocking {
        java.net.ServerSocket(0, 1).use { server ->
            val bridges = (1..1000).map { "127.0.0.1:${server.localPort} " + "%040x".format(it) }
            val repo = TorBridgeProbeRepository()
            val started = System.nanoTime()
            val results = repo.probeBridges(bridges, timeoutMs = 1500, maxConcurrency = 1, totalBudgetMs = 50)
            assertTrue("A scan must not spend one timeout per queued bridge", (System.nanoTime() - started) / 1_000_000 < 800)
            assertTrue(results.size < bridges.size)
            assertFalse(repo.isProbing.value)
        }
    }

    @Test fun speedMeasurementsAreBoundToNetworkSettingsAndCacheGeneration() {
        val repo = TorBridgeProbeRepository()
        val settings = TorSettings(bridges = listOf("v0", "v1"))
        val token = repo.cacheGeneration()
        assertTrue(repo.recordSpeeds(settings, "wifi", mapOf("v0" to 2.0, "v1" to Double.NaN), token))
        assertEquals(mapOf("v0" to 2.0), repo.speedsFor(settings, "wifi"))
        assertTrue(repo.speedsFor(settings, "cellular").isEmpty())
        assertTrue(repo.speedsFor(settings.copy(exitNodes = "{de}"), "wifi").isEmpty())
        repo.clearCache()
        assertFalse(repo.recordSpeeds(settings, "wifi", mapOf("v0" to 3.0), token))
        assertTrue(repo.bridgeSpeeds.value.isEmpty())
    }


    @Test
    fun hasFreshProbesReturnsFalseWhenEmpty() {
        val repo = TorBridgeProbeRepository()
        assertFalse(repo.hasFreshProbes())
        assertNull(repo.getCachedLatency("obfs4 1.2.3.4:443 1111111111111111111111111111111111111111"))
    }

    @Test
    fun clearCacheResetsStateAndLatencies() {
        val repo = TorBridgeProbeRepository()
        repo.clearCache()
        assertFalse(repo.hasFreshProbes())
        assertEquals(0, repo.bridgeLatencies.value.size)
    }

    @Test
    fun probeSingleBridgeExtractsRealHostForWebTunnelDummyIp() = runBlocking {
        val repo = TorBridgeProbeRepository()
        val closedPort = java.net.ServerSocket(0).use { it.localPort }
        // WebTunnel bridge with dummy RFC 5737 192.0.2.1 and closed local endpoint
        val bridge = "webtunnel 192.0.2.1:443 1111111111111111111111111111111111111111 url=https://127.0.0.1:$closedPort/secret"
        val (resBridge, resLatency) = repo.probeSingleBridge(bridge, timeoutMs = 200L)
        assertEquals(bridge, resBridge)
        // Since the extracted endpoint is closed, it safely returns -1L without throwing unhandled exception
        assertEquals(-1L, resLatency)
    }

    @Test
    fun cacheInvalidatedOnNetworkOrConfigurationChange() = runBlocking {
        val repo = TorBridgeProbeRepository()
        val bridges = listOf("v0", "v1")
        repo.probeBridges(bridges, timeoutMs = 200L, networkKey = "net_wifi", transport = "auto")

        // Fresh on same network and settings
        assertTrue(repo.hasFreshProbes(networkKey = "net_wifi", bridges = bridges, transport = "auto"))

        // Invalidated if network changes to cellular
        assertFalse(repo.hasFreshProbes(networkKey = "net_cellular", bridges = bridges, transport = "auto"))

        // Invalidated if bridge list changes
        assertFalse(repo.hasFreshProbes(networkKey = "net_wifi", bridges = listOf("v0", "v2"), transport = "auto"))

        // Invalidated if transport filter changes
        assertFalse(repo.hasFreshProbes(networkKey = "net_wifi", bridges = bridges, transport = "webtunnel"))
    }
}
