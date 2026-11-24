package com.danieldk.splatevpn.data

import kotlin.math.abs

/** Only end-to-end HTTP successes count as verified; TCP latency is never proof of health. */
internal class ServerQualityTracker(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private data class Quality(
        var mean: Double = 0.0,
        var jitter: Double = 0.0,
        var successes: Int = 0,
        var failures: Int = 0,
        var lastSuccess: Long = Long.MIN_VALUE,
        var lastFailure: Long = Long.MIN_VALUE,
        var retryAfter: Long = Long.MIN_VALUE
    )
    private val history = mutableMapOf<String, Quality>()
    private val recoveryUntil = mutableMapOf<String, Long>()
    private val recoveryAttempt = mutableMapOf<String, Long>()
    private var networkKey: Long? = null

    @Synchronized fun onNetworkChanged(key: Long?) {
        if (key != networkKey) { history.clear(); recoveryUntil.clear(); recoveryAttempt.clear(); networkKey = key }
    }

    @Synchronized fun forget(id: String) { history.remove(id); recoveryUntil.remove(id); recoveryAttempt.remove(id) }

    /** Bounded half-open probes. Quarantine ends only after verified success. */
    @Synchronized fun recoveryCandidates(allowed: Set<String>, limit: Int = 2): Set<String> {
        val now = clock()
        val active = allowed.filter { now < (recoveryUntil[it] ?: Long.MIN_VALUE) }.take(limit)
        val next = allowed.filter { it !in active && now - (recoveryAttempt[it] ?: history[it]?.lastFailure ?: now) >= 10_000L }
            .sortedBy { recoveryAttempt[it] ?: history[it]?.lastFailure ?: Long.MIN_VALUE }.take(limit - active.size)
        next.forEach { recoveryAttempt[it] = now; recoveryUntil[it] = now + 30_000L }
        return (active + next).toSet()
    }

    @Synchronized fun canProbe(id: String): Boolean = !isQuarantined(id) || clock() < (recoveryUntil[id] ?: Long.MIN_VALUE)

    @Synchronized fun recordSuccess(id: String, delayMs: Long) {
        if (delayMs <= 0) return
        val quality = history.getOrPut(id) { Quality() }
        recoveryUntil.remove(id)
        if (quality.successes == 0 || clock() - quality.lastSuccess > 300_000L) {
            quality.mean = delayMs.toDouble()
            quality.jitter = 0.0
            quality.successes = 0
        } else {
            quality.jitter = quality.jitter * 0.7 + abs(delayMs - quality.mean) * 0.3
            quality.mean = quality.mean * 0.7 + delayMs * 0.3
        }
        quality.successes++
        quality.lastSuccess = clock()
        quality.retryAfter = Long.MIN_VALUE
        // A single good response does not erase recent flapping history.
        if (quality.failures > 0 && clock() - quality.lastFailure > 300_000L) quality.failures--
    }

    @Synchronized fun recordFailure(id: String) {
        recoveryUntil.remove(id)
        recoveryAttempt[id] = clock()
        val quality = history.getOrPut(id) { Quality() }
        quality.failures = (quality.failures + 1).coerceAtMost(5)
        quality.lastFailure = clock()
        quality.retryAfter = clock() + (60_000L shl (quality.failures - 1).coerceIn(0, 4)).coerceAtMost(300_000L)
    }

    @Synchronized fun isQuarantined(id: String): Boolean =
        history[id]?.let { clock() < it.retryAfter } ?: false

    @Synchronized fun hasFreshSuccess(id: String, maxAgeMs: Long = 60_000L): Boolean =
        history[id]?.let {
            it.successes > 0 && it.lastSuccess > it.lastFailure &&
                clock() - it.lastSuccess <= maxAgeMs && !isQuarantined(id)
        } ?: false

    @Synchronized fun score(id: String, latestDelayMs: Long, active: Boolean = false): Long {
        if (latestDelayMs <= 0 || isQuarantined(id)) return Long.MAX_VALUE
        val quality = history[id]
        val recent = quality != null && quality.successes > 0 && clock() - quality.lastSuccess <= 300_000L
        var score = if (recent) {
            quality!!.mean * 0.7 + latestDelayMs * 0.3 + quality.jitter * 1.5
        } else latestDelayMs.toDouble()
        if (quality != null && quality.failures > 0 && clock() - quality.lastFailure <= 300_000L) {
            score += quality.failures * 180.0
        }
        if (score > 300.0) score += (score - 300.0) * 0.5
        if (active) score -= (score * 0.10).coerceAtMost(25.0)
        return score.toLong().coerceAtLeast(1L)
    }
}
