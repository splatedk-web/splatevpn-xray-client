package com.danieldk.splatevpn.data

import android.os.SystemClock
import android.util.Log
import com.danieldk.splatevpn.SplateConstants
import com.danieldk.splatevpn.vpn.VpnState
import com.danieldk.splatevpn.vpn.VpnStatusManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

enum class SpeedtestError { VPN_REQUIRED, VPN_CHANGED, TIMEOUT, DNS, NETWORK, INVALID_SAMPLE }

sealed class SpeedtestState {
    data object Idle : SpeedtestState()
    data class Running(val progress: Float, val currentMbps: Float, val downloadedBytes: Long,
        val samples: List<Float> = emptyList()) : SpeedtestState()
    data class Completed(val downloadMbps: Float, val pingMs: Long, val totalBytes: Long,
        val durationSeconds: Double, val endpoint: String, val samples: List<Float>) : SpeedtestState()
    data class Error(val reason: SpeedtestError) : SpeedtestState()
}

@Singleton
class SpeedtestManager @Inject constructor(private val vpnStatusManager: VpnStatusManager) {
    private val _testState = MutableStateFlow<SpeedtestState>(SpeedtestState.Idle)
    val testState = _testState.asStateFlow()
    val isRunning get() = _testState.value is SpeedtestState.Running
    private val gate = Mutex()
    private val stateLock = Any()
    private var generation = 0L
    @Volatile private var activeJob: Job? = null
    private val connections = ConcurrentHashMap.newKeySet<SampleConnection>()
    private val activeCalls = ConcurrentHashMap.newKeySet<okhttp3.Call>()
    // Kotlin Collection.toList() has a size == 1 shortcut that can race a concurrent removal.
    private fun activeCallSnapshot(): List<okhttp3.Call> = buildList { for (call in activeCalls) add(call) }
    private val client = okhttp3.OkHttpClient.Builder().connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(2, java.util.concurrent.TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    private class SampleConnection(val call: okhttp3.Call, val response: okhttp3.Response) {
        private val closed = java.util.concurrent.atomic.AtomicBoolean()
        val inputStream get() = requireNotNull(response.body).byteStream()
        fun disconnect() {
            if (!closed.compareAndSet(false, true)) return
            call.cancel()
            try { response.close() } catch (_: Exception) { /* Cancellation already closed the socket. */ }
        }
    }

    fun reset() {
        val (job, calls) = synchronized(stateLock) {
            generation++
            _testState.value = SpeedtestState.Idle
            activeJob to activeCallSnapshot()
        }
        job?.cancel()
        // The owning IO coroutine closes response bodies after its readers stop.
        calls.forEach { it.cancel() }
    }

    suspend fun startSpeedtest() = runSpeedtest(listOf(
        SpeedtestEndpoint("https://speed.cloudflare.com/__down?bytes=100000000", 100_000_000L),
        SpeedtestEndpoint("https://cachefly.cachefly.net/100mb.test", 104_857_600L)
    ), ::measureHttpsResponse)

    internal suspend fun runSpeedtest(endpoints: List<SpeedtestEndpoint>, latencyProbe: suspend (() -> Unit) -> Long) {
        val ticket = synchronized(stateLock) { generation }
        withContext(Dispatchers.IO) {
        if (!gate.tryLock()) return@withContext
        fun publish(state: SpeedtestState) = synchronized(stateLock) {
            if (generation == ticket) _testState.value = state
        }
        var cancellationWatcher: Job? = null
        val serverId = vpnStatusManager.connectedServerId
        try {
            val job = currentCoroutineContext()[Job]
            synchronized(stateLock) {
                if (generation != ticket) return@withContext
                activeJob = job
            }
            cancellationWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() }
                finally {
                    val calls = synchronized(stateLock) {
                        if (activeJob === job) activeCallSnapshot() else emptyList()
                    }
                    calls.forEach { it.cancel() }
                }
            }
            if (vpnStatusManager.state.value != VpnState.CONNECTED) {
                publish(SpeedtestState.Error(SpeedtestError.VPN_REQUIRED))
                return@withContext
            }
            fun checkTunnel() {
                if (vpnStatusManager.state.value != VpnState.CONNECTED || vpnStatusManager.connectedServerId != serverId)
                    throw IOException("vpn_changed")
            }
            // Provider UID bypasses TUN: never fall back to measuring a direct connection.
            publish(SpeedtestState.Running(0f, 0f, 0L))
            val latency = latencyProbe(::checkTunnel)
            val remainingBytes = AtomicLong(300_000_000L)
            var lastError: Exception? = null
            for (endpoint in endpoints) {
                currentCoroutineContext().ensureActive()
                checkTunnel()
                try {
                    val result = measureDownload(endpoint, remainingBytes, ::checkTunnel, ::publish)
                    currentCoroutineContext().ensureActive()
                    checkTunnel()
                    publish(SpeedtestState.Completed(result.first.mbps, latency,
                        result.first.totalBytes, result.first.seconds, URL(endpoint.url).host, result.second))
                    return@withContext
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    activeCalls.forEach { it.cancel() }
                    activeCalls.clear()
                    connections.forEach { it.disconnect() }
                    connections.clear()
                    lastError = e
                    Log.w("SpeedtestManager", "Download sample failed: ${e.message}")
                    if (e.message == "vpn_changed") throw e
                }
            }
            throw lastError ?: IOException("invalid_sample")
        } catch (e: CancellationException) { publish(SpeedtestState.Idle); throw e }
        catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            publish(SpeedtestState.Error(when {
                e.message == "vpn_changed" || vpnStatusManager.state.value != VpnState.CONNECTED ||
                    vpnStatusManager.connectedServerId != serverId -> SpeedtestError.VPN_CHANGED
                e.message == "invalid_sample" -> SpeedtestError.INVALID_SAMPLE
                e is java.net.SocketTimeoutException -> SpeedtestError.TIMEOUT
                e is java.net.UnknownHostException -> SpeedtestError.DNS
                else -> SpeedtestError.NETWORK
            }))
        } finally {
            cancellationWatcher?.cancel()
            if (!currentCoroutineContext().isActive) publish(SpeedtestState.Idle)
            activeCalls.forEach { it.cancel() }
            activeCalls.clear()
            connections.forEach { it.disconnect() }
            connections.clear()
            activeJob = null
            gate.unlock()
        }
        }
    }

    private suspend fun open(endpoint: SpeedtestEndpoint): SampleConnection {
        currentCoroutineContext().ensureActive()
        val separator = if ('?' in endpoint.url) '&' else '?'
        val reqBuilder = okhttp3.Request.Builder()
            .url("${endpoint.url}${separator}sample=${SystemClock.elapsedRealtimeNanos()}")
            .header("Accept-Encoding", "identity")
            .header("Cache-Control", "no-cache, no-store")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 SplateVPN-Speedtest/1.0")
        if (endpoint.url.contains("cloudflare.com")) {
            reqBuilder.header("Referer", "https://speed.cloudflare.com/")
            reqBuilder.header("Origin", "https://speed.cloudflare.com")
        }
        val call = com.danieldk.splatevpn.vpn.LocalProxyAccess.client(client).newCall(reqBuilder.build())
        // The call is cancellable even while HTTP CONNECT/TLS is still opening.
        activeCalls.add(call)
        val response = try {
            currentCoroutineContext().ensureActive()
            call.execute()
        } catch (error: Exception) { activeCalls.remove(call); call.cancel(); throw error }
        val conn = SampleConnection(call, response)
        connections.add(conn)
        try {
            if (response.code != 200) throw IOException("HTTP ${response.code}")
            if (response.header("Content-Type").orEmpty().let { it.contains("text/", true) || it.contains("json", true) })
                throw IOException("invalid_sample")
            val length = response.body?.contentLength() ?: -1
            if (length > 0 && length != endpoint.bytes) throw IOException("invalid_sample")
            return conn
        } catch (e: Exception) { close(conn); throw e }
    }

    private fun close(conn: SampleConnection) {
        connections.remove(conn)
        activeCalls.remove(conn.call)
        conn.disconnect()
    }

    private suspend fun measureDownload(endpoint: SpeedtestEndpoint, remainingBytes: AtomicLong, checkTunnel: () -> Unit,
        publish: (SpeedtestState) -> Unit): Pair<DownloadMeasurement.Sample, List<Float>> = coroutineScope {
        publish(SpeedtestState.Running(0f, 0f, 0L))
        // Start after both HTTPS connections are ready. Aggregate payload from two streams.
        val prepared = (0..1).map { async(Dispatchers.IO) { open(endpoint) } }.awaitAll()
        val start = SystemClock.elapsedRealtimeNanos()
        val deadline = start + 10_000_000_000L
        val finishedNs = AtomicLong(start)
        val meter = DownloadMeasurement(start)
        val samples = mutableListOf<Float>()
        val workers = prepared.map { initial -> async(Dispatchers.IO) {
            var conn: SampleConnection? = initial
            try {
                val buffer = ByteArray(64 * 1024)
                while (isActive && SystemClock.elapsedRealtimeNanos() < deadline && remainingBytes.get() > 0) {
                    checkTunnel()
                    val current = conn ?: open(endpoint).also { conn = it }
                    var payload = 0L
                    current.inputStream.use { input ->
                        while (isActive && SystemClock.elapsedRealtimeNanos() < deadline && remainingBytes.get() > 0) {
                            checkTunnel()
                            val readStart = SystemClock.elapsedRealtimeNanos()
                            val count = input.read(buffer)
                            if (count < 0) {
                                if (payload != endpoint.bytes) throw IOException("invalid_sample")
                                break
                            }
                            payload += count
                            remainingBytes.addAndGet(-count.toLong())
                            meter.record(count, readStart)
                        }
                    }
                    close(current)
                    conn = null
                }
            } finally {
                val end = SystemClock.elapsedRealtimeNanos()
                finishedNs.updateAndGet { maxOf(it, end) }
                conn?.let(::close)
            }
        } }
        var previousBytes = 0L
        var previousNs = start + 500_000_000L
        var smoothedMbps = 0f
        try {
            while (workers.any { !it.isCompleted }) {
                delay(300)
                checkTunnel()
                val now = SystemClock.elapsedRealtimeNanos()
                val sample = meter.snapshot(now)
                val current = if (now > previousNs) ((sample.measuredBytes - previousBytes) * 8.0 * 1000 / (now - previousNs)).toFloat() else 0f
                if (now > start + 500_000_000L) {
                    smoothedMbps = if (smoothedMbps <= 0f) current else (smoothedMbps * 0.65f + current * 0.35f)
                    samples.add(smoothedMbps)
                    previousBytes = sample.measuredBytes
                    previousNs = now
                }
                val displaySpeed = if (smoothedMbps > 0f) smoothedMbps else current
                publish(SpeedtestState.Running(((now - start) / 10_000_000_000.0).toFloat().coerceIn(0f, .99f), displaySpeed,
                    sample.totalBytes, samples.toList()))
            }
            workers.awaitAll()
            val rawResult = meter.snapshot(finishedNs.get())
            if (rawResult.measuredBytes < 64_000 || rawResult.seconds < .5) throw IOException("invalid_sample")

            val stableSamples = samples.dropWhile { it < 1f }.filter { it > 0.5f }
            val finalMbps = if (stableSamples.size >= 5) {
                val sorted = stableSamples.sorted()
                val dropLow = (sorted.size * 0.20).toInt()
                val dropHigh = maxOf(1, (sorted.size * 0.10).toInt())
                val core = sorted.subList(dropLow, (sorted.size - dropHigh).coerceAtLeast(dropLow + 1))
                core.average().toFloat()
            } else if (stableSamples.isNotEmpty()) {
                stableSamples.average().toFloat()
            } else {
                rawResult.mbps
            }
            val finalSample = DownloadMeasurement.Sample(
                totalBytes = rawResult.totalBytes,
                measuredBytes = rawResult.measuredBytes,
                seconds = rawResult.seconds,
                mbps = finalMbps
            )
            finalSample to samples.toList()
        } finally { prepared.forEach(::close) }
    }

    private suspend fun measureHttpsResponse(checkTunnel: () -> Unit): Long {
        val samples = mutableListOf<Long>()
        repeat(3) {
            currentCoroutineContext().ensureActive()
            checkTunnel()
            val base = client.newBuilder().connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS).callTimeout(4, java.util.concurrent.TimeUnit.SECONDS).build()
            val call = com.danieldk.splatevpn.vpn.LocalProxyAccess.client(base).newCall(okhttp3.Request.Builder()
                .url("https://cp.cloudflare.com/generate_204").header("Cache-Control", "no-cache").build())
            activeCalls.add(call)
            try {
                val start = SystemClock.elapsedRealtimeNanos()
                call.execute().use { if (it.code in 200..299) samples.add((SystemClock.elapsedRealtimeNanos() - start) / 1_000_000) }
            } catch (_: IOException) { }
            finally { activeCalls.remove(call); call.cancel() }
        }
        return samples.sorted().let { if (it.isEmpty()) -1 else it[it.size / 2] }
    }

}

internal data class SpeedtestEndpoint(val url: String, val bytes: Long)
