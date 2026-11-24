package com.danieldk.splatevpn.data

import kotlinx.coroutines.sync.withPermit

import android.util.Log
import kotlinx.coroutines.CancellationException
import com.danieldk.splatevpn.SplateConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

@Singleton
class ServerProbeRepository @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val serverDao: ServerDao,
    private val settingsManager: SettingsManager,
    private val vpnStatusManagerProvider: javax.inject.Provider<com.danieldk.splatevpn.vpn.VpnStatusManager>,
    private val xrayConfigGenerator: com.danieldk.splatevpn.vpn.XrayConfigGenerator,
    private val xrayCoreManagerProvider: javax.inject.Provider<com.danieldk.splatevpn.vpn.XrayCoreManager>,
    private val physicalEvidence: PhysicalFailureEvidence
) {
    private val TAG = "ServerProbeRepository"
    private val pingConcurrencyLimit = Semaphore(24)
    private val realDelayGate = ServerProbeGate()
    // Dedicated high-priority gate for the auto-balancer; never starved by bulk UI pings.
    internal val balancerGate = BalancerProbeGate()
    private val realDelayBatch = ServerProbeBatch {
        val state = vpnStatusManagerProvider.get().state.value
        state != com.danieldk.splatevpn.vpn.VpnState.DISCONNECTED &&
            state != com.danieldk.splatevpn.vpn.VpnState.FAILED
    }
    internal val pingActivity = PingActivity()
    val activePingIds = pingActivity.activeIds
    private val sessionPings = SessionPingResults()
    val sessionPingResults = sessionPings.results
    private val _verifiedDelays = kotlinx.coroutines.flow.MutableStateFlow<Map<String, VerifiedServerDelay>>(emptyMap())
    val verifiedDelays = _verifiedDelays.asStateFlow()
    private val evidenceLock = Any()
    private val profileRevisions = mutableMapOf<String, Long>()
    internal fun currentNetworkKey() = synchronized(evidenceLock) {
        NetworkAccessPolicy.physicalNetworkKey(context).also { qualityTracker.onNetworkChanged(it) }
    }
    companion object {
        const val STANDBY_PROOF_FRESH_MS = 120_000L // 2 minutes maximum validity for warm standby proof
    }

    internal fun hasFreshTunnelProof(server: ServerNode, maxAgeMs: Long = STANDBY_PROOF_FRESH_MS): Boolean = synchronized(evidenceLock) {
        val network = currentNetworkKey()
        verifiedDelays.value[server.id]?.let {
            it.network == network && it.profile == QuickServerSelection.profile(server) &&
                System.currentTimeMillis() - it.checkedAt in 0..maxAgeMs
        } == true
    }

    internal fun validateImportedProfile(server: ServerNode) {
        xrayConfigGenerator.generateTestConfig(server, socksPort = 10808)
    }

    internal val qualityTracker = ServerQualityTracker { android.os.SystemClock.elapsedRealtime() }
    private val nodesRequiringFragment = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun requiresTlsFragment(serverId: String): Boolean = nodesRequiringFragment.contains(serverId)
    fun markRequiresTlsFragment(serverId: String) { nodesRequiringFragment.add(serverId) }
    private val nextEphemeralPort = java.util.concurrent.atomic.AtomicInteger(25000)

    private fun allocateFreePort(): Int {
        for (attempt in 0..50) {
            val candidate = nextEphemeralPort.getAndIncrement()
            if (candidate > 60000) {
                nextEphemeralPort.set(25000)
            }
            try {
                java.net.ServerSocket(candidate).use {
                    return candidate
                }
            } catch (_: Exception) {
                // Port occupied, try next
            }
        }
        return try {
            java.net.ServerSocket(0).use { it.localPort }
        } catch (_: Exception) {
            -1
        }
    }

    /** Measures the selected TCP, ICMP or Xray tunnel latency mode. */
    suspend fun testPing(server: ServerNode): Long = NetworkAccessPolicy.withChecks(context) {
        pingActivity.track(server.id) { testPingInternal(server) }
    }

    private suspend fun testPingInternal(server: ServerNode): Long = withContext(Dispatchers.IO) {
        // Keep the selected measurement mode consistent before and after connecting.
        // QUIC has no TCP listener to probe; check the actual tunnel for these nodes.
        val bestPing = if (server.usesUdpTransport) testRealDelay(server) else when (settingsManager.getPingMode().lowercase()) {
            "real_delay", "http", "real" -> testRealDelay(server)
            "icmp" -> testPingIcmp(server)
            else -> testPingTcp(server)
        }
        savePingIfUnchanged(server, bestPing)
        bestPing
    }
    suspend fun updateServerPing(serverId: String, ping: Long) = withContext(Dispatchers.IO) {
        savePing(serverId, ping)
    }

    internal suspend fun savePing(serverId: String, ping: Long) {
        serverDao.updatePing(serverId, ping)
        sessionPings.record(serverId, ping)
    }

    internal suspend fun savePingIfUnchanged(server: ServerNode, ping: Long) {
        settingsManager.requireChecksNetwork()
        if (serverDao.updatePingIfUnchanged(server, ping)) sessionPings.record(server.id, ping)
    }

    suspend fun testPingViaXray(
        testUrl: String = com.danieldk.splatevpn.vpn.TunnelProbe.PRIMARY_URL,
        timeoutMs: Int = 5000
    ): Long = withContext(Dispatchers.IO) {
        val proxy = java.net.Proxy(java.net.Proxy.Type.SOCKS, InetSocketAddress(SplateConstants.XRAY_SOCKS_ADDRESS, com.danieldk.splatevpn.vpn.LocalProxyAccess.activeSocksPort))
        NetworkAccessPolicy.withChecks(context) {
            com.danieldk.splatevpn.vpn.TunnelProbe.measure(proxy, testUrl, timeoutMs, priority = true,
                credentials = com.danieldk.splatevpn.vpn.LocalProxyAccess.active)
        }
    }
    /**
     * Полноценный Real Delay (HTTPS Test) для любого узла, независимо от того, подключен VPN или нет.
     * Аналог функции Real Delay / URL Test в Nekobox, v2rayN и Sing-box:
     * 1. Для текущего активного узла: мгновенный замер через активный SOCKS5 (10808) на HTTPS 204 endpoint.
     * 2. Для любого другого (отключенного) узла: запуск изолированного легковесного воркера Xray
     *    на динамическом свободном порту с передачей реального протокола и проверкой HTTPS-ответа 204.
     *
     * Успешный HTTPS-ответ подтверждает работу туннеля в момент проверки.
     * Ошибка отдельной проверки не доказывает недоступность сервера.
     */
    suspend fun testRealDelay(
        server: ServerNode,
        testUrl: String = com.danieldk.splatevpn.vpn.TunnelProbe.PRIMARY_URL,
        timeoutMs: Int = 5000
    ): Long = testRealDelayGated(server, testUrl, timeoutMs, useBalancerGate = false)

    /**
     * Вариант testRealDelay для авто-балансировщика: использует выделенный [BalancerProbeGate]
     * с увеличенным таймаутом, чтобы не конкурировать с UI-пингами за слоты [ServerProbeGate].
     */
    internal suspend fun testRealDelayForBalancer(
        server: ServerNode,
        testUrl: String = com.danieldk.splatevpn.vpn.TunnelProbe.PRIMARY_URL,
        timeoutMs: Int = 6000
    ): Long = testRealDelayGated(server, testUrl, timeoutMs, useBalancerGate = true)

    private suspend fun testRealDelayGated(
        server: ServerNode,
        testUrl: String,
        timeoutMs: Int,
        useBalancerGate: Boolean
    ): Long {
        // Only actual startup and network work consume the budget, not waiting for a free slot.
        var checkedNetwork: Long? = null
        val adaptive = !settingsManager.isTlsFragmentEnabled() && server.security.lowercase() in setOf("tls", "reality")
        val gateTimeoutMs = realDelayOperationBudget(timeoutMs, adaptive)
        val result = NetworkAccessPolicy.withChecks(context) {
            if (useBalancerGate) {
                balancerGate.measure(gateTimeoutMs) {
                    settingsManager.requireChecksNetwork()
                    checkedNetwork = NetworkAccessPolicy.physicalNetworkKey(context)
                    pingActivity.track(server.id) { testRealDelayInternal(server, testUrl, timeoutMs) }
                }
            } else {
                realDelayGate.measure(gateTimeoutMs) {
                    settingsManager.requireChecksNetwork()
                    checkedNetwork = NetworkAccessPolicy.physicalNetworkKey(context)
                    pingActivity.track(server.id) { testRealDelayInternal(server, testUrl, timeoutMs) }
                }
            }
        }
        settingsManager.requireChecksNetwork()
        if (checkedNetwork != currentNetworkKey()) return -1L
        savePingIfUnchanged(server, result)
        if (result > 0) recordTunnelSuccess(server, result, checkedNetwork)
        else recordServerFailure(server, checkedNetwork)
        return result
    }

    internal suspend fun diagnoseFragmentation(
        serverId: String, active: FragmentationProfile, current: () -> Boolean
    ): Boolean {
        if (!settingsManager.areChecksAllowed()) return false
        val server = serverDao.getSelectedServerSync()?.takeIf { it.id == serverId } ?: return false
        if (server.security.lowercase() !in setOf("tls", "reality")) return false
        return confirmFragmentationMismatch(active, current) { profile ->
            NetworkAccessPolicy.withChecks(context) {
                realDelayGate.measure(7500L) {
                    testRealDelayInternal(server, com.danieldk.splatevpn.vpn.TunnelProbe.PRIMARY_URL, 5000, profile)
                }
            }
        }
    }

    private suspend fun testRealDelayInternal(server: ServerNode, testUrl: String, timeoutMs: Int, fragment: FragmentationProfile? = null): Long {
        var delay = withProbeTunnel(server, fragment) { port, credentials ->
            val proxy = java.net.Proxy(java.net.Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
            com.danieldk.splatevpn.vpn.TunnelProbe.measure(proxy, testUrl, timeoutMs, credentials = credentials)
        } ?: -1L
        // Adaptive DPI bypass probe: if standard handshake failed on TLS/Reality and fragmentation wasn't already specified, retry with TLS fragment
        if (delay <= 0 && fragment == null && !settingsManager.isTlsFragmentEnabled() &&
            (server.security.lowercase() in setOf("reality", "tls"))) {
            val fallbackFragment = FragmentationProfile(enabled = true, length = "100-200", interval = "10-20", packets = "tlshello")
            val fallbackTimeoutMs = minOf(timeoutMs, 2000)
            val fragmentDelay = withProbeTunnel(server, fallbackFragment) { port, credentials ->
                val proxy = java.net.Proxy(java.net.Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
                com.danieldk.splatevpn.vpn.TunnelProbe.measure(proxy, testUrl, fallbackTimeoutMs, credentials = credentials)
            } ?: -1L
            if (fragmentDelay > 0) {
                Log.i(TAG, "Server '${server.name}' passed Real Delay with TLS fragment DPI bypass (${fragmentDelay}ms)")
                nodesRequiringFragment.add(server.id)
                delay = fragmentDelay
            }
        }
        return delay
    }

    internal suspend fun <T> withServerTunnel(server: ServerNode, timeoutMs: Long, fragment: FragmentationProfile? = null,
        useBalancerGate: Boolean = false,
        block: suspend (Int, com.danieldk.splatevpn.vpn.LocalProxyCredentials?) -> T): T? =
        NetworkAccessPolicy.withChecks(context) {
            val operation: suspend () -> T? = {
                settingsManager.requireChecksNetwork()
                pingActivity.track(server.id) { withProbeTunnel(server, fragment, block = block) }
            }
            if (useBalancerGate) balancerGate.withSlot(timeoutMs + 2500L, operation)
            else realDelayGate.withSlot(timeoutMs + 2500L, operation)
        }

    private val awgProbeSlots = kotlinx.coroutines.sync.Semaphore(2)
    private suspend fun <T> withProbeTunnel(server: ServerNode, fragment: FragmentationProfile? = null, block: suspend (Int, com.danieldk.splatevpn.vpn.LocalProxyCredentials?) -> T): T? =
        if (server.isAmneziaWg) awgProbeSlots.withPermit { openProbeTunnel(server, fragment, block) } else openProbeTunnel(server, fragment, block)

    private suspend fun <T> openProbeTunnel(server: ServerNode, fragment: FragmentationProfile? = null, block: suspend (Int, com.danieldk.splatevpn.vpn.LocalProxyCredentials?) -> T): T? = withContext(Dispatchers.IO) {
        val xrayMgr = xrayCoreManagerProvider.get()
        xrayMgr.ensureAssetsExtracted()
        val xrayBin = xrayMgr.getXrayBinary()
        if (xrayBin == null || !xrayBin.exists()) {
            Log.w(TAG, "RealDelay: Xray binary not available; tunnel verification cannot run")
            return@withContext null
        }

        val freePort = allocateFreePort()
        if (freePort <= 0) {
            return@withContext null
        }

        val safeHash = kotlin.math.abs(server.id.hashCode())
        val testConfigFile = java.io.File(context.cacheDir, "xray_test_${System.currentTimeMillis()}_$safeHash.json")
        val localAccess = com.danieldk.splatevpn.vpn.LocalProxyCredentials.generate()

        var process: Process? = null
        var prepared: com.danieldk.splatevpn.vpn.PreparedTransports? = null
        val outputBuffer = StringBuilder()
        try {
            val effectiveTlsFragment = fragment?.enabled ?: (settingsManager.isTlsFragmentEnabled() || requiresTlsFragment(server.id))
            val effectiveLength = fragment?.length ?: settingsManager.getTlsFragmentLength()
            val effectiveInterval = fragment?.interval ?: settingsManager.getTlsFragmentInterval()
            val effectivePackets = fragment?.packets ?: settingsManager.getTlsFragmentPackets()

            val configJson = xrayConfigGenerator.generateTestConfig(
                node = server,
                socksPort = freePort,
                routingRegion = settingsManager.getRoutingRegion(),
                xhttpModeOverride = settingsManager.getXhttpMode(),
                enableTlsFragment = effectiveTlsFragment,
                tlsFragmentLength = effectiveLength,
                tlsFragmentInterval = effectiveInterval,
                tlsFragmentPackets = effectivePackets,
                tlsMixedSni = settingsManager.isTlsMixedSniEnabled(),
                enableMux = settingsManager.isMuxEnabled(),
                muxConcurrency = settingsManager.getMuxConcurrency(),
                localProxyAccess = localAccess
            )
            val transports = com.danieldk.splatevpn.vpn.PreparedTransports.prepare(context, configJson)
            prepared = transports
            testConfigFile.writeText(transports.json)

            val pb = ProcessBuilder(
                "sh", "-c",
                "export XRAY_LOCATION_ASSET='${context.filesDir.absolutePath}'; cd '${context.filesDir.absolutePath}'; exec '${xrayBin.absolutePath}' run -c '${testConfigFile.absolutePath}'"
            )
            pb.directory(context.filesDir)
            pb.environment()["XRAY_LOCATION_ASSET"] = context.filesDir.absolutePath
            pb.redirectErrorStream(true)
            process = pb.start()

            // Дренируем вывод процесса в фоне и собираем логи для диагностики
            val inputStream = process.inputStream
            Thread {
                try {
                    val reader = BufferedReader(InputStreamReader(inputStream))
                    while (process.isAlive) {
                        val line = reader.readLine() ?: break
                        if (outputBuffer.length < 2048) {
                            outputBuffer.append(line).append("\n")
                        }
                    }
                } catch (_: Exception) {}
            }.apply {
                isDaemon = true
                name = "xray-drain-$freePort"
                start()
            }

            // Ожидаем готовности SOCKS-порта до 2500мс (выходим сразу при готовности сокета)
            var portReady = false
            val checkStart = System.currentTimeMillis()
            while (System.currentTimeMillis() - checkStart < 2500) {
                if (!process.isAlive) break
                try {
                    Socket("127.0.0.1", freePort).use { portReady = true }
                    break
                } catch (e: Exception) {
                    kotlinx.coroutines.delay(40)
                }
            }

            if (!portReady || !process.isAlive) {
                Log.w(TAG, "Probe worker did not start")
                    return@withContext null
            }

            block(freePort, localAccess)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e is WifiRequiredException) throw e
            Log.w(TAG, "Probe failed: ${e.javaClass.simpleName}")
            null
        } finally {
            try {
                process?.destroyForcibly()
            } catch (_: Exception) {}
            prepared?.close()
            try {
                testConfigFile.delete()
            } catch (_: Exception) {}
        }
    }

    suspend fun testServersRealDelay(
        servers: List<ServerNode>,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): ServerPingSummary = withContext(Dispatchers.IO) {
        settingsManager.requireChecksNetwork()
        val (sponsor, others) = servers.partition { it.subId == SplateConstants.PRESET_SUB_SPONSOR_ID }
        val available = java.util.concurrent.atomic.AtomicInteger()
        suspend fun probe(server: ServerNode) {
            if (testRealDelay(server) >= 0L) available.incrementAndGet()
        }
        val first = realDelayBatch.measure(sponsor, { done, _ -> onProgress(done, servers.size) }, ::probe)
        val second = realDelayBatch.measure(others, { done, _ -> onProgress(first + done, servers.size) }, ::probe)
        ServerPingSummary(first + second, available.get())
    }

    suspend fun testAllRealDelays(
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): ServerPingSummary = withContext(Dispatchers.IO) {
        val servers = serverDao.getServersForBulkCheck()
        testServersRealDelay(servers, onProgress)
    }

    private suspend fun testPingIcmp(server: ServerNode): Long = withContext(Dispatchers.IO) {
        val activeProcess = java.util.concurrent.atomic.AtomicReference<Process?>()
        val bestPing = BoundedNetworkTask.run(2500, onCancel = { activeProcess.get()?.destroyForcibly() }) {
            var ping = -1L
            try {
                val process = ProcessBuilder("ping", "-c", "1", "-W", "2", server.address).start()
                activeProcess.set(process)
                process.inputStream.bufferedReader().use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val match = Regex("time=([0-9.]+)").find(line.orEmpty())
                        if (match != null) {
                            ping = match.groupValues[1].toFloat().toLong()
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "ICMP Ping failed: ${e.message}")
            } finally {
                activeProcess.getAndSet(null)?.destroyForcibly()
            }
            ping
        } ?: -1L
        if (bestPing == -1L) {
            Log.w(TAG, "ICMP Ping ${server.name}: timeout or unreachable")
        } else {
            Log.d(TAG, "ICMP Ping ${server.name}: ${bestPing}ms")
        }
        bestPing
    }

    private suspend fun testPingTcp(server: ServerNode, timeout: Int = 3000): Long =
        TcpProbe.measure(server.address, server.port, timeout)

    /** Rank verified latency, recent jitter and failures; names and country flags are not evidence. */
    fun calculateEffectivePing(server: ServerNode, rawPing: Long, isCurrentlyActive: Boolean = false): Long =
        qualityTracker.score(server.id, rawPing, isCurrentlyActive)

    fun invalidateServerQuality(serverId: String) = synchronized(evidenceLock) {
        profileRevisions[serverId] = profileRevisions.getOrDefault(serverId, 0L) + 1L
        _verifiedDelays.update { it - serverId }
        qualityTracker.forget(serverId)
        sessionPings.record(serverId, 0)
    }

    suspend fun recordTunnelSuccess(server: ServerNode, delayMs: Long,
        checkedNetwork: Long? = NetworkAccessPolicy.physicalNetworkKey(context)) {
        if (delayMs <= 0 || !settingsManager.areChecksAllowed()) return
        val revision = synchronized(evidenceLock) {
            if (checkedNetwork == null || checkedNetwork != currentNetworkKey()) return
            profileRevisions.getOrDefault(server.id, 0L)
        }
        if (serverDao.recordVerificationIfUnchanged(server, System.currentTimeMillis(), successful = true)) {
            synchronized(evidenceLock) {
                if (checkedNetwork != currentNetworkKey() || !settingsManager.areChecksAllowed() ||
                    revision != profileRevisions.getOrDefault(server.id, 0L)) return
                qualityTracker.recordSuccess(server.id, delayMs)
                val now = System.currentTimeMillis()
                _verifiedDelays.update { current -> current.filterValues { now - it.checkedAt in 0..QuickServerSelection.FRESH_FOR_MS } +
                    (server.id to VerifiedServerDelay(QuickServerSelection.profile(server), delayMs, now, requireNotNull(checkedNetwork))) }
            }
        }
    }

    suspend fun recordServerFailure(server: ServerNode, checkedNetwork: Long? = currentNetworkKey()): Boolean {
        if (!settingsManager.areChecksAllowed()) return false
        val revision = synchronized(evidenceLock) {
            if (checkedNetwork == null || checkedNetwork != currentNetworkKey()) return false
            // Revoke stale success even when the cause cannot be attributed to this server.
            _verifiedDelays.update { current ->
                val proof = current[server.id]
                if (proof?.network == checkedNetwork && proof.profile == QuickServerSelection.profile(server)) current - server.id else current
            }
            profileRevisions.getOrDefault(server.id, 0L)
        }
        val otherTunnelWorks = verifiedDelays.value.any { (id, proof) -> id != server.id &&
            proof.network == checkedNetwork && System.currentTimeMillis() - proof.checkedAt in 0..500L }
        if (!otherTunnelWorks && physicalEvidence.assess(checkedNetwork) != PhysicalEvidence.REACHABLE) return false
        if (checkedNetwork != currentNetworkKey() || !settingsManager.areChecksAllowed()) return false
        if (serverDao.recordVerificationIfUnchanged(server, System.currentTimeMillis(), successful = false)) {
            synchronized(evidenceLock) {
                if (checkedNetwork != currentNetworkKey() || !settingsManager.areChecksAllowed() ||
                    revision != profileRevisions.getOrDefault(server.id, 0L)) return false
                _verifiedDelays.update { current ->
                    val proof = current[server.id]
                    if (proof == null || proof.network == checkedNetwork && proof.profile == QuickServerSelection.profile(server))
                        current - server.id else current
                }
                qualityTracker.recordFailure(server.id)
                return true
            }
        }
        return false
    }

    suspend fun testServersPing(servers: List<ServerNode>): ServerPingSummary = withContext(Dispatchers.IO) {
        settingsManager.requireChecksNetwork()
        val (sponsor, others) = servers.partition { it.subId == SplateConstants.PRESET_SUB_SPONSOR_ID }
        var available = 0
        for (group in listOf(sponsor, others)) {
            available += group.map { server ->
                async { pingConcurrencyLimit.withPermit { testPing(server) } }
            }.awaitAll().count { it >= 0L }
        }
        ServerPingSummary(servers.size, available)
    }

    suspend fun testAllPings(): ServerPingSummary = withContext(Dispatchers.IO) {
        val servers = serverDao.getServersForBulkCheck()
        testServersPing(servers)
    }

    suspend fun testSubscriptionPings(subId: String): ServerPingSummary = withContext(Dispatchers.IO) {
        settingsManager.requireChecksNetwork()
        val servers = serverDao.getServersBySubIdSync(subId)
        val currentMode = settingsManager.getPingMode().lowercase()
        if (currentMode in listOf("real_delay", "http", "real")) {
            testServersRealDelay(servers)
        } else {
            testServersPing(servers)
        }
    }

}
