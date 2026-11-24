package com.danieldk.splatevpn.vpn

import org.junit.Assert.*
import org.junit.Test

class TorBootstrapStatusTest {
    @Test fun reportsActualFourteenPercentAsRelayTlsHandshake() {
        val status = requireNotNull(TorBootstrapStatus.parse(
            """NOTICE BOOTSTRAP PROGRESS=14 TAG=handshake SUMMARY="Handshaking with a relay""""))
        assertEquals(14, status.progress)
        assertEquals(TorBootstrapStage.TLS_HANDSHAKE, status.stage)
        assertFalse(status.warning)
        assertNull(status.reason)
    }

    @Test fun warningStoresOnlyAnAllowlistedReasonAndNeverEndpointDetails() {
        val secrets = listOf("193.182.111.126:42888", "https://private.example/token", "A".repeat(40), "/data/user/0/private/tor")
        val status = requireNotNull(TorBootstrapStatus.parse(
            """WARN BOOTSTRAP PROGRESS=14 TAG=handshake SUMMARY="TLS ${secrets[0]}" WARNING="Failed ${secrets.joinToString(" ")}" REASON=CONNECTRESET COUNT=3 RECOMMENDATION=warn"""))
        assertEquals(TorBootstrapReason.RESET, status.reason)
        assertTrue(status.warning)
        secrets.forEach { assertFalse("Raw bootstrap data leaked: $it", status.toString().contains(it)) }
        assertEquals(TorBootstrapStage.TLS_HANDSHAKE, status.stage)
    }

    @Test fun quotedWarningCannotSpoofProgressOrStage() {
        val status = requireNotNull(TorBootstrapStatus.parse(
            """WARN BOOTSTRAP PROGRESS=14 TAG=handshake WARNING="TLS failed: \"TAG=done PROGRESS=100\"" REASON=TIMEOUT"""))
        assertEquals(14, status.progress)
        assertEquals(TorBootstrapStage.TLS_HANDSHAKE, status.stage)
        assertEquals(TorBootstrapReason.TIMEOUT, status.reason)
        assertTrue(status.warning)
    }

    @Test fun quotedSummaryCannotSpoofProgressOrStage() {
        val status = requireNotNull(TorBootstrapStatus.parse(
            """NOTICE BOOTSTRAP PROGRESS=14 TAG=handshake SUMMARY="A relay said TAG=done PROGRESS=100""""))
        assertEquals(14, status.progress)
        assertEquals(TorBootstrapStage.TLS_HANDSHAKE, status.stage)
        assertFalse(status.warning)
    }

    @Test fun unknownMachineFieldsAreMappedToSafeConstants() {
        val status = requireNotNull(TorBootstrapStatus.parse(
            """WARN BOOTSTRAP PROGRESS=27 TAG="private.example" REASON="https://private.example/token" WARNING="opaque""""))
        assertEquals(27, status.progress)
        assertEquals(TorBootstrapStage.UNKNOWN, status.stage)
        assertEquals(TorBootstrapReason.UNKNOWN, status.reason)
        assertFalse(status.toString().contains("private.example"))
        assertFalse(status.toString().contains("https://"))
    }

    @Test fun malformedOrUnrelatedEventsDoNotResetBootstrapState() {
        listOf(
            "",
            "NOTICE CIRC PROGRESS=14 TAG=handshake",
            "NOTICE BOOTSTRAP TAG=handshake",
            "NOTICE BOOTSTRAP PROGRESS=oops TAG=handshake",
            "NOTICE BOOTSTRAP PROGRESS=-1 TAG=handshake",
            "NOTICE BOOTSTRAP PROGRESS=101 TAG=done",
            "NOTICE BOOTSTRAP PROGRESS=14.0 TAG=handshake",
            "NOTICE BOOTSTRAP PROGRESS=14 TAG=handshake " + "x".repeat(16_385)
        ).forEach { assertNull(it.take(100), TorBootstrapStatus.parse(it)) }
    }

    @Test fun elapsedTimeNeverFabricatesProgressAndRepeatedPollsDoNotHideStall() {
        val tracker = TorBootstrapTracker(startedAtMs = 1_000)
        val status = TorBootstrapStatus(14, TorBootstrapStage.TLS_HANDSHAKE)
        tracker.update(status, nowMs = 2_000)
        tracker.update(status, nowMs = 33_000)
        assertEquals(14, tracker.status.progress)
        assertEquals(32L, tracker.elapsedSeconds(33_000))
        assertEquals(31L, tracker.stalledSeconds(33_000))
        assertTrue(tracker.stalledSeconds(33_000) >= 30)
    }

    @Test fun genuineProgressAdvanceResetsOnlyStallTimer() {
        val tracker = TorBootstrapTracker(startedAtMs = 0)
        tracker.update(TorBootstrapStatus(14, TorBootstrapStage.TLS_HANDSHAKE), nowMs = 1_000)
        tracker.update(TorBootstrapStatus(15, TorBootstrapStage.TLS_READY), nowMs = 32_000)
        assertEquals(15, tracker.status.progress)
        assertEquals(32L, tracker.elapsedSeconds(32_000))
        assertEquals(0L, tracker.stalledSeconds(32_000))
        assertEquals(2L, tracker.stalledSeconds(34_000))
    }

    @Test fun connectionRetriesReportRealRegressionsWithoutResettingTheStallClock() {
        val tracker = TorBootstrapTracker(startedAtMs = 0)
        tracker.update(TorBootstrapStatus(14, TorBootstrapStage.TLS_HANDSHAKE), nowMs = 1_000)
        tracker.update(TorBootstrapStatus(10, TorBootstrapStage.RELAY_CONNECTING), nowMs = 20_000)
        assertEquals("Display Tor's actual event, rather than a fabricated high-water percentage", 10, tracker.status.progress)
        tracker.update(TorBootstrapStatus(14, TorBootstrapStage.TLS_HANDSHAKE), nowMs = 40_000)
        assertEquals(14, tracker.status.progress)
        assertEquals(39L, tracker.stalledSeconds(40_000))
    }

    @Test fun clockValuesBeforeTheStartAreClampedToZero() {
        val tracker = TorBootstrapTracker(startedAtMs = 10_000)
        assertEquals(0L, tracker.elapsedSeconds(9_000))
        assertEquals(0L, tracker.stalledSeconds(9_000))
    }
}
