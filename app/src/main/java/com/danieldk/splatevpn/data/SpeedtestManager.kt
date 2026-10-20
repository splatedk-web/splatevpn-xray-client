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
    @Volatile private var activeJob: Job? = null
    private val connections = ConcurrentHashMap.newKeySet<HttpURLConnection>()

    fun reset() {
        activeJob?.cancel()
        connections.forEach { it.disconnect() }
        _testState.value = SpeedtestState.Idle
    }

    suspend fun startSpeedtest() = withContext(Dispatchers.IO) {
        if (!gate.tryLock()) return@withContext
        activeJob = currentCoroutineContext()[Job]
        try {
            if (vpnStatusManager.state.value != VpnState.CONNECTED) {
                _testState.value = SpeedtestState.Error(SpeedtestError.VPN_REQUIRED)
                return@withContext
            }
            val serverId = vpnStatusManager.connectedServerId
            fun checkTunnel() {
                if (vpnStatusManager.state.value != VpnState.CONNECTED || vpnStatusManager.connectedServerId != serverId)
                    throw IOException("vpn_changed")
            }
            // Provider UID bypasses TUN: never fall back to measuring a direct connection.
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(SplateConstants.XRAY_SOCKS_ADDRESS, SplateConstants.XRAY_SOCKS_PORT))
            _testState.value = SpeedtestState.Running(0f, 0f, 0L)
            val latency = measureHttpsResponse(proxy, ::checkTunnel)
            val remainingBytes = AtomicLong(128_000_000L)
            var lastError: Exception? = null
            for (endpoint in listOf(
                Endpoint("https://speed.cloudflare.com/__down?bytes=25000000", 25_000_000L),
                Endpoint("https://cachefly.cachefly.net/10mb.test", 10_485_760L)
            )) {
                currentCoroutineContext().ensureActive()
                checkTunnel()
                try {
                    val result = measureDownload(endpoint, proxy, remainingBytes, ::checkTunnel)
                    currentCoroutineContext().ensureActive()
                    checkTunnel()
                    _testState.value = SpeedtestState.Completed(result.first.mbps, latency,
                        result.first.totalBytes, result.first.seconds, URL(endpoint.url).host, result.second)
                    return@withContext
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    connections.forEach { it.disconnect() }
                    connections.clear()
                    lastError = e
                    Log.w("SpeedtestManager", "Download sample failed: ${e.message}")
                    if (e.message == "vpn_changed") throw e
                }
            }
            throw lastError ?: IOException("invalid_sample")
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            _testState.value = SpeedtestState.Error(when {
                e.message == "vpn_changed" -> SpeedtestError.VPN_CHANGED
                e.message == "invalid_sample" -> SpeedtestError.INVALID_SAMPLE
                e is java.net.SocketTimeoutException -> SpeedtestError.TIMEOUT
                e is java.net.UnknownHostException -> SpeedtestError.DNS
                else -> SpeedtestError.NETWORK
            })
        } finally {
            connections.forEach { it.disconnect() }
            connections.clear()
            activeJob = null
            gate.unlock()
        }
    }

    private fun open(endpoint: Endpoint, proxy: Proxy): HttpURLConnection {
        val separator = if ('?' in endpoint.url) '&' else '?'
        val conn = URL("${endpoint.url}${separator}sample=${SystemClock.elapsedRealtimeNanos()}")
            .openConnection(proxy) as HttpURLConnection
        connections.add(conn)
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 2_000
            conn.useCaches = false
            conn.setRequestProperty("Accept-Encoding", "identity")
            conn.setRequestProperty("Cache-Control", "no-cache, no-store")
            conn.setRequestProperty("User-Agent", "SplateVPN-Speedtest/1.0")
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            if (conn.contentType.orEmpty().let { it.contains("text/", true) || it.contains("json", true) })
                throw IOException("invalid_sample")
            if (conn.contentLengthLong > 0 && conn.contentLengthLong != endpoint.bytes) throw IOException("invalid_sample")
            return conn
        } catch (e: Exception) { close(conn); throw e }
    }

    private fun close(conn: HttpURLConnection) {
        connections.remove(conn)
        conn.disconnect()
    }

    private suspend fun measureDownload(endpoint: Endpoint, proxy: Proxy, remainingBytes: AtomicLong, checkTunnel: () -> Unit): Pair<DownloadMeasurement.Sample, List<Float>> = coroutineScope {
        _testState.value = SpeedtestState.Running(0f, 0f, 0L)
        // Start after both HTTPS connections are ready. Aggregate payload from two streams.
        val prepared = (0..1).map { async(Dispatchers.IO) { open(endpoint, proxy) } }.awaitAll()
        val start = SystemClock.elapsedRealtimeNanos()
        val deadline = start + 10_000_000_000L
        val finishedNs = AtomicLong(start)
        val meter = DownloadMeasurement(start)
        val samples = mutableListOf<Float>()
        val workers = prepared.map { initial -> async(Dispatchers.IO) {
            var conn: HttpURLConnection? = initial
            try {
                val buffer = ByteArray(64 * 1024)
                while (isActive && SystemClock.elapsedRealtimeNanos() < deadline && remainingBytes.get() > 0) {
                    checkTunnel()
                    val current = conn ?: open(endpoint, proxy).also { conn = it }
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
        try {
            while (workers.any { !it.isCompleted }) {
                delay(300)
                checkTunnel()
                val now = SystemClock.elapsedRealtimeNanos()
                val sample = meter.snapshot(now)
                val current = if (now > previousNs) ((sample.measuredBytes - previousBytes) * 8.0 * 1000 / (now - previousNs)).toFloat() else 0f
                if (now > start + 500_000_000L) {
                    samples.add(current)
                    previousBytes = sample.measuredBytes
                    previousNs = now
                }
                _testState.value = SpeedtestState.Running(((now - start) / 10_000_000_000.0).toFloat().coerceIn(0f, .99f), current,
                    sample.totalBytes, samples.toList())
            }
            workers.awaitAll()
            val result = meter.snapshot(finishedNs.get())
            if (result.measuredBytes < 64_000 || result.seconds < .5) throw IOException("invalid_sample")
            result to samples.toList()
        } finally { prepared.forEach(::close) }
    }

    private suspend fun measureHttpsResponse(proxy: Proxy, checkTunnel: () -> Unit): Long {
        val samples = mutableListOf<Long>()
        repeat(3) {
            currentCoroutineContext().ensureActive()
            checkTunnel()
            val conn = URL("https://cp.cloudflare.com/generate_204").openConnection(proxy) as HttpURLConnection
            connections.add(conn)
            try {
                conn.connectTimeout = 3_000
                conn.readTimeout = 3_000
                conn.useCaches = false
                val start = SystemClock.elapsedRealtimeNanos()
                if (conn.responseCode in 200..299) samples.add((SystemClock.elapsedRealtimeNanos() - start) / 1_000_000)
            } catch (_: IOException) { }
            finally { close(conn) }
        }
        return samples.sorted().let { if (it.isEmpty()) -1 else it[it.size / 2] }
    }

    private data class Endpoint(val url: String, val bytes: Long)
}
