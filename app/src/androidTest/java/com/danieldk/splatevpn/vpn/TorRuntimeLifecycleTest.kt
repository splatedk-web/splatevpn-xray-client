package com.danieldk.splatevpn.vpn

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.data.TorSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

class TorRuntimeLifecycleTest {
    @Test fun cancellingBootstrapStopsChildProcessAndAllowsAnotherStart() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = TorRuntime(context)
        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { bridge ->
            val settings = TorSettings(enabled = true, useBridges = true,
                bridges = listOf("127.0.0.1:${bridge.localPort} ${"A".repeat(40)}"))
            var previousPid: Int? = null
            try {
                repeat(2) {
                    val start = launch(Dispatchers.Default) { runtime.start(settings) }
                    try {
                        val pid = withTimeout(15_000) {
                            while (runtime.processIdForTest() == null) {
                                assertTrue("Tor failed before its private control connection opened", start.isActive)
                                delay(25)
                            }
                            requireNotNull(runtime.processIdForTest())
                        }
                        assertNotEquals("A new start must use a fresh Tor process", previousPid, pid)
                        Os.kill(pid, 0)
                        withTimeout(15_000) { start.cancelAndJoin() }
                        assertEquals(TorPhase.STOPPED, runtime.state.value.phase)
                        assertNull(runtime.processIdForTest())
                        try { Os.kill(pid, 0); fail("Cancelled bootstrap left the child Tor process running") }
                        catch (error: ErrnoException) { assertEquals(OsConstants.ESRCH, error.errno) }
                        previousPid = pid
                    } finally { start.cancelAndJoin() }
                }
            } finally { withContext(NonCancellable) { runtime.stop() } }
        }
    }

    @Test fun failedBootstrapDiagnosticsSurviveCleanupAndResetOnNextStart() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = TorRuntime(context)
        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { bridge ->
            val settings = TorSettings(enabled = true, useBridges = true,
                bridges = listOf("127.0.0.1:${bridge.localPort} ${"A".repeat(40)}"))
            val firstStart = async(Dispatchers.Default) { runCatching { runtime.start(settings) } }
            var secondStart: Deferred<Result<Int>>? = null
            try {
                // The local listener accepts TCP handshakes but never speaks Tor. Observe a real
                // bootstrap phase without depending on a particular Tor version reporting 14%.
                val bootstrap = withTimeout(15_000) {
                    runtime.state.first {
                        it.phase == TorPhase.STARTING && it.progress > 0 && it.elapsedSeconds >= 1 &&
                            it.bootstrap.stage !in setOf(TorBootstrapStage.STARTING, TorBootstrapStage.UNKNOWN)
                    }
                }
                val pid = requireNotNull(runtime.processIdForTest())
                Os.kill(pid, OsConstants.SIGKILL)
                val result = withTimeout(15_000) { firstStart.await() }
                assertTrue("Killing Tor must fail the pending start", result.isFailure)
                val failure = requireNotNull(result.exceptionOrNull())
                // EOF can leave jtorctl's response waiter pending until the control deadline.
                // That timeout is a genuine startup failure, unlike external cancellation.
                assertFalse("Child exit must remain a failure, not caller cancellation: $failure",
                    failure is CancellationException && failure !is TimeoutCancellationException)

                val failed = runtime.state.value
                assertEquals(TorPhase.FAILED, failed.phase)
                assertTrue(failed.progress > 0)
                assertFalse(failed.bootstrap.stage in setOf(TorBootstrapStage.STARTING, TorBootstrapStage.UNKNOWN))
                assertTrue(failed.elapsedSeconds >= bootstrap.elapsedSeconds)
                assertNotNull(failed.error)
                assertNull(runtime.processIdForTest())
                repeat(2) {
                    runtime.stop()
                    assertEquals("Service cleanup must retain the failed bootstrap diagnostics", failed, runtime.state.value)
                }

                // Register before starting so even the first STARTING emission is observed before
                // the new process opens its control connection and publishes another phase.
                val reset = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                    runtime.state.first { it.phase == TorPhase.STARTING }
                }
                try {
                    val replacementStart = async(Dispatchers.Default) { runCatching { runtime.start(settings) } }
                    secondStart = replacementStart
                    val fresh = withTimeout(15_000) { reset.await() }
                    assertNull(fresh.error)
                    assertEquals(0, fresh.progress)
                    assertEquals(TorBootstrapStatus(), fresh.bootstrap)
                    assertEquals(0L, fresh.elapsedSeconds)
                    assertEquals(0L, fresh.stalledSeconds)
                    val replacementPid = withTimeout(15_000) {
                        while (runtime.processIdForTest() == null) {
                            assertTrue("The replacement Tor process failed before control was ready", replacementStart.isActive)
                            delay(25)
                        }
                        requireNotNull(runtime.processIdForTest())
                    }
                    assertNotEquals("Retry must launch a fresh Tor process", pid, replacementPid)
                    withTimeout(15_000) { replacementStart.cancelAndJoin() }
                    assertEquals(TorPhase.STOPPED, runtime.state.value.phase)
                    assertNull(runtime.state.value.error)
                    assertNull(runtime.processIdForTest())
                } finally { reset.cancelAndJoin() }
            } finally {
                firstStart.cancelAndJoin()
                secondStart?.cancelAndJoin()
                withContext(NonCancellable) { runtime.stop() }
            }
        }
    }
}
