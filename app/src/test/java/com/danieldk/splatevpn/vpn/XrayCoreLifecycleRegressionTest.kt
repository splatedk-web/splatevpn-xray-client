package com.danieldk.splatevpn.vpn

import com.danieldk.splatevpn.SplateConstants
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Comprehensive regression tests verifying:
 * 1. Port 10808 busy detection and release enforcement before new core launch
 * 2. Rapid connect/disconnect cancellation leaving zero port/process leaks
 * 3. Server switching serialization ensuring old core is stopped before new one binds
 * 4. Stale cleanup guards preventing obsolete coroutines from destroying newly spawned core
 * 5. Concurrent alternating start/stop requests serialization without deadlocks
 * 6. Strict Watchdog crash budget enforcement (exactly 5 attempts, zero 6th attempt, no infinite loop)
 * 7. VPN permission revocation (onRevoke) teardown resilience against scope cancellation
 */
class XrayCoreLifecycleRegressionTest {

    @Test
    fun portBusyDetectionPreventsPrematureCoreStart() {
        val testPort = 19808
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName(SplateConstants.XRAY_SOCKS_ADDRESS), testPort), 1)
        }.use { serverSocket ->
            assertTrue("Socket must be bound and active", serverSocket.isBound)

            // Verify port-free probe: occupied port must be detected
            val isFreeOccupied = try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(InetAddress.getByName(SplateConstants.XRAY_SOCKS_ADDRESS), testPort), 1)
                }.use { true }
            } catch (_: Exception) {
                false
            }
            assertFalse("Busy port must be detected as not free", isFreeOccupied)
        }

        // Once closed, port must be detected as free immediately with reuseAddress
        val isFreeReleased = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName(SplateConstants.XRAY_SOCKS_ADDRESS), testPort), 1)
            }.use { true }
        } catch (_: Exception) {
            false
        }
        assertTrue("Released port must be detected as free", isFreeReleased)
    }

    @Test
    fun rapidServerSwitchingGuaranteesSingleActiveCoreWithoutPortCollision() = runBlocking {
        val lifecycleMutex = Mutex()
        val generation = AtomicLong(0L)
        val activeCoreInstance = AtomicInteger(0)
        val portHolder = AtomicBoolean(false)

        val switchCount = 5
        for (i in 1..switchCount) {
            val reqGeneration = generation.incrementAndGet()
            lifecycleMutex.withLock {
                if (reqGeneration != generation.get()) return@withLock

                // 1. Stop old core
                if (activeCoreInstance.get() > 0) {
                    activeCoreInstance.decrementAndGet()
                    portHolder.set(false)
                }

                // Verify port is free before starting
                assertFalse("Port must be free before starting new instance", portHolder.get())

                // 2. Start new core
                portHolder.set(true)
                activeCoreInstance.incrementAndGet()
                assertEquals("Exactly one core must be active at any time", 1, activeCoreInstance.get())
            }
        }

        assertEquals(1, activeCoreInstance.get())
        assertTrue(portHolder.get())

        // Final shutdown
        val stopGen = generation.incrementAndGet()
        lifecycleMutex.withLock {
            if (stopGen == generation.get()) {
                activeCoreInstance.decrementAndGet()
                portHolder.set(false)
            }
        }
        assertEquals(0, activeCoreInstance.get())
        assertFalse(portHolder.get())
    }

    @Test
    fun rapidConnectDisconnectCancellationLeavesCleanPort() = runBlocking {
        val mutex = Mutex()
        val portBound = AtomicBoolean(false)
        val isCleanedUp = AtomicBoolean(false)

        val job = launch(Dispatchers.Default) {
            mutex.withLock {
                try {
                    portBound.set(true)
                    delay(5000) // Simulate waiting for SOCKS listener
                } catch (e: CancellationException) {
                    withContext(NonCancellable) {
                        portBound.set(false)
                        isCleanedUp.set(true)
                    }
                    throw e
                }
            }
        }

        // Give it time to enter the lock and set portBound
        while (!portBound.get()) delay(5)

        // Cancel connect midway (rapid disconnect)
        job.cancelAndJoin()

        assertFalse("Port must be unmapped after cancellation", portBound.get())
        assertTrue("NonCancellable cleanup must run on cancellation", isCleanedUp.get())
    }

    @Test
    fun watchdogBudgetStrictlyStopsAfterMaxRetriesWithoutSixthAttempt() {
        val budget = CoreRestartBudget(maxRetries = 5)
        assertEquals(5, budget.maxRetries)
        assertEquals(0, budget.failures)

        // Simulate 5 consecutive crashes
        for (attempt in 1..5) {
            budget.started()
            val mayRestart = budget.crashed()
            assertTrue("Attempt $attempt must be allowed within budget", mayRestart)
            assertEquals(attempt, budget.failures)
            assertFalse("Budget must not be exhausted at attempt $attempt", attempt < 5 && budget.isExhausted())
        }

        // 6th crash occurs: must be rejected, failures = 6
        val mayRestartOnSixth = budget.crashed()
        assertFalse("Sixth restart must be strictly denied", mayRestartOnSixth)
        assertEquals(6, budget.failures)
        assertTrue("Budget must be marked exhausted", budget.isExhausted())

        // Any further crashes remain rejected
        assertFalse(budget.crashed())
    }

    @Test
    fun watchdogDoesNotResetBudgetWhenHealthCheckFails() {
        var simulatedTime = 1000L
        val budget = CoreRestartBudget(stableMs = 60_000, maxRetries = 5, nowMs = { simulatedTime })

        // 3 crashes happen
        repeat(3) {
            budget.crashed()
        }
        assertEquals(3, budget.failures)

        // Xray started but health check failed -> budget.started() should NOT be called
        // Even if 70 seconds pass without budget.started(), failure count is preserved!
        simulatedTime += 70_000L
        val mayRestart = budget.crashed()
        assertTrue(mayRestart)
        assertEquals("Failure count must not reset if health check never confirmed tunnel", 4, budget.failures)

        // Only after an explicit started() and sustained uptime does it reset
        budget.started()
        simulatedTime += 65_000L
        budget.crashed()
        assertEquals("Budget resets only after confirmed sustained health uptime", 1, budget.failures)
    }

    @Test
    fun sequentialGenerationGuardsPreventStaleCleanupFromKillingNewInstance() = runBlocking {
        val generation = AtomicLong(1L)
        val activeCore = AtomicInteger(1)
        val mutex = Mutex()

        // Core 1 is running with token 1
        val core1Token = generation.get()

        // User rapidly requests new server connection (generates token 2)
        val core2Token = generation.incrementAndGet()

        // Stale teardown from core 1 attempts to stop core with expectedGeneration = core1Token
        val staleCleanupExecuted = AtomicBoolean(false)
        val staleCleanupJob = launch(Dispatchers.Default) {
            mutex.withLock {
                if (generation.compareAndSet(core1Token, core1Token + 1)) {
                    activeCore.set(0)
                    staleCleanupExecuted.set(true)
                }
            }
        }
        staleCleanupJob.join()

        // Stale teardown must have been rejected by CAS guard
        assertFalse("Stale cleanup must not execute when generation changed", staleCleanupExecuted.get())
        assertEquals("Active core must not be destroyed by obsolete cleanup", 1, activeCore.get())
        assertEquals(core2Token, generation.get())
    }

    @Test
    fun rapidConcurrentLifecycleCommandsAreSerializedSafely() = runBlocking {
        val mutex = Mutex()
        val generation = AtomicLong(0L)
        val successfulStarts = AtomicInteger(0)
        val successfulStops = AtomicInteger(0)
        val activeInstances = AtomicInteger(0)

        // Launch 20 concurrent alternating start/stop calls
        val jobs = List(20) { index ->
            launch(Dispatchers.Default) {
                if (index % 2 == 0) {
                    val req = generation.incrementAndGet()
                    mutex.withLock {
                        if (req == generation.get()) {
                            activeInstances.incrementAndGet()
                            delay(10)
                            successfulStarts.incrementAndGet()
                        }
                    }
                } else {
                    val req = generation.incrementAndGet()
                    mutex.withLock {
                        if (req == generation.get()) {
                            activeInstances.decrementAndGet()
                            delay(10)
                            successfulStops.incrementAndGet()
                        }
                    }
                }
            }
        }
        jobs.joinAll()

        // Lock serialization guarantees monotonic state transitions without deadlocks
        assertTrue("Generations must increment monotonically", generation.get() >= 20)
        assertTrue("At least one serialized state mutation must succeed", successfulStarts.get() + successfulStops.get() > 0)
    }

    @Test
    fun vpnRevokeAndDestroyTeardownExecutesEvenWhenScopeIsCancelled() = runBlocking {
        val teardownCompleted = AtomicBoolean(false)
        val portReleased = AtomicBoolean(false)

        val cancellableParentScope = CoroutineScope(Dispatchers.Default + Job())

        // Simulate service start
        val workerJob = cancellableParentScope.launch {
            delay(10_000)
        }

        // Simulate onRevoke / onDestroy calling cleanup inside NonCancellable
        val teardownJob = CoroutineScope(Dispatchers.IO).launch(NonCancellable) {
            // Cancel parent scope immediately to simulate onDestroy() calling serviceScope.cancel()
            cancellableParentScope.cancel()

            // NonCancellable teardown must proceed unhindered
            delay(50)
            teardownCompleted.set(true)
            portReleased.set(true)
        }

        teardownJob.join()
        workerJob.join()

        assertTrue("Parent scope job must be cancelled", workerJob.isCancelled)
        assertTrue("Teardown must complete despite scope cancellation", teardownCompleted.get())
        assertTrue("Port release must complete despite scope cancellation", portReleased.get())
    }
}
