package com.danieldk.splatevpn.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Run explicitly on a seeded emulator; does not start the VPN or change selection/settings. */
@RunWith(AndroidJUnit4::class)
class BulkProbeDiagnosticTest {
    @Test fun tcpBatchCountsOnlyRespondingEndpoints() = runBlocking {
        assumeTrue(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = EntryPointAccessors.fromApplication(app, SubscriptionUpdateWorker.WorkerDependencies::class.java).serverRepository()
        val settings = (app.applicationContext as com.danieldk.splatevpn.SplateApplication).settingsManager
        val before = settings.preferenceSnapshot()
        try {
            settings.setChecksWifiOnly(false)
            settings.setPingMode("tcp")
            java.net.ServerSocket(0).use { listener ->
                val closed = java.net.ServerSocket(0).use { it.localPort }
                val nodes = listOf(listener.localPort, closed).map { port ->
                    requireNotNull(LinkParser.parse("socks5://127.0.0.1:$port"))
                }
                assertEquals(ServerPingSummary(2, 1), repository.testServersPing(nodes))
            }
        } finally {
            settings.restorePreferenceSnapshot(before)
        }
    }

    @Test fun fortyRealServersRunWithSixConcurrentProbes() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = EntryPointAccessors.fromApplication(app, SubscriptionUpdateWorker.WorkerDependencies::class.java).serverRepository()
        val servers = repository.allServers.first().take(40)
        assumeTrue("Requires an emulator with at least 40 saved servers", servers.size == 40)
        val peak = AtomicInteger()
        val progress = AtomicInteger()
        val monitor = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            repository.activePingIds.collect { active -> peak.updateAndGet { maxOf(it, active.size) } }
        }
        val started = System.nanoTime()
        try {
            val checked = withTimeout(90000) {
                repository.testServersRealDelay(servers) { _, _ -> progress.incrementAndGet() }
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            File(app.getExternalFilesDir(null), "probe-batch-qa.txt").writeText(
                "checked=${checked.checked}\navailable=${checked.available}\ncompleted=${progress.get()}\npeak=${peak.get()}\nelapsedMs=$elapsedMs\n")
            assertEquals(40, checked.checked)
            assertEquals(40, progress.get())
            assertTrue("Expected 4–6 active probes, got ${peak.get()}", peak.get() in 4..6)
            assertTrue(repository.activePingIds.value.isEmpty())
        } finally { monitor.cancelAndJoin() }
    }
}
