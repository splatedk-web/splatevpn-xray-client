package com.danieldk.splatevpn.data

import android.util.Base64
import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import com.danieldk.splatevpn.SplateConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class ServerRepository @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val serverDao: ServerDao,
    private val subscriptionDao: SubscriptionDao,
    private val settingsManager: SettingsManager,
    private val vpnStatusManagerProvider: javax.inject.Provider<com.danieldk.splatevpn.vpn.VpnStatusManager>,
    private val xrayConfigGenerator: com.danieldk.splatevpn.vpn.XrayConfigGenerator,
    private val xrayCoreManagerProvider: javax.inject.Provider<com.danieldk.splatevpn.vpn.XrayCoreManager>,
    private val autoBackupManager: AutoBackupManager,
    private val database: AppDatabase
) {
    private val TAG = "ServerRepository"
    private val pingConcurrencyLimit = Semaphore(24)
    private val realDelayGate = ServerProbeGate()
    private val realDelayBatch = ServerProbeBatch {
        val state = vpnStatusManagerProvider.get().state.value
        state != com.danieldk.splatevpn.vpn.VpnState.DISCONNECTED &&
            state != com.danieldk.splatevpn.vpn.VpnState.FAILED
    }
    private val pingActivity = PingActivity()
    val activePingIds = pingActivity.activeIds

    // Параметры двухэтапного балансировщика (TCP-префильтр -> Real Delay верификация)
    private val AUTO_SELECT_BATCH_SIZE = 8
    private val selectionMutex = Mutex()
    private val selectionHandoff = SelectionHandoff<AutoSelectionRequest, ServerNode>(selectionMutex)
    private data class AutoSelectionRequest(
        val excluded: Set<String>, val urgent: Boolean, val commit: Boolean, val probeSettings: List<Any>
    )
    private val presetInitializationMutex = Mutex()
    private val qualityTracker = ServerQualityTracker()
    private val AUTO_SELECT_SHORTLIST_SIZE = 4
    private val AUTO_SELECT_TCP_TIMEOUT_MS = 1400
    private val AUTO_SELECT_REAL_DELAY_TIMEOUT_MS = 3500
    private val AUTO_SELECT_FAST_PATH_THRESHOLD_MS = 130L

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

    val allServers: Flow<List<ServerNode>> = serverDao.getAllServers()
    val selectedServer: Flow<ServerNode?> = serverDao.getSelectedServer().onEach { selected ->
        // Refresh the widget cache after startup and subscription renames as well as manual selection.
        val name = selected?.name.orEmpty()
        if (settingsManager.getSelectedServerName() != name) settingsManager.setSelectedServerName(name)
    }
    val allSubscriptions: Flow<List<SubscriptionEntity>> = subscriptionDao.getAllSubscriptions()

    private fun readAssetString(filename: String): String {
        return try {
            context.assets.open(filename).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read asset $filename: ${e.message}")
            ""
        }
    }

    /**
     * Инициализирует репозиторий:
     * 1. Пытается восстановить пользовательские серверы из локального автобэкапа, если БД пуста.
     * 2. Инициализирует встроенные подписки: CIDR RU, Harvester и VLESS Universal.
     */
    suspend fun initDefaultServers() = withContext(Dispatchers.IO) {
        val restoredCount = autoBackupManager.tryAutoRestoreIfEmpty()
        if (restoredCount > 0) {
            Log.i(TAG, "initDefaultServers: Автоматически восстановлено $restoredCount узлов из резервной копии!")
        }
        initPresetSubscriptions()
    }

    suspend fun initPresetSubscriptions(forceRefresh: Boolean = false) = withContext(Dispatchers.IO) {
        presetInitializationMutex.withLock { initPresetSubscriptionsLocked(forceRefresh) }
    }

    private suspend fun initPresetSubscriptionsLocked(forceRefresh: Boolean) = withContext(Dispatchers.IO) {
        val legacyInitialized = settingsManager.hasInitializedPresetSubscriptions()
        val universalInitialized = settingsManager.hasInitializedUniversalWhitelist()
        if (!forceRefresh && legacyInitialized && universalInitialized) {
            return@withContext
        }

        try {
            val updateIds = mutableListOf<String>()
            if (!legacyInitialized || forceRefresh) {
                updateIds += SplateConstants.PRESET_SUB_CIDR_ID
                updateIds += SplateConstants.PRESET_SUB_HARVESTER_ID
            }
            // 1. Предустановленная подписка White Lists CIDR RU
            val existingCidr = subscriptionDao.getSubscriptionById(SplateConstants.PRESET_SUB_CIDR_ID)
            if ((!legacyInitialized && existingCidr == null) || forceRefresh) {
                val rawCidr = readAssetString("preset_white_cidr.txt")
                val nodes = if (rawCidr.isNotBlank()) {
                    LinkParser.parseSubscription(
                        rawCidr,
                        subId = SplateConstants.PRESET_SUB_CIDR_ID,
                        subName = SplateConstants.PRESET_SUB_CIDR_NAME
                    )
                } else emptyList()

                subscriptionDao.insertSubscription(
                    SubscriptionEntity(
                        id = SplateConstants.PRESET_SUB_CIDR_ID,
                        name = SplateConstants.PRESET_SUB_CIDR_NAME,
                        url = SplateConstants.PRESET_SUB_CIDR_URL,
                        lastUpdated = System.currentTimeMillis(),
                        nodeCount = nodes.size,
                        autoUpdate = true
                    )
                )
                if (nodes.isNotEmpty()) {
                    serverDao.deleteServersBySubId(SplateConstants.PRESET_SUB_CIDR_ID)
                    serverDao.insertServers(nodes)
                }
            }

            // 2. Предустановленная подписка Harvester (Tested)
            val existingHarvester = subscriptionDao.getSubscriptionById(SplateConstants.PRESET_SUB_HARVESTER_ID)
            if ((!legacyInitialized && existingHarvester == null) || forceRefresh) {
                val rawHarvester = readAssetString("preset_harvester.txt")
                val nodes = if (rawHarvester.isNotBlank()) {
                    LinkParser.parseSubscription(
                        rawHarvester,
                        subId = SplateConstants.PRESET_SUB_HARVESTER_ID,
                        subName = SplateConstants.PRESET_SUB_HARVESTER_NAME
                    )
                } else emptyList()

                subscriptionDao.insertSubscription(
                    SubscriptionEntity(
                        id = SplateConstants.PRESET_SUB_HARVESTER_ID,
                        name = SplateConstants.PRESET_SUB_HARVESTER_NAME,
                        url = SplateConstants.PRESET_SUB_HARVESTER_URL,
                        lastUpdated = System.currentTimeMillis(),
                        nodeCount = nodes.size,
                        autoUpdate = true
                    )
                )
                if (nodes.isNotEmpty()) {
                    serverDao.deleteServersBySubId(SplateConstants.PRESET_SUB_HARVESTER_ID)
                    serverDao.insertServers(nodes)
                }
            }

            // Add the new source once on upgrades, without restoring deleted legacy presets.
            if (!universalInitialized || forceRefresh) {
                val existing = subscriptionDao.getSubscriptionById(SplateConstants.PRESET_SUB_UNIVERSAL_ID)
                if (existing == null || forceRefresh) {
                    val parsed = LinkParser.parseSubscription(
                        readAssetString("preset_white_universal.txt"),
                        subId = SplateConstants.PRESET_SUB_UNIVERSAL_ID,
                        subName = SplateConstants.PRESET_SUB_UNIVERSAL_NAME
                    )
                    check(parsed.isNotEmpty()) { "Universal whitelist asset contains no valid nodes" }
                    database.withTransaction {
                        val previous = serverDao.getServersBySubIdSync(SplateConstants.PRESET_SUB_UNIVERSAL_ID)
                        val nodes = SubscriptionNodeMerger.merge(previous, parsed)
                        val subscription = (existing ?: SubscriptionEntity(
                            id = SplateConstants.PRESET_SUB_UNIVERSAL_ID,
                            name = SplateConstants.PRESET_SUB_UNIVERSAL_NAME,
                            url = SplateConstants.PRESET_SUB_UNIVERSAL_URL
                        )).copy(lastUpdated = System.currentTimeMillis(), nodeCount = nodes.size)
                        subscriptionDao.insertSubscription(subscription)
                        serverDao.deleteServersBySubId(subscription.id)
                        serverDao.insertServers(nodes)
                    }
                }
                updateIds += SplateConstants.PRESET_SUB_UNIVERSAL_ID
            }

            // Если ни один сервер не выбран, активируем первый доступный
            if (serverDao.getSelectedServerSync() == null) {
                serverDao.getAllServersSync().firstOrNull()?.let {
                    serverDao.setActiveServer(it.id)
                }
            }

            settingsManager.setInitializedPresetSubscriptions(true)
            settingsManager.setInitializedUniversalWhitelist()
            autoBackupManager.backupToVault()

            // Асинхронно обновляем по сети актуальные списки серверов
            CoroutineScope(Dispatchers.IO).launch {
                for (id in updateIds) {
                    try {
                        val subscription = subscriptionDao.getSubscriptionById(id)
                        if (subscription?.enabled == true && subscription.autoUpdate) updateSubscription(id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        Log.w(TAG, "Background update of built-in subscription failed")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "initPresetSubscriptions failed: ${e.message}", e)
        }
    }

    suspend fun restorePresetSubscriptions(): Int = withContext(Dispatchers.IO) {
        initPresetSubscriptions(forceRefresh = true)
        SplateConstants.PRESET_SUB_IDS.sumOf { serverDao.getServersBySubIdSync(it).size }
    }

    suspend fun setActiveServer(id: String) = withContext(Dispatchers.IO) {
        serverDao.setActiveServer(id)
        serverDao.getSelectedServerSync()?.let {
            settingsManager.setSelectedServerName(it.name)
        }
    }

    suspend fun getActiveServerSync(): ServerNode? = withContext(Dispatchers.IO) {
        val selected = serverDao.getSelectedServerSync()
        val disabledSubIds = subscriptionDao.getAllSubscriptionsSync().filter { !it.enabled }.map { it.id }.toSet()
        if (selected != null && selected.subId !in disabledSubIds) {
            return@withContext selected
        }
        val all = serverDao.getAllServersSync().filter { it.subId !in disabledSubIds }
        val fallback = all.firstOrNull()
        if (fallback != null && fallback.id != selected?.id) {
            serverDao.setActiveServer(fallback.id)
            settingsManager.setSelectedServerName(fallback.name)
        }
        fallback
    }

    suspend fun addServerFromLink(link: String): Boolean = withContext(Dispatchers.IO) {
        val count = addServersFromLinks(link)
        count > 0
    }

    suspend fun addServersFromLinks(text: String): Int = withContext(Dispatchers.IO) {
        val importPayload = LinkParser.unwrapImportLink(text) ?: text
        val nodes = LinkParser.parseBatch(importPayload, subId = "custom", subName = "Пользовательские")
        if (nodes.isNotEmpty()) {
            val all = serverDao.getAllServersSync()
            val toInsert = if (all.isEmpty()) {
                nodes.mapIndexed { idx, node -> if (idx == 0) node.copy(isSelected = true) else node }
            } else {
                nodes
            }
            serverDao.insertServers(toInsert)
            autoBackupManager.backupToVault()
            nodes.size
        } else {
            0
        }
    }

    suspend fun deleteServer(id: String) = withContext(Dispatchers.IO) {
        serverDao.deleteServer(id)
        autoBackupManager.backupToVault()
    }

    suspend fun updateServer(server: ServerNode) = withContext(Dispatchers.IO) {
        serverDao.updateServer(server)
        autoBackupManager.backupToVault()
    }

    suspend fun resetToDefault() = withContext(Dispatchers.IO) {
        serverDao.clearAll()
        subscriptionDao.clearAll()
        initDefaultServers()
    }

    /** Measures the selected TCP, ICMP or Xray tunnel latency mode. */
    suspend fun testPing(server: ServerNode): Long = pingActivity.track(server.id) { testPingInternal(server) }

    private suspend fun testPingInternal(server: ServerNode): Long = withContext(Dispatchers.IO) {
        // Keep the selected measurement mode consistent before and after connecting.
        val bestPing = when (settingsManager.getPingMode().lowercase()) {
            "real_delay", "http", "real" -> testRealDelay(server)
            "icmp" -> testPingIcmp(server)
            else -> testPingTcp(server)
        }
        serverDao.updatePing(server.id, bestPing)
        bestPing
    }
    suspend fun updateServerPing(serverId: String, ping: Long) = withContext(Dispatchers.IO) {
        serverDao.updatePing(serverId, ping)
    }

    suspend fun testPingViaXray(
        testUrl: String = "http://cp.cloudflare.com/generate_204",
        timeoutMs: Int = 3000
    ): Long = withContext(Dispatchers.IO) {
        val proxy = java.net.Proxy(java.net.Proxy.Type.SOCKS, InetSocketAddress(SplateConstants.XRAY_SOCKS_ADDRESS, SplateConstants.XRAY_SOCKS_PORT))
        com.danieldk.splatevpn.vpn.TunnelProbe.measure(proxy, testUrl, timeoutMs, priority = true)
    }
    /**
     * Полноценный Real Delay (HTTP Test) для любого узла, независимо от того, подключен VPN или нет.
     * Аналог функции Real Delay / URL Test в Nekobox, v2rayN и Sing-box:
     * 1. Для текущего активного узла: мгновенный замер через активный SOCKS5 (10808) на http://cp.cloudflare.com/generate_204.
     * 2. Для любого другого (отключенного) узла: запуск изолированного легковесного воркера Xray
     *    на динамическом свободном порту с передачей реального протокольного заголовка и проверкой ответа HTTP 204.
     *
     * Успешный HTTP-ответ подтверждает работу туннеля в момент проверки.
     * Ошибка отдельной проверки не доказывает недоступность сервера.
     */
    suspend fun testRealDelay(
        server: ServerNode,
        testUrl: String = "http://cp.cloudflare.com/generate_204",
        timeoutMs: Int = 5000
    ): Long {
        // Only actual startup and network work consume the budget, not waiting for a free slot.
        val result = realDelayGate.measure(timeoutMs.toLong() + 2500L) {
            pingActivity.track(server.id) { testRealDelayInternal(server, testUrl, timeoutMs) }
        }
        if (result > 0) qualityTracker.recordSuccess(server.id, result)
        else qualityTracker.recordFailure(server.id)
        return result
    }

    private suspend fun testRealDelayInternal(server: ServerNode, testUrl: String, timeoutMs: Int): Long = withContext(Dispatchers.IO) {
        val status = vpnStatusManagerProvider.get()
        val isCurrentConnected = status.connectedServerId == server.id &&
            status.state.value == com.danieldk.splatevpn.vpn.VpnState.CONNECTED

        if (isCurrentConnected) {
            val delay = testPingViaXray(testUrl, timeoutMs)
            serverDao.updatePing(server.id, delay)
            return@withContext delay
        }

        val xrayMgr = xrayCoreManagerProvider.get()
        xrayMgr.ensureAssetsExtracted()
        val xrayBin = xrayMgr.getXrayBinary()
        if (xrayBin == null || !xrayBin.exists()) {
            Log.w(TAG, "RealDelay: Xray binary not available; tunnel verification cannot run")
            serverDao.updatePing(server.id, -1L)
            return@withContext -1L
        }

        val freePort = allocateFreePort()
        if (freePort <= 0) {
            return@withContext -1L
        }

        val configJson = xrayConfigGenerator.generateTestConfig(
            node = server,
            socksPort = freePort,
            xhttpModeOverride = settingsManager.getXhttpMode(),
            enableTlsFragment = settingsManager.isTlsFragmentEnabled(),
            tlsFragmentLength = settingsManager.getTlsFragmentLength(),
            tlsFragmentInterval = settingsManager.getTlsFragmentInterval(),
            tlsFragmentPackets = settingsManager.getTlsFragmentPackets(),
            enableMux = settingsManager.isMuxEnabled(),
            muxConcurrency = settingsManager.getMuxConcurrency()
        )
        val safeHash = kotlin.math.abs(server.id.hashCode())
        val testConfigFile = java.io.File(context.cacheDir, "xray_test_${System.currentTimeMillis()}_$safeHash.json")

        var process: Process? = null
        val outputBuffer = StringBuilder()
        try {
            testConfigFile.writeText(configJson)

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
                Log.w(TAG, "RealDelay: Test Xray process failed to bind port $freePort or crashed for ${server.name}. Output: $outputBuffer")
                serverDao.updatePing(server.id, -1L)
                return@withContext -1L
            }

            // Perform HTTP 204 check via ephemeral SOCKS5 proxy
            val proxy = java.net.Proxy(java.net.Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", freePort))
            val realDelay = com.danieldk.splatevpn.vpn.TunnelProbe.measure(proxy, testUrl, timeoutMs)

            serverDao.updatePing(server.id, realDelay)
            realDelay
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "RealDelay failed for ${server.name}: ${e.message}. Logs: $outputBuffer")
            serverDao.updatePing(server.id, -1L)
            -1L
        } finally {
            try {
                process?.destroyForcibly()
            } catch (_: Exception) {}
            try {
                testConfigFile.delete()
            } catch (_: Exception) {}
        }
    }

    suspend fun testServersRealDelay(
        servers: List<ServerNode>,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Int = withContext(Dispatchers.IO) {
        realDelayBatch.measure(servers, onProgress) { testRealDelay(it) }
    }

    suspend fun testAllRealDelays(
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Int = withContext(Dispatchers.IO) {
        val servers = serverDao.getAllServersSync()
        testServersRealDelay(servers, onProgress)
    }

    private suspend fun testPingIcmp(server: ServerNode): Long = withContext(Dispatchers.IO) {
        var bestPing = -1L
        try {
            val process = Runtime.getRuntime().exec("ping -c 1 -W 2 ${server.address}")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                // Example: 64 bytes from 1.1.1.1: icmp_seq=1 ttl=58 time=14.1 ms
                val match = Regex("time=([0-9.]+)").find(line ?: "")
                if (match != null) {
                    bestPing = match.groupValues[1].toFloat().toLong()
                    break
                }
            }
            process.waitFor()
        } catch (e: Exception) {
            Log.w(TAG, "ICMP Ping failed: ${e.message}")
        }
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

    fun recordTunnelSuccess(serverId: String, delayMs: Long) = qualityTracker.recordSuccess(serverId, delayMs)
    fun recordServerFailure(serverId: String) = qualityTracker.recordFailure(serverId)

    /** Background checks never change selection. Two probes leave room for foreground selection. */
    suspend fun refreshStandbyServers(excludeIds: Set<String> = emptySet()) = withContext(Dispatchers.IO) {
        if (selectionMutex.isLocked) return@withContext
        val selectedId = serverDao.getSelectedServerSync()?.id
        val candidates = getRankedCandidateServers(excludeIds + setOfNotNull(selectedId)).take(2)
        candidates.map { node -> async { measureRealDelaySafely(node) } }.awaitAll()
    }

    /** Commit only if the user has not disabled balancing, changed the node or removed its source. */
    suspend fun activateAutoCandidate(candidate: ServerNode, expectedCurrentId: String): Boolean =
        database.withTransaction {
            if (!settingsManager.isAutoSelectServer() || serverDao.getSelectedServerSync()?.id != expectedCurrentId) {
                return@withTransaction false
            }
            val disabled = subscriptionDao.getAllSubscriptionsSync().filter { !it.enabled }.map { it.id }.toSet()
            if (candidate.subId in disabled || serverDao.getAllServersSync().none { it.id == candidate.id }) {
                return@withTransaction false
            }
            serverDao.setActiveServer(candidate.id)
            settingsManager.setSelectedServerName(candidate.name)
            true
        }

    suspend fun testServersPing(servers: List<ServerNode>) = withContext(Dispatchers.IO) {
        if (servers.isEmpty()) return@withContext
        servers.map { server ->
            async {
                pingConcurrencyLimit.withPermit {
                    testPing(server)
                }
            }
        }.awaitAll()
    }

    suspend fun testAllPings() = withContext(Dispatchers.IO) {
        val servers = serverDao.getAllServersSync()
        testServersPing(servers)
    }

    suspend fun testSubscriptionPings(subId: String): Int = withContext(Dispatchers.IO) {
        val servers = serverDao.getServersBySubIdSync(subId)
        if (servers.isEmpty()) return@withContext 0
        val currentMode = settingsManager.getPingMode().lowercase()
        if (currentMode in listOf("real_delay", "http", "real")) {
            testServersRealDelay(servers)
        } else {
            testServersPing(servers)
        }
        servers.size
    }

    /**
     * Возвращает отранжированный список доступных серверов (лучшие первые),
     * исключая временно недоступные узлы из excludeIds.
     */
    suspend fun getRankedCandidateServers(excludeIds: Set<String> = emptySet()): List<ServerNode> = withContext(Dispatchers.IO) {
        val disabled = subscriptionDao.getAllSubscriptionsSync().filter { !it.enabled }.map { it.id }.toSet()
        eligibleBalancerCandidates(serverDao.getAllServersSync(), excludeIds, disabled, qualityTracker)
            .sortedBy { node ->
                when {
                    qualityTracker.hasFreshSuccess(node.id) -> calculateEffectivePing(node, node.ping)
                    node.ping > 0 -> 1000L + calculateEffectivePing(node, node.ping)
                    node.ping == 0L -> 4000L
                    else -> 10_000L
                }
            }
    }

    /** Prefer fresh, end-to-end verified reserves; exclusions are never relaxed on exhaustion. */
    suspend fun autoSelectFastestServer(
        excludeIds: Set<String> = emptySet(),
        urgent: Boolean = false,
        commitSelection: Boolean = true,
        reuseRecentSelection: Boolean = false
    ): ServerNode? {
        val request = AutoSelectionRequest(excludeIds.toSet(), urgent, commitSelection, listOf(
            settingsManager.getXhttpMode(), settingsManager.isTlsFragmentEnabled(),
            settingsManager.getTlsFragmentLength(), settingsManager.getTlsFragmentInterval(),
            settingsManager.getTlsFragmentPackets(), settingsManager.isMuxEnabled(), settingsManager.getMuxConcurrency()
        ))
        return selectionHandoff.run(request, reuseRecentSelection, isValid = { previous ->
            val eligible = getRankedCandidateServers(excludeIds).firstOrNull { it.id == previous.id }
            eligible != null && qualityTracker.hasFreshSuccess(previous.id, 10_000L) &&
                eligible.copy(ping = previous.ping, isSelected = previous.isSelected) == previous &&
                (!commitSelection || serverDao.getSelectedServerSync()?.id == previous.id)
        }) {
            // Waiting for another caller no longer spends this search's own probe budget.
            val started = android.os.SystemClock.elapsedRealtime()
            Log.i(TAG, "autoSelect: начат поиск рабочего сервера")
            val result = withTimeoutOrNull(if (urgent) 15_000L else 45_000L) {
                selectFastestServerInternal(excludeIds, urgent, commitSelection)
            }
            Log.i(TAG, "autoSelect: поиск завершён за ${android.os.SystemClock.elapsedRealtime() - started} мс: " +
                if (result == null) "рабочий сервер не найден или истёк таймаут" else "сервер проверен")
            result
        }
    }

    private suspend fun selectFastestServerInternal(
        excludeIds: Set<String>, urgent: Boolean, commitSelection: Boolean
    ): ServerNode? = withContext(Dispatchers.IO) {
        val candidates = getRankedCandidateServers(excludeIds)
        if (candidates.isEmpty()) return@withContext null
        val currentSelected = serverDao.getSelectedServerSync()

        if (!urgent && currentSelected != null && candidates.any { it.id == currentSelected.id }) {
            val realPing = measureRealDelaySafely(currentSelected)
            if (realPing > 0 && calculateEffectivePing(currentSelected, realPing, true) <= AUTO_SELECT_FAST_PATH_THRESHOLD_MS) {
                return@withContext currentSelected.copy(ping = realPing)
            }
        }

        // Revalidate a small warm reserve directly, without waiting for a full TCP batch.
        val reserves = candidates.filter {
            it.id != currentSelected?.id && qualityTracker.hasFreshSuccess(it.id)
        }.take(AUTO_SELECT_SHORTLIST_SIZE)
        val reserve = verifyShortlistByRealDelay(reserves, currentSelected?.id)
        if (reserve != null) {
            val (chosen, delayMs) = reserve
            if (commitSelection) setActiveServer(chosen.id)
            return@withContext chosen.copy(ping = delayMs)
        }

        for (batch in candidates.filter { it.id !in reserves.map { reserveNode -> reserveNode.id } }
            .chunked(AUTO_SELECT_BATCH_SIZE)) {
            val responsive = prefilterByTcpWithPings(batch, currentSelected?.id).map { it.first }
            for (shortlist in responsive.chunked(AUTO_SELECT_SHORTLIST_SIZE)) {
                val verified = verifyShortlistByRealDelay(shortlist, currentSelected?.id) ?: continue
                val (chosen, delayMs) = verified
                if (commitSelection) setActiveServer(chosen.id)
                Log.i(TAG, "autoSelect: verified '${chosen.name}', ${delayMs}ms")
                return@withContext chosen.copy(ping = delayMs)
            }
        }
        null
    }

    /**
     * Этап 1: TCP/TLS-префильтр пачки с сохранением замеров задержки.
     */
    private suspend fun prefilterByTcpWithPings(
        batch: List<ServerNode>,
        currentSelectedId: String? = null
    ): List<Pair<ServerNode, Long>> = kotlinx.coroutines.coroutineScope {
        val resultsChannel = Channel<Pair<ServerNode, Long>>(batch.size)
        val pingJobs = batch.map { server ->
            launch {
                val ping = try {
                    pingActivity.track(server.id) { testPingTcp(server, timeout = AUTO_SELECT_TCP_TIMEOUT_MS) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "autoSelect: TCP-префильтр '${server.name}' завершился ошибкой: ${e.message}")
                    -1L
                }
                resultsChannel.send(server to ping)
            }
        }

        val responsive = mutableListOf<Pair<ServerNode, Long>>()
        withTimeoutOrNull(AUTO_SELECT_TCP_TIMEOUT_MS + 100L) {
            for (i in batch.indices) {
                val (server, ping) = resultsChannel.receive()
                if (ping <= 0 || qualityTracker.isQuarantined(server.id)) continue
                responsive += server to ping
            }
        }

        pingJobs.forEach { job ->
            if (job.isActive) job.cancel()
        }
        resultsChannel.close()

        responsive
            .sortedBy { (server, ping) -> calculateEffectivePing(server, ping, isCurrentlyActive = (server.id == currentSelectedId)) }
    }

    /**
     * Этап 1: TCP/TLS-префильтр пачки. Возвращает до [AUTO_SELECT_SHORTLIST_SIZE] узлов, принявших соединение.
     */
    private suspend fun prefilterByTcp(
        batch: List<ServerNode>,
        currentSelectedId: String? = null
    ): List<ServerNode> = prefilterByTcpWithPings(batch, currentSelectedId)
        .take(AUTO_SELECT_SHORTLIST_SIZE)
        .map { (server, _) -> server }

    /**
     * Этап 2: параллельная Real Delay верификация шорт-листа (HTTP 204 через изолированный Xray).
     * Возвращает узел с лучшим скором по реальной задержке либо null, если ни один не пропустил трафик.
     */
    private suspend fun verifyShortlistByRealDelay(
        shortlist: List<ServerNode>,
        currentSelectedId: String? = null
    ): Pair<ServerNode, Long>? =
        selectResponsiveCandidate(shortlist, score = { server, delay ->
            calculateEffectivePing(server, delay, isCurrentlyActive = server.id == currentSelectedId)
        }) { server ->
            measureRealDelaySafely(server)
        }
    /**
     * Фоновая оптимизация балансировщика во время активной сессии VPN:
     * Ищет узел, который СУЩЕСТВЕННО лучше текущего.
     * Использует анти-флаппинг гистерезис: новый узел должен давать выигрыш >= [minImprovementRatio] (30%)
     * И уменьшать задержку минимум на [minMsSaved] (40мс).
     */
    suspend fun findBetterServerIfAvailable(
        currentServer: ServerNode,
        currentPing: Long,
        excludeIds: Set<String> = emptySet(),
        minImprovementRatio: Float = 0.30f,
        minMsSaved: Long = 40L
    ): ServerNode? = withContext(Dispatchers.IO) {
        if (currentPing <= 65L) {
            // Текущий узел уже отличный (< 65мс), переключать нет смысла
            return@withContext null
        }

        val allServers = serverDao.getAllServersSync()
        val disabledSubIds = subscriptionDao.getAllSubscriptionsSync().filter { !it.enabled }.map { it.id }.toSet()
        val candidates = allServers.filter {
            it.id != currentServer.id &&
            it.id !in excludeIds &&
            !qualityTracker.isQuarantined(it.id) &&
            it.subId !in disabledSubIds &&
            (it.ping in 1..(currentPing - minMsSaved) || (it.ping == 0L && currentPing > 180L))
        }

        if (candidates.isEmpty()) return@withContext null

        val topCandidates = candidates
            .sortedBy { calculateEffectivePing(it, if (it.ping > 0) it.ping else 100L) }
            .take(5)

        val verified = verifyShortlistByRealDelay(topCandidates, currentSelectedId = currentServer.id) ?: return@withContext null
        val (bestCandidate, bestRealDelay) = verified

        val currentScore = calculateEffectivePing(currentServer, currentPing, isCurrentlyActive = true)
        val candidateScore = calculateEffectivePing(bestCandidate, bestRealDelay, isCurrentlyActive = false)

        val msSaved = currentScore - candidateScore
        val improvement = msSaved.toFloat() / currentScore.coerceAtLeast(1L)

        if (msSaved >= minMsSaved && improvement >= minImprovementRatio) {
            Log.i(
                TAG,
                "findBetterServer: найден узел '${bestCandidate.name}' (${bestRealDelay}ms, score $candidateScore) взамен '${currentServer.name}' (${currentPing}ms, score $currentScore). Выигрыш: ${msSaved}ms / ${(improvement * 100).toInt()}%"
            )
            return@withContext bestCandidate.copy(ping = bestRealDelay)
        }

        null
    }

    private suspend fun measureRealDelaySafely(server: ServerNode): Long = try {
        testRealDelay(server, timeoutMs = AUTO_SELECT_REAL_DELAY_TIMEOUT_MS)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "autoSelect: Real Delay '${server.name}' завершился ошибкой: ${e.message}")
        serverDao.updatePing(server.id, -1L)
        -1L
    }

    private val subscriptionHttpClient = okhttp3.OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private fun executeSubscriptionRequest(url: String, userAgent: String): okhttp3.Response {
        val request = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "*/*")
            .build()
        return subscriptionHttpClient.newCall(request).execute()
    }

    private fun safeSubscriptionHost(url: String): String = try {
        java.net.URI(url).host ?: "сервер подписки"
    } catch (_: Exception) {
        "сервер подписки"
    }

    private fun subscriptionNetworkError(error: Throwable, url: String): Exception {
        val causes = generateSequence(error) { it.cause }.take(12).toList()
        val host = safeSubscriptionHost(url)
        return when {
            causes.any { it is java.security.cert.CertificateExpiredException } ->
                IOException("TLS-сертификат сервера $host истёк. Обновите сертификат на сервере подписки.", error)
            causes.any {
                it is javax.net.ssl.SSLHandshakeException ||
                        it is javax.net.ssl.SSLPeerUnverifiedException ||
                        it is java.security.cert.CertificateException
            } -> IOException(
                "Сертификат HTTPS сервера $host не прошёл проверку. Проверьте его срок действия, цепочку доверия и имя домена.",
                error
            )
            else -> IOException(error.localizedMessage ?: error.message ?: "Ошибка загрузки подписки", error)
        }
    }

    private fun decodeProfileTitle(value: String?): String? {
        val title = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!title.startsWith("base64:", ignoreCase = true)) return title
        val encoded = title.substringAfter(':').trim()
        return try {
            val decoded = android.util.Base64.decode(
                encoded,
                android.util.Base64.DEFAULT or android.util.Base64.URL_SAFE
            ).toString(Charsets.UTF_8)
            decoded.lineSequence().firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun importSubscription(subUrl: String, subTitle: String? = null): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val input = subUrl.trim()
            if (input.isBlank()) {
                return@withContext Result.failure(Exception("URL подписки пуст"))
            }
            if (LinkParser.isEncryptedSubscriptionLink(input)) {
                return@withContext Result.failure(
                    Exception("Зашифрованные ссылки Happ/INCY пока нельзя открыть напрямую. Используйте обычную HTTPS-ссылку подписки.")
                )
            }
            val trimmedUrl = LinkParser.normalizeSubscriptionUrl(input)
                ?: return@withContext Result.failure(Exception("Ожидается HTTPS/HTTP-ссылка подписки или ссылка Happ/INCY с такой ссылкой внутри"))

            Log.d(TAG, "importSubscription: Fetching subscription")

            // First attempt with v2rayNG User-Agent
            var response = executeSubscriptionRequest(trimmedUrl, userAgent = "v2rayNG/1.8.5")
            var rawContent = response.body?.string() ?: ""
            var responseCode = response.code
            var responseMessage = response.message

            // Fallback to ClashMeta if 4xx or empty response
            if ((!response.isSuccessful || rawContent.isBlank()) && response.code in 400..499) {
                Log.w(TAG, "importSubscription: v2rayNG returned HTTP ${response.code}, retrying with ClashMeta...")
                response.close()
                response = executeSubscriptionRequest(trimmedUrl, userAgent = "ClashMeta/1.18.0")
                rawContent = response.body?.string() ?: ""
                responseCode = response.code
                responseMessage = response.message
            }

            val userInfoHeader = response.header("Subscription-Userinfo") ?: response.header("subscription-userinfo")
            val profileTitleHeader = response.header("profile-title") ?: response.header("subscription-name")
            val isSuccessful = response.isSuccessful
            response.close()

            if (!isSuccessful) {
                return@withContext Result.failure(Exception("HTTP Error $responseCode: $responseMessage"))
            }

            if (rawContent.isBlank()) {
                return@withContext Result.failure(Exception("Пустой ответ от сервера подписки"))
            }

            val userInfo = parseSubscriptionUserInfo(userInfoHeader)

            val parsedHost = try { java.net.URI(trimmedUrl).host } catch (e: Exception) { null }
            val subId = UUID.randomUUID().toString()
            val subName = subTitle?.takeIf { it.isNotBlank() }
                ?: decodeProfileTitle(profileTitleHeader)
                ?: parsedHost
                ?: "Подписка"

            var nodes = LinkParser.parseSubscription(rawContent, subId = subId, subName = subName)

            // If 0 nodes parsed, try fallback to ClashMeta
            if (nodes.isEmpty()) {
                Log.w(TAG, "importSubscription: 0 nodes parsed from v2rayNG body, retrying with ClashMeta...")
                try {
                    val clashResp = executeSubscriptionRequest(trimmedUrl, userAgent = "ClashMeta/1.18.0")
                    try {
                        if (clashResp.isSuccessful) {
                            val clashBody = clashResp.body?.string() ?: ""
                            if (clashBody.isNotBlank()) {
                                val clashNodes = LinkParser.parseSubscription(clashBody, subId = subId, subName = subName)
                                if (clashNodes.isNotEmpty()) {
                                    nodes = clashNodes
                                    Log.i(TAG, "importSubscription: Successfully parsed ${nodes.size} nodes via ClashMeta!")
                                }
                            }
                        }
                    } finally {
                        clashResp.close()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "ClashMeta fallback failed: ${e.message}")
                }
            }

            Log.i(TAG, "importSubscription: Total parsed nodes = ${nodes.size}")

            if (nodes.isNotEmpty()) {
                val currentServers = serverDao.getAllServersSync()
                val toInsert = if (currentServers.isEmpty()) {
                    nodes.mapIndexed { idx, node -> if (idx == 0) node.copy(isSelected = true) else node }
                } else {
                    nodes
                }
                serverDao.insertServers(toInsert)
                subscriptionDao.insertSubscription(
                    SubscriptionEntity(
                        id = subId,
                        name = subName,
                        url = trimmedUrl,
                        lastUpdated = System.currentTimeMillis(),
                        nodeCount = nodes.size,
                        autoUpdate = true,
                        uploadBytes = userInfo.upload,
                        downloadBytes = userInfo.download,
                        totalBytes = userInfo.total,
                        expireTimestamp = userInfo.expire
                    )
                )
                settingsManager.setSubscriptionUrl(trimmedUrl)
                autoBackupManager.backupToVault()
                Result.success(nodes.size)
            } else {
                Log.e(TAG, "importSubscription: No valid nodes parsed")
                Result.failure(Exception("В подписке не найдено подходящих узлов (VLESS, VMess, SS, Trojan, Clash)"))
            }
        } catch (e: Exception) {
            val url = LinkParser.normalizeSubscriptionUrl(subUrl) ?: subUrl
            Log.e(TAG, "importSubscription failed for ${safeSubscriptionHost(url)}: ${e.javaClass.simpleName}")
            Result.failure(subscriptionNetworkError(e, url))
        }
    }

    suspend fun updateSubscription(subId: String): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val sub = subscriptionDao.getSubscriptionById(subId)
                ?: return@withContext Result.failure(Exception("Подписка не найдена"))

            if (sub.url.isBlank()) {
                return@withContext Result.failure(Exception("У подписки нет URL для обновления"))
            }

            if (LinkParser.isEncryptedSubscriptionLink(sub.url)) {
                return@withContext Result.failure(Exception("Зашифрованные ссылки Happ/INCY пока нельзя обновлять напрямую. Добавьте обычную HTTPS-ссылку подписки."))
            }
            val trimmedUrl = LinkParser.normalizeSubscriptionUrl(sub.url)
                ?: return@withContext Result.failure(Exception("Некорректный URL подписки"))
            Log.d(TAG, "updateSubscription: Fetching subscription")

            val isCidrSub = sub.id == SplateConstants.PRESET_SUB_CIDR_ID ||
                    trimmedUrl.contains("WHITE-CIDR-RU-all.txt", ignoreCase = true) ||
                    trimmedUrl.contains("white.igareck.net", ignoreCase = true)

            val candidateUrls = if (isCidrSub) {
                listOf(trimmedUrl, SplateConstants.PRESET_SUB_CIDR_FALLBACK_URL).distinct()
            } else {
                listOf(trimmedUrl)
            }

            var rawContent = ""
            var userInfoHeader: String? = null
            var profileTitleHeader: String? = null
            var lastNetworkError: Exception? = null
            var responseCode = 0
            var responseMessage = ""
            var isSuccessful = false

            for ((attemptIdx, targetUrl) in candidateUrls.withIndex()) {
                try {
                    Log.d(TAG, "updateSubscription: Attempt ${attemptIdx + 1}/${candidateUrls.size} fetching ${safeSubscriptionHost(targetUrl)}")
                    var response = executeSubscriptionRequest(targetUrl, userAgent = "v2rayNG/1.8.5")
                    var bodyText = response.body?.string() ?: ""
                    responseCode = response.code
                    responseMessage = response.message

                    if ((!response.isSuccessful || bodyText.isBlank()) && response.code in 400..499) {
                        response.close()
                        response = executeSubscriptionRequest(targetUrl, userAgent = "ClashMeta/1.18.0")
                        bodyText = response.body?.string() ?: ""
                        responseCode = response.code
                        responseMessage = response.message
                    }

                    val header = response.header("Subscription-Userinfo") ?: response.header("subscription-userinfo")
                    val titleHeader = response.header("profile-title") ?: response.header("subscription-name")
                    val ok = response.isSuccessful
                    response.close()

                    if (ok && bodyText.isNotBlank()) {
                        rawContent = bodyText
                        userInfoHeader = header
                        profileTitleHeader = titleHeader
                        isSuccessful = true
                        Log.i(TAG, "updateSubscription: Успешно получено ${bodyText.length} байт из ${safeSubscriptionHost(targetUrl)}")
                        break
                    } else {
                        Log.w(TAG, "updateSubscription: Запрос к ${safeSubscriptionHost(targetUrl)} вернул HTTP $responseCode: $responseMessage (пусто=${bodyText.isBlank()})")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastNetworkError = e
                    Log.w(TAG, "updateSubscription: Ошибка сети при запросе ${safeSubscriptionHost(targetUrl)}: ${e.javaClass.simpleName}")
                }
            }

            if (!isSuccessful) {
                lastNetworkError?.let { return@withContext Result.failure(subscriptionNetworkError(it, trimmedUrl)) }
                return@withContext Result.failure(Exception("HTTP Error $responseCode: $responseMessage"))
            }

            if (rawContent.isBlank()) {
                return@withContext Result.failure(Exception("Пустой ответ от сервера подписки"))
            }

            val userInfo = parseSubscriptionUserInfo(userInfoHeader)

            var nodes = LinkParser.parseSubscription(rawContent, subId = sub.id, subName = sub.name)

            if (nodes.isEmpty() && isCidrSub && trimmedUrl != SplateConstants.PRESET_SUB_CIDR_FALLBACK_URL) {
                // Yandex translate mirror might have returned HTML error without throwing HTTP error.
                // Try fallback raw GitHub mirror directly!
                try {
                    Log.w(TAG, "updateSubscription: CIDR вернул 0 узлов, пробуем резервный GitHub URL...")
                    val fbResp = executeSubscriptionRequest(SplateConstants.PRESET_SUB_CIDR_FALLBACK_URL, userAgent = "v2rayNG/1.8.5")
                    try {
                        if (fbResp.isSuccessful) {
                            val fbBody = fbResp.body?.string() ?: ""
                            if (fbBody.isNotBlank()) {
                                val fbNodes = LinkParser.parseSubscription(fbBody, subId = sub.id, subName = sub.name)
                                if (fbNodes.isNotEmpty()) {
                                    nodes = fbNodes
                                }
                            }
                        }
                    } finally {
                        fbResp.close()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "updateSubscription: Резервный GitHub CIDR также вернул ошибку: ${e.message}")
                }
            }

            if (nodes.isEmpty() && isCidrSub && trimmedUrl != SplateConstants.PRESET_SUB_CIDR_FALLBACK_URL) {
                // Yandex translate mirror might have returned HTML error without throwing HTTP error.
                // Try fallback raw GitHub mirror directly!
                try {
                    Log.w(TAG, "updateSubscription: CIDR вернул 0 узлов, пробуем резервный GitHub URL...")
                    val fbResp = executeSubscriptionRequest(SplateConstants.PRESET_SUB_CIDR_FALLBACK_URL, userAgent = "v2rayNG/1.8.5")
                    try {
                        if (fbResp.isSuccessful) {
                            val fbBody = fbResp.body?.string() ?: ""
                            if (fbBody.isNotBlank()) {
                                val fbNodes = LinkParser.parseSubscription(fbBody, subId = sub.id, subName = sub.name)
                                if (fbNodes.isNotEmpty()) {
                                    nodes = fbNodes
                                }
                            }
                        }
                    } finally {
                        fbResp.close()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "updateSubscription: Резервный GitHub CIDR также вернул ошибку: ${e.message}")
                }
            }

            if (nodes.isEmpty()) {
                try {
                    val clashResp = executeSubscriptionRequest(trimmedUrl, userAgent = "ClashMeta/1.18.0")
                    try {
                        if (clashResp.isSuccessful) {
                            val clashBody = clashResp.body?.string() ?: ""
                            if (clashBody.isNotBlank()) {
                                val clashNodes = LinkParser.parseSubscription(clashBody, subId = sub.id, subName = sub.name)
                                if (clashNodes.isNotEmpty()) {
                                    nodes = clashNodes
                                }
                            }
                        }
                    } finally {
                        clashResp.close()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "updateSubscription: ClashMeta fallback failed: ${e.message}")
                }
            }

            if (nodes.isNotEmpty()) {
                val saved = database.withTransaction {
                    val latestSub = subscriptionDao.getSubscriptionById(sub.id)
                        ?: return@withTransaction false
                    check(latestSub.url == sub.url) { "Subscription URL changed. Please update again." }
                    nodes = SubscriptionNodeMerger.merge(serverDao.getServersBySubIdSync(sub.id), nodes)
                        .map { it.copy(subName = latestSub.name) }
                    serverDao.deleteServersBySubId(sub.id)
                    serverDao.insertServers(nodes)
                    if (serverDao.getSelectedServerSync() == null) {
                        serverDao.setActiveServer(nodes.first().id)
                    }
                    subscriptionDao.insertSubscription(
                        latestSub.copy(
                            lastUpdated = System.currentTimeMillis(),
                            nodeCount = nodes.size,
                            name = latestSub.name,
                            uploadBytes = if (userInfo.total > 0L || userInfo.upload > 0L) userInfo.upload else latestSub.uploadBytes,
                            downloadBytes = if (userInfo.total > 0L || userInfo.download > 0L) userInfo.download else latestSub.downloadBytes,
                            totalBytes = if (userInfo.total > 0L) userInfo.total else latestSub.totalBytes,
                            expireTimestamp = if (userInfo.expire > 0L) userInfo.expire else latestSub.expireTimestamp
                        )
                    )
                    true
                }
                if (!saved) return@withContext Result.failure(Exception("Подписка удалена во время обновления"))
                autoBackupManager.backupToVault()
                Result.success(nodes.size)
            } else {
                Result.failure(Exception("В обновлении не найдено подходящих узлов"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "updateSubscription failed: ${e.javaClass.simpleName}")
            Result.failure(subscriptionNetworkError(e, ""))
        }
    }

    private data class SubscriptionUserInfo(
        val upload: Long = 0L,
        val download: Long = 0L,
        val total: Long = 0L,
        val expire: Long = 0L
    )

    private fun parseSubscriptionUserInfo(headerValue: String?): SubscriptionUserInfo {
        if (headerValue.isNullOrBlank()) return SubscriptionUserInfo()
        var upload = 0L
        var download = 0L
        var total = 0L
        var expire = 0L

        headerValue.split(';').forEach { part ->
            val eqIdx = part.indexOf('=')
            if (eqIdx > 0) {
                val key = part.substring(0, eqIdx).trim().lowercase()
                val value = part.substring(eqIdx + 1).trim().toLongOrNull() ?: 0L
                when (key) {
                    "upload" -> upload = value
                    "download" -> download = value
                    "total" -> total = value
                    "expire" -> expire = value
                }
            }
        }
        return SubscriptionUserInfo(upload, download, total, expire)
    }

    suspend fun deleteSubscription(subId: String) = withContext(Dispatchers.IO) {
        serverDao.deleteServersBySubId(subId)
        subscriptionDao.deleteSubscription(subId)
        autoBackupManager.backupToVault()
    }

    suspend fun setSubscriptionEnabled(subId: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        subscriptionDao.updateSubscriptionEnabled(subId, enabled)
        autoBackupManager.backupToVault()
    }

    suspend fun setSubscriptionAutoUpdate(subId: String, autoUpdate: Boolean) = withContext(Dispatchers.IO) {
        subscriptionDao.updateSubscriptionAutoUpdate(subId, autoUpdate)
        autoBackupManager.backupToVault()
    }

    suspend fun editSubscription(subId: String, name: String, url: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            require(name.isNotBlank()) { "Subscription name is required" }
            val normalized = LinkParser.normalizeSubscriptionUrl(url)
            val validUrl = normalized?.let { runCatching { java.net.URI(it).host?.isNotBlank() == true }.getOrDefault(false) } == true
            require(validUrl || LinkParser.isEncryptedSubscriptionLink(url)) { "Invalid subscription URL" }
            database.withTransaction {
                checkNotNull(subscriptionDao.getSubscriptionById(subId)) { "Subscription no longer exists" }
                subscriptionDao.updateSubscriptionDetails(subId, name.trim(), normalized ?: url.trim())
                serverDao.updateSubscriptionName(subId, name.trim())
            }
            autoBackupManager.backupToVault()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateAllSubscriptions(): Int = refreshSubscriptions(manual = false).updated

    suspend fun refreshSubscriptions(manual: Boolean): SubscriptionUpdateReport = withContext(Dispatchers.IO) {
        val subs = subscriptionsToUpdate(subscriptionDao.getAllSubscriptionsSync(), manual)
        var totalUpdated = 0
        val failures = mutableListOf<Pair<String, String>>()
        subs.forEach { sub ->
            val res = updateSubscription(sub.id)
            if (res.isSuccess) {
                totalUpdated++
            } else {
                failures += sub.name to (res.exceptionOrNull()?.localizedMessage ?: "Update failed")
            }
        }
        settingsManager.setLastAutoUpdateTime(System.currentTimeMillis())
        SubscriptionUpdateReport(totalUpdated, failures)
    }


}
