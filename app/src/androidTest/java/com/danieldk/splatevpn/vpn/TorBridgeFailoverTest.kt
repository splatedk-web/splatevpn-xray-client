package com.danieldk.splatevpn.vpn

import android.content.ContextWrapper
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.data.TorSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/** Real Tor instances and inert localhost bridges; no public bridge is contacted. */
class TorBridgeFailoverTest {
    @Test fun stalledAutoBridgeIsReplacedAndCancellationStopsTheReplacement() = fixture { runtime, settings ->
        val phases = ConcurrentLinkedQueue<TorPhase>()
        val collector = launch(Dispatchers.Default) { runtime.state.collect { phases += it.phase } }
        val start = launch(Dispatchers.Default) { runtime.start(settings) }
        try {
            val first = awaitPid(runtime, 1)
            val second = awaitPid(runtime, 2)
            assertNotEquals(first, second)
            assertGone(first)
            assertEquals(2, runtime.state.value.attemptCount)
            assertTrue(runtime.state.value.autoSelecting)
            assertFalse("A failed candidate must not flash a terminal failure", phases.contains(TorPhase.FAILED))
            start.cancelAndJoin()
            assertGone(second)
            assertEquals(TorPhase.STOPPED, runtime.state.value.phase)
            assertNull(runtime.processIdForTest())
        } finally { start.cancelAndJoin(); collector.cancelAndJoin() }
    }

    @Test fun exhaustingAutoBridgesPublishesOneTerminalFailureAndRetainsDiagnostics() = fixture { runtime, settings ->
        val snapshots = ConcurrentLinkedQueue<TorRuntimeState>()
        val collector = launch(Dispatchers.Default) { runtime.state.collect { snapshots += it } }
        val start = async(Dispatchers.Default) { runCatching { runtime.start(settings) } }
        try {
            assertTrue(withTimeout(30_000) { start.await() }.isFailure)
            val failed = runtime.state.value
            assertEquals(TorPhase.FAILED, failed.phase)
            assertEquals(2, failed.attemptNumber)
            assertEquals(2, failed.attemptCount)
            assertTrue(failed.autoSelecting)
            assertNull(runtime.processIdForTest())
            assertTrue(snapshots.none { it.phase == TorPhase.FAILED && it.attemptNumber < 2 })
            runtime.stop()
            assertEquals(failed, runtime.state.value)
        } finally { start.cancelAndJoin(); collector.cancelAndJoin() }
    }

    private suspend fun awaitPid(runtime: TorRuntime, attempt: Int): Int = withTimeout(20_000) {
        while (runtime.state.value.attemptNumber != attempt || runtime.processIdForTest() == null) {
            assertNotEquals(TorPhase.FAILED, runtime.state.value.phase)
            delay(10)
        }
        requireNotNull(runtime.processIdForTest())
    }

    private fun assertGone(pid: Int) {
        try { Os.kill(pid, 0); fail("The previous bridge left a Tor process alive") }
        catch (error: ErrnoException) { assertEquals(OsConstants.ESRCH, error.errno) }
    }

    private fun fixture(block: suspend CoroutineScope.(TorRuntime, TorSettings) -> Unit) = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(app.cacheDir, "tor-failover-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getNoBackupFilesDir() = directory
        }
        val runtime = TorRuntime(context)
        runtime.bridgeTimings = TorBridgeTimings(autoAttemptMillis = 6_000, earlyStallMillis = 2_000)
        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { first ->
            ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { second ->
                val settings = TorSettings(useBridges = true, bridges = listOf(
                    "127.0.0.1:${first.localPort} ${"A".repeat(40)}",
                    "127.0.0.1:${second.localPort} ${"B".repeat(40)}"))
                try { block(runtime, settings) }
                finally { withContext(NonCancellable) { runtime.stop(); directory.deleteRecursively() } }
            }
        }
    }
}
