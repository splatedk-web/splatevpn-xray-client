package com.danieldk.splatevpn.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TorBridgeProbeRepository @Inject constructor() {
    private val _bridgeLatencies = MutableStateFlow<Map<String, Long>>(emptyMap())
    val bridgeLatencies = _bridgeLatencies.asStateFlow()

    private val _isProbing = MutableStateFlow(false)
    val isProbing = _isProbing.asStateFlow()

    private val probeMutex = Mutex()
    private val probeRotation = java.util.concurrent.atomic.AtomicInteger()
    private val generation = java.util.concurrent.atomic.AtomicLong()
    private val speedHistory = TorBridgeSpeedHistory()
    private val _bridgeSpeeds = MutableStateFlow<Map<String, Double>>(emptyMap())
    val bridgeSpeeds = _bridgeSpeeds.asStateFlow()

    internal fun speedsFor(settings: TorSettings, network: String): Map<String, Double> =
        speedHistory.speeds(settings, network)

    internal fun speedCandidates(settings: TorSettings, network: String, delays: Map<String, Long>) =
        speedHistory.candidates(settings, network, delays)

    internal fun speedProbeStarted(settings: TorSettings, network: String, bridge: String) =
        speedHistory.started(settings, network, bridge)

    @Synchronized internal fun recordSpeeds(settings: TorSettings, network: String, speeds: Map<String, Double>, token: Long): Boolean {
        if (generation.get() != token) return false
        speedHistory.record(settings, network, speeds)
        _bridgeSpeeds.value = speedHistory.speeds(settings, network)
        return true
    }
    internal fun cacheGeneration(): Long = generation.get()

    private val latencyCache = ConcurrentHashMap<String, Long>()
    @Volatile
    private var lastProbeTimestamp = 0L
    @Volatile
    private var cachedNetworkKey: String? = null
    @Volatile
    private var cachedBridgesSignature: Int = 0
    @Volatile
    private var cachedTransport: String? = null

    fun getCachedLatency(bridge: String): Long? = latencyCache[bridge]

    @Synchronized fun hasFreshProbes(
        networkKey: String? = null,
        bridges: List<String>? = null,
        transport: String? = null,
        maxAgeMs: Long = 600_000L
    ): Boolean {
        if (latencyCache.isEmpty()) return false
        val now = System.currentTimeMillis()
        val ageLimit = if (latencyCache.values.any { it > 0 }) maxAgeMs else minOf(maxAgeMs, 15_000L)
        if (now - lastProbeTimestamp !in 0..ageLimit) return false
        if (networkKey != null && networkKey != cachedNetworkKey) return false
        if (bridges != null && bridges.hashCode() != cachedBridgesSignature) return false
        if (transport != null && transport != cachedTransport) return false
        return true
    }

    @Synchronized fun clearCache() {
        generation.incrementAndGet()
        speedHistory.clear()
        _bridgeSpeeds.value = emptyMap()
        latencyCache.clear()
        lastProbeTimestamp = 0L
        cachedNetworkKey = null
        cachedBridgesSignature = 0
        cachedTransport = null
        _bridgeLatencies.value = emptyMap()
    }

    suspend fun probeBridges(
        bridges: List<String>,
        timeoutMs: Long = 1500L,
        maxConcurrency: Int = 24,
        networkKey: String? = null,
        transport: String? = null,
        totalBudgetMs: Long = 8_000L
    ): Map<String, Long> = probeMutex.withLock { withContext(Dispatchers.IO) {
        val eligible = torBridgeProbeOrder(bridges.distinct().filter {
            transport == null || transport == "auto" || torBridgeTransport(it) == transport
        }, probeRotation.getAndIncrement())
        if (eligible.isEmpty()) return@withContext emptyMap()
        val token = generation.get()
        val results = ConcurrentHashMap<String, Long>()
        val next = java.util.concurrent.atomic.AtomicInteger()
        _isProbing.value = true
        try {
            withTimeoutOrNull(totalBudgetMs.coerceAtLeast(1)) {
                coroutineScope {
                    List(minOf(maxConcurrency.coerceIn(1, 32), eligible.size)) {
                        launch {
                            while (true) {
                                val bridge = eligible.getOrNull(next.getAndIncrement()) ?: break
                                val result = probeSingleBridge(bridge, timeoutMs)
                                results[result.first] = result.second
                            }
                        }
                    }.joinAll()
                }
            }
            currentCoroutineContext().ensureActive()
            synchronized(this@TorBridgeProbeRepository) {
                if (generation.get() != token) return@withContext emptyMap()
                lastProbeTimestamp = System.currentTimeMillis()
                cachedNetworkKey = networkKey
                cachedBridgesSignature = bridges.hashCode()
                cachedTransport = transport
                latencyCache.clear()
                latencyCache.putAll(results)
                _bridgeLatencies.value = HashMap(results)
                results.toMap()
            }
        } finally { _isProbing.value = false }
    } }

    suspend fun probeSingleBridge(bridge: String, timeoutMs: Long = 1800L): Pair<String, Long> = withContext(Dispatchers.IO) {
        val address = TorConfigParser.extractBridgeAddress(bridge)
        if (address == null) {
            return@withContext bridge to -1L
        }
        var (host, port) = address
        if (torBridgeTransport(bridge) == "webtunnel") {
            val url = bridge.split(Regex("\\s+")).firstOrNull { it.startsWith("url=") }?.substringAfter("url=")
            val endpoint = runCatching { java.net.URI(url.orEmpty()) }.getOrNull()
            if (endpoint?.host != null) { host = endpoint.host; port = if (endpoint.port == -1) 443 else endpoint.port }
        } else if (host.startsWith("192.0.2.") || host.startsWith("198.51.100.") || host.startsWith("203.0.113.")) {
            TorConfigParser.extractBridgeSni(bridge)?.takeIf { it.isNotBlank() }?.let { host = it; port = 443 }
        }
        if (port !in 1..65535) return@withContext bridge to -1L
        val startNs = System.nanoTime()
        val socket = Socket()
        try {
            BoundedNetworkTask.run(timeoutMs.coerceIn(1, 10_000).toInt(), onCancel = { socket.close() }) {
                try {
                    socket.tcpNoDelay = true
                    socket.connect(InetSocketAddress(host, port), timeoutMs.toInt().coerceIn(1, 10000))
                    (System.nanoTime() - startNs).div(1_000_000L).coerceAtLeast(1L)
                } catch (_: java.io.IOException) { -1L }
            }?.let { bridge to it } ?: (bridge to -1L)
        } finally { runCatching { socket.close() } }
    }
}
