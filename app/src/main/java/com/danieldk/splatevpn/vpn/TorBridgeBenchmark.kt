package com.danieldk.splatevpn.vpn

import android.content.Context
import android.content.ContextWrapper
import com.danieldk.splatevpn.data.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class TorBridgeBenchmarkState(val running: Boolean = false, val completed: Int = 0, val total: Int = 0, val failed: Boolean = false)
private class TorProbeCleanupException : IllegalStateException("Tor speed probe could not stop")

/** A small sample of whole Tor circuits, never a download from a bridge's camouflage website. */
@Singleton
class TorBridgeBenchmark @Inject constructor(
    @ApplicationContext private val context: Context,
    private val probes: TorBridgeProbeRepository
) {
    private val gate = Mutex()
    // Candidate and connected-circuit downloads share the same physical bandwidth.
    private val downloads = Semaphore(1)
    private val _state = MutableStateFlow(TorBridgeBenchmarkState())
    val state = _state.asStateFlow()

    internal suspend fun confirmConnection(
        settings: TorSettings,
        network: String,
        bridge: String,
        port: Int,
        stillCurrent: () -> Boolean,
        credentials: LocalProxyCredentials? = null
    ) = downloads.withPermit {
        NetworkAccessPolicy.withChecks(context) {
            if (!stillCurrent()) return@withChecks
            val token = probes.cacheGeneration()
            val base = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS).callTimeout(14, TimeUnit.SECONDS)
                .followRedirects(false).followSslRedirects(false).build()
            try {
                val client = LocalProxyAccess.socksClient(base, port, credentials = credentials, requireRunningVpn = false)
                val speed = measureTorDownloadWithFallback(client)
                if (speed != null && stillCurrent() && NetworkAccessPolicy.physicalNetworkKey(context)?.toString() == network) {
                    probes.recordSpeeds(settings, network, mapOf(bridge to speed), token)
                }
            } finally {
                base.connectionPool.evictAll()
                base.dispatcher.executorService.shutdown()
            }
        }
    }

    suspend fun measure(settings: TorSettings, network: String, delays: Map<String, Long>, refresh: Boolean = false): Map<String, Double> =
        gate.withLock { NetworkAccessPolicy.withChecks(context) {
            if (!refresh) probes.speedsFor(settings, network).takeIf { it.isNotEmpty() }?.let { return@withChecks it }
            val candidates = probes.speedCandidates(settings, network, delays)
            if (candidates.isEmpty()) return@withChecks emptyMap()
            val token = probes.cacheGeneration()
            val results = ConcurrentHashMap<String, Double>()
            val workers = Semaphore(2)
            _state.value = TorBridgeBenchmarkState(true, 0, candidates.size)
            try {
                withTimeoutOrNull(35_000L) {
                    coroutineScope {
                        candidates.map { bridge -> launch(Dispatchers.IO) { workers.withPermit {
                            try {
                                probes.speedProbeStarted(settings, network, bridge)
                                sampleBridge(settings, bridge, downloads)?.let { results[bridge] = it }
                            } catch (error: CancellationException) { throw error
                            } catch (error: TorProbeCleanupException) {
                                android.util.Log.w("TorBenchmark", "Speed sample cleanup failed: transport=${torBridgeTransport(bridge)}, error=${error.message}")
                            } catch (error: Exception) {
                                android.util.Log.w("TorBenchmark", "Speed sample failed: transport=${torBridgeTransport(bridge)}, error=${error.javaClass.simpleName}")
                            } finally { synchronized(_state) { _state.value = _state.value.copy(completed = _state.value.completed + 1) } }
                        } } }.joinAll()
                    }
                }
                currentCoroutineContext().ensureActive()
                if (NetworkAccessPolicy.physicalNetworkKey(context)?.toString() != network) return@withChecks emptyMap()
                _state.value = _state.value.copy(failed = results.isEmpty())
                if (probes.recordSpeeds(settings, network, results.toMap(), token)) probes.speedsFor(settings, network) else emptyMap()
            } finally { _state.value = _state.value.copy(running = false) }
        } }

    private suspend fun sampleBridge(settings: TorSettings, bridge: String, downloads: Semaphore): Double? {
        val name = "tor-speed-${UUID.randomUUID()}"
        val directory = File(context.cacheDir, name).apply { check(mkdirs()) }
        val isolated = object : ContextWrapper(context) {
            override fun getNoBackupFilesDir() = directory
            override fun getSharedPreferences(key: String, mode: Int) = context.getSharedPreferences("$name-$key", mode)
        }
        val runtime = TorRuntime(isolated)
        runtime.bridgeTimings = TorBridgeTimings(earlyStallMillis = 8_000)
        try {
            // Only public directory documents are shared. Guard state, cookies and keys stay separate.
            val original = File(context.noBackupFilesDir, "tor/data")
            val data = File(directory, "tor/data").apply { mkdirs() }
            for (file in listOf("cached-microdesc-consensus", "cached-microdescs", "cached-microdescs.new", "cached-certs")) {
                File(original, file).takeIf { it.isFile && it.length() <= 16 * 1024 * 1024 }?.copyTo(File(data, file))
            }
            val port = withTimeoutOrNull(22_000L) { runtime.start(settings.copy(
                bridges = listOf(bridge), selectedBridge = bridge, useBridges = true,
                autoSelectFastestBridge = false, bridgeSelectionMetric = "latency"
            ), initialHandshakeProbe = true) } ?: run {
                android.util.Log.w("TorBenchmark", "Bootstrap sample budget expired: transport=${torBridgeTransport(bridge)}, progress=${runtime.state.value.progress}%")
                return null
            }
            return downloads.withPermit {
                val base = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
                    .readTimeout(8, TimeUnit.SECONDS).callTimeout(14, TimeUnit.SECONDS)
                    .followRedirects(false).followSslRedirects(false).build()
                try {
                    val client = LocalProxyAccess.socksClient(base, port, credentials = runtime.socksCredentials, requireRunningVpn = false)
                    measureTorDownloadWithFallback(client)
                } finally { base.connectionPool.evictAll(); base.dispatcher.executorService.shutdown() }
            }
        } finally {
            withContext(NonCancellable) {
                runtime.stop()
                if (runtime.hasRunningProcess()) throw TorProbeCleanupException()
                directory.deleteRecursively()
                context.deleteSharedPreferences("$name-tor_bridge_preference")
            }
        }
    }
}

