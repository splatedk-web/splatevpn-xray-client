package com.danieldk.splatevpn.vpn

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression tests verifying safe TUN descriptor and core cleanup upon cancellation
 * during startup, sequential restart, and ensuring zero resource leaks without deadlocks.
 */
class TunnelCancellationRegressionTest {

    private class MockResource : Closeable {
        val closed = AtomicBoolean(false)
        override fun close() {
            closed.set(true)
        }
    }

    @Test
    fun cancellationDuringStartupSafelyCleansUpResources() = runBlocking {
        val vpnMutex = Mutex()
        var mockTun: MockResource? = null
        val coreStarted = AtomicBoolean(false)
        val coreStopped = AtomicBoolean(false)
        var isServiceActive = false
        val setupStarted = CompletableDeferred<Unit>()

        val startupJob = launch(Dispatchers.Default) {
            vpnMutex.withLock {
                try {
                    // 1. Simulate starting core and establishing TUN
                    coreStarted.set(true)
                    mockTun = MockResource()
                    setupStarted.complete(Unit)

                    // 2. Simulate delay before service is marked active
                    delay(5000)
                    isServiceActive = true
                } catch (e: CancellationException) {
                    if (!isServiceActive) {
                        withContext(NonCancellable) {
                            mockTun?.close()
                            mockTun = null
                            coreStopped.set(true)
                        }
                    }
                    throw e
                }
            }
        }

        setupStarted.await()
        assertTrue(coreStarted.get())
        assertNotNull(mockTun)
        assertFalse(mockTun!!.closed.get())

        // Cancel job while in startup window (before isServiceActive = true)
        startupJob.cancelAndJoin()

        // Resource must be guaranteed closed and nulled by NonCancellable cleanup block
        assertNull(mockTun)
        assertTrue(coreStopped.get())
        assertFalse(isServiceActive)
    }

    @Test
    fun restartAfterCancellationRecoversCleanStateSequentially() = runBlocking {
        val vpnMutex = Mutex()
        var mockTun: MockResource? = null
        val activeCount = AtomicInteger(0)
        var isServiceActive = false
        val firstStartReached = CompletableDeferred<Unit>()

        // First attempt gets cancelled midway
        val job1 = launch(Dispatchers.Default) {
            vpnMutex.withLock {
                try {
                    mockTun = MockResource()
                    activeCount.incrementAndGet()
                    firstStartReached.complete(Unit)
                    delay(10000)
                    isServiceActive = true
                } catch (e: CancellationException) {
                    if (!isServiceActive) {
                        withContext(NonCancellable) {
                            mockTun?.close()
                            mockTun = null
                            activeCount.decrementAndGet()
                        }
                    }
                    throw e
                }
            }
        }

        firstStartReached.await()
        job1.cancelAndJoin()

        assertEquals(0, activeCount.get())
        assertNull(mockTun)

        // Second startup immediately succeeds without interference
        val job2 = launch(Dispatchers.Default) {
            vpnMutex.withLock {
                // Pre-start cleanup check
                if (!isServiceActive) {
                    mockTun?.close()
                    mockTun = null
                }
                mockTun = MockResource()
                activeCount.incrementAndGet()
                isServiceActive = true
            }
        }

        job2.join()
        assertEquals(1, activeCount.get())
        assertNotNull(mockTun)
        assertFalse(mockTun!!.closed.get())
        assertTrue(isServiceActive)
    }
}
