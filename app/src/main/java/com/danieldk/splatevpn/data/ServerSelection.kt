package com.danieldk.splatevpn.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Collect a short comparison window after the first success, then cancel slow failures. */
internal suspend fun <T> selectResponsiveCandidate(
    candidates: List<T>,
    settleMs: Long = 250L,
    score: (T, Long) -> Long,
    measure: suspend (T) -> Long
): Pair<T, Long>? = coroutineScope {
    if (candidates.isEmpty()) return@coroutineScope null
    val results = Channel<Pair<T, Long>>(candidates.size)
    val jobs = candidates.map { candidate ->
        launch {
            val ping = try { measure(candidate) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { -1L }
            results.trySend(candidate to ping)
        }
    }
    try {
        var remaining = candidates.size
        var best: Pair<T, Long>? = null
        while (remaining > 0 && best == null) {
            val result = results.receive()
            remaining--
            if (result.second > 0) best = result
        }
        if (best != null && remaining > 0) {
            withTimeoutOrNull(settleMs) {
                while (remaining > 0) {
                    val result = results.receive()
                    remaining--
                    if (result.second > 0 && score(result.first, result.second) < score(best!!.first, best!!.second)) best = result
                }
            }
        }
        best
    } finally {
        jobs.forEach { it.cancel() }
        results.close()
    }
}

/** A depleted pool must not silently reintroduce a dead node or a disabled subscription. */
internal fun eligibleBalancerCandidates(
    servers: List<ServerNode>, excludedIds: Set<String>, disabledSubIds: Set<String>,
    quality: ServerQualityTracker
): List<ServerNode> = servers.filter {
    it.id !in excludedIds && it.subId !in disabledSubIds && !quality.isQuarantined(it.id)
}