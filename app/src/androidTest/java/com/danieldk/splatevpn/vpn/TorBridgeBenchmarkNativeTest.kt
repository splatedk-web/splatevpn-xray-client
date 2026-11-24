package com.danieldk.splatevpn.vpn

import android.content.ContextWrapper
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.data.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.util.UUID

class TorBridgeBenchmarkNativeTest {
    @Test fun cancellingParallelBridgeMeasurementsStopsEveryTorChild() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(app.cacheDir, "tor-benchmark-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getNoBackupFilesDir() = File(directory, "private").apply { mkdirs() }
            override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
        }
        val listeners = List(3) { ServerSocket(0) }
        val probes = TorBridgeProbeRepository()
        val benchmark = TorBridgeBenchmark(context, probes)
        val settings = TorSettings(useBridges = true, bridgeSelectionMetric = "speed", bridges = listeners.mapIndexed { index, server ->
            "127.0.0.1:${server.localPort} " + ("%040x".format(index + 1))
        })
        val network = NetworkAccessPolicy.physicalNetworkKey(context)?.toString() ?: "none"
        val task = async(Dispatchers.IO) { benchmark.measure(settings, network, emptyMap()) }
        try {
            suspend fun awaitPids(): List<Int> {
                while (true) {
                    val found = context.cacheDir.listFiles().orEmpty().mapNotNull { worker ->
                        runCatching { File(worker, "tor/tor.pid").readText().trim().toIntOrNull() }.getOrNull()
                    }
                    if (found.size == 2) return found
                    check(task.isActive) { "Benchmark stopped before starting parallel Tor processes" }
                    delay(25)
                }
            }
            val pids = withTimeout(12_000) { awaitPids() }
            task.cancelAndJoin()
            assertFalse(benchmark.state.value.running)
            assertTrue(probes.bridgeSpeeds.value.isEmpty())
            assertTrue(context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("tor-speed-") })
            pids.forEach { pid ->
                try { Os.kill(pid, 0); fail("A cancelled speed measurement left a Tor process alive") }
                catch (error: ErrnoException) { assertEquals(OsConstants.ESRCH, error.errno) }
            }
        } finally {
            task.cancelAndJoin(); listeners.forEach { it.close() }; directory.deleteRecursively()
        }
    }

    @Test fun livePublicBridgeSpeedsWhenExplicitlyRequested() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("run_live_tor_speed") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(app.cacheDir, "tor-live-speed-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getNoBackupFilesDir() = File(directory, "private").apply { mkdirs() }
            override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
        }
        try {
            val document = SubscriptionTransport().fetch(TOR_TOP100_SUBSCRIPTION, "SplateVPN/TorDiagnostic")
            assertTrue("Public bridge list unavailable", document.isSuccessful)
            val bridges = TorConfigParser.parseSubscription(document.content).bridges
            val settings = TorSettings(useBridges = true, bridges = bridges, bridgeSelectionMetric = "speed")
            val network = NetworkAccessPolicy.physicalNetworkKey(context)?.toString() ?: "none"
            val probes = TorBridgeProbeRepository()
            val delays = probes.probeBridges(bridges, totalBudgetMs = 1800)
            val benchmark = TorBridgeBenchmark(context, probes)
            val results = benchmark.measure(settings, network, delays)
            android.util.Log.i("TorLiveSpeed", "sampled=${results.size}; mbps=${results.values}")
            assertTrue("No full Tor circuit completed the speed sample on this route", results.isNotEmpty())
            fun sampleCount(): Int {
                val history = TorBridgeProbeRepository::class.java.getDeclaredField("speedHistory")
                    .apply { isAccessible = true }.get(probes)
                synchronized(history) {
                    val samples = history.javaClass.getDeclaredField("samples").apply { isAccessible = true }
                        .get(history) as Map<*, *>
                    return samples.values.sumOf { (it as List<*>).size }
                }
            }
            val before = sampleCount()
            val runtime = TorRuntime(context, torBridgeProbeRepository = probes, torBridgeBenchmark = benchmark)
            try {
                withTimeout(120_000) { runtime.start(settings) }
                withTimeout(20_000) { while (sampleCount() <= before) delay(100) }
                assertEquals(TorPhase.RUNNING, runtime.state.value.phase)
                android.util.Log.i("TorLiveSpeed", "Connected circuit added a speed observation")
            } finally {
                withContext(NonCancellable) { runtime.stop() }
            }
        } finally { directory.deleteRecursively() }
    }
}
