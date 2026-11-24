package com.danieldk.splatevpn.data

import org.junit.Assert.*
import org.junit.Test

class TorBridgeSpeedHistoryTest {
    @Test fun repeatedSearchReachesSlowPingCandidatesAndKeepsLeader() {
        val history = TorBridgeSpeedHistory()
        val settings = TorSettings(bridges = (1..12).map { "bridge-$it" })
        val delays = settings.bridges.mapIndexed { index, bridge -> bridge to (index + 1L) * 100 }.toMap()
        history.record(settings, "wifi", mapOf("bridge-1" to 3.0))
        val visited = mutableSetOf<String>()
        repeat(6) {
            val selected = history.candidates(settings, "wifi", delays)
            assertEquals("bridge-1", selected.first())
            selected.forEach { bridge ->
                visited.add(bridge)
                history.started(settings, "wifi", bridge)
            }
        }
        assertEquals(settings.bridges.toSet(), visited)
    }

    @Test fun partialResultsMergeAndAnOutlierDoesNotReplaceTypicalSpeed() {
        val history = TorBridgeSpeedHistory()
        val settings = TorSettings(bridges = listOf("a", "b"))
        history.record(settings, "wifi", mapOf("a" to 2.0, "b" to 4.0))
        history.record(settings, "wifi", mapOf("a" to 3.0))
        history.record(settings, "wifi", mapOf("a" to 100.0))
        assertEquals(mapOf("a" to 3.0, "b" to 4.0), history.speeds(settings, "wifi"))
    }

    @Test fun expiredSamplesAndDifferentNetworksCannotSelectAnOldLeader() {
        var now = 0L
        val history = TorBridgeSpeedHistory { now }
        val settings = TorSettings(bridges = listOf("a", "b"))
        history.record(settings, "wifi", mapOf("a" to 2.0))
        now = 600_001
        assertTrue(history.speeds(settings, "wifi").isEmpty())
        history.record(settings, "wifi", mapOf("a" to 2.0))
        assertTrue(history.speeds(settings, "mobile").isEmpty())
    }

    @Test fun transportFilterAndUntestedBridgesSurvivePartialTcpScan() {
        val history = TorBridgeSpeedHistory()
        val settings = TorSettings(bridges = listOf("v0", "v1", "obfs4 a", "webtunnel b"))
        assertEquals(3, history.candidates(settings, "wifi", mapOf("v0" to 1)).map(::torBridgeTransport).distinct().size)
        assertEquals(listOf("obfs4 a"), history.candidates(settings.copy(bridgeTransport = "obfs4"), "wifi", emptyMap()))
    }
}
