package com.danieldk.splatevpn.data

import org.junit.Assert.*
import org.junit.Test

class CandidateMeasurementTest {
    @Test fun refreshingServiceHealthPreservesSpeedWithoutRenewingItsMeasurementTime() {
        val original = sample()
        val rechecked = original.copy(checkedAt = now + 60_001, downloadMbps = null, downloadCheckedAt = null)
            .retainDownload(original)
        assertEquals(original.downloadCheckedAt, rechecked.downloadCheckedAt)
        assertEquals(original.downloadMbps, rechecked.freshDownload(now + 60_001))
        assertNull(rechecked.freshDownload(now + QuickServerSelection.FRESH_FOR_MS + 1))
        assertNull(rechecked.copy(network = 8, downloadMbps = null).retainDownload(original).downloadMbps)
    }
    private val now = 2_000_000L
    private val node = ServerNode("node", "Server", "vless", "192.0.2.1", 443, uuid = "credential")
    private val configuration = listOf<Any>("auto", false, "100-200", "1-5", "tlshello", false, 8, "ru")
    private fun sample() = CandidateMeasurement(QuickServerSelection.profile(node), 7, configuration,
        now, BalancerGoal.TELEGRAM, qualified = true, downloadMbps = 42.5, country = "DE")

    @Test fun qualificationCannotCarryOverToAnotherNetworkServiceOrTunnelConfiguration() {
        val proof = sample()
        assertTrue(proof.matches(node, 7, configuration, BalancerGoal.TELEGRAM, now))
        assertFalse(proof.matches(node, 8, configuration, BalancerGoal.TELEGRAM, now))
        assertFalse(proof.matches(node, null, configuration, BalancerGoal.TELEGRAM, now))
        assertFalse(proof.matches(node, 7, configuration, BalancerGoal.YOUTUBE, now))
        assertFalse(proof.matches(node.copy(uuid = "changed"), 7, configuration, BalancerGoal.TELEGRAM, now))
        assertFalse(proof.matches(node.copy(port = 8443), 7, configuration, BalancerGoal.TELEGRAM, now))
        assertFalse(proof.matches(node, 7, configuration.dropLast(1) + "global", BalancerGoal.TELEGRAM, now))
    }

    @Test fun displayChangesPreserveEvidenceButExpiryAndFailedQualificationDoNot() {
        val proof = sample()
        val renamed = node.copy(name = "Renamed", ping = 5, isSelected = true, subName = "Renamed source",
            lastVerifiedAt = now + 1, isRetained = true)
        assertTrue(proof.matches(renamed, 7, configuration, BalancerGoal.TELEGRAM, now))
        assertTrue(proof.matches(node, 7, configuration, BalancerGoal.TELEGRAM, now + 60_000))
        assertFalse(proof.matches(node, 7, configuration, BalancerGoal.TELEGRAM, now + 60_001))
        assertTrue(proof.copy(goal = BalancerGoal.GENERAL).matches(node, 7, configuration,
            BalancerGoal.GENERAL, now + QuickServerSelection.FRESH_FOR_MS))
        assertFalse(proof.matches(node, 7, configuration, BalancerGoal.TELEGRAM, now - 1))
        assertFalse(proof.copy(qualified = false).matches(node, 7, configuration, BalancerGoal.TELEGRAM, now))
    }

    @Test fun serviceQualificationDoesNotRequireAnOptionalSpeedSample() {
        val latencyOnly = sample().copy(downloadMbps = null)
        assertTrue(latencyOnly.matches(node, 7, configuration, BalancerGoal.TELEGRAM, now))
        assertFalse(latencyOnly.matches(node, 7, configuration, BalancerGoal.YOUTUBE, now))
    }

    @Test fun reservedSamplesConsumeTheHourlyAllowanceEvenWhenInterrupted() {
        val budget = ProbeTrafficBudget()
        var start = now
        var used = 0L
        repeat(4) {
            val reservation = budget.reserve(now + it, start, used, 16, SpeedProbePurpose.BASELINE)!!
            start = reservation.first
            used = reservation.second
        }
        assertEquals(16L * 1024 * 1024, used)
        assertNull(budget.reserve(now + 60_000, start, used, 16))
        val renewed = budget.reserve(now + ProbeTrafficBudget.WINDOW_MS, start, used, 16)!!
        assertEquals(now + ProbeTrafficBudget.WINDOW_MS, renewed.first)
        assertEquals(ProbeTrafficBudget.SAMPLE_BYTES.toLong(), renewed.second)
    }

    @Test fun candidatesCannotSpendTheLastBaselineSample() {
        val budget = ProbeTrafficBudget()
        val sample = ProbeTrafficBudget.SAMPLE_BYTES.toLong()
        assertNotNull(budget.reserve(now, now, 2 * sample, 16, SpeedProbePurpose.CANDIDATE))
        assertNull(budget.reserve(now, now, 3 * sample, 16, SpeedProbePurpose.CANDIDATE))
        assertNotNull(budget.reserve(now, now, 3 * sample, 16, SpeedProbePurpose.BASELINE))
        assertNull(budget.reserve(now, now, 4 * sample, 16, SpeedProbePurpose.BASELINE))
    }

    @Test fun backgroundRotationKeepsAReserveAndStillExploresUntestedServers() {
        val nodes = (1..20).map { node.copy(id = it.toString()) }
        val rotation = StandbyProbeRotation()
        val fresh = nodes.take(10).mapTo(hashSetOf()) { it.id }
        val first = rotation.take(nodes, fresh, now)
        assertEquals(2, first.size)
        assertEquals(1, first.count { it.id in fresh })
        assertEquals(1, first.count { it.id !in fresh })
        val next = rotation.take(nodes, fresh, now + 1000)
        assertTrue(next.none { nextNode -> first.any { it.id == nextNode.id } })
        assertEquals(1, next.count { it.id !in fresh })
    }
}
