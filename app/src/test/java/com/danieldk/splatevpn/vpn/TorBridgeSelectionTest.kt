package com.danieldk.splatevpn.vpn

import com.danieldk.splatevpn.data.TorSettings
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class TorBridgeSelectionTest {
    @Test fun measuredLatencyWinsOverAnOlderSuccessfulBridge() {
        val plan = TorBridgeSelection.plan(settings, lastSuccessful = "v0",
            delays = mapOf("v0" to 800L, "webtunnel w0" to 70L, "obfs4 o0" to 160L))
        assertEquals("webtunnel w0", plan.candidates.first().bridge)
        assertEquals(3, plan.candidates.take(3).map { it.transport }.distinct().size)
    }

    @Test fun speedModePrefersMeasuredCircuitThroughputOverTcpPingAndPreviousSuccess() {
        val plan = TorBridgeSelection.plan(settings.copy(bridgeSelectionMetric = "speed"), lastSuccessful = "v0",
            delays = mapOf("v0" to 1L, "webtunnel w0" to 500L),
            speeds = mapOf("v0" to .3, "webtunnel w0" to 3.5, "obfs4 o0" to Double.NaN))
        assertEquals("webtunnel w0", plan.candidates.first().bridge)
        assertEquals("v0", TorBridgeSelection.plan(settings, lastSuccessful = "v0",
            speeds = mapOf("webtunnel w0" to 3.5)).candidates.first().bridge)
        assertEquals(listOf("v1"), TorBridgeSelection.plan(settings.copy(selectedBridge = "v1", bridgeSelectionMetric = "speed"),
            speeds = mapOf("webtunnel w0" to 20.0)).candidates.map { it.bridge })
    }

    private val bridges = listOf("v0", "v1", "v2", "webtunnel w0", "webtunnel w1", "obfs4 o0", "obfs4 o1")
    private val settings = TorSettings(useBridges = true, bridges = bridges)

    @Test fun autoAlternatesTransportsAndNeverExpandsToDirectTor() {
        val plan = TorBridgeSelection.plan(settings)
        assertTrue(plan.automatic)
        assertEquals(listOf("v0", "webtunnel w0", "obfs4 o0", "v1", "webtunnel w1", "obfs4 o1"),
            plan.candidates.map { it.bridge })
        plan.candidates.forEach { assertEquals(listOf(it.bridge), it.settings(settings).bridges); assertTrue(it.settings(settings).useBridges) }
        assertEquals(bridges, settings.bridges)
    }

    @Test fun pinnedBridgeIsOnlyTriedOnceAndTransportFilterIsRespected() {
        val pin = TorBridgeSelection.plan(settings.copy(selectedBridge = "webtunnel w1"))
        assertFalse(pin.automatic)
        assertEquals(listOf("webtunnel w1"), pin.candidates.map { it.bridge })
        val filtered = TorBridgeSelection.plan(settings.copy(bridgeTransport = "obfs4"))
        assertEquals(listOf("obfs4 o0", "obfs4 o1"), filtered.candidates.map { it.bridge })
        assertThrows(IllegalArgumentException::class.java) { TorBridgeSelection.plan(TorSettings(useBridges = true)) }
        assertNull(TorBridgeSelection.plan(TorSettings()).candidates.single().bridge)
    }

    @Test fun previousSuccessIsPreferredWithoutDuplicatesAndNewSearchRotates() {
        val preferred = TorBridgeSelection.plan(settings, "webtunnel w1")
        assertEquals("webtunnel w1", preferred.candidates.first().bridge)
        assertEquals(preferred.candidates.size, preferred.candidates.distinct().size)
        assertEquals("v1", TorBridgeSelection.plan(settings, rotation = 1).candidates.first().bridge)
    }

    @Test fun fastTcpProbesCannotCrowdOutOtherTransportsAndFailedBridgeIsDeferred() {
        val vanilla = (0..8).map { "v" + it }
        val web = "webtunnel w0"
        val obfs = "obfs4 o0"
        val settings = settings.copy(bridges = vanilla + listOf(web, obfs))
        val failed = vanilla.first()
        val plan = TorBridgeSelection.plan(settings, lastSuccessful = failed,
            delays = vanilla.withIndex().associate { it.value to it.index + 1L }, recentlyFailed = failed)
        assertEquals(listOf("v1", web, obfs), plan.candidates.take(3).map { it.bridge })
        assertFalse(plan.candidates.any { it.bridge == failed })
        assertEquals(failed, TorBridgeSelection.plan(settings.copy(selectedBridge = failed),
            recentlyFailed = failed).candidates.single().bridge)
    }

    @Test fun retryStopsAtTheFirstSuccessfulTorConnection() = runBlocking {
        val attempts = mutableListOf<Int>()
        val port = tryTorBridgeCandidates(TorBridgeSelection.plan(settings), { it is IOException }) { _, number, _ ->
            attempts += number
            if (number == 1) throw IOException("Bridge unreachable")
            9050
        }
        assertEquals(9050, port)
        assertEquals(listOf(1, 2), attempts)
    }

    @Test fun cancellationWrappedAsIoFailureCannotLaunchTheNextBridge() = runBlocking {
        var attempts = 0
        var cancelled = false
        val job = launch {
            try {
                tryTorBridgeCandidates(TorBridgeSelection.plan(settings), { true }) { _, _, _ ->
                    attempts++
                    currentCoroutineContext().cancel()
                    throw IOException("Interrupted")
                }
            } catch (_: CancellationException) { cancelled = true }
        }
        job.join()
        assertEquals(1, attempts)
        assertTrue(cancelled)
    }

    @Test fun perAttemptDeadlineCanRetryButFatalCleanupFailureCannot() = runBlocking {
        var attempts = 0
        val result = tryTorBridgeCandidates(TorBridgeSelection.plan(settings), { it is TimeoutCancellationException }) { _, _, _ ->
            attempts++
            if (attempts == 1) withTimeout(30) { delay(10_000) }
            "connected"
        }
        assertEquals("connected", result)
        assertEquals(2, attempts)
        attempts = 0
        val failure = runCatching {
            tryTorBridgeCandidates(TorBridgeSelection.plan(settings), { false }) { _, _, _ ->
                attempts++; throw IllegalStateException("Old process is still alive")
            }
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(1, attempts)
    }

    @Test fun onlyAnEarlyHandshakeStallIsEligibleForTheShorterWait() {
        val timings = TorBridgeTimings()
        assertFalse(timings.shouldLeaveEarly(14, 11))
        assertTrue(timings.shouldLeaveEarly(10, 12))
        assertTrue(timings.shouldLeaveEarly(14, 13))
        assertFalse(timings.shouldLeaveEarly(25, 70))
        assertFalse(timings.shouldLeaveEarly(95, 100))
    }

    @Test fun explicitEarlyRefusalCanMoveOnWithoutCuttingSlowSuccessfulBootstrap() {
        val timings = TorBridgeTimings()
        assertTrue(timings.shouldLeaveEarly(14, 10, true, TorBootstrapReason.REFUSED))
        assertFalse(timings.shouldLeaveEarly(14, 5, true, TorBootstrapReason.REFUSED))
        assertFalse(timings.shouldLeaveEarly(14, 10, false, TorBootstrapReason.REFUSED))
        assertFalse(timings.shouldLeaveEarly(14, 10, true, TorBootstrapReason.TIMEOUT))
        assertFalse(timings.shouldLeaveEarly(30, 10, true, TorBootstrapReason.REFUSED))
    }

    @Test
    fun adaptiveStallPolicyAllowsLongerWaitForSnowflakeAndWebTunnelAndPreviousSuccess() {
        val timings = TorBridgeTimings()
        // Snowflake WebRTC establishment takes up to 30s: stalled for 24s should NOT leave early
        assertFalse(timings.shouldLeaveEarly(progress = 5, stalledSeconds = 24, transport = "snowflake"))
        assertTrue(timings.shouldLeaveEarly(progress = 5, stalledSeconds = 30, transport = "snowflake"))

        // WebTunnel HTTP upgrade takes up to 18s: stalled for 17s should NOT leave early
        assertFalse(timings.shouldLeaveEarly(progress = 10, stalledSeconds = 17, transport = "webtunnel"))
        assertTrue(timings.shouldLeaveEarly(progress = 10, stalledSeconds = 18, transport = "webtunnel"))

        // Known previous successful bridge gets extra grace time (+10s)
        assertFalse(timings.shouldLeaveEarly(progress = 10, stalledSeconds = 21, transport = "vanilla", isPreviousSuccess = true))
        assertTrue(timings.shouldLeaveEarly(progress = 10, stalledSeconds = 22, transport = "vanilla", isPreviousSuccess = true))
    }
}
