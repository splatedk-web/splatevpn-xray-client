package com.danieldk.splatevpn.vpn

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LiveConnectionTracker @Inject constructor() {

    private val maxConnections = 150

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val lock = Any()
    private val hasPendingUpdates = AtomicBoolean(false)

    // Map of unique endpoints: key = "$network:$cleanDest:$port"
    private val endpointMap = LinkedHashMap<String, LiveConnection>()
    private var totalRequestsCount = 0

    private val _connections = MutableStateFlow<List<LiveConnection>>(emptyList())
    val connections: StateFlow<List<LiveConnection>> = _connections.asStateFlow()

    // Unique endpoints count (e.g. 8)
    private val _totalCount = MutableStateFlow(0)
    val totalCount: StateFlow<Int> = _totalCount.asStateFlow()

    // Total requests/packets processed (e.g. 352)
    private val _totalHits = MutableStateFlow(0)
    val totalHits: StateFlow<Int> = _totalHits.asStateFlow()

    private val _proxyCount = MutableStateFlow(0)
    val proxyCount: StateFlow<Int> = _proxyCount.asStateFlow()

    private val _directCount = MutableStateFlow(0)
    val directCount: StateFlow<Int> = _directCount.asStateFlow()

    private val _blockCount = MutableStateFlow(0)
    val blockCount: StateFlow<Int> = _blockCount.asStateFlow()

    private val _dnsCount = MutableStateFlow(0)
    val dnsCount: StateFlow<Int> = _dnsCount.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    // Regex matching Xray access logs:
    // Format examples:
    // 2026/10/04 16:40:00 127.0.0.1:54321 accepted tcp:api.telegram.org:443 [socks-in -> proxy]
    // 127.0.0.1:54321 accepted tcp:api.telegram.org:443 [proxy]
    // 2026/10/04 16:40:00 127.0.0.1:54321 rejected udp:adservice.google.com:53 [socks-in -> block]
    private val accessLogPattern = Regex(
        """(?:^\d{4}/\d{2}/\d{2}\s+\d{2}:\d{2}:\d{2}\s+)?(\S+)\s+(accepted|rejected)\s+([a-zA-Z0-9]+):(.+):(\d+)\s+\[([^\]]+)\]"""
    )

    private val ipv4Pattern = Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$""")

    init {
        // High-performance batched publisher: eliminates Compose recomposition storms
        // and periodically prunes inactive connections (idle > 45 seconds) to keep the list clean.
        scope.launch {
            var pruneTickCounter = 0
            while (isActive) {
                val hasPending = hasPendingUpdates.compareAndSet(true, false)
                val hasConnections = synchronized(lock) { endpointMap.isNotEmpty() }
                val shouldPrune = pruneTickCounter >= 10 && hasConnections && !_isPaused.value
                if (hasPending || shouldPrune) {
                    pruneTickCounter = 0
                    publishSnapshot()
                }
                val isIdle = !hasConnections && !hasPendingUpdates.get()
                delay(if (isIdle) 1000L else 350L)
                pruneTickCounter++
            }
        }
    }

    fun recordLine(line: String): Boolean {
        if (!line.contains("accepted") && !line.contains("rejected")) return false
        val trimmed = line.trim()
        val match = accessLogPattern.find(trimmed) ?: return false

        val source = match.groupValues[1]
        val action = match.groupValues[2].lowercase()
        val network = match.groupValues[3].uppercase()
        val dest = match.groupValues[4]
        val port = match.groupValues[5].toIntOrNull() ?: 0
        val rawTags = match.groupValues[6]

        val cleanDest = dest.removeSurrounding("[", "]")

        // Filter out internal loopback/health checks from user-facing monitor
        if (cleanDest == "127.0.0.1" ||
            cleanDest == "localhost" ||
            cleanDest == "::1" ||
            cleanDest == "0.0.0.0" ||
            cleanDest.startsWith("127.0.0.")
        ) {
            return true
        }

        val tags = if (rawTags.contains("->")) {
            val split = rawTags.split("->", limit = 2)
            Pair(split[0].trim(), split[1].trim())
        } else {
            Pair("", rawTags.trim())
        }

        val inboundTag = tags.first
        val outboundTag = tags.second
        val outboundLower = outboundTag.lowercase()

        val ruleType = when {
            outboundLower.contains("block") || outboundLower.contains("blackhole") || action == "rejected" -> OutboundRuleType.BLOCK
            outboundLower.contains("direct") -> OutboundRuleType.DIRECT
            outboundLower.contains("dns") -> OutboundRuleType.DNS
            outboundLower.contains("proxy") || outboundLower.contains("fragment") -> OutboundRuleType.PROXY
            else -> OutboundRuleType.OTHER
        }

        val isDomain = !ipv4Pattern.matches(cleanDest) && !cleanDest.contains(":")
        val status = if (action == "rejected") ConnectionStatus.REJECTED else ConnectionStatus.ACCEPTED

        val endpointKey = "$network:$cleanDest:$port"

        synchronized(lock) {
            totalRequestsCount += 1

            if (!_isPaused.value) {
                val existing = endpointMap[endpointKey]
                if (existing != null) {
                    // Endpoint already tracked: increment hit count and update timestamp
                    val updated = existing.copy(
                        lastSeen = System.currentTimeMillis(),
                        status = status,
                        inboundTag = inboundTag,
                        outboundTag = outboundTag,
                        ruleType = ruleType,
                        hitCount = existing.hitCount + 1
                    )
                    // Move to latest in LRU order
                    endpointMap.remove(endpointKey)
                    endpointMap[endpointKey] = updated
                } else {
                    // New endpoint: enforce maxConnections capacity
                    if (endpointMap.size >= maxConnections) {
                        val oldestKey = endpointMap.keys.firstOrNull()
                        if (oldestKey != null) {
                            endpointMap.remove(oldestKey)
                        }
                    }
                    val newConnection = LiveConnection(
                        source = source,
                        status = status,
                        network = network,
                        destination = cleanDest,
                        port = port,
                        inboundTag = inboundTag,
                        outboundTag = outboundTag,
                        ruleType = ruleType,
                        isDomain = isDomain,
                        hitCount = 1
                    )
                    endpointMap[endpointKey] = newConnection
                }
                hasPendingUpdates.set(true)
            }
        }

        return true
    }

    private fun publishSnapshot() {
        var proxies = 0
        var directs = 0
        var blocks = 0
        var dns = 0

        val now = System.currentTimeMillis()
        val inactivityTimeoutMs = 45_000L // 45 seconds idle TTL to prune closed/inactive streams

        synchronized(lock) {
            if (_isPaused.value) return
            // Automatically clean up connections that have had no packets for > 45 seconds
            val iterator = endpointMap.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value.lastSeen > inactivityTimeoutMs) {
                    iterator.remove()
                }
            }

            // Most recently seen endpoints first
            // The map already keeps endpoints in LRU order; no sort is needed.
            val snapshot = endpointMap.values.toList().asReversed()
            for (conn in snapshot) {
                when (conn.ruleType) {
                    OutboundRuleType.PROXY -> proxies++
                    OutboundRuleType.DIRECT -> directs++
                    OutboundRuleType.BLOCK -> blocks++
                    OutboundRuleType.DNS -> dns++
                    OutboundRuleType.OTHER -> {}
                }
            }
            // Publish under the same lock as clear() so a stale snapshot cannot
            // restore rows after the user has cleared the monitor.
            _connections.value = snapshot
            _totalCount.value = snapshot.size
            _totalHits.value = totalRequestsCount
            _proxyCount.value = proxies
            _directCount.value = directs
            _blockCount.value = blocks
            _dnsCount.value = dns
        }
    }

    fun clear() {
        synchronized(lock) {
            endpointMap.clear()
            totalRequestsCount = 0
            hasPendingUpdates.set(false)
            _connections.value = emptyList()
            _totalCount.value = 0
            _totalHits.value = 0
            _proxyCount.value = 0
            _directCount.value = 0
            _blockCount.value = 0
            _dnsCount.value = 0
        }
    }

    fun setPaused(paused: Boolean) {
        synchronized(lock) {
            _isPaused.value = paused
            if (!paused) hasPendingUpdates.set(true)
        }
    }

    fun togglePause() {
        synchronized(lock) {
            setPaused(!_isPaused.value)
        }
    }
}
