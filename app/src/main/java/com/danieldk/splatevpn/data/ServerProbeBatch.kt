package com.danieldk.splatevpn.data

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

internal const val REAL_DELAY_PARALLELISM = 6

/** Share slots across batches and wait outside each individual probe's timeout. */
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
                slots.withPermit {
                    if (reserveForBalancer()) connectedSlots.withPermit { probe(server) }
                    else probe(server)
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
}
