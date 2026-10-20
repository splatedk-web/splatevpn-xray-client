package com.danieldk.splatevpn.data

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ServerProbeBatchTest {
    @Test fun connectedBulkLeavesTwoSlotsForBalancer() = runBlocking {
        val batch = ServerProbeBatch { true }
        val gate = ServerProbeGate()
        val active = AtomicInteger()
        val bulkReady = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val bulk = launch {
            batch.measure((1..40).toList()) {
                gate.measure(5000) {
                    if (active.incrementAndGet() == 4) bulkReady.complete(Unit)
                    try { release.await(); 1L } finally { active.decrementAndGet() }
                }
            }
        }
        withTimeout(2000) { bulkReady.await() }
        val balancerReady = CompletableDeferred<Unit>()
        val balancing = List(2) {
            async {
                gate.measure(5000) {
                    if (active.incrementAndGet() == 6) balancerReady.complete(Unit)
                    try { release.await(); 1L } finally { active.decrementAndGet() }
                }
            }
        }
        withTimeout(2000) { balancerReady.await() }
        assertEquals(6, active.get())
        release.complete(Unit)
        balancing.forEach { assertEquals(1L, it.await()) }
        bulk.join()
        assertEquals(0, active.get())
    }

    @Test fun queueWaitDoesNotMarkAnUntestedServerUnreachable() = runBlocking {
        val gate = ServerProbeGate(parallelism = 1)
        val occupied = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async { gate.measure(5000) { occupied.complete(Unit); release.await(); 1L } }
        occupied.await()
        val queued = async(start = CoroutineStart.UNDISPATCHED) { gate.measure(20) { 7L } }
        delay(100)
        assertFalse(queued.isCompleted)
        release.complete(Unit)
        assertEquals(1L, first.await())
        assertEquals(7L, queued.await())
    }

    @Test fun timedOutAndCancelledProbesReleaseTheirSlots() = runBlocking {
        val gate = ServerProbeGate(parallelism = 1)
        assertEquals(-1L, gate.measure(20) { awaitCancellation() })
        val started = CompletableDeferred<Unit>()
        val cancelled = launch { gate.measure(5000) { started.complete(Unit); awaitCancellation() } }
        started.await()
        cancelled.cancelAndJoin()
        assertEquals(9L, withTimeout(1000) { gate.measure(20) { 9L } })
    }

    @Test fun fortyServersUseSixSlotsAndCompleteExactlyOnce() = runBlocking {
        val batch = ServerProbeBatch()
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val firstWave = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val visited = mutableListOf<Int>()
        val progress = mutableListOf<Int>()
        val job = async {
            batch.measure((1..40).toList(), { done, total ->
                assertEquals(40, total)
                progress.add(done)
            }) { node ->
                val count = active.incrementAndGet()
                peak.updateAndGet { maxOf(it, count) }
                if (count == 6) firstWave.complete(Unit)
                try {
                    release.await()
                    delay(1)
                    visited.add(node)
                } finally { active.decrementAndGet() }
            }
        }
        withTimeout(2000) { firstWave.await() }
        assertEquals(6, active.get())
        release.complete(Unit)
        assertEquals(40, withTimeout(2000) { job.await() })
        assertEquals(6, peak.get())
        assertEquals((1..40).toList(), visited.sorted())
        assertEquals((1..40).toList(), progress)
    }

    @Test fun concurrentBatchesShareSixSlotsAndCancellationReleasesThem() = runBlocking {
        val batch = ServerProbeBatch()
        val active = AtomicInteger()
        val firstWave = CompletableDeferred<Unit>()
        val jobs = List(2) {
            launch {
                batch.measure((1..40).toList()) {
                    val count = active.incrementAndGet()
                    assertTrue(count <= 6)
                    if (count == 6) firstWave.complete(Unit)
                    try { awaitCancellation() } finally { active.decrementAndGet() }
                }
            }
        }
        withTimeout(2000) { firstWave.await() }
        jobs.forEach { it.cancelAndJoin() }
        assertEquals(0, active.get())
        val completed = AtomicInteger()
        assertEquals(40, withTimeout(2000) {
            batch.measure((1..40).toList()) { completed.incrementAndGet() }
        })
        assertEquals(40, completed.get())
    }
}
