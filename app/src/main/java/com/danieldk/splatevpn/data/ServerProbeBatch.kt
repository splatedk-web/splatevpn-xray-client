package com.danieldk.splatevpn.data

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

data class ServerPingSummary(val checked: Int, val available: Int)

/** Parallelism for bulk user-initiated pings (UI). */
internal const val REAL_DELAY_PARALLELISM = 6
/** Dedicated slots for the balancer — isolated from bulk UI pings so auto-select is never starved. */
internal const val BALANCER_PROBE_PARALLELISM = 6

/** Both core startups and the optional second HTTP attempt belong to this operation. */
internal fun realDelayOperationBudget(timeoutMs: Int, adaptive: Boolean): Long =
    timeoutMs.toLong() + 2500L + if (adaptive) minOf(timeoutMs, 2000).toLong() + 2500L else 0L

/**
 * Share slots across batches; wait outside each individual probe's timeout.
 * UI-initiated bulk pings use [REAL_DELAY_PARALLELISM]; the balancer gets its own pool via
 * [ServerProbeGate] so they never compete for the same semaphore permits.
 */
internal class ServerProbeBatch(private val reserveForBalancer: () -> Boolean = { false }) {
    private val slots = Semaphore(REAL_DELAY_PARALLELISM)
    private val connectedSlots = Semaphore(REAL_DELAY_PARALLELISM - 2)

    suspend fun <T> measure(
        servers: List<T>,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        probe: suspend (T) -> Unit
    ): Int = coroutineScope {
        val completed = AtomicInteger()
        servers.forEach { server ->
            launch {
                try {
                    slots.withPermit {
                        if (reserveForBalancer()) connectedSlots.withPermit { probe(server) }
                        else probe(server)
                    }
                } finally {
                    // Always increment so the progress bar completes even on exception.
                    onProgress(completed.incrementAndGet(), servers.size)
                }
            }
        }
        servers.size
    }
}

/** Queue time says nothing about a server's reachability and must not consume its test budget. */
internal class ServerProbeGate(parallelism: Int = REAL_DELAY_PARALLELISM) {
    private val slots = Semaphore(parallelism)

    suspend fun measure(timeoutMs: Long, probe: suspend () -> Long): Long = slots.withPermit {
        withTimeoutOrNull(timeoutMs) { probe() } ?: -1L
    }

    suspend fun <T> withSlot(timeoutMs: Long, probe: suspend () -> T): T? = slots.withPermit {
        withTimeoutOrNull(timeoutMs) { probe() }
    }
}

/**
 * High-priority probe gate for the auto-balancer.
 * Uses a separate pool of [BALANCER_PROBE_PARALLELISM] permits that are never shared with
 * bulk UI pings, ensuring auto-select always has capacity even during mass ping operations.
 */
internal class BalancerProbeGate {
    private val slots = Semaphore(BALANCER_PROBE_PARALLELISM)

    suspend fun measure(timeoutMs: Long, probe: suspend () -> Long): Long = slots.withPermit {
        withTimeoutOrNull(timeoutMs) { probe() } ?: -1L
    }

    suspend fun <T> withSlot(timeoutMs: Long, probe: suspend () -> T): T? = slots.withPermit {
        withTimeoutOrNull(timeoutMs) { probe() }
    }
}
