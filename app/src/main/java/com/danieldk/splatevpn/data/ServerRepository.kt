package com.danieldk.splatevpn.data
import kotlinx.coroutines.async

import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import com.danieldk.splatevpn.SplateConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class ServerRepository @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val serverDao: ServerDao,
    private val subscriptionDao: SubscriptionDao,
    private val settingsManager: SettingsManager,
    private val autoBackupManager: AutoBackupManager,
    private val database: AppDatabase,
    private val probes: ServerProbeRepository,
    private val performance: CandidatePerformanceRepository,
    private val selection: ServerSelectionCoordinator,
    private val subscriptionsRepository: SubscriptionRepository
) {
    private val TAG = "ServerRepository"
    private val presetInitializationMutex = Mutex()
    val activePingIds get() = probes.activePingIds
    val sessionPingResults get() = probes.sessionPingResults
    val verifiedDelays get() = probes.verifiedDelays
    val candidateMeasurements get() = performance.measurements
    fun candidateMeasurement(node: ServerNode) = performance.fresh(node, settingsManager.getBalancerPreferences().copy(metric = BalancerMetric.LATENCY))
    internal fun speedBudgetResumesAt() = performance.speedBudgetResumesAt()
    internal fun candidateMeasurementSnapshot(nodes: List<ServerNode>) = performance.freshSnapshot(nodes)
    internal suspend fun qualifyQuickServers(nodes: List<ServerNode>): Unit = kotlinx.coroutines.coroutineScope {
        val prefs = settingsManager.getBalancerPreferences()
        if (prefs.metric == BalancerMetric.DOWNLOAD || prefs.needsServiceQualification) {
            nodes.take(6).map { node -> async {
                if (probes.hasFreshTunnelProof(node, 60_000)) performance.qualify(node, prefs) else null
            } }.forEach { it.await() }
        }
    }

    val allServers: Flow<List<ServerNode>> = serverDao.getAllServers()
    val selectedServer: Flow<ServerNode?> = serverDao.getSelectedServer().onEach { selected ->
        // Refresh the widget cache after startup and subscription renames as well as manual selection.
        val name = selected?.name.orEmpty()
        if (settingsManager.getSelectedServerName() != name) settingsManager.setSelectedServerName(name)
    }
    val updatingSubscriptionIds get() = subscriptionsRepository.updatingIds
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
     * 2. Инициализирует локальные снимки встроенных подписок, включая три источника белых списков.
     */
    suspend fun initDefaultServers(restoreMissingPresets: Boolean = false) = withContext(Dispatchers.IO) {
        val restoredCount = autoBackupManager.tryAutoRestoreIfEmpty()
        if (restoredCount > 0) {
            Log.i(TAG, "initDefaultServers: Автоматически восстановлено $restoredCount узлов из резервной копии!")
        }
        subscriptionsRepository.pruneExpiredRetainedNodes()
        if (!settingsManager.needsOnboarding.value) initPresetSubscriptions(restoreMissing = restoreMissingPresets)
    }

    suspend fun initPresetSubscriptions(forceRefresh: Boolean = false, restoreMissing: Boolean = false, refreshFromNetwork: Boolean = true) = withContext(Dispatchers.IO) {
        presetInitializationMutex.withLock { initPresetSubscriptionsLocked(forceRefresh, restoreMissing, refreshFromNetwork) }
    }

    private suspend fun initPresetSubscriptionsLocked(forceRefresh: Boolean, restoreMissing: Boolean, refreshFromNetwork: Boolean) = withContext(Dispatchers.IO) {
        val initialized = mapOf(
            PresetGroup.LEGACY to settingsManager.hasInitializedPresetSubscriptions(),
            PresetGroup.UNIVERSAL to settingsManager.hasInitializedUniversalWhitelist(),
            PresetGroup.EXPANDED_WHITELISTS to settingsManager.hasInitializedExpandedWhitelists(),
            PresetGroup.SPONSOR to settingsManager.hasInitializedSponsorSubscription(),
            PresetGroup.BLACK_LISTS to settingsManager.hasInitializedBlackLists(),
            PresetGroup.RKP to settingsManager.hasInitializedRkpSubscriptions()
        )
        val presets = builtInSubscriptions.filter {
            it.group == PresetGroup.SPONSOR || settingsManager.regionalPresetsEnabled() || restoreMissing || forceRefresh
        }
        if (!forceRefresh && !restoreMissing && presets.all { initialized[it.group] == true }) return@withContext

        try {
            val updateIds = database.withTransaction {
                val updates = mutableListOf<String>()
                for (preset in presets) {
                    val existing = subscriptionDao.getSubscriptionById(preset.id)
                    val newGroup = initialized[preset.group] != true
                    if (existing == null && (newGroup || restoreMissing || forceRefresh)) {
                        if (preset.group == PresetGroup.SPONSOR || preset.group == PresetGroup.RKP) {
                            val normalizedPreset = LinkParser.normalizeSubscriptionUrl(preset.url)
                            val duplicate = subscriptionDao.getAllSubscriptionsSync().firstOrNull {
                                it.url == preset.url || normalizedPreset != null &&
                                    LinkParser.normalizeSubscriptionUrl(it.url) == normalizedPreset
                            }
                            if (duplicate != null) { updates += duplicate.id; continue }
                        }
                        val nodes = if (preset.asset.isBlank()) emptyList() else
                            parseUsableSubscription(readAssetString(preset.asset), preset.id, preset.name)
                        check(preset.asset.isBlank() || nodes.isNotEmpty()) { "Bundled subscription has no valid nodes: " + preset.id }
                        // An APK snapshot is available offline, but is not a successful network update.
                        subscriptionDao.insertSubscription(SubscriptionEntity(
                            id = preset.id, name = preset.name, url = preset.url, lastUpdated = 0L,
                            nodeCount = nodes.size, autoUpdate = true,
                            publisherMetadataJson = if (preset.group == PresetGroup.SPONSOR)
                                SubscriptionMetadata(title = preset.name).encode() else ""
                        ))
                        serverDao.insertServers(nodes)
                        updates += preset.id
                    } else if (existing != null && (newGroup || forceRefresh)) {
                        // Keep edited URLs, names, switches, node IDs and the selected server.
                        // Restoring presets only re-creates missing sources; existing data stays
                        // usable until a fully parsed network response replaces it transactionally.
                        updates += preset.id
                    }
                }
                if (serverDao.getSelectedServerSync() == null) {
                    serverDao.getAllServersSync().firstOrNull()?.let { serverDao.setActiveServer(it.id) }
                }
                updates
            }
            if (presets.any { it.group == PresetGroup.LEGACY }) settingsManager.setInitializedPresetSubscriptions(true)
            if (presets.any { it.group == PresetGroup.UNIVERSAL }) settingsManager.setInitializedUniversalWhitelist()
            if (presets.any { it.group == PresetGroup.EXPANDED_WHITELISTS }) settingsManager.setInitializedExpandedWhitelists()
            if (presets.any { it.group == PresetGroup.SPONSOR }) settingsManager.setInitializedSponsorSubscription()
            if (presets.any { it.group == PresetGroup.BLACK_LISTS }) settingsManager.setInitializedBlackLists()
            if (presets.any { it.group == PresetGroup.RKP }) settingsManager.setInitializedRkpSubscriptions()
            autoBackupManager.backupToVault()

            if (refreshFromNetwork && (forceRefresh || settingsManager.getAutoUpdateInterval() != "off")) {
                CoroutineScope(Dispatchers.IO).launch {
                    for (id in updateIds) {
                        try {
                            val subscription = subscriptionDao.getSubscriptionById(id)
                            if (subscription?.enabled == true && subscription.autoUpdate) updateSubscription(id)
                        } catch (e: CancellationException) { throw e
                        } catch (_: Exception) {
                            Log.w(TAG, "Background update of built-in subscription failed")
                        }
                    }
                }
            }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            Log.e(TAG, "initPresetSubscriptions failed: " + e.message, e)
        }
    }

    suspend fun restorePresetSubscriptions(): Int = withContext(Dispatchers.IO) {
        initPresetSubscriptions(forceRefresh = true)
        SplateConstants.PRESET_SUB_IDS.sumOf { serverDao.getServersBySubIdSync(it).size }
    }

    suspend fun setActiveServer(id: String) {
        settingsManager.invalidateServerSelection()
        withContext(Dispatchers.IO) {
        if (serverDao.getServerById(id)?.let { settingsManager.isServerEnabled(it) } != true) return@withContext
        serverDao.setActiveServer(id)
        serverDao.getSelectedServerSync()?.let {
            settingsManager.setSelectedServerName(it.name)
        }
        }
    }

    suspend fun getActiveServerSync(): ServerNode? = withContext(Dispatchers.IO) {
        val selected = serverDao.getSelectedServerSync()
        val disabledSubIds = subscriptionDao.getAllSubscriptionsSync().filter { !it.enabled }.map { it.id }.toSet()
        if (selected != null && settingsManager.isServerEnabled(selected) && selected.subId !in disabledSubIds) {
            return@withContext selected
        }
        val disabled = settingsManager.disabledServerIds.value
        val now = System.currentTimeMillis()
        val fallback = serverDao.getAllServersSync().firstOrNull { it.subId !in disabledSubIds && settingsManager.isServerEnabled(it, disabled, now) }
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
            // Validate the entire batch before writing anything; keep saved profiles untouched on error.
            nodes.forEach { probes.validateImportedProfile(it) }
            database.withTransaction {
                val all = serverDao.getAllServersSync()
                val toInsert = if (all.isEmpty()) {
                    nodes.mapIndexed { idx, node -> if (idx == 0) node.copy(isSelected = true) else node }
                } else {
                    nodes
                }
                serverDao.insertServers(toInsert)
            }
            autoBackupManager.backupToVault()
            nodes.size
        } else {
            0
        }
    }

    suspend fun deleteServer(id: String) = withContext(Dispatchers.IO) {
        autoBackupManager.deleteAndBackup { serverDao.deleteServer(id) }
    }

    suspend fun deleteServers(ids: Collection<String>): Int = withContext(Dispatchers.IO) {
        val distinctIds = ids.asSequence().filter { it.isNotBlank() }.distinct().toList()
        if (distinctIds.isEmpty()) return@withContext 0
        autoBackupManager.deleteAndBackup {
            database.withTransaction {
                distinctIds.chunked(500).forEach { chunk ->
                    serverDao.deleteServers(chunk)
                }
            }
        }
        distinctIds.size
    }

    suspend fun updateServer(server: ServerNode) = withContext(Dispatchers.IO) {
        var connectionChanged = false
        database.withTransaction {
            val current = checkNotNull(serverDao.getServerById(server.id)) { "Server no longer exists" }
            require(!isProtectedWtpn(current.subId, subscriptionDao.getSubscriptionById(current.subId)?.url)) {
                "WTPN servers are managed by the subscription provider"
            }
            val candidate = ServerEditPolicy.prepare(current, server)
            val updated = if (SubscriptionNodeMerger.sameConnection(current, candidate)) {
                candidate.copy(lastVerifiedAt = current.lastVerifiedAt, lastVerificationFailedAt = current.lastVerificationFailedAt,
                    isRetained = current.isRetained, retainedAt = current.retainedAt)
            } else {
                probes.validateImportedProfile(candidate)
                connectionChanged = true
                candidate.copy(ping = 0, lastVerifiedAt = 0, lastVerificationFailedAt = 0,
                    isRetained = current.isRetained, retainedAt = current.retainedAt)
            }
            serverDao.updateServer(updated)
        }
        if (connectionChanged) probes.invalidateServerQuality(server.id)
        autoBackupManager.backupToVault()
    }

    suspend fun resetToDefault() = withContext(Dispatchers.IO) {
        autoBackupManager.deleteAndBackup {
            serverDao.clearAll()
            subscriptionDao.clearAll()
            subscriptionDao.clearUpdateHistory()
        }
        initPresetSubscriptions(forceRefresh = true)
    }

    suspend fun testPing(server: ServerNode) = probes.testPing(server)
    suspend fun updateServerPingIfUnchanged(server: ServerNode, ping: Long) = probes.savePingIfUnchanged(server, ping)

    suspend fun updateServerPing(serverId: String, ping: Long) = probes.updateServerPing(serverId, ping)
    suspend fun testPingViaXray(testUrl: String = com.danieldk.splatevpn.vpn.TunnelProbe.PRIMARY_URL, timeoutMs: Int = 3000) = probes.testPingViaXray(testUrl, timeoutMs)
    suspend fun testRealDelay(server: ServerNode, testUrl: String = com.danieldk.splatevpn.vpn.TunnelProbe.PRIMARY_URL, timeoutMs: Int = 5000) = probes.testRealDelay(server, testUrl, timeoutMs)
    suspend fun testServersRealDelay(servers: List<ServerNode>, onProgress: (Int, Int) -> Unit = { _, _ -> }): ServerPingSummary = probes.testServersRealDelay(servers, onProgress)
    suspend fun testAllRealDelays(onProgress: (Int, Int) -> Unit = { _, _ -> }): ServerPingSummary = probes.testAllRealDelays(onProgress)
    suspend fun testServersPing(servers: List<ServerNode>): ServerPingSummary = probes.testServersPing(servers)
    suspend fun testAllPings(): ServerPingSummary = probes.testAllPings()
    suspend fun testSubscriptionPings(subId: String): ServerPingSummary = probes.testSubscriptionPings(subId)
    fun calculateEffectivePing(server: ServerNode, rawPing: Long, isCurrentlyActive: Boolean = false) = probes.calculateEffectivePing(server, rawPing, isCurrentlyActive)
    suspend fun recordTunnelSuccess(server: ServerNode, delayMs: Long) = probes.recordTunnelSuccess(server, delayMs)
    suspend fun recordServerFailure(server: ServerNode): Boolean = probes.recordServerFailure(server)
    fun requiresTlsFragment(serverId: String): Boolean = probes.requiresTlsFragment(serverId)
    fun qualifiesForGoal(server: ServerNode) = selection.qualifiesForGoal(server)

    suspend fun refreshStandbyServers(excludeIds: Set<String> = emptySet(), sampleDownload: Boolean = false) =
        selection.refreshStandbyServers(excludeIds, sampleDownload)
    suspend fun activateAutoCandidate(candidate: ServerNode, expectedCurrent: ServerNode?, stillCurrent: () -> Boolean) =
        selection.activateAutoCandidate(candidate, expectedCurrent, stillCurrent)
    suspend fun getRankedCandidateServers(excludeIds: Set<String> = emptySet()) = selection.getRankedCandidateServers(excludeIds)
    suspend fun autoSelectFastestServer(
        excludeIds: Set<String> = emptySet(), urgent: Boolean = false, commitSelection: Boolean = true,
        reuseRecentSelection: Boolean = false, allowSpeedProbe: Boolean = true
    ) = selection.autoSelectFastestServer(excludeIds, urgent, commitSelection, reuseRecentSelection, allowSpeedProbe)
    suspend fun findBetterServerIfAvailable(currentServer: ServerNode, currentPing: Long, excludeIds: Set<String> = emptySet(), minImprovementRatio: Float = 0.30f, minMsSaved: Long = 40L, verifyGoalOnly: Boolean = false) =
        selection.findBetterServerIfAvailable(currentServer, currentPing, excludeIds, minImprovementRatio, minMsSaved, verifyGoalOnly)

    suspend fun importSubscription(subUrl: String, subTitle: String? = null) = subscriptionsRepository.importSubscription(subUrl, subTitle)
    fun subscriptionUpdateHistory(id: String) = subscriptionsRepository.updateHistory(id)
    suspend fun updateSubscription(subId: String) = subscriptionsRepository.updateSubscription(subId)
    suspend fun deleteSubscription(subId: String) = subscriptionsRepository.deleteSubscription(subId)
    suspend fun setSubscriptionEnabled(subId: String, enabled: Boolean) = subscriptionsRepository.setSubscriptionEnabled(subId, enabled)
    suspend fun setSubscriptionAutoUpdate(subId: String, autoUpdate: Boolean) = subscriptionsRepository.setSubscriptionAutoUpdate(subId, autoUpdate)
    suspend fun setSubscriptionRetention(subId: String, enabled: Boolean) = subscriptionsRepository.setSubscriptionRetention(subId, enabled)
    suspend fun purgeRetainedNodes(subId: String) = subscriptionsRepository.purgeRetainedNodes(subId)
    suspend fun pruneExpiredRetainedNodes() = subscriptionsRepository.pruneExpiredRetainedNodes()
    suspend fun setSubscriptionRulesEnabled(subId: String, enabled: Boolean, expectedRulesJson: String? = null) = subscriptionsRepository.setSubscriptionRulesEnabled(subId, enabled, expectedRulesJson)
    suspend fun editSubscription(subId: String, name: String, url: String) = subscriptionsRepository.editSubscription(subId, name, url)
    suspend fun updateAllSubscriptions() = subscriptionsRepository.refreshSubscriptions(manual = false).updated
    suspend fun refreshSubscriptions(manual: Boolean) = subscriptionsRepository.refreshSubscriptions(manual)
}
