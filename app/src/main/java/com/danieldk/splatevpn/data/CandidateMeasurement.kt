package com.danieldk.splatevpn.data

data class CandidateMeasurement(
    val profile: ServerNode,
    val network: Long,
    val configuration: List<Any>,
    val checkedAt: Long,
    val goal: BalancerGoal,
    val qualified: Boolean,
    val downloadMbps: Double? = null,
    val country: String? = null,
    val downloadCheckedAt: Long? = downloadMbps?.let { checkedAt }
) {
    fun matches(node: ServerNode, networkKey: Long?, settings: List<Any>, goal: BalancerGoal,
        now: Long = System.currentTimeMillis()): Boolean = qualified && network == networkKey &&
        profile == QuickServerSelection.profile(node) && configuration == settings && this.goal == goal &&
        now - checkedAt in 0..freshForMillis(goal)
    companion object {
        internal fun freshForMillis(goal: BalancerGoal): Long =
            if (goal == BalancerGoal.GENERAL) QuickServerSelection.FRESH_FOR_MS else 60_000L
    }

    fun freshDownload(now: Long = System.currentTimeMillis()): Double? = downloadMbps?.takeIf {
        it.isFinite() && it > 0 && downloadCheckedAt?.let { at -> now - at in 0..QuickServerSelection.FRESH_FOR_MS } == true
    }

    internal fun retainDownload(previous: CandidateMeasurement?): CandidateMeasurement {
        if (downloadMbps != null || previous == null || profile != previous.profile || network != previous.network ||
            configuration != previous.configuration || goal != previous.goal) return this
        val speed = previous.freshDownload(checkedAt) ?: return this
        return copy(downloadMbps = speed, downloadCheckedAt = previous.downloadCheckedAt)
    }
}

/** Reserve a whole sample before starting it; interrupted samples still consume their allowance. */
internal enum class SpeedProbePurpose { CANDIDATE, BASELINE }

internal class ProbeTrafficBudget {
    companion object { const val SAMPLE_BYTES = 4 * 1024 * 1024; const val WINDOW_MS = 60 * 60 * 1000L }
    fun reserve(now: Long, startedAt: Long, used: Long, limitMiB: Int,
        purpose: SpeedProbePurpose = SpeedProbePurpose.CANDIDATE): Pair<Long, Long>? {
        val start = if (now - startedAt in 0 until WINDOW_MS) startedAt else now
        val consumed = if (start == startedAt) used.coerceAtLeast(0) else 0
        val limit = limitMiB.coerceIn(16, 256) * 1024L * 1024L
        val reservedForBaseline = if (purpose == SpeedProbePurpose.CANDIDATE) SAMPLE_BYTES else 0
        return if (consumed + SAMPLE_BYTES <= limit - reservedForBaseline)
            start to consumed + SAMPLE_BYTES else null
    }
}

/** A bounded rotation explores new nodes instead of repeatedly probing the same fastest pair. */
internal class StandbyProbeRotation {
    private val attempted = mutableMapOf<String, Long>()
    @Synchronized fun take(nodes: List<ServerNode>, fresh: Set<String>, now: Long, count: Int = 2): List<ServerNode> {
        attempted.keys.retainAll(nodes.mapTo(hashSetOf()) { it.id })
        val candidates = nodes.filter { now - (attempted[it.id] ?: 0L) >= 60_000 }
        val exploring = candidates.filter { it.id !in fresh }.sortedBy { attempted[it.id] ?: 0 }
        val renewing = candidates.filter { it.id in fresh }.sortedBy { attempted[it.id] ?: 0 }
        // Keep a ten-node reserve alive while spending the other slot on discovery.
        var picked = if (fresh.size >= 10) (renewing.take(1) + exploring + renewing.drop(1)).take(count)
            else (exploring + renewing).take(count)
        // Pool exhaustion guard: if no candidates passed the 60-second cooldown, pick the least
        // recently attempted nodes so the warm-standby loop is never permanently blocked.
        if (picked.isEmpty() && nodes.isNotEmpty()) {
            picked = nodes.sortedBy { attempted[it.id] ?: Long.MIN_VALUE }.take(count)
        }
        picked.forEach { attempted[it.id] = now }
        return picked
    }
}
