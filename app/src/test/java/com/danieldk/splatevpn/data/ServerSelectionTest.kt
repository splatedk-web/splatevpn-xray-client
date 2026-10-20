package com.danieldk.splatevpn.data

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ServerSelectionTest {
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
}