internal suspend fun measureTorDownloadWithFallback(client: OkHttpClient): Double? {
    val urls = listOf(
        "https://speed.cloudflare.com/__down?bytes=524288",
        "https://cachefly.cachefly.net/1mb.test"
    )
    for (url in urls) {
        val speed = runCatching { measureTorDownload(client, url) }.getOrNull()
        if (speed != null) return speed
    }
    return null
}

internal suspend fun measureTorDownload(client: OkHttpClient,
    url: String = "https://speed.cloudflare.com/__down?bytes=524288"): Double? {
    val caller = currentCoroutineContext()
    val request = Request.Builder().url(url).header("Cache-Control", "no-cache")
        .header("Accept-Encoding", "identity").build()
    return client.newCall(request).awaitConsumedResponse { response ->
        val contentType = response.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase()
        if (response.code != 200 || (contentType.isNotEmpty() && contentType != "application/octet-stream" && contentType != "binary/octet-stream"))
            return@awaitConsumedResponse null
        val input = response.body?.byteStream() ?: return@awaitConsumedResponse null
        var started = System.nanoTime()
        val buffer = ByteArray(8192)
        var bytes = 0
        var warmup = 16 * 1024
        val maxMeasureBytes = 512 * 1024 - 16 * 1024
        while (bytes < maxMeasureBytes && System.nanoTime() - started < 4_000_000_000L) {
            caller.ensureActive()
            val remaining = if (warmup > 0) warmup else maxMeasureBytes - bytes
            val count = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (count < 0) break
            if (warmup > 0) {
                warmup -= count
                started = System.nanoTime()
            } else bytes += count
        }
        if (bytes < 32 * 1024) null else bytes * 8.0 / (System.nanoTime() - started).coerceAtLeast(1) * 1000.0
    }
}
