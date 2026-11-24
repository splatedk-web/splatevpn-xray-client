package com.danieldk.splatevpn.data

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ServerSelectionTest {
    @Test fun recoveredQuarantinedNodeGetsAProbeEvenWithUntriedCandidatesRemaining() {
        var now = 1000L
        val quality = ServerQualityTracker { now }
        val recovered = ServerNode("recovered", "Recovered", "vless", "recovered.example", 443)
        val untried = (1..100).map { ServerNode("$it", "Untried", "vless", "$it.example", 443) }
        repeat(5) { quality.recordFailure(recovered.id) }
        assertFalse(recovered in eligibleBalancerCandidates(untried + recovered, emptySet(), emptySet(), quality))
        now += 10_001L
        assertTrue(recovered in eligibleBalancerCandidates(untried + recovered, emptySet(), emptySet(), quality))
        assertTrue(quality.isQuarantined(recovered.id))
        assertTrue(quality.canProbe(recovered.id))
        val rotation = CandidateDiscoveryRotation()
        rotation.started(recovered.id)
        assertEquals(recovered, rotation.order(untried + recovered, 1L, emptySet(), setOf(recovered.id)).first())
        assertFalse(recovered in eligibleBalancerCandidates(untried + recovered, setOf(recovered.id), emptySet(), quality))
    }

    @Test fun speedComparisonDoesNotFinishAtTheLatencyFastEnoughThreshold() = runBlocking {
        val winner = selectResponsiveCandidate(listOf(20L, 1L), settleMs = 100, alwaysCompare = true,
            score = { _, result -> result }) { value -> if (value == 1L) delay(20); value }
        assertEquals(1L to 1L, winner)
    }
    @Test fun slowUdpPoolDoesNotOccupyEveryFirstWaveSlot() = runBlocking {
        val udp = (1..40).map { ServerNode("hy$it", "Hy", "hysteria2", "hy$it.example", 443) }
        val tcp = ServerNode("vless", "TCP", "vless", "tcp.example", 443)
        val candidates = protocolDiverseCandidates(udp + tcp)
        val shortlist = prefilterBalancerCandidates(candidates, 24, 350, bypass = { it.usesUdpTransport }, measure = { 1L })
        val ordered = protocolDiverseCandidates(completeDiscoveryQueue(shortlist, candidates))
        assertEquals(udp.first(), ordered.first())
        assertEquals(tcp, ordered[1])
        assertEquals((udp + tcp).toSet(), ordered.toSet())
    }
    @Test fun tcpResponsiveBlackholesDoNotHideTheWorkingProfileAfterTheShortlist() = runBlocking {
        val all = (1..40).toList()
        val tried = mutableListOf<Int>()
        val found = selectResponsiveCandidate(completeDiscoveryQueue(all.take(6), all), parallelism = 6,
            settleMs = 0, score = { _, ping -> ping }) {
            tried += it
            if (it == 37) 91L else -1L
        }
        assertEquals(37 to 91L, found)
        assertTrue(37 in tried)
    }

    @Test fun cancelledSearchesRotatePastSlowProfilesButStillPreferVerifiedReserves() {
        val rotation = CandidateDiscoveryRotation()
        val nodes = (1..30).map { ServerNode("$it", "Node $it", "hysteria2", "example.com", 443) }
        val first = rotation.order(nodes, 7, emptySet()).take(6)
        first.forEach { rotation.started(it.id) }
        assertEquals(nodes.drop(6).take(6), rotation.order(nodes, 7, emptySet()).take(6))
        assertEquals(first.first(), rotation.order(nodes, 7, setOf(first.first().id)).first())
        assertEquals(nodes, rotation.order(nodes, 8, emptySet()))
    }
    @Test fun verifiedReserveDoesNotWaitForAThousandDeadTcpChecks() = runBlocking {
        var tcpChecks = 0
        val selected = withTimeout(500) {
            prefilterBalancerCandidates((0..1000).toList(), 6, 1000,
                bypass = { it == 999 }, measure = { tcpChecks++; delay(10_000); -1 })
        }
        assertEquals(999, selected.first())
        assertEquals(6, selected.size)
        assertEquals(0, tcpChecks)
    }

    @Test fun tcpDiscoveryHasABoundedWorkerCountAndLeavesTimeForTunnelChecks() = runBlocking {
        var started = 0
        var finished = 0
        val selected = withTimeout(1000) {
            prefilterBalancerCandidates((1..1000).toList(), 6, 50, bypass = { false }) {
                started++
                try { delay(10_000); -1 } finally { finished++ }
            }
        }
        assertEquals((1..6).toList(), selected)
        assertEquals(32, started)
        assertEquals(started, finished)
    }

    @Test fun failedWorkerAdvancesWhileAnotherEndpointIsStillStalled() = runBlocking {
        var active = 0
        var maximum = 0
        val result = withTimeout(1500) {
            selectResponsiveCandidate(listOf("stalled", "failed", "working"), settleMs = 20, parallelism = 2,
                score = { _, ping -> ping }) { candidate ->
                active++; maximum = maxOf(maximum, active)
                try { when (candidate) {
                    "stalled" -> { awaitCancellation() }
                    "failed" -> -1L
                    else -> 45L
                } } finally { active-- }
            }
        }
        assertEquals("working" to 45L, result)
        assertTrue(maximum <= 2)
        assertEquals(0, active)
    }

    @Test fun preferredProbeStartsFirstButStallDoesNotBlockFallback() = runBlocking {
        val starts = mutableListOf<String>()
        val result = withTimeout(1_500) {
            selectResponsiveCandidate(
                listOf("preferred", "fallback"), parallelism = 2, settleMs = 0,
                headStartWorkers = 1, headStartMs = 20, score = { _, ping -> ping }
            ) { candidate ->
                starts += candidate
                if (candidate == "preferred") awaitCancellation() else 42L
            }
        }
        assertEquals(listOf("preferred", "fallback"), starts)
        assertEquals("fallback" to 42L, result)
    }

    @Test fun endpointDiversityPreservesProfilesWithoutBlockingOtherHosts() {
        val nodes = listOf(
            ServerNode("a1", "A1", "vless", "A.example.", 443),
            ServerNode("a2", "A2", "vless", "a.example", 443),
            ServerNode("a3", "A3", "trojan", "a.example", 443),
            ServerNode("b", "B", "vless", "b.example", 443),
            ServerNode("c", "C", "vless", "c.example", 443)
        )
        assertEquals(listOf("a1", "b", "c", "a2", "a3"), diverseBalancerCandidates(nodes).map { it.id })
    }

    @Test fun throughputRankingAlsoConsidersInstabilityAndHandlesMissingSamples() {
        assertTrue(downloadCandidateScore(60.0, 90, 90) < downloadCandidateScore(10.0, 30, 30))
        assertTrue(downloadCandidateScore(40.0, 80, 80) < downloadCandidateScore(45.0, 20, 600))
        assertTrue(downloadCandidateScore(1.0, 100, 100) < downloadCandidateScore(null, 30, 30))
        assertEquals(downloadCandidateScore(null, 30, 30), downloadCandidateScore(Double.NaN, 30, 30))
        assertEquals(Long.MAX_VALUE, downloadCandidateScore(100.0, 30, Long.MAX_VALUE))
    }

    @Test fun healthyResultDoesNotWaitForDeadCandidate() = runBlocking {
        var slowCancelled = false
        val result = withTimeout(1500) {
            selectResponsiveCandidate(listOf("dead", "healthy"), settleMs = 30, score = { _, ping -> ping }) {
                if (it == "dead") {
                    try { delay(10_000); -1L } finally { slowCancelled = true }
                } else { delay(20); 42L }
            }
        }
        assertEquals("healthy" to 42L, result)
        assertTrue(slowCancelled)
    }

    @Test fun comparesAvailableSuccessesWithinWindow() = runBlocking {
        val result = selectResponsiveCandidate(listOf(1, 2, 3), settleMs = 120, score = { _, ping -> ping }) {
            delay(it * 15L)
            when (it) { 1 -> 100L; 2 -> -1L; else -> 40L }
        }
        assertEquals(3 to 40L, result)
    }

    @Test fun allFailuresReturnNullAndExceptionDoesNotHideSuccess() = runBlocking {
        assertNull(selectResponsiveCandidate(listOf(1, 2), score = { _, ping -> ping }) { -1L })
        assertEquals(2 to 50L, selectResponsiveCandidate(listOf(1, 2), score = { _, ping -> ping }) {
            if (it == 1) error("unreachable") else 50L
        })
    }

    @Test fun parentCancellationCancelsAllCandidates() = runBlocking {
        var finished = 0
        val job = launch {
            selectResponsiveCandidate(listOf(1, 2), score = { _, ping -> ping }) {
                try { delay(10_000); 1L } finally { finished++ }
            }
        }
        delay(20)
        job.cancelAndJoin()
        assertEquals(2, finished)
    }

    @Test fun nestedAndConcurrentPingStatusSurvivesUntilLastProbeEnds() = runBlocking {
        val activity = PingActivity()
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val job = launch { activity.track("node") { started.complete(Unit); release.await() } }
        started.await()
        activity.track("node") { assertEquals(setOf("node"), activity.activeIds.value) }
        assertEquals(setOf("node"), activity.activeIds.value)
        release.complete(Unit)
        job.join()
        assertTrue(activity.activeIds.value.isEmpty())
        val cancelled = launch { activity.track("other") { delay(10_000) } }
        yield()
        cancelled.cancelAndJoin()
        assertTrue(activity.activeIds.value.isEmpty())
    }

    @Test fun hysteriaPriorityScoreDiscountAppliesOnlyWhenExplicitlyPrioritized() = runBlocking {
        val hysteriaNode = ServerNode("hy2_node", "Hy2 Node", "hysteria2", "192.0.2.1", 443)
        val vlessNode = ServerNode("vless_node", "VLESS Node", "vless", "192.0.2.2", 443)

        fun score(server: ServerNode, delay: Long, prioritizeHysteria: Boolean): Long {
            return if (prioritizeHysteria && server.isHysteria2) (delay * 0.35).toLong().coerceAtLeast(1L) else delay
        }

        // 1. Для подписки WTPN (prioritizeHysteria = true): Hysteria (120мс) побеждает VLESS (70мс), т.к. 42 < 70
        val wtpnWinner = selectResponsiveCandidate(
            candidates = listOf(hysteriaNode, vlessNode),
            settleMs = 50,
            score = { s, d -> score(s, d, prioritizeHysteria = true) }
        ) { s -> if (s.id == "hy2_node") 120L else 70L }
        assertEquals("Для WTPN должна побеждать рабочая Hysteria 2", hysteriaNode.id, wtpnWinner?.first?.id)

        // 2. Для сторонних подписок (prioritizeHysteria = false): побеждает VLESS (70мс), т.к. 70 < 120
        val otherWinner = selectResponsiveCandidate(
            candidates = listOf(hysteriaNode, vlessNode),
            settleMs = 50,
            score = { s, d -> score(s, d, prioritizeHysteria = false) }
        ) { s -> if (s.id == "hy2_node") 120L else 70L }
        assertEquals("Для сторонних подписок побеждает сервер с меньшим пингом без приоритета", vlessNode.id, otherWinner?.first?.id)
    }
}
