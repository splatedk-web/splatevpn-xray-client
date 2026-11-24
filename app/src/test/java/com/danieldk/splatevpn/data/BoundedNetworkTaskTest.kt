package com.danieldk.splatevpn.data
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
class BoundedNetworkTaskTest {
    @Test fun deadlineIncludesBlockingResolutionAndClosesResource() = runBlocking {
        var closed = false
        val started = System.nanoTime()
        val result = BoundedNetworkTask.run(100, onCancel = { closed = true }) { Thread.sleep(5000); 1 }
        assertNull(result)
        assertTrue(closed)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1000)
    }
    @Test fun parentCancellationIsNotReportedAsAnUnreachableServer() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var returned = false
        val job = launch {
            BoundedNetworkTask.run(5000) { started.complete(Unit); Thread.sleep(5000); 1 }
            returned = true
        }
        started.await()
        job.cancelAndJoin()
        assertFalse(returned)
    }

    @Test fun errorInsideTaskImmediatelyUnblocksContinuationWithoutWaitingTimeout() = runBlocking {
        val started = System.currentTimeMillis()
        var caught: Throwable? = null
        try {
            BoundedNetworkTask.run(4000) {
                throw AssertionError("Simulated JVM assertion error")
            }
        } catch (t: Throwable) {
            caught = t
        }
        val elapsed = System.currentTimeMillis() - started
        assertNotNull(caught)
        assertTrue(caught is AssertionError)
        assertTrue("Expected rapid completion, took: $elapsed ms", elapsed < 2000)
    }

    @Test fun hungTaskDoesNotBlockConcurrentProbes() = runBlocking {
        val latch = java.util.concurrent.CountDownLatch(1)
        val hungJob = launch(Dispatchers.IO) {
            BoundedNetworkTask.run(200) {
                latch.countDown()
                Thread.sleep(3000)
                "hung"
            }
        }
        assertTrue(latch.await(1, java.util.concurrent.TimeUnit.SECONDS))
        val normalResult = withTimeout(1000) {
            BoundedNetworkTask.run(500) {
                "active"
            }
        }
        assertEquals("active", normalResult)
        hungJob.join()
    }
}
