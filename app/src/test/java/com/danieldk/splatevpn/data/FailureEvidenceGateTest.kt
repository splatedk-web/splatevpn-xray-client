package com.danieldk.splatevpn.data

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class FailureEvidenceGateTest {
    @Test fun allControlsBlockedMeansUnconfirmedRatherThanAnOfflineVerdict() = runBlocking {
        val gate = FailureEvidenceGate()
        assertEquals(PhysicalEvidence.UNCONFIRMED, gate.assess(10, { 10 }) { false })
    }

    @Test fun aRegionalResponseSufficesEvenWhenEveryForeignControlFails() = runBlocking {
        for (region in listOf(RoutingRegion.RUSSIA, RoutingRegion.CHINA, RoutingRegion.IRAN)) {
            val first = physicalControlUrls(region).first()
            val success = anyReachable(physicalControlUrls(region)) { it == first }
            assertTrue("Regional access must not depend on foreign controls: $region", success)
        }
    }

    @Test fun aWrongSelectedRegionDoesNotExcludeOtherRegionalControls() {
        RoutingRegion.entries.forEach { region ->
            assertTrue(physicalControlUrls(region).containsAll(listOf("https://ya.ru/", "https://www.baidu.com/", "https://www.aparat.com/")))
        }
    }

    @Test fun aSuccessDoesNotWaitForHangingControlsAndCancelsThem() = runBlocking {
        val cancelled = CompletableDeferred<Unit>()
        val success = withTimeout(1000) { anyReachable(listOf(false, true)) {
            if (it) { yield(); true } else try { awaitCancellation() } finally { cancelled.complete(Unit) }
        } }
        assertTrue(success)
        assertTrue(cancelled.isCompleted)
    }

    @Test fun networkChangeDuringProbeCannotPublishOldReachability() = runBlocking {
        var current = 10L
        assertEquals(PhysicalEvidence.NO_NETWORK, FailureEvidenceGate().assess(10, { current }) { current = 11; true })
    }

    @Test fun sameWaveSharesResultsButOutageAndRecoveryAreRechecked() = runBlocking {
        var now = 0L
        var connected = true
        var calls = 0
        val gate = FailureEvidenceGate { now }
        suspend fun read() = gate.assess(10, { 10 }) { calls++; connected }
        coroutineScope { List(6) { async { read() } }.awaitAll() }
        assertEquals(1, calls)
        connected = false; now = 501
        assertEquals(PhysicalEvidence.UNCONFIRMED, read())
        connected = true; now = 2502
        assertEquals(PhysicalEvidence.REACHABLE, read())
    }
}
