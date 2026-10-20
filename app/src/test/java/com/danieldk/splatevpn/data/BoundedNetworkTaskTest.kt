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
}
