package com.danieldk.splatevpn.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.joinAll

/** Bound discovery independently of pool size. Known/UDP candidates must reach tunnel verification immediately. */
internal suspend fun <T> prefilterBalancerCandidates(
    candidates: List<T>, targetCount: Int, timeoutMs: Long,
    bypass: (T) -> Boolean, measure: suspend (T) -> Long
): List<T> {
    if (candidates.size <= targetCount) return candidates
    val (ready, pending) = candidates.partition(bypass)
    if (ready.isNotEmpty()) return (ready + pending).take(targetCount)
    val responsive = java.util.concurrent.ConcurrentLinkedQueue<Pair<T, Long>>()
    val responsiveCount = AtomicInteger()
    val next = AtomicInteger()
    withTimeoutOrNull(timeoutMs) {
        coroutineScope {
            List(minOf(32, pending.size)) {
                launch {
                    while (responsiveCount.get() < targetCount * 2) {
                        val node = pending.getOrNull(next.getAndIncrement()) ?: break
                        val ping = measure(node)
                        if (ping > 0) {
                            responsive.add(node to ping)
                            responsiveCount.incrementAndGet()
                        }
                    }
                }
            }.joinAll()
        }
    }
    return responsive.sortedBy { it.second }.map { it.first }.take(targetCount)
        .ifEmpty { candidates.take(targetCount) }
}

internal fun balancerSelectionBudgetMillis(urgent: Boolean, extended: Boolean): Long =
    if (urgent) {
        if (extended) 40_000L else 30_000L
    } else {
        if (extended) 90_000L else 45_000L
    }

/** Remember attempted profiles across bounded searches, including cancelled/slow probes. */
internal class CandidateDiscoveryRotation {
    private var network: Long? = null
    private var sequence = 0L
    private val attempts = mutableMapOf<String, Long>()

    @Synchronized fun order(nodes: List<ServerNode>, networkKey: Long?, verified: Set<String>,
        recovery: Set<String> = emptySet()): List<ServerNode> {
        if (network != networkKey) { network = networkKey; attempts.clear(); sequence = 0 }
        return nodes.sortedWith(compareBy<ServerNode> { it.id !in recovery }
            .thenBy { it.id !in verified }.thenBy { attempts[it.id] ?: 0L })
    }

    @Synchronized fun started(id: String) { attempts[id] = ++sequence }
}

/** TCP discovers likely candidates; it must never exclude other profiles from HTTPS verification. */
internal fun <T> completeDiscoveryQueue(preferred: List<T>, all: List<T>): List<T> =
    (preferred + all).distinct()

/** Collect a short comparison window after the first success, then cancel slow failures. */
internal suspend fun <T> selectResponsiveCandidate(
    candidates: List<T>,
    settleMs: Long = 250L,
    parallelism: Int = 4,
    score: (T, Long) -> Long,
    headStartWorkers: Int = 0,
    headStartMs: Long = 350L,
    alwaysCompare: Boolean = false,
    measure: suspend (T) -> Long
): Pair<T, Long>? = coroutineScope {
    if (candidates.isEmpty()) return@coroutineScope null
    val results = Channel<Pair<T, Long>>(candidates.size)
    val next = AtomicInteger()
    val jobs = List(minOf(candidates.size, parallelism.coerceAtLeast(1))) { worker ->
        launch {
            if (headStartWorkers > 0 && worker >= headStartWorkers) delay(headStartMs)
            while (true) {
                val candidate = candidates.getOrNull(next.getAndIncrement()) ?: break
                val ping = try { measure(candidate) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { -1L }
                results.trySend(candidate to ping)
            }
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
        if (best != null && remaining > 0 && (alwaysCompare || score(best.first, best.second) > 85L)) {
            withTimeoutOrNull(settleMs) {
                while (remaining > 0) {
                    val result = results.receive()
                    remaining--
                    if (result.second > 0 && score(result.first, result.second) < score(best!!.first, best!!.second)) best = result
                    if (remaining.toFloat() / candidates.size < 0.2f) break
                }
            }
        }
        best
    } finally {
        jobs.forEach { it.cancel() }
        results.close()
    }
}

/** Try independent endpoints first so dozens of profiles on one dead host cannot block discovery. */
internal fun diverseBalancerCandidates(nodes: List<ServerNode>): List<ServerNode> {
    val groups = ArrayDeque(nodes.groupBy { it.address.trim().lowercase().removeSuffix(".") to it.port }.values.map { it.iterator() })
    return buildList(nodes.size) {
        while (groups.isNotEmpty()) {
            val group = groups.removeFirst()
            add(group.next())
            if (group.hasNext()) groups.addLast(group)
        }
    }
}

/** Give different transports a first-wave slot, retaining endpoint diversity within each transport. */
internal fun protocolDiverseCandidates(nodes: List<ServerNode>): List<ServerNode> {
    val groups = ArrayDeque(nodes.groupBy { it.protocol.lowercase() }.values.map { diverseBalancerCandidates(it).iterator() })
    return buildList(nodes.size) {
        while (groups.isNotEmpty()) {
            val group = groups.removeFirst()
            add(group.next())
            if (group.hasNext()) groups.addLast(group)
        }
    }
}

/** Throughput remains the main metric; instability and high latency reduce its value. */
internal fun downloadCandidateScore(mbps: Double?, delayMs: Long, effectiveDelayMs: Long): Long {
    if (mbps == null || !mbps.isFinite() || mbps <= 0.0)
        return Long.MAX_VALUE / 2 + effectiveDelayMs.coerceIn(1L, 1_000_000L)
    if (effectiveDelayMs == Long.MAX_VALUE) return Long.MAX_VALUE
    val effectiveMbps = mbps.coerceAtLeast(0.01)
    val instability = ((effectiveDelayMs - delayMs).coerceAtLeast(0) / 400.0).coerceAtMost(1.5)
    val latency = (delayMs / 2000.0).coerceIn(0.0, .25)
    return (1_000_000.0 / effectiveMbps * (1.0 + instability + latency)).toLong().coerceIn(1, Long.MAX_VALUE / 4)
}

/** Revisit at most two quarantined nodes, even when a large untried pool remains. */
internal fun eligibleBalancerCandidates(
    servers: List<ServerNode>, excludedIds: Set<String>, disabledSubIds: Set<String>,
    quality: ServerQualityTracker
): List<ServerNode> {
    val allowed = servers.filter { it.id !in excludedIds && it.subId !in disabledSubIds }
    val quarantined = allowed.filter { quality.isQuarantined(it.id) }.mapTo(linkedSetOf()) { it.id }
    val retries = quality.recoveryCandidates(quarantined)
    return allowed.filter { it.id !in quarantined || it.id in retries }
}
