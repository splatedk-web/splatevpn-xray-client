package com.danieldk.splatevpn.data

import org.junit.Assert.*
import org.junit.Test

class ServerQualityTrackerTest {
    @Test fun exhaustedPoolCanRecheckWithoutWaitingForFiveMinuteQuarantine() {
        var now = 1000L
        val tracker = ServerQualityTracker { now }
        repeat(5) { tracker.recordFailure("recovered") }
        assertTrue(tracker.recoveryCandidates(setOf("recovered")).isEmpty())
        now += 10_001
        assertEquals(setOf("recovered"), tracker.recoveryCandidates(setOf("recovered")))
        assertTrue(tracker.isQuarantined("recovered"))
        assertTrue(tracker.canProbe("recovered"))
        tracker.recordSuccess("recovered", 75)
        assertFalse(tracker.isQuarantined("recovered"))
    }

    @Test fun recoverySlotsAreBoundedAndCannotIncludeExcludedProfiles() {
        var now = 1000L
        val tracker = ServerQualityTracker { now }
        (1..10).forEach { tracker.recordFailure("$it") }
        now += 10_001
        val allowed = (2..10).mapTo(hashSetOf()) { "$it" }
        val selected = tracker.recoveryCandidates(allowed)
        assertEquals(2, selected.size)
        assertFalse("1" in selected)
        selected.forEach { tracker.recordFailure(it) }
        assertTrue(tracker.recoveryCandidates(selected).isEmpty())
    }
    @Test fun changingNetworkClearsOldQuarantineAndLatencyHistory() {
        val tracker = ServerQualityTracker { 1000L }
        tracker.onNetworkChanged(1L)
        tracker.recordSuccess("good", 45)
        tracker.recordFailure("blockedOnWifi")
        tracker.onNetworkChanged(1L)
        assertTrue(tracker.isQuarantined("blockedOnWifi"))
        tracker.onNetworkChanged(2L)
        assertFalse(tracker.isQuarantined("blockedOnWifi"))
        assertFalse(tracker.hasFreshSuccess("good"))
        assertEquals(45L, tracker.score("blockedOnWifi", 45))
    }

    @Test fun jitteryFastNodeLosesToStableNode() {
        val tracker = ServerQualityTracker { 1000L }
        listOf(20L, 450L, 30L, 300L, 25L).forEach { tracker.recordSuccess("flaky", it) }
        repeat(5) { tracker.recordSuccess("stable", 100) }
        assertTrue(tracker.score("stable", 100) < tracker.score("flaky", 25))
    }

    @Test fun quarantineExpiresButRecentFailuresStillPenalizeRecovery() {
        var now = 1000L
        val tracker = ServerQualityTracker { now }
        tracker.recordSuccess("flaky", 20)
        tracker.recordFailure("flaky")
        assertTrue(tracker.isQuarantined("flaky"))
        assertFalse(tracker.hasFreshSuccess("flaky"))
        now += 60_001L
        assertFalse(tracker.isQuarantined("flaky"))
        tracker.recordSuccess("flaky", 20)
        tracker.recordSuccess("stable", 100)
        assertTrue(tracker.score("flaky", 20) > tracker.score("stable", 100))
    }

    @Test fun repeatedFailuresIncreaseCooldownAndDoNotReselectFailedReserve() {
        var now = 1000L
        val tracker = ServerQualityTracker { now }
        tracker.recordFailure("bad")
        now += 60_001L
        tracker.recordFailure("bad")
        now += 60_001L
        assertTrue(tracker.isQuarantined("bad"))
        now += 60_000L
        assertFalse(tracker.isQuarantined("bad"))
    }

    @Test fun staleMeasurementCannotServeAsWarmReserve() {
        var now = 1000L
        val tracker = ServerQualityTracker { now }
        tracker.recordSuccess("reserve", 100)
        assertTrue(tracker.hasFreshSuccess("reserve"))
        now += 60_001L
        assertFalse(tracker.hasFreshSuccess("reserve"))
    }

    @Test fun poorHistoryEventuallyExpiresAndActiveBonusIsSmall() {
        var now = 1000L
        val tracker = ServerQualityTracker { now }
        tracker.recordFailure("recovered")
        now += 300_001L
        tracker.recordSuccess("recovered", 100)
        assertEquals(100L, tracker.score("recovered", 100))
        assertEquals(90L, tracker.score("recovered", 100, active = true))
    }
    @Test fun exclusionsAreNotRelaxedWhenEveryCandidateIsUnavailable() {
        val quality = ServerQualityTracker { 1000L }
        val nodes = listOf(
            ServerNode("dead", "Dead", "vless", "example.com", 443),
            ServerNode("disabled", "Disabled", "vless", "example.com", 443, subId = "off"),
            ServerNode("cooldown", "Cooldown", "vless", "example.com", 443)
        )
        quality.recordFailure("cooldown")
        assertTrue(eligibleBalancerCandidates(nodes, setOf("dead"), setOf("off"), quality).isEmpty())
    }

    @Test fun disabledSourcesStayExcludedEvenWithFreshGoodMeasurement() {
        val quality = ServerQualityTracker { 1000L }
        quality.recordSuccess("disabled", 10)
        val nodes = listOf(
            ServerNode("disabled", "Disabled", "vless", "example.com", 443, subId = "off"),
            ServerNode("good", "Good", "vless", "example.com", 443)
        )
        assertEquals(listOf("good"), eligibleBalancerCandidates(nodes, emptySet(), setOf("off"), quality).map { it.id })
    }
}
