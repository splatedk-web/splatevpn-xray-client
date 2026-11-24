package com.danieldk.splatevpn.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danieldk.splatevpn.data.ServerNode
import com.danieldk.splatevpn.data.ServerRepository
import com.danieldk.splatevpn.data.SettingsManager
import com.danieldk.splatevpn.data.AppLogManager
import com.danieldk.splatevpn.data.LogEntry
import com.danieldk.splatevpn.data.ServerPingSummary
import com.danieldk.splatevpn.data.UpdateCheckResult
import com.danieldk.splatevpn.data.UpdateInfo
import com.danieldk.splatevpn.data.UpdateDownloadState
import com.danieldk.splatevpn.data.UpdateManager
import com.danieldk.splatevpn.vpn.VpnStatusManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.danieldk.splatevpn.data.withSessionPing
import com.danieldk.splatevpn.vpn.SplateVpnService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.stateIn
import com.danieldk.splatevpn.data.SpeedtestManager
import com.danieldk.splatevpn.data.SpeedtestState
import com.danieldk.splatevpn.data.BackupManager
import com.danieldk.splatevpn.data.forConnection
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val serverRepository: ServerRepository,
    private val settingsManager: SettingsManager,
    private val geoData: com.danieldk.splatevpn.data.GeoDataRepository,
    private val vpnStatusManager: VpnStatusManager,
    private val appLogManager: AppLogManager,
    private val updateManager: UpdateManager,
    private val torBridgeBenchmark: com.danieldk.splatevpn.vpn.TorBridgeBenchmark,
    private val speedtestManager: SpeedtestManager,
    private val backupManager: BackupManager,
    private val autoBackupManager: com.danieldk.splatevpn.data.AutoBackupManager,
    private val liveConnectionTracker: com.danieldk.splatevpn.vpn.LiveConnectionTracker,
    private val lanShareManager: com.danieldk.splatevpn.vpn.LanShareManager,
    private val trafficHistory: com.danieldk.splatevpn.data.TrafficHistory,
    private val connectionChecks: com.danieldk.splatevpn.data.ConnectionChecks,
    private val aiDnsAutoSelector: com.danieldk.splatevpn.data.AiDnsAutoSelector,
    private val adFilters: com.danieldk.splatevpn.data.AdFilterRepository,
    private val temporaryRouting: com.danieldk.splatevpn.data.TemporaryRouting,
    private val networkBindings: com.danieldk.splatevpn.data.NetworkProfileBindings,
    private val profileStore: com.danieldk.splatevpn.data.ConnectionProfiles,
    private val dnsProviderChecker: com.danieldk.splatevpn.data.DnsProviderChecker,
    private val dnsHealthAdvisor: com.danieldk.splatevpn.data.DnsHealthAdvisor,
    private val tunnelHealthAdvisor: com.danieldk.splatevpn.data.TunnelHealthAdvisor,
    private val torRepository: com.danieldk.splatevpn.data.TorRepository,
    private val torRuntime: com.danieldk.splatevpn.vpn.TorRuntime,
    private val torBridgeProbeRepository: com.danieldk.splatevpn.data.TorBridgeProbeRepository,
    val deviceTransferManager: com.danieldk.splatevpn.data.DeviceTransferManager,
    val webDavSyncManager: com.danieldk.splatevpn.data.WebDavSyncManager,
    val cloudBackupManager: com.danieldk.splatevpn.data.CloudBackupManager,
    val cloudBackupLinkImporter: com.danieldk.splatevpn.data.CloudBackupLinkImporter,
    val anonymousReports: com.danieldk.splatevpn.data.AnonymousReportStore
) : ViewModel() {
    val torSettings = torRepository.settings
    val torState = torRuntime.state
    val torStorageError = torRepository.storageError
    val torBridgeLatencies = torBridgeProbeRepository.bridgeLatencies
    val isProbingTorBridges = torBridgeProbeRepository.isProbing
    val torBridgeSpeeds = combine(torBridgeProbeRepository.bridgeSpeeds, torSettings) { _, settings ->
        torBridgeProbeRepository.speedsFor(settings,
            com.danieldk.splatevpn.data.NetworkAccessPolicy.physicalNetworkKey(context)?.toString() ?: "none")
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())
    val torBridgeBenchmarkState = torBridgeBenchmark.state
    private var torBridgeProbeJob: Job? = null

    fun probeTorBridges() {
        val settings = torSettings.value
        val bridges = settings.bridges
        if (bridges.isEmpty()) return
        if (torBridgeProbeJob?.isActive == true) return
        torBridgeProbeJob = viewModelScope.launch {
            try { withChecksPolicy(Unit) {
                val network = com.danieldk.splatevpn.data.NetworkAccessPolicy.physicalNetworkKey(context)?.toString() ?: "none"
                val speed = com.danieldk.splatevpn.data.TorBridgeMetric.fromId(settings.bridgeSelectionMetric) == com.danieldk.splatevpn.data.TorBridgeMetric.SPEED
                val delays = torBridgeProbeRepository.probeBridges(bridges, networkKey = network,
                    transport = settings.bridgeTransport, totalBudgetMs = if (speed) 1800 else 8000)
                if (speed) torBridgeBenchmark.measure(settings, network, delays, refresh = true)
                Unit
            } } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                appLogManager.w("Tor", "Bridge measurement failed: ${error.javaClass.simpleName}")
                android.widget.Toast.makeText(context, context.getString(com.danieldk.splatevpn.R.string.tor_speed_unavailable),
                    android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    fun cancelTorBridgeProbes() { torBridgeProbeJob?.cancel() }

    fun refreshTorCircuit() {
        viewModelScope.launch {
            torRuntime.newIdentity()
        }
    }
    private val requestedServerBrowserTor = kotlinx.coroutines.flow.MutableStateFlow<Boolean?>(null)
    val serverBrowserTor = combine(requestedServerBrowserTor, torSettings) { requested, saved -> requested ?: saved.enabled }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    fun browseServerMode(useTor: Boolean) { requestedServerBrowserTor.value = useTor }

    private fun localizedMessage(ru: String, en: String, zh: String, fa: String): String {
        val lang = com.danieldk.splatevpn.ui.theme.appLocale(
            settingsManager.getAppLanguage(),
            java.util.Locale.getDefault()
        ).language
        return when (lang) {
            "ru" -> ru
            "zh" -> zh
            "fa" -> fa
            else -> en
        }
    }

    fun receiveDeviceTransferInvitation(raw: String): Boolean {
        if (!raw.trim().startsWith("splate-transfer://", ignoreCase = true)) return false
        viewModelScope.launch {
            deviceTransferManager.receiveInvitation(raw).onFailure {
                android.widget.Toast.makeText(
                    context,
                    localizedMessage(
                        ru = "SplateVPN: не удалось открыть перенос настроек. Проверьте Wi-Fi и срок действия QR.",
                        en = "SplateVPN: could not open settings transfer. Check Wi-Fi and QR expiration.",
                        zh = "SplateVPN：无法打开设置传输。请检查 Wi-Fi 及二维码是否过期。",
                        fa = "SplateVPN: امکان باز کردن انتقال تنظیمات وجود ندارد. وای‌فای و انقضای کد QR را بررسی کنید."
                    ),
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
        return true
    }
    private val torSelectionMutex = kotlinx.coroutines.sync.Mutex()

    /** One explicit mode action also works while the foreground activity is being recreated. */
    suspend fun selectConnectionMode(useTor: Boolean): Result<Unit> = torSelectionMutex.withLock {
        quickPreferenceSelectionJob?.cancel()
        val caller = kotlinx.coroutines.currentCoroutineContext()[Job]
        manualSelectionJob?.takeIf { it !== caller }?.let { it.cancel(); it.join() }
        settingsManager.invalidateServerSelection()
        val current = torRepository.load()
        if (current.enabled == useTor) return@withLock Result.success(Unit)
        persistTorConnectionSettings(current.copy(enabled = useTor))
    }

    suspend fun selectTorBridge(bridge: String, transport: String): Result<Unit> = torSelectionMutex.withLock {
        val current = torRepository.load()
        persistTorConnectionSettings(current.copy(useBridges = true, selectedBridge = bridge, bridgeTransport = transport))
    }

    /** Select the VPN node before restarting out of Tor, without an intermediate Tor reload. */
    suspend fun selectVpnServer(id: String): Result<Unit> = torSelectionMutex.withLock {
        try {
            val caller = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
            quickPreferenceSelectionJob?.takeIf { it !== caller }?.cancel()
            manualSelectionJob?.takeIf { it !== caller }?.let { it.cancel(); it.join() }
            settingsManager.invalidateServerSelection()
            selectServerJob?.takeIf { it !== caller }?.cancel()
            val candidate = serverRepository.allServers.first().firstOrNull { it.id == id }
            require(candidate != null && settingsManager.isServerEnabled(candidate)) { "Server is no longer available" }
            require(com.danieldk.splatevpn.data.QuickServerSelection.isPersonal(candidate) ||
                serverRepository.allSubscriptions.first().any { it.id == candidate.subId && it.enabled }) { "Subscription is disabled" }
            settingsManager.setAutoSelectServer(false)
            com.danieldk.splatevpn.vpn.SplateVpnService.unmarkDeadServer(id)
            serverRepository.setActiveServer(id)
            check(serverRepository.getActiveServerSync()?.id == id) { "Server selection changed" }
            val current = torRepository.load()
            if (current.enabled) persistTorConnectionSettings(current.copy(enabled = false))
            else {
                if (vpnState.value !in setOf(com.danieldk.splatevpn.vpn.VpnState.DISCONNECTED, com.danieldk.splatevpn.vpn.VpnState.FAILED)) {
                    context.startService(Intent(context, SplateVpnService::class.java).setAction("RELOAD"))
                }
                Result.success(Unit)
            }
        } catch (error: CancellationException) { throw error
        } catch (error: Exception) { Result.failure(error) }
    }

    suspend fun applyTorConnectionSettings(settings: com.danieldk.splatevpn.data.TorSettings): Result<Unit> =
        torSelectionMutex.withLock { persistTorConnectionSettings(settings) }

    val notificationPreferences = settingsManager.notificationPreferences
    fun setNotificationPreferences(value: com.danieldk.splatevpn.data.NotificationPreferences) = settingsManager.setNotificationPreferences(value)

    fun toggleConnectionFromServers() {
        if (vpnState.value in setOf(com.danieldk.splatevpn.vpn.VpnState.DISCONNECTED, com.danieldk.splatevpn.vpn.VpnState.FAILED)) {
            context.startActivity(com.danieldk.splatevpn.InternalActivityEntry.intent(context)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("OPEN_TAB", "servers").putExtra("AUTO_CONNECT", true))
        } else context.startService(Intent(context, SplateVpnService::class.java).setAction("STOP"))
    }

    private suspend fun persistTorConnectionSettings(settings: com.danieldk.splatevpn.data.TorSettings): Result<Unit> {
        return try {
            val safe = com.danieldk.splatevpn.data.TorConfigParser.validate(settings)
            if (safe.enabled && safe.useBridges) safe.forConnection()
            val before = torRepository.load()
            torRepository.save(safe)
            if (safe.enabled) cancelAiDnsSelection()
            val active = vpnState.value !in setOf(com.danieldk.splatevpn.vpn.VpnState.DISCONNECTED, com.danieldk.splatevpn.vpn.VpnState.FAILED)
            if (active && (before.enabled || safe.enabled)) {
                context.startService(Intent(context, SplateVpnService::class.java).setAction("RESTART_MODE"))
            }
            Result.success(Unit)
        } catch (error: CancellationException) { throw error
        } catch (error: Exception) { Result.failure(error) }
    }

    private fun canEditTor(requested: com.danieldk.splatevpn.data.TorSettings): Boolean =
        com.danieldk.splatevpn.data.canEditTorSettings(
            vpnState.value !in setOf(com.danieldk.splatevpn.vpn.VpnState.DISCONNECTED, com.danieldk.splatevpn.vpn.VpnState.FAILED),
            torSettings.value.enabled, requested.enabled)

    suspend fun saveTorSettings(settings: com.danieldk.splatevpn.data.TorSettings): Result<Unit> {
        if (!canEditTor(settings)) return Result.failure(IllegalStateException("Disconnect Tor before changing its active settings"))
        return try {
            torRepository.save(settings)
            if (settings.enabled) cancelAiDnsSelection()
            Result.success(Unit)
        }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { Result.failure(error) }
    }

    suspend fun updateTorSubscription(url: String, settings: com.danieldk.splatevpn.data.TorSettings? = null): Result<Int> {
        if (!canEditTor(settings ?: torRepository.load())) return Result.failure(IllegalStateException("Disconnect Tor before updating its active settings"))
        return torRepository.updateSubscription(url, settings)
    }

    suspend fun removeTorSubscription(url: String): Result<Unit> = editTorSubscriptions {
        torRepository.removeSubscription(url)
    }

    suspend fun addTorTop100Subscription(): Result<Unit> = editTorSubscriptions {
        torRepository.addTop100Subscription()
    }

    private suspend fun editTorSubscriptions(action: suspend () -> Unit): Result<Unit> {
        if (!canEditTor(torRepository.load())) return Result.failure(IllegalStateException("Disconnect Tor before changing its active settings"))
        return try {
            action()
            Result.success(Unit)
        } catch (error: CancellationException) { throw error
        } catch (error: Exception) { Result.failure(error) }
    }

    val tunnelHealthIssue: StateFlow<com.danieldk.splatevpn.data.TunnelHealthIssue?> = tunnelHealthAdvisor.issue
    val dnsHealthIssue: StateFlow<com.danieldk.splatevpn.data.DnsHealthIssue?> = tunnelHealthAdvisor.dnsIssue

    fun applyRecommendedDns() {
        if (tunnelHealthAdvisor.applyCandidate() && isConnected.value) {
            viewModelScope.launch { _reconnectEvent.emit(Unit) }
        }
    }

    fun applyFakeDnsRecommendation() {
        if (tunnelHealthAdvisor.applyFakeDns() && isConnected.value) {
            viewModelScope.launch { _reconnectEvent.emit(Unit) }
        }
    }

    fun dismissDnsHealthIssue() {
        tunnelHealthAdvisor.dismiss()
    }

    fun handleHealthIssuePrimaryAction(issue: com.danieldk.splatevpn.data.TunnelHealthIssue) {
        when (issue.kind) {
            com.danieldk.splatevpn.data.HealthIssueKind.NETWORK_UNAVAILABLE,
            com.danieldk.splatevpn.data.HealthIssueKind.CONNECTIVITY_UNVERIFIED -> tunnelHealthAdvisor.dismiss()
            com.danieldk.splatevpn.data.HealthIssueKind.TLS_FRAGMENTATION -> {
                if (tunnelHealthAdvisor.applyStandardFragmentation() && isConnected.value) {
                    viewModelScope.launch { _reconnectEvent.emit(Unit) }
                }
            }
            com.danieldk.splatevpn.data.HealthIssueKind.DNS_BLOCKED -> {
                if (issue.primaryActionId == "ENABLE_FAKEDNS") applyFakeDnsRecommendation()
                else if (issue.primaryActionId == "APPLY_DNS") applyRecommendedDns()
            }
            com.danieldk.splatevpn.data.HealthIssueKind.SILENT_BLACKHOLE,
            com.danieldk.splatevpn.data.HealthIssueKind.REALITY_TLS_FAILURE -> {
                tunnelHealthAdvisor.dismiss()
                viewModelScope.launch {
                    val currentId = vpnStatusManager.connectedServerId
                    val revision = settingsManager.selectionRevision()
                    val nextServer = serverRepository.autoSelectFastestServer(excludeIds = setOfNotNull(currentId), urgent = true,
                        commitSelection = false, allowSpeedProbe = false)
                    if (nextServer != null && revision == settingsManager.selectionRevision() && currentId == vpnStatusManager.connectedServerId) {
                        serverRepository.setActiveServer(nextServer.id)
                        _reconnectEvent.emit(Unit)
                    }
                }
            }
            com.danieldk.splatevpn.data.HealthIssueKind.MTU_FRAGMENTATION -> {
                settingsManager.setMtu(1280)
                tunnelHealthAdvisor.dismiss()
                if (isConnected.value) {
                    viewModelScope.launch { _reconnectEvent.emit(Unit) }
                }
            }
            com.danieldk.splatevpn.data.HealthIssueKind.GEO_BLOCKED_DOMAIN -> {
                val domain = issue.primaryActionExtra
                if (!domain.isNullOrBlank()) {
                    temporaryRouting.add(domain, app = false, outbound = "direct", durationMinutes = 1440)
                    tunnelHealthAdvisor.dismiss()
                    if (isConnected.value) {
                        viewModelScope.launch { _reconnectEvent.emit(Unit) }
                    }
                }
            }
            com.danieldk.splatevpn.data.HealthIssueKind.CAPTIVE_PORTAL -> {
                val intent = Intent(context, SplateVpnService::class.java).apply {
                    action = "PAUSE_VPN_120S"
                }
                context.startService(intent)
            }
            com.danieldk.splatevpn.data.HealthIssueKind.TIME_DESYNC -> {
                tunnelHealthAdvisor.dismiss()
                try {
                    val intent = Intent(android.provider.Settings.ACTION_DATE_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (_: Exception) {}
            }
            com.danieldk.splatevpn.data.HealthIssueKind.ROUTING_LOOP -> {
                tunnelHealthAdvisor.dismiss()
            }
        }
    }

    fun handleHealthIssueSecondaryAction(issue: com.danieldk.splatevpn.data.TunnelHealthIssue) {
        when (issue.kind) {
            com.danieldk.splatevpn.data.HealthIssueKind.DNS_BLOCKED -> {
                applyFakeDnsRecommendation()
            }
            com.danieldk.splatevpn.data.HealthIssueKind.CAPTIVE_PORTAL -> {
                val portalUrl = issue.secondaryActionExtra ?: "http://connectivitycheck.gstatic.com/generate_204"
                try {
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(portalUrl)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(browserIntent)
                } catch (_: Exception) {}
            }
            else -> {
                tunnelHealthAdvisor.dismiss()
            }
        }
    }

    private val _dnsProviderChecks = MutableStateFlow(com.danieldk.splatevpn.data.DnsProviderCheckState())
    val dnsProviderChecks = _dnsProviderChecks.asStateFlow()
    private var dnsProviderCheckJob: Job? = null
    private var dnsProviderCheckRevision = 0L
    init {
        viewModelScope.launch {
            vpnStatusManager.state.collect { clearDnsProviderChecks() }
        }
    }
    fun clearDnsProviderChecks() {
        dnsProviderCheckRevision++
        dnsProviderCheckJob?.cancel()
        _dnsProviderChecks.value = com.danieldk.splatevpn.data.DnsProviderCheckState()
    }
    fun cancelDnsProviderChecks() {
        dnsProviderCheckRevision++
        dnsProviderCheckJob?.cancel()
        _dnsProviderChecks.value = _dnsProviderChecks.value.copy(running = false,
            results = _dnsProviderChecks.value.results.filterValues {
                it.status !in setOf(com.danieldk.splatevpn.data.DnsProviderCheckStatus.WAITING, com.danieldk.splatevpn.data.DnsProviderCheckStatus.CHECKING)
            })
    }
    fun checkDnsProviders() {
        if (dnsProviderCheckJob?.isActive == true) return
        val vpnState = vpnStatusManager.state.value
        if (vpnState !in setOf(com.danieldk.splatevpn.vpn.VpnState.CONNECTED, com.danieldk.splatevpn.vpn.VpnState.DISCONNECTED,
                com.danieldk.splatevpn.vpn.VpnState.FAILED)) return
        val revision = ++dnsProviderCheckRevision
        val endpoints = com.danieldk.splatevpn.data.DnsProviderCatalog.endpoints(settingsManager.getCustomDohUrl())
            .filter { it.key != "custom" || it.address.isNotBlank() }
        val viaVpn = vpnState == com.danieldk.splatevpn.vpn.VpnState.CONNECTED
        _dnsProviderChecks.value = com.danieldk.splatevpn.data.DnsProviderCheckState(running = true, viaVpn = viaVpn,
            results = endpoints.associate { it.key to com.danieldk.splatevpn.data.DnsProviderCheckResult(com.danieldk.splatevpn.data.DnsProviderCheckStatus.WAITING) })
        dnsProviderCheckJob = viewModelScope.launch {
            val gate = kotlinx.coroutines.sync.Semaphore(3)
            try {
                coroutineScope {
                    endpoints.map { endpoint -> async {
                        gate.acquire()
                        try {
                            ensureActive()
                            if (revision != dnsProviderCheckRevision) return@async
                            _dnsProviderChecks.value = _dnsProviderChecks.value.copy(results = _dnsProviderChecks.value.results +
                                (endpoint.key to com.danieldk.splatevpn.data.DnsProviderCheckResult(com.danieldk.splatevpn.data.DnsProviderCheckStatus.CHECKING)))
                            val result = com.danieldk.splatevpn.data.NetworkAccessPolicy.withChecks(context) { dnsProviderChecker.check(endpoint, viaVpn) }
                            if (revision == dnsProviderCheckRevision) _dnsProviderChecks.value = _dnsProviderChecks.value.copy(
                                results = _dnsProviderChecks.value.results + (endpoint.key to result))
                        } finally { gate.release() }
                    } }.awaitAll()
                }
            } catch (error: com.danieldk.splatevpn.data.WifiRequiredException) { showWifiRequired()
            } finally {
                if (revision == dnsProviderCheckRevision) _dnsProviderChecks.value = _dnsProviderChecks.value.copy(running = false, completedAt = System.currentTimeMillis())
            }
        }
    }
    internal val settingsRecovery = settingsManager.recoveryNotice
    fun acceptRecoveredSettingsDefaults() = settingsManager.acceptRecoveredDefaults()
    fun dismissSettingsRecoveryNotice() = settingsManager.dismissRecoveryNotice()
    internal val networkPreferences = settingsManager.networkPreferences
    internal val activeRouting = vpnStatusManager.activeRouting
    suspend fun inspectDomain(host: String): Result<com.danieldk.splatevpn.data.DomainRouteMatch?> = withContext(Dispatchers.IO) {
        runCatching {
            val active = requireNotNull(vpnStatusManager.activeRouting.value) { "Connect VPN first" }
            val directory = requireNotNull(active.assetDirectory)
            com.danieldk.splatevpn.data.DomainRouteInspector.inspect(host, active.domainRules, java.io.File(directory))
        }
    }
    val temporaryExceptions = temporaryRouting.entries
    val networkProfileBindings = networkBindings.bindings
    suspend fun bindNetworkProfile(network: String, profile: String?, label: String) = withContext(Dispatchers.IO) { networkBindings.bind(network, profile, label) }
    suspend fun addTemporaryException(target: String, app: Boolean, outbound: String, minutes: Int): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (app && !settingsManager.isGlobalAppProxy() && settingsManager.getPerAppMode() == "include") {
                require((settingsManager.getPerAppPackages() - target).isNotEmpty()) { "Keep at least one VPN application" }
            }
            temporaryRouting.add(target, app, outbound, minutes)
        }
    }
    suspend fun removeTemporaryException(id: String) = withContext(Dispatchers.IO) { temporaryRouting.remove(id) }
    val connectionProfiles = profileStore.profiles
    val canUndoConnectionProfile = profileStore.canUndo
    suspend fun saveConnectionProfile(name: String): Result<Unit> = withContext(Dispatchers.IO) { runCatching { profileStore.save(name) } }
    suspend fun deleteConnectionProfile(id: String): Result<Unit> = withContext(Dispatchers.IO) { runCatching { profileStore.delete(id) } }
    fun connectionProfileTarget(id: String): Map<String, *> = profileStore.target(id)
    suspend fun applyConnectionProfile(id: String?, reviewed: Map<String, *>? = null): Result<Unit> {
        onManualAiSettingsChange()
        return runCatching {
            val changed = withContext(Dispatchers.IO) { if (id == null) profileStore.undo() else profileStore.apply(id, reviewed) }
            _perAppPackages.value = settingsManager.getPerAppPackages()
            _dnsProvider.value = settingsManager.getDnsProvider()
            _xhttpMode.value = settingsManager.getXhttpMode()
            _isBlockIpv6.value = settingsManager.isBlockIpv6()
            _isRouteOnlySniffing.value = settingsManager.isRouteOnlySniffing()
            if (changed && vpnStatusManager.state.value == com.danieldk.splatevpn.vpn.VpnState.CONNECTED) _reconnectEvent.emit(Unit)
        }.onFailure { if (it is CancellationException) throw it }
    }
    val trafficDays = trafficHistory.days
    val serviceChecks = connectionChecks.services
    val diagnostics = connectionChecks.diagnostics
    private var serviceCheckJob: Job? = null
    private var diagnosticsJob: Job? = null
    fun clearTrafficHistory() = trafficHistory.clear()
    fun checkServices() {
        if (serviceCheckJob?.isActive == true) return
        serviceCheckJob = viewModelScope.launch { withChecksPolicy(Unit) { connectionChecks.checkServices(); Unit } }
    }
    fun diagnoseConnection() {
        if (diagnosticsJob?.isActive == true) return
        val node = selectedServer.value
        diagnosticsJob = viewModelScope.launch { withChecksPolicy(Unit) { connectionChecks.diagnose(node); Unit } }
    }
    fun cancelServiceChecks() { serviceCheckJob?.cancel() }
    fun cancelDiagnostics() { diagnosticsJob?.cancel() }

    val isShareLan: StateFlow<Boolean> = settingsManager.isShareLanFlow
    fun subscriptionUpdateHistory(id: String) = serverRepository.subscriptionUpdateHistory(id)
    fun getLanInterface() = settingsManager.getLanInterface()
    val lanAuthentication = settingsManager.lanAuthentication
    fun setLanAuthenticationEnabled(enabled: Boolean) {
        settingsManager.setLanAuthenticationEnabled(enabled)
        if (isConnected.value) viewModelScope.launch { _reconnectEvent.emit(Unit) }
    }
    fun getLanPassword() = settingsManager.getLanPassword()
    fun setLanInterface(name: String) {
        settingsManager.setLanInterface(name)
        if (isConnected.value) viewModelScope.launch { _reconnectEvent.emit(Unit) }
    }
    fun rotateLanPassword(): String {
        val password = settingsManager.rotateLanPassword()
        if (isConnected.value) viewModelScope.launch { _reconnectEvent.emit(Unit) }
        return password
    }

    fun setShareLan(enabled: Boolean) {
        settingsManager.setShareLanEnabled(enabled)
        if (isConnected.value) viewModelScope.launch { _reconnectEvent.emit(Unit) }
    }

    fun getActiveLanEndpoints(): List<com.danieldk.splatevpn.vpn.LanEndpoint> {
        return lanShareManager.getActiveLanEndpoints()
    }

    val liveConnections: StateFlow<List<com.danieldk.splatevpn.vpn.LiveConnection>> = liveConnectionTracker.connections
    val liveConnectionsTotalCount: StateFlow<Int> = liveConnectionTracker.totalCount
    val liveConnectionsActiveCount: StateFlow<Int> = liveConnectionTracker.activeCount
    val liveConnectionsHistoryCount: StateFlow<Int> = liveConnectionTracker.historyCount
    val liveConnectionsTotalHits: StateFlow<Int> = liveConnectionTracker.totalHits
    val liveConnectionsProxyCount: StateFlow<Int> = liveConnectionTracker.proxyCount
    val liveConnectionsDirectCount: StateFlow<Int> = liveConnectionTracker.directCount
    val liveConnectionsBlockCount: StateFlow<Int> = liveConnectionTracker.blockCount
    val liveConnectionsDnsCount: StateFlow<Int> = liveConnectionTracker.dnsCount
    val isLiveConnectionsPaused: StateFlow<Boolean> = liveConnectionTracker.isPaused

    fun clearLiveConnections() {
        liveConnectionTracker.clear()
    }

    fun togglePauseLiveConnections() {
        liveConnectionTracker.togglePause()
    }

    private val _selectedTab = MutableStateFlow(com.danieldk.splatevpn.ui.screens.AppTab.HOME)
    val selectedTab: StateFlow<com.danieldk.splatevpn.ui.screens.AppTab> = _selectedTab.asStateFlow()

    val uiMode: StateFlow<com.danieldk.splatevpn.data.AppUiMode> = settingsManager.uiModeFlow

    fun setUiMode(mode: com.danieldk.splatevpn.data.AppUiMode) {
        settingsManager.setUiMode(mode)
        if (mode.isSimple && _selectedTab.value !in com.danieldk.splatevpn.ui.screens.visibleAppTabs(mode)) {
            _selectedTab.value = com.danieldk.splatevpn.ui.screens.AppTab.HOME
        }
    }

    fun openAllServersInPro() {
        if (uiMode.value.isSimple) settingsManager.setUiMode(com.danieldk.splatevpn.data.AppUiMode.PRO)
        _selectedTab.value = com.danieldk.splatevpn.ui.screens.AppTab.SERVERS
    }

    // Полноэкранная заставка инициализации требуется исключительно при первом холодном запуске после установки,
    // пока предустановленные узлы распаковываются в базу данных Room.
    // Если база данных уже заполнена или туннель активен, экран загрузки не показывается, и главный экран открывается мгновенно.
    private val isInitialSetupNeeded: Boolean =
        !settingsManager.hasInitializedPresetSubscriptions() ||
        !settingsManager.hasInitializedUniversalWhitelist()

    private val _isStartupLoading = MutableStateFlow(isInitialSetupNeeded)
    val isStartupLoading: StateFlow<Boolean> = _isStartupLoading.asStateFlow()

    fun selectTab(tab: com.danieldk.splatevpn.ui.screens.AppTab) {
        val visible = com.danieldk.splatevpn.ui.screens.visibleAppTabs(uiMode.value)
        _selectedTab.value = if (tab in visible) tab else com.danieldk.splatevpn.ui.screens.AppTab.HOME
    }

    fun restoreFromAutoVault(onComplete: (Result<Int>) -> Unit) {
        viewModelScope.launch {
            val res = autoBackupManager.restoreFromVault()
            onComplete(res)
        }
    }

    fun hasAutoVaultBackup(): Boolean = autoBackupManager.hasVaultBackup()

    val logsFlow: StateFlow<List<LogEntry>> = appLogManager.logsFlow
    val errorsFlow: StateFlow<List<LogEntry>> = appLogManager.errorsFlow

    val vpnState: StateFlow<com.danieldk.splatevpn.vpn.VpnState> = vpnStatusManager.state
    private val releaseNotesTranslator by lazy { com.danieldk.splatevpn.data.ReleaseNotesTranslator(context) }
    internal suspend fun translateReleaseNotes(markdown: String, language: String): String =
        releaseNotesTranslator.translate(markdown, language)
    val vpnStatusMessage: StateFlow<String?> = combine(vpnStatusManager.statusMessage, settingsManager.appLanguageFlow) { message, language ->
        val resources = com.danieldk.splatevpn.ui.theme.localizedResources(context,
            com.danieldk.splatevpn.ui.theme.appLocale(language, java.util.Locale.getDefault()))
        com.danieldk.splatevpn.data.ConnectionStatusText.localize(message, resources)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val isConnected: StateFlow<Boolean> = vpnStatusManager.state
        .map { it == com.danieldk.splatevpn.vpn.VpnState.CONNECTED }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val uploadSpeed: StateFlow<String> = vpnStatusManager.uploadSpeed
    val downloadSpeed: StateFlow<String> = vpnStatusManager.downloadSpeed
    val speedHistory: StateFlow<List<Float>> = vpnStatusManager.speedHistory
    val speedtestState: StateFlow<SpeedtestState> = speedtestManager.testState
    val countryFlag: StateFlow<String> = vpnStatusManager.countryFlag
    val countryName: StateFlow<String> = vpnStatusManager.countryName
    val currentIp: StateFlow<String> = vpnStatusManager.currentIp
    val isDetectingIp: StateFlow<Boolean> = vpnStatusManager.isDetectingIp
    val activePingIds = serverRepository.activePingIds
    val rttMs: StateFlow<Long> = vpnStatusManager.rttMs

    val isTlsFragment: StateFlow<Boolean> = settingsManager.isTlsFragmentFlow
    val tlsFragmentPreset: StateFlow<String> = settingsManager.tlsFragmentPresetFlow
    val tlsFragmentLength: StateFlow<String> = settingsManager.tlsFragmentLengthFlow
    val tlsFragmentInterval: StateFlow<String> = settingsManager.tlsFragmentIntervalFlow
    val tlsFragmentPackets: StateFlow<String> = settingsManager.tlsFragmentPacketsFlow

    val customDohUrl: StateFlow<String> = settingsManager.customDohUrlFlow
    val isMux: StateFlow<Boolean> = settingsManager.isMuxFlow
    val muxConcurrency: StateFlow<Int> = settingsManager.muxConcurrencyFlow

    val mtu = settingsManager.mtuFlow
    val tlsMixedSni = settingsManager.tlsMixedSni

    fun setMtu(value: Int) {
        if (value == settingsManager.getMtu()) return
        settingsManager.setMtu(value)
        if (isConnected.value) viewModelScope.launch { _reconnectEvent.emit(Unit) }
    }

    fun setTlsMixedSni(enabled: Boolean) {
        settingsManager.setTlsMixedSniEnabled(enabled)
        if (isConnected.value) viewModelScope.launch { _reconnectEvent.emit(Unit) }
    }

    fun setTlsFragment(enabled: Boolean) {
        settingsManager.setTlsFragmentEnabled(enabled)
        if (isConnected.value) viewModelScope.launch { _reconnectEvent.emit(Unit) }
    }

    fun setTlsFragmentPreset(preset: String) {
        settingsManager.setTlsFragmentPreset(preset)
        if (isConnected.value) viewModelScope.launch { _reconnectEvent.emit(Unit) }
    }

    fun setTlsFragmentCustomParams(length: String, interval: String, packets: String) {
        settingsManager.setTlsFragmentPreset("custom")
        settingsManager.setTlsFragmentLength(length)
        settingsManager.setTlsFragmentInterval(interval)
        settingsManager.setTlsFragmentPackets(packets)
        if (isConnected.value) viewModelScope.launch { _reconnectEvent.emit(Unit) }
    }

    fun setCustomDohUrl(url: String) {
        clearDnsProviderChecks()
        settingsManager.setCustomDohUrl(url)
        if (isConnected.value && _dnsProvider.value == "custom") {
            viewModelScope.launch { _reconnectEvent.emit(Unit) }
        }
    }

    fun setMux(enabled: Boolean) {
        settingsManager.setMuxEnabled(enabled)
        if (isConnected.value) viewModelScope.launch { _reconnectEvent.emit(Unit) }
    }

    fun setMuxConcurrency(concurrency: Int) {
        settingsManager.setMuxConcurrency(concurrency)
        if (isConnected.value && isMux.value) viewModelScope.launch { _reconnectEvent.emit(Unit) }
    }


    fun startSpeedtest() {
        viewModelScope.launch {
            withChecksPolicy(Unit) { speedtestManager.startSpeedtest(); Unit }
        }
    }

    fun resetSpeedtest() {
        speedtestManager.reset()
    }

    suspend fun exportBackup(uri: android.net.Uri, password: CharArray? = null): Result<Int> = backupManager.exportBackup(uri, password)
    suspend fun importBackup(uri: android.net.Uri, mode: BackupManager.ImportMode = BackupManager.ImportMode.MERGE, password: CharArray? = null): Result<Int> {
        onManualAiSettingsChange()
        val result = backupManager.importBackup(uri, mode, password)
        if (result.isSuccess) {
            profileStore.reload()
            _perAppPackages.value = settingsManager.getPerAppPackages()
            _dnsProvider.value = settingsManager.getDnsProvider()
            _xhttpMode.value = settingsManager.getXhttpMode()
            _isBlockIpv6.value = settingsManager.isBlockIpv6()
            _isRouteOnlySniffing.value = settingsManager.isRouteOnlySniffing()
            if (isConnected.value) _reconnectEvent.emit(Unit)
        }
        return result
    }

    // Session traffic and timing
    val sessionStartTime: StateFlow<Long> = vpnStatusManager.sessionStartTime
    val sessionRxBytes: StateFlow<Long> = vpnStatusManager.sessionRxBytes
    val sessionTxBytes: StateFlow<Long> = vpnStatusManager.sessionTxBytes

    val allServers: StateFlow<List<ServerNode>> = com.danieldk.splatevpn.data.serverUiSnapshots(
        serverRepository.allServers, serverRepository.sessionPingResults)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allSubscriptions = serverRepository.allSubscriptions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val verifiedServerDelays = serverRepository.verifiedDelays
    val candidateMeasurements = serverRepository.candidateMeasurements
    internal fun speedBudgetResumesAt() = serverRepository.speedBudgetResumesAt()
    internal suspend fun quickCandidateMeasurements(nodes: List<ServerNode>) = withContext(Dispatchers.Default) {
        serverRepository.candidateMeasurementSnapshot(nodes)
    }
    val quickServerCatalog = combine(serverRepository.allServers, serverRepository.allSubscriptions) { nodes, subscriptions ->
        com.danieldk.splatevpn.data.QuickServerCatalog(nodes, subscriptions)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    fun quickServerSource(): com.danieldk.splatevpn.data.QuickServerSource = runCatching {
        com.danieldk.splatevpn.data.QuickServerSource.valueOf(context.getSharedPreferences("quick_server_picker", 0)
            .getString("source", "ALL") ?: "ALL")
    }.getOrDefault(com.danieldk.splatevpn.data.QuickServerSource.ALL)
    fun setQuickServerSource(source: com.danieldk.splatevpn.data.QuickServerSource) {
        context.getSharedPreferences("quick_server_picker", 0).edit().putString("source", source.name).apply()
    }
    internal suspend fun quickServerCandidates(nodes: List<ServerNode>, proofs: Map<String, com.danieldk.splatevpn.data.VerifiedServerDelay>,
        source: com.danieldk.splatevpn.data.QuickServerSource, now: Long,
        subscriptions: List<com.danieldk.splatevpn.data.SubscriptionEntity>): List<com.danieldk.splatevpn.data.QuickServerCandidate> = withContext(Dispatchers.Default) {
        val disabled = settingsManager.getDisabledServerIds()
        val dead = com.danieldk.splatevpn.vpn.SplateVpnService.deadServerIds
        val enabledSubscriptions = subscriptions.filter { it.enabled }.mapTo(hashSetOf()) { it.id }
        com.danieldk.splatevpn.data.QuickServerSelection.candidates(nodes, proofs, source, now,
            com.danieldk.splatevpn.data.NetworkAccessPolicy.physicalNetworkKey(context), Int.MAX_VALUE) { node ->
            settingsManager.isServerEnabled(node, disabled, now) && node.id !in dead &&
                (com.danieldk.splatevpn.data.QuickServerSelection.isPersonal(node) ||
                    node.subId in enabledSubscriptions) && serverRepository.qualifiesForGoal(node)
        }.let { candidates ->
            if (settingsManager.getBalancerPreferences().metric == com.danieldk.splatevpn.data.BalancerMetric.DOWNLOAD) {
                val measured = serverRepository.candidateMeasurementSnapshot(candidates.map { it.server })
                candidates.sortedByDescending { measured[it.server.id]?.downloadMbps ?: 0.0 }
            } else candidates
        }
    }

    fun refreshQuickServers(source: com.danieldk.splatevpn.data.QuickServerSource = quickServerSource(), onFinished: () -> Unit = {}) {
        if (_isPinging.value) { onFinished(); return }
        pingChecksJob = viewModelScope.launch {
            _isPinging.value = true
            try {
                val nodes = quickServerCatalog.value?.nodes ?: serverRepository.allServers.first()
                val subscriptions = quickServerCatalog.value?.subscriptions ?: serverRepository.allSubscriptions.first()
                val disabled = settingsManager.getDisabledServerIds()
                val dead = com.danieldk.splatevpn.vpn.SplateVpnService.deadServerIds
                val enabledSubscriptions = subscriptions.filter { it.enabled }.mapTo(hashSetOf()) { it.id }
                val eligible = nodes.filter { node ->
                    settingsManager.isServerEnabled(node, disabled) && node.id !in dead &&
                        (com.danieldk.splatevpn.data.QuickServerSelection.isPersonal(node) || node.subId in enabledSubscriptions) &&
                        (source == com.danieldk.splatevpn.data.QuickServerSource.ALL ||
                            (source == com.danieldk.splatevpn.data.QuickServerSource.PERSONAL && com.danieldk.splatevpn.data.QuickServerSelection.isPersonal(node)) ||
                            (source == com.danieldk.splatevpn.data.QuickServerSource.SUBSCRIPTIONS && !com.danieldk.splatevpn.data.QuickServerSelection.isPersonal(node)))
                }
                if (eligible.isEmpty()) return@launch

                val proofs = verifiedServerDelays.value
                val now = System.currentTimeMillis()
                val net = com.danieldk.splatevpn.data.NetworkAccessPolicy.physicalNetworkKey(context)

                val needProbe = eligible.filter { node ->
                    val proof = proofs[node.id]
                    proof == null || proof.network != net || (now - proof.checkedAt) !in 0..com.danieldk.splatevpn.data.QuickServerSelection.FRESH_FOR_MS
                }

                val prefs = settingsManager.getBalancerPreferences()
                val missingSpeed = if (prefs.metric == com.danieldk.splatevpn.data.BalancerMetric.DOWNLOAD) {
                    val measured = serverRepository.candidateMeasurementSnapshot(eligible)
                    eligible.filter { measured[it.id]?.downloadMbps == null }.sortedBy { node ->
                        proofs[node.id]?.takeIf { it.network == net && now - it.checkedAt in 0..com.danieldk.splatevpn.data.QuickServerSelection.FRESH_FOR_MS }?.delayMs ?: Long.MAX_VALUE
                    }
                } else emptyList()
                val toCheck = com.danieldk.splatevpn.data.protocolDiverseCandidates((missingSpeed + needProbe + eligible).distinctBy { it.id }).take(6)
                if (toCheck.isNotEmpty()) {
                    withChecksPolicy(false) {
                        serverRepository.testServersRealDelay(toCheck)
                        serverRepository.qualifyQuickServers(toCheck)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("MainViewModel", "Quick server refresh failed: ${e.message}")
            } finally {
                _isPinging.value = false
                onFinished()
            }
        }
    }

    val selectedServer: StateFlow<ServerNode?> = serverRepository.selectedServer
        .combine(serverRepository.sessionPingResults) { server, measured ->
            server?.withSessionPing(measured)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun sourceDownloadsViaVpn() = settingsManager.isSourceDownloadsViaVpn()
    val sourceDownloadsViaVpnState = settingsManager.sourceDownloadsViaVpnFlow
    val advancedRouting = settingsManager.advancedRouting
    private val advancedRulesGate = kotlinx.coroutines.sync.Mutex()
    fun domainSniffingEnabled() = settingsManager.isEnableSniffing()
    suspend fun saveAdvancedRoute(rule: com.danieldk.splatevpn.data.AdvancedRouteRule, enableDomainDetection: Boolean = false): Result<Unit> = withContext(Dispatchers.IO) { advancedRulesGate.withLock {
        runCatching {
            val validated = com.danieldk.splatevpn.data.AdvancedRoutingRules.validate(rule)
            val current = com.danieldk.splatevpn.data.AdvancedRoutingRules.decode(settingsManager.advancedRoutingJson())
            val next = if (current.any { it.id == validated.id }) current.map { if (it.id == validated.id) validated else it } else current + validated
            com.danieldk.splatevpn.vpn.AppFlowIdentityResolver.validatePackages(context,
                com.danieldk.splatevpn.data.AdvancedRoutingRules.packageNames(next))
            val encoded = com.danieldk.splatevpn.data.AdvancedRoutingRules.encode(next)
            val changed = com.danieldk.splatevpn.data.AdvancedRoutingRules.compile(current) != com.danieldk.splatevpn.data.AdvancedRoutingRules.compile(next)
            val needsDetection = com.danieldk.splatevpn.data.AdvancedRoutingRules.requiresDomainSniffing(next)
            require(!needsDetection || settingsManager.isEnableSniffing() || enableDomainDetection) {
                localizedMessage(
                    ru = "Разрешите определение доменов для составных правил",
                    en = "Enable domain detection for combined rules",
                    zh = "请为组合规则启用域名检测",
                    fa = "تشخیص دامنه را برای قوانین ترکیبی فعال کنید"
                )
            }
            if (needsDetection && enableDomainDetection) settingsManager.setEnableSniffing(true)
            settingsManager.storeAdvancedRouting(encoded)
            if (changed && isConnected.value && !torSettings.value.enabled) _reconnectEvent.emit(Unit)
        }.onFailure { if (it is CancellationException) throw it }
    } }
    suspend fun removeAdvancedRoute(id: String): Result<Unit> = changeAdvancedRoutes { list -> list.filterNot { it.id == id } }
    suspend fun moveAdvancedRoute(id: String, delta: Int): Result<Unit> = changeAdvancedRoutes { list ->
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) list else list.toMutableList().apply {
            val target = (index + delta.coerceIn(-1, 1)).coerceIn(0, lastIndex)
            add(target, removeAt(index))
        }
    }
    private suspend fun changeAdvancedRoutes(change: (List<com.danieldk.splatevpn.data.AdvancedRouteRule>) -> List<com.danieldk.splatevpn.data.AdvancedRouteRule>): Result<Unit> = withContext(Dispatchers.IO) { advancedRulesGate.withLock {
        runCatching {
            val current = com.danieldk.splatevpn.data.AdvancedRoutingRules.decode(settingsManager.advancedRoutingJson())
            val next = change(current)
            val changed = com.danieldk.splatevpn.data.AdvancedRoutingRules.compile(current) != com.danieldk.splatevpn.data.AdvancedRoutingRules.compile(next)
            settingsManager.storeAdvancedRouting(com.danieldk.splatevpn.data.AdvancedRoutingRules.encode(next))
            if (changed && isConnected.value && !torSettings.value.enabled) _reconnectEvent.emit(Unit)
        }.onFailure { if (it is CancellationException) throw it }
    } }
    suspend fun resetAdvancedRoutes(): Result<Unit> = withContext(Dispatchers.IO) { advancedRulesGate.withLock {
        runCatching {
            settingsManager.storeAdvancedRouting("[]")
            if (isConnected.value && !torSettings.value.enabled) _reconnectEvent.emit(Unit)
        }.onFailure { if (it is CancellationException) throw it }
    } }
    val updatesWifiOnly = settingsManager.updatesWifiOnly
    val checksWifiOnly = settingsManager.checksWifiOnly
    fun setUpdatesWifiOnly(enabled: Boolean) = settingsManager.setUpdatesWifiOnly(enabled)
    fun setChecksWifiOnly(enabled: Boolean) = settingsManager.setChecksWifiOnly(enabled)
    private fun showWifiRequired() {
        android.widget.Toast.makeText(
            context,
            localizedMessage(
                ru = "В настройках разрешены проверки только по Wi-Fi. Сохранённые результаты не изменены.",
                en = "Wi-Fi is required by your check settings. Saved results were kept.",
                zh = "根据您的检查设置，仅允许通过 Wi-Fi 进行检查。已保留保存的结果。",
                fa = "طبق تنظیمات شما، بررسی‌ها فقط از طریق وای‌فای مجاز است. نتایج ذخیره‌شده حفظ شدند."
            ),
            android.widget.Toast.LENGTH_LONG
        ).show()
    }
    private suspend fun <T> withChecksPolicy(fallback: T, action: suspend () -> T): T = try {
        com.danieldk.splatevpn.data.NetworkAccessPolicy.withChecks(context, action)
    } catch (_: com.danieldk.splatevpn.data.WifiRequiredException) { showWifiRequired(); fallback }
    val localProxyAuthentication = settingsManager.localProxyAuthentication
    suspend fun getLocalProxyCredentials() = withContext(Dispatchers.IO) { settingsManager.getLocalProxyCredentials() }
    suspend fun saveLocalProxyCredentials(enabled: Boolean, username: String, password: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            settingsManager.setLocalProxyCredentials(username, password)
            settingsManager.setLocalProxyAuthenticationEnabled(enabled)
            if (isConnected.value) _reconnectEvent.emit(Unit)
        }.onFailure { if (it is CancellationException) throw it }
    }
    suspend fun regenerateLocalProxyCredentials() = withContext(Dispatchers.IO) {
        settingsManager.regenerateLocalProxyCredentials().also { if (isConnected.value) _reconnectEvent.emit(Unit) }
    }
    fun setSourceDownloadsViaVpn(enabled: Boolean) = settingsManager.setSourceDownloadsViaVpn(enabled)
    val geoDataStatus = geoData.status
    suspend fun updateGeoData() = geoData.refresh()
    suspend fun rollbackGeoData() = geoData.rollback()
    val routingRegion = settingsManager.routingRegion
    val needsOnboarding = settingsManager.needsOnboarding
    private val tvSetupPreferences = com.danieldk.splatevpn.data.TvSetupPreferences(context)
    val tvTransferOfferPending = tvSetupPreferences.offerPending
    @Volatile private var onboardingCompletionInProgress = false
    suspend fun completeTvTransferOffer() = withContext(Dispatchers.IO) { tvSetupPreferences.finishOffer() }
    fun shouldDeferTvSetupConnection(): Boolean = com.danieldk.splatevpn.data.deferTvSetupConnection(
        com.danieldk.splatevpn.ui.screens.isTelevisionDevice(context), needsOnboarding.value, tvTransferOfferPending.value,
        startupPending = onboardingCompletionInProgress
    )
    fun showOnboarding() = settingsManager.showOnboarding()
    fun regionalPresetsEnabled() = settingsManager.regionalPresetsEnabled()
    fun completeOnboarding(language: String, region: String, mode: String, presets: Boolean,
        appMode: com.danieldk.splatevpn.data.AppRoutingMode? = null, packages: Set<String> = emptySet(),
        onReady: () -> Unit = {}) {
        if (onboardingCompletionInProgress) return
        onboardingCompletionInProgress = true
        _isStartupLoading.value = true
        viewModelScope.launch {
            try {
                val before = com.danieldk.splatevpn.data.NetworkPreferences.capture(settingsManager.preferenceSnapshot())
                settingsManager.completeOnboarding(language, region, mode, presets, appMode, packages)
                serverRepository.initDefaultServers(restoreMissingPresets = settingsManager.regionalPresetsEnabled())
                if (settingsManager.regionalPresetsEnabled()) {
                    val present = serverRepository.allSubscriptions.first()
                    check(com.danieldk.splatevpn.data.builtInSubscriptions.all { preset -> present.any {
                        it.id == preset.id || preset.group == com.danieldk.splatevpn.data.PresetGroup.SPONSOR && it.url == preset.url
                    } }) {
                        "Preset subscriptions were not initialized"
                    }
                }
                if (isConnected.value && before != com.danieldk.splatevpn.data.NetworkPreferences.capture(settingsManager.preferenceSnapshot())) {
                    _reconnectEvent.emit(Unit)
                }
                withContext(Dispatchers.IO) {
                    tvSetupPreferences.afterOnboarding(com.danieldk.splatevpn.ui.screens.isTelevisionDevice(context))
                }
                onReady()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Onboarding initialization failed", e)
                settingsManager.showOnboarding()
                android.widget.Toast.makeText(context, context.getString(com.danieldk.splatevpn.R.string.onboarding_preset_failed), android.widget.Toast.LENGTH_LONG).show()
            } finally {
                onboardingCompletionInProgress = false
                _isStartupLoading.value = false
            }
        }
    }
    fun setRoutingRegion(region: String) {
        settingsManager.setRoutingRegion(region)
        if (isConnected.value) viewModelScope.launch { _reconnectEvent.emit(Unit) }
    }
    val routingMode: StateFlow<String> = settingsManager.routingModeFlow
    val isGlobalAppProxy: StateFlow<Boolean> = settingsManager.isGlobalAppProxyFlow
    val perAppMode: StateFlow<String> = settingsManager.perAppModeFlow

    fun setPerAppMode(mode: String) {
        settingsManager.setPerAppMode(mode)
        if (isConnected.value && !isGlobalAppProxy.value) {
            viewModelScope.launch { _reconnectEvent.emit(Unit) }
        }
    }
    val isAutoSelect: StateFlow<Boolean> = settingsManager.isAutoSelectFlow
    val balancerPreferences = settingsManager.balancerPreferences
    val glassTransparency = settingsManager.glassTransparency
    val glassBlurEnabled = settingsManager.glassBlurEnabled
    val performanceSettings = settingsManager.performanceSettings
    fun setUiAnimationsEnabled(enabled: Boolean) = settingsManager.setUiAnimationsEnabled(enabled)
    fun setDecorativeBackgroundEnabled(enabled: Boolean) = settingsManager.setDecorativeBackgroundEnabled(enabled)
    fun setGlassHighlightsEnabled(enabled: Boolean) = settingsManager.setGlassHighlightsEnabled(enabled)
    fun setLightUiMode() = settingsManager.setLightUiMode()
    fun restoreFullUiEffects() = settingsManager.restoreFullUiEffects()
    fun setGlassBlurEnabled(enabled: Boolean) = settingsManager.setGlassBlurEnabled(enabled)
    fun previewGlassTransparency(value: Float) = settingsManager.setGlassTransparency(value, persist = false)
    fun setGlassTransparency(value: Float) = settingsManager.setGlassTransparency(value)
    fun commitGlassTransparency() = settingsManager.setGlassTransparency(glassTransparency.value)
    fun setBalancerPreferences(value: com.danieldk.splatevpn.data.BalancerPreferences) = settingsManager.setBalancerPreferences(value)
    fun setQuickBalancerPreferences(value: com.danieldk.splatevpn.data.BalancerPreferences) {
        quickPreferenceSelectionJob?.cancel()
        settingsManager.setBalancerPreferences(value)
        val previousPings = pingChecksJob
        val previousSelection = manualSelectionJob
        previousPings?.cancel()
        previousSelection?.cancel()
        val revision = settingsManager.selectionRevision()
        quickPreferenceSelectionJob = viewModelScope.launch {
            previousPings?.join()
            previousSelection?.join()
            ensureActive()
            if (revision == settingsManager.selectionRevision() && !torSettings.value.enabled)
                autoSelectFastestServer(enableAutoBalancer = true)
        }
    }

    // Custom domain routing rules
    val customRoutes: StateFlow<List<Pair<String, String>>> = settingsManager.customRoutesFlow

    // Event to trigger VPN reconnect when server changes while connected
    private val _reconnectEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val reconnectEvent: SharedFlow<Unit> = _reconnectEvent.asSharedFlow()

    private val _installedApps = MutableStateFlow<List<com.danieldk.splatevpn.ui.screens.AppItem>>(emptyList())
    val installedApps: StateFlow<List<com.danieldk.splatevpn.ui.screens.AppItem>> = _installedApps.asStateFlow()

    private val _isLoadingApps = MutableStateFlow(false)
    val isLoadingApps: StateFlow<Boolean> = _isLoadingApps.asStateFlow()

    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val updateInfo: StateFlow<UpdateInfo?> = _updateInfo.asStateFlow()

    private val _updateCheckResult = MutableStateFlow<UpdateCheckResult?>(null)
    val updateCheckResult = _updateCheckResult.asStateFlow()

    private val _isCheckingUpdate = MutableStateFlow(false)
    val isCheckingUpdate: StateFlow<Boolean> = _isCheckingUpdate.asStateFlow()

    /** Download progress/state from UpdateManager */
    val updateDownloadState: StateFlow<UpdateDownloadState> = updateManager.downloadState

    fun loadInstalledApps(context: android.content.Context, forceReload: Boolean = false) {
        if (_installedApps.value.size > 1 && !forceReload) return
        // Claim before dispatching: repeated tab entries/Refresh share the in-flight load.
        if (!_isLoadingApps.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val pm = context.packageManager
                val myPackage = context.packageName
                val packages = pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
                val items = packages
                    .asSequence()
                    .filter { appInfo ->
                        // Exclude Splate VPN itself from per-app list
                        if (appInfo.packageName == myPackage) return@filter false

                        val isSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                        val isUpdatedSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                        // Show all non-system apps or apps with launcher UI (Chrome, YouTube, etc.)
                        !isSystem || isUpdatedSystem || pm.getLaunchIntentForPackage(appInfo.packageName) != null
                    }
                    .map { appInfo ->
                        val name = pm.getApplicationLabel(appInfo).toString()
                        val iconDrawable = try {
                            pm.getApplicationIcon(appInfo)
                        } catch (e: Exception) {
                            pm.defaultActivityIcon
                        }
                        val iconBitmap = com.danieldk.splatevpn.ui.screens.drawableToBitmap(iconDrawable)
                        com.danieldk.splatevpn.ui.screens.AppItem(name, appInfo.packageName, iconBitmap)
                    }
                    .sortedBy { it.name.lowercase() }
                    .toList()
                _installedApps.value = items
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Error loading apps: ${e.message}", e)
            } finally {
                _isLoadingApps.value = false
            }
        }
    }

    private val _perAppPackages = MutableStateFlow(settingsManager.getPerAppPackages())
    val perAppPackages: StateFlow<Set<String>> = _perAppPackages.asStateFlow()

    private var pingChecksJob: Job? = null
    private var manualSelectionJob: Job? = null
    private var quickPreferenceSelectionJob: Job? = null
    private val _isPinging = MutableStateFlow(false)
    val isPinging: StateFlow<Boolean> = _isPinging.asStateFlow()

    fun getServersListPreferences() = settingsManager.getServersListPreferences()

    fun saveServersListPreferences(state: com.danieldk.splatevpn.data.ServersListPreferences) {
        settingsManager.setServersListPreferences(state)
    }

    private val _isSortByPing = MutableStateFlow(settingsManager.isSortServersByPing())
    val isSortByPing: StateFlow<Boolean> = _isSortByPing.asStateFlow()

    fun toggleSortByPing() {
        _isSortByPing.value = !_isSortByPing.value
        settingsManager.setSortServersByPing(_isSortByPing.value)
    }

    private val _dnsProvider = MutableStateFlow(settingsManager.getDnsProvider())
    val dnsProvider: StateFlow<String> = _dnsProvider.asStateFlow()

    private val _xhttpMode = MutableStateFlow(settingsManager.getXhttpMode())
    val xhttpMode: StateFlow<String> = _xhttpMode.asStateFlow()

    val autoUpdateInterval = settingsManager.autoUpdateInterval

    private val _isBlockIpv6 = MutableStateFlow(settingsManager.isBlockIpv6())
    val isBlockIpv6: StateFlow<Boolean> = _isBlockIpv6.asStateFlow()

    val isBlockQuic: StateFlow<Boolean> = settingsManager.isBlockQuicFlow
    val isAdBlocker: StateFlow<Boolean> = settingsManager.isAdBlockerFlow
    val adFilterState = adFilters.state
    val adBlockAllowlist = settingsManager.adBlockAllowlist
    init { viewModelScope.launch(Dispatchers.IO) { if (settingsManager.isAdBlocker()) adFilters.prepareMatcher() } }
    fun updateAdFilters() {
        if (adFilters.state.value.updating) return
        viewModelScope.launch {
            if (adFilters.refresh() && isConnected.value && isAdBlocker.value) _reconnectEvent.emit(Unit)
        }
    }
    fun setAdBlockAllowlist(domains: List<String>) {
        settingsManager.setAdBlockAllowlist(domains)
        viewModelScope.launch(Dispatchers.IO) {
            adFilters.prepareMatcher()
            if (isConnected.value && isAdBlocker.value) _reconnectEvent.emit(Unit)
        }
    }
    val isFakeDns: StateFlow<Boolean> = settingsManager.isFakeDnsFlow
    val isBypassAi: StateFlow<Boolean> = settingsManager.isBypassAiFlow
    val aiRoutingMode: StateFlow<String> = settingsManager.aiRoutingModeFlow
    val aiDnsProvider: StateFlow<String> = settingsManager.aiDnsProviderFlow
    val isAiTlsFragment: StateFlow<Boolean> = settingsManager.aiTlsFragmentFlow
    val isAiDnsAutoSwitch: StateFlow<Boolean> = settingsManager.aiDnsAutoSwitchFlow
    fun setAiDnsAutoSwitch(enabled: Boolean) { settingsManager.setAiDnsAutoSwitchEnabled(enabled) }
    val aiDnsSelection = aiDnsAutoSelector.state
    private var aiDnsSelectionJob: Job? = null
    private var aiSettingsRevision = 0

    fun startAiDnsSelection() {
        if (torSettings.value.enabled || !isBypassAi.value) return
        val mode = aiRoutingMode.value
        if (mode !in setOf("dns_ai", "dns_vpn") || aiDnsSelectionJob?.isActive == true) return
        val revision = aiSettingsRevision
        aiDnsSelectionJob = viewModelScope.launch {
            val node = serverRepository.getActiveServerSync()
            val viaVpn = mode == "dns_vpn"
            val route = node?.let { com.danieldk.splatevpn.data.AiDnsVpnRoute.capture(it, settingsManager, serverRepository.requiresTlsFragment(it.id)) }
            if (viaVpn && (!isConnected.value || route == null || node.id != vpnStatusManager.connectedServerId ||
                    route.node != vpnStatusManager.connectedServerProfile?.let(com.danieldk.splatevpn.data.QuickServerSelection::profile))) return@launch
            val session = vpnStatusManager.sessionToken
            aiDnsAutoSelector.run(viaVpn = viaVpn, vpnRoute = if (viaVpn) route else null) { choice ->
                val currentRoute = selectedServer.value?.let { com.danieldk.splatevpn.data.AiDnsVpnRoute.capture(it, settingsManager, serverRepository.requiresTlsFragment(it.id)) }
                if (revision != aiSettingsRevision || !isBypassAi.value || aiRoutingMode.value != mode || torSettings.value.enabled ||
                    viaVpn && (!isConnected.value || session != vpnStatusManager.sessionToken || currentRoute != route)) false
                else {
                    settingsManager.setAiSmartDnsChoice(choice)
                    if (isConnected.value) viewModelScope.launch { _reconnectEvent.emit(Unit) }
                    true
                }
            }
        }
    }

    fun cancelAiDnsSelection() { aiDnsSelectionJob?.cancel() }
    private fun onManualAiSettingsChange() {
        aiSettingsRevision++
        cancelAiDnsSelection()
    }

    val pingMode: StateFlow<String> = settingsManager.pingModeFlow

    fun setPingMode(mode: String) {
        if (settingsManager.getPingMode() != mode) pingChecksJob?.cancel()
        settingsManager.setPingMode(mode)
    }

    private val _isRouteOnlySniffing = MutableStateFlow(settingsManager.isRouteOnlySniffing())
    val isRouteOnlySniffing: StateFlow<Boolean> = _isRouteOnlySniffing.asStateFlow()

    internal var entryConnectionAttempted = false
    val autoConnectOnOpen = settingsManager.autoConnectOnOpenFlow
    fun setAutoConnectOnOpen(enabled: Boolean) = settingsManager.setAutoConnectOnOpen(enabled)
    val isAutoConnectOnBoot: StateFlow<Boolean> = settingsManager.isAutoConnectOnBootFlow

    fun setAutoConnectOnBoot(enabled: Boolean) {
        settingsManager.setAutoConnectOnBoot(enabled)
    }

    init {
        viewModelScope.launch {
            uiMode.collect { mode ->
                if (_selectedTab.value !in com.danieldk.splatevpn.ui.screens.visibleAppTabs(mode)) {
                    _selectedTab.value = com.danieldk.splatevpn.ui.screens.AppTab.HOME
                }
            }
        }
        viewModelScope.launch {
            deviceTransferManager.applied.collect {
                profileStore.reload()
                _perAppPackages.value = settingsManager.getPerAppPackages()
                _dnsProvider.value = settingsManager.getDnsProvider()
                _xhttpMode.value = settingsManager.getXhttpMode()
                _isBlockIpv6.value = settingsManager.isBlockIpv6()
                _isRouteOnlySniffing.value = settingsManager.isRouteOnlySniffing()
            }
        }
        viewModelScope.launch {
            settingsManager.networkPreferences.collect {
                _perAppPackages.value = settingsManager.getPerAppPackages()
                _dnsProvider.value = settingsManager.getDnsProvider()
                _xhttpMode.value = settingsManager.getXhttpMode()
                _isBlockIpv6.value = settingsManager.isBlockIpv6()
                _isRouteOnlySniffing.value = settingsManager.isRouteOnlySniffing()
            }
        }
        viewModelScope.launch {
            try {
                // Подготавливаем базу данных и встроенные подписки при первом запуске.
                // При повторных запусках этот метод завершается мгновенно (в кеше), не задерживая UI.
                serverRepository.initDefaultServers()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.e("MainViewModel", "Startup data preparation failed", error)
            } finally {
                _isStartupLoading.value = false
            }
        }
        checkForUpdates()
    }

    val appTheme: StateFlow<String> = settingsManager.appThemeFlow
    val appLanguage: StateFlow<String> = settingsManager.appLanguageFlow

    fun refreshIpLocation() {
        viewModelScope.launch {
            vpnStatusManager.fetchCurrentIpDetails()
        }
    }

    private var selectServerJob: Job? = null

    val disabledServerIds = settingsManager.disabledServerIds
    fun setPersonalFolderEnabled(enabled: Boolean) {
        settingsManager.setPersonalFolderEnabled(enabled)
        val connected = vpnStatusManager.connectedServerProfile
        val activeNode = connected ?: selectedServer.value
        if (!enabled && !torSettings.value.enabled &&
            activeNode?.let(com.danieldk.splatevpn.data.QuickServerSelection::isPersonal) == true &&
            vpnStatusManager.state.value !in setOf(com.danieldk.splatevpn.vpn.VpnState.DISCONNECTED, com.danieldk.splatevpn.vpn.VpnState.FAILED)) {
            context.startService(android.content.Intent(context, com.danieldk.splatevpn.vpn.SplateVpnService::class.java).setAction("STOP"))
        }
    }
    fun isPersonalFolderEnabled(disabled: Set<String>) = settingsManager.isPersonalFolderEnabled(disabled)

    fun isNodeEnabled(node: ServerNode, disabled: Set<String> = disabledServerIds.value, now: Long = System.currentTimeMillis()) =
        settingsManager.isServerEnabled(node, disabled, now)

    fun selectServer(id: String) {
        quickPreferenceSelectionJob?.cancel()
        manualSelectionJob?.cancel()
        if (allServers.value.firstOrNull { it.id == id }?.let { settingsManager.isServerEnabled(it) } != true) return
        val currentId = selectedServer.value?.id
        android.util.Log.d("MainViewModel", "selectServer: requested id=$id, currentId=$currentId")
        if (currentId == id && !torSettings.value.enabled) {
            // If auto-select was active, explicit click on the current node locks it in and disables auto-balancer
            if (isAutoSelect.value) {
                setAutoSelect(false)
            }
            android.util.Log.d("MainViewModel", "selectServer: server $id is already selected, locked selection")
            return
        }

        // Disable auto-select when user explicitly chooses a server
        if (isAutoSelect.value) {
            setAutoSelect(false)
        }

        selectServerJob?.cancel()
        selectServerJob = viewModelScope.launch {
            selectVpnServer(id).onFailure { android.util.Log.w("MainViewModel", "Server selection failed: ${it.javaClass.simpleName}") }
        }
    }

    fun updateServer(server: ServerNode, onResult: (Result<Unit>) -> Unit = {}) {
        viewModelScope.launch {
            val result = runCatching { serverRepository.updateServer(server) }
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            onResult(result)
            if (result.isSuccess && selectedServer.value?.id == server.id && isConnected.value) {
                _reconnectEvent.emit(Unit)
            }
        }
    }

    fun setAppTheme(theme: String) {
        settingsManager.setAppTheme(theme)
    }

    fun setAppLanguage(lang: String) {
        settingsManager.setAppLanguage(lang)
    }

    fun deleteServer(id: String) {
        viewModelScope.launch {
            serverRepository.deleteServer(id)
        }
    }

    fun deleteServers(ids: Collection<String>, onResult: (Int) -> Unit = {}) {
        val targets = ids.asSequence().filter { it.isNotBlank() }.distinct().toList()
        if (targets.isEmpty()) {
            onResult(0)
            return
        }
        viewModelScope.launch {
            val deleted = serverRepository.deleteServers(targets)
            onResult(deleted)
        }
    }

    fun addServerFromLink(link: String, onResult: (Boolean) -> Unit) {
        addServersFromLinks(link) { count -> onResult(count > 0) }
    }

    fun addServersFromLinks(links: String, onImportError: ((String) -> Unit)? = null, onResult: (Int) -> Unit) {
        viewModelScope.launch {
            try {
                val trimmed = links.trim()
                if (com.danieldk.splatevpn.data.LinkParser.normalizeSubscriptionUrl(trimmed) != null ||
                    com.danieldk.splatevpn.data.LinkParser.isEncryptedSubscriptionLink(trimmed)
                ) {
                    val res = localizedSubscriptionResult(serverRepository.importSubscription(trimmed, null))
                    res.exceptionOrNull()?.let { failure ->
                        if (onImportError != null) onImportError(failure.localizedMessage.orEmpty())
                        else {
                            android.widget.Toast.makeText(context, failure.localizedMessage.orEmpty(), android.widget.Toast.LENGTH_LONG).show()
                            onResult(0)
                        }
                        return@launch
                    }
                    onResult(res.getOrDefault(0))
                } else {
                    val count = serverRepository.addServersFromLinks(
                        com.danieldk.splatevpn.data.LinkParser.unwrapImportLink(trimmed) ?: links
                    )
                    onResult(count)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = context.getString(if (e.message?.contains("REALITY") == true)
                    com.danieldk.splatevpn.R.string.profile_import_invalid_reality_key
                    else com.danieldk.splatevpn.R.string.profile_import_invalid)
                if (onImportError != null) onImportError(message)
                else {
                    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
                    onResult(0)
                }
            }
        }
    }

    fun canSendSubscriptionHwid(url: String): Boolean = com.danieldk.splatevpn.data.SubscriptionHwidPolicy.canSend(url)
    fun isSubscriptionHwidEnabled(url: String): Boolean = settingsManager.isSubscriptionHwidEnabled(url)
    fun setSubscriptionHwidEnabled(url: String, enabled: Boolean) {
        if (canSendSubscriptionHwid(url)) settingsManager.setSubscriptionHwidEnabled(url, enabled)
    }

    private fun localizedSubscriptionResult(result: Result<Int>): Result<Int> {
        val error = result.exceptionOrNull() ?: return result
        val hwidError = generateSequence(error) { it.cause }.take(12)
            .filterIsInstance<com.danieldk.splatevpn.data.SubscriptionHwidException>().firstOrNull() ?: return result
        val resources = com.danieldk.splatevpn.ui.theme.localizedResources(context,
            com.danieldk.splatevpn.ui.theme.appLocale(settingsManager.getAppLanguage(), java.util.Locale.getDefault()))
        return Result.failure(IllegalStateException(subscriptionErrorText(resources, hwidError), hwidError))
    }

    fun getSubscriptionUserAgent(url: String) = settingsManager.getSubscriptionUserAgent(url)
    fun setSubscriptionUserAgent(url: String, agent: com.danieldk.splatevpn.data.SubscriptionUserAgent) =
        settingsManager.setSubscriptionUserAgent(url, agent)

    fun importSubscription(url: String, title: String? = null, hwid: Boolean? = null,
        userAgent: com.danieldk.splatevpn.data.SubscriptionUserAgent? = null, onResult: (Result<Int>) -> Unit) {
        viewModelScope.launch {
            if (hwid != null) setSubscriptionHwidEnabled(url, hwid)
            if (userAgent != null) setSubscriptionUserAgent(url, userAgent)
            onResult(localizedSubscriptionResult(serverRepository.importSubscription(url, title)))
        }
    }

    val updatingSubscriptionIds = serverRepository.updatingSubscriptionIds

    fun updateSubscription(subId: String, onResult: (Result<Int>) -> Unit) {
        viewModelScope.launch {
            val res = serverRepository.updateSubscription(subId)
            onResult(localizedSubscriptionResult(res))
        }
    }

    fun deleteSubscription(subId: String) {
        viewModelScope.launch {
            serverRepository.deleteSubscription(subId)
        }
    }

    fun toggleSubscriptionEnabled(subId: String, enabled: Boolean) {
        viewModelScope.launch {
            serverRepository.setSubscriptionEnabled(subId, enabled)
        }
    }

    fun toggleSubscriptionAutoUpdate(subId: String, autoUpdate: Boolean) {
        viewModelScope.launch {
            serverRepository.setSubscriptionAutoUpdate(subId, autoUpdate)
        }
    }

    fun editSubscription(subId: String, name: String, url: String, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch { onResult(serverRepository.editSubscription(subId, name, url)) }
    }
    fun setSubscriptionRetention(id: String, enabled: Boolean, callback: (Result<Unit>) -> Unit) {
        viewModelScope.launch { callback(runCatching { serverRepository.setSubscriptionRetention(id, enabled) }
            .onFailure { if (it is CancellationException) throw it }) }
    }
    fun purgeRetainedNodes(id: String, callback: (Result<Int>) -> Unit) {
        viewModelScope.launch {
            val result = runCatching { serverRepository.purgeRetainedNodes(id) }.onFailure { if (it is CancellationException) throw it }
            if (result.isSuccess && isConnected.value) _reconnectEvent.emit(Unit)
            callback(result)
        }
    }
    fun setSubscriptionRulesEnabled(id: String, enabled: Boolean, expectedRulesJson: String?, callback: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            val result = serverRepository.setSubscriptionRulesEnabled(id, enabled, expectedRulesJson)
            if (result.isSuccess && isConnected.value) _reconnectEvent.emit(Unit)
            callback(result)
        }
    }

    fun updateAllSubscriptions(onResult: (com.danieldk.splatevpn.data.SubscriptionUpdateReport) -> Unit) {
        viewModelScope.launch {
            val report = try {
                serverRepository.refreshSubscriptions(manual = true)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                com.danieldk.splatevpn.data.SubscriptionUpdateReport(0, listOf("" to (e.localizedMessage ?: "Update failed")))
            }
            onResult(report)
        }
    }

    fun restorePresetSubscriptions(onResult: (Int) -> Unit = {}) {
        viewModelScope.launch {
            val count = serverRepository.restorePresetSubscriptions()
            onResult(count)
        }
    }

    fun autoSelectFastestServer(enableAutoBalancer: Boolean = false, onResult: (ServerNode?) -> Unit = {}) {
        if (_isPinging.value) return
        manualSelectionJob = viewModelScope.launch {
            if (_isPinging.value) { onResult(null); return@launch }
            _isPinging.value = true
            val best = try {
                withChecksPolicy<ServerNode?>(null) {
                    if (enableAutoBalancer) setAutoSelect(true)
                    serverRepository.autoSelectFastestServer(excludeIds = com.danieldk.splatevpn.vpn.SplateVpnService.deadServerIds)
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                android.util.Log.w("MainViewModel", "Server selection failed: ${error.javaClass.simpleName}")
                val resources = com.danieldk.splatevpn.ui.theme.localizedResources(context,
                    com.danieldk.splatevpn.ui.theme.appLocale(settingsManager.getAppLanguage(), java.util.Locale.getDefault()))
                android.widget.Toast.makeText(context, resources.getString(com.danieldk.splatevpn.R.string.server_search_failed), android.widget.Toast.LENGTH_LONG).show()
                null
            } finally { _isPinging.value = false }
            if (best != null && vpnStatusManager.state.value == com.danieldk.splatevpn.vpn.VpnState.CONNECTED &&
                vpnStatusManager.connectedServerId != best.id) _reconnectEvent.emit(Unit)
            onResult(best)
        }
    }
    fun toggleAutoSelectServer(onResult: (ServerNode?) -> Unit = {}) {
        val newState = !isAutoSelect.value
        setAutoSelect(newState)
        if (newState) {
            autoSelectFastestServer(enableAutoBalancer = true, onResult = onResult)
        } else {
            onResult(null)
        }
    }

    fun setAutoSelect(enabled: Boolean) {
        if (!enabled) {
            quickPreferenceSelectionJob?.cancel()
            manualSelectionJob?.cancel()
        }
        settingsManager.setAutoSelectServer(enabled)
    }

    fun setDnsProvider(provider: String) {
        settingsManager.setDnsProvider(provider)
        _dnsProvider.value = provider
        if (isConnected.value) {
            viewModelScope.launch {
                _reconnectEvent.emit(Unit)
            }
        }
    }

    fun setXhttpMode(mode: String) {
        settingsManager.setXhttpMode(mode)
        _xhttpMode.value = mode
        if (isConnected.value) {
            viewModelScope.launch { _reconnectEvent.emit(Unit) }
        }
    }

    fun setAutoUpdateInterval(interval: String) {
        settingsManager.setAutoUpdateInterval(interval)
    }

    fun setBlockIpv6(block: Boolean) {
        settingsManager.setBlockIpv6(block)
        _isBlockIpv6.value = block
        if (isConnected.value) {
            viewModelScope.launch { _reconnectEvent.emit(Unit) }
        }
    }

    fun setAdBlocker(enabled: Boolean) {
        settingsManager.setAdBlocker(enabled)
        if (enabled) viewModelScope.launch(Dispatchers.IO) { adFilters.prepareMatcher() }
        if (isConnected.value) {
            viewModelScope.launch { _reconnectEvent.emit(Unit) }
        }
    }

    fun setBlockQuic(block: Boolean) {
        settingsManager.setBlockQuic(block)
        if (isConnected.value) {
            viewModelScope.launch { _reconnectEvent.emit(Unit) }
        }
    }

    fun setFakeDns(enabled: Boolean) {
        settingsManager.setFakeDns(enabled)
        if (isConnected.value) {
            viewModelScope.launch { _reconnectEvent.emit(Unit) }
        }
    }

    fun setRouteOnlySniffing(routeOnly: Boolean) {
        settingsManager.setRouteOnlySniffing(routeOnly)
        _isRouteOnlySniffing.value = routeOnly
        if (isConnected.value) {
            viewModelScope.launch { _reconnectEvent.emit(Unit) }
        }
    }

    fun cancelPingChecks() {
        quickPreferenceSelectionJob?.cancel()
        pingChecksJob?.cancel()
        manualSelectionJob?.cancel()
    }

    fun testAllPings(onFinished: (ServerPingSummary) -> Unit = {}) = runPingBatch(onFinished) {
        com.danieldk.splatevpn.vpn.SplateVpnService.clearDeadServers()
        if (settingsManager.getPingMode().lowercase() in listOf("real_delay", "http", "real")) {
            serverRepository.testAllRealDelays()
        } else {
            serverRepository.testAllPings()
        }
    }

    fun testSubscriptionPings(subId: String, onFinished: (ServerPingSummary) -> Unit = {}) =
        runPingBatch(onFinished) { serverRepository.testSubscriptionPings(subId) }

    fun testServersPing(servers: List<ServerNode>, onFinished: (ServerPingSummary) -> Unit = {}) {
        if (servers.isEmpty()) return
        runPingBatch(onFinished) {
            if (settingsManager.getPingMode().lowercase() in listOf("real_delay", "http", "real")) {
                serverRepository.testServersRealDelay(servers)
            } else {
                serverRepository.testServersPing(servers)
            }
        }
    }

    private fun runPingBatch(onFinished: (ServerPingSummary) -> Unit, probe: suspend () -> ServerPingSummary) {
        if (_isPinging.value) return
        pingChecksJob = viewModelScope.launch {
            if (_isPinging.value) return@launch
            _isPinging.value = true
            val result = try { withChecksPolicy<ServerPingSummary?>(null) { probe() } }
                finally { _isPinging.value = false }
            result?.let(onFinished)
        }
    }

    fun testServerPing(server: ServerNode, onResult: (Long) -> Unit) {
        viewModelScope.launch { onResult(withChecksPolicy(server.ping) { serverRepository.testPing(server) }) }
    }

    fun testServerRealDelay(server: ServerNode, onResult: (Long) -> Unit) {
        viewModelScope.launch { onResult(withChecksPolicy(server.ping) { serverRepository.testRealDelay(server) }) }
    }

    fun testAllRealDelays(onComplete: () -> Unit = {}) {
        if (_isPinging.value) return
        pingChecksJob = viewModelScope.launch {
            if (_isPinging.value) return@launch
            _isPinging.value = true
            try {
                withChecksPolicy(Unit) {
                com.danieldk.splatevpn.vpn.SplateVpnService.clearDeadServers()
                serverRepository.testAllRealDelays()
                }
            } finally { _isPinging.value = false }
            onComplete()
        }
    }
    fun setRoutingMode(mode: String) {
        settingsManager.setRoutingMode(mode)
        if (isConnected.value) {
            viewModelScope.launch {
                _reconnectEvent.emit(Unit)
            }
        }
    }

    fun setBypassAi(enabled: Boolean) {
        onManualAiSettingsChange()
        settingsManager.setBypassAi(enabled)
        if (isConnected.value) {
            viewModelScope.launch {
                _reconnectEvent.emit(Unit)
            }
        }
    }

    fun setAiRoutingMode(mode: String) {
        onManualAiSettingsChange()
        settingsManager.setAiRoutingMode(mode)
        if (isConnected.value) {
            viewModelScope.launch { _reconnectEvent.emit(Unit) }
        }
    }

    fun setAiDnsProvider(provider: String) {
        onManualAiSettingsChange()
        settingsManager.setAiDnsProvider(provider)
        if (isConnected.value && isBypassAi.value && aiRoutingMode.value != "vpn") {
            viewModelScope.launch { _reconnectEvent.emit(Unit) }
        }
    }

    fun setAiTlsFragment(enabled: Boolean) {
        onManualAiSettingsChange()
        settingsManager.setAiTlsFragmentEnabled(enabled)
        if (isConnected.value && isBypassAi.value && aiRoutingMode.value != "vpn") {
            viewModelScope.launch { _reconnectEvent.emit(Unit) }
        }
    }

    fun setGlobalAppProxy(global: Boolean) {
        settingsManager.setGlobalAppProxy(global)
        if (isConnected.value) {
            viewModelScope.launch {
                _reconnectEvent.emit(Unit)
            }
        }
    }

    fun applyAppRouting(mode: com.danieldk.splatevpn.data.AppRoutingMode, packages: Set<String>) {
        val previous = com.danieldk.splatevpn.data.AppRoutingMode.fromSettings(isGlobalAppProxy.value, perAppMode.value)
        val reconnect = previous != mode ||
            (mode != com.danieldk.splatevpn.data.AppRoutingMode.ALL && packages != _perAppPackages.value)
        settingsManager.setAppRouting(mode, packages)
        _perAppPackages.value = HashSet(packages)
        if (reconnect && isConnected.value) viewModelScope.launch { _reconnectEvent.emit(Unit) }
    }

    fun toggleAppPackage(pkg: String) {
        val current = _perAppPackages.value.toMutableSet()
        if (current.contains(pkg)) {
            current.remove(pkg)
        } else {
            current.add(pkg)
        }
        _perAppPackages.value = current
        settingsManager.setPerAppPackages(current)
        if (isConnected.value && !isGlobalAppProxy.value) {
            viewModelScope.launch { _reconnectEvent.emit(Unit) }
        }
    }

    fun selectAllApps(packages: List<String>) {
        val current = _perAppPackages.value.toMutableSet()
        current.addAll(packages)
        _perAppPackages.value = current
        settingsManager.setPerAppPackages(current)
        if (isConnected.value && !isGlobalAppProxy.value) {
            viewModelScope.launch { _reconnectEvent.emit(Unit) }
        }
    }

    fun deselectAllApps() {
        val empty = emptySet<String>()
        _perAppPackages.value = empty
        settingsManager.setPerAppPackages(empty)
        if (isConnected.value && !isGlobalAppProxy.value) {
            viewModelScope.launch { _reconnectEvent.emit(Unit) }
        }
    }

    fun resetDefaultServers() {
        viewModelScope.launch {
            serverRepository.resetToDefault()
        }
    }

    fun clearLogs() {
        appLogManager.clear()
    }

    fun getDiagnosticReport(): String {
        val isTor = torSettings.value.enabled
        val server = selectedServer.value
        val activeName = if (isTor) "Tor" else server?.name
        val activeProtocol = if (isTor) "socks/tcp" else "${server?.protocol}/${server?.network}"
        val ipDet = if (currentIp.value.isNotBlank()) "${countryFlag.value} ${countryName.value} (${currentIp.value})" else null
        return appLogManager.generateDiagnosticReport(
            activeServerName = activeName,
            activeServerProtocol = activeProtocol,
            routingMode = routingMode.value,
            dnsProvider = dnsProvider.value,
            vpnConnected = isConnected.value,
            ipDetails = ipDet,
            privateValues = allServers.value.flatMap { listOf(it.name, it.address, it.uuid, it.publicKey, it.shortId, it.rawLink) }
        )
    }

    fun syncDisconnectedState() {
        vpnStatusManager.setState(com.danieldk.splatevpn.vpn.VpnState.DISCONNECTED)
    }

    fun addCustomRoute(domain: String, outboundTag: String) {
        settingsManager.setCustomRoute(domain, outboundTag)
        if (isConnected.value) {
            viewModelScope.launch { _reconnectEvent.emit(Unit) }
        }
    }

    fun removeCustomRoute(domain: String) {
        settingsManager.removeCustomRoute(domain)
        if (isConnected.value) {
            viewModelScope.launch { _reconnectEvent.emit(Unit) }
        }
    }

    fun checkForUpdates() {
        viewModelScope.launch {
            if (_isCheckingUpdate.value) return@launch
            _isCheckingUpdate.value = true
            try {
                val result = updateManager.checkUpdate()
                _updateCheckResult.value = result
                when (result) {
                    is UpdateCheckResult.Available -> _updateInfo.value = result.info
                    UpdateCheckResult.UpToDate -> _updateInfo.value = null
                    UpdateCheckResult.Failed, UpdateCheckResult.WifiRequired -> Unit
                }
            } finally {
                _isCheckingUpdate.value = false
            }
        }
    }

    /** Starts background download + auto-prompts install when done. */
    fun openUpdateRelease(info: UpdateInfo) = updateManager.openReleasePage(info)

    fun startUpdateDownload() {
        val info = _updateInfo.value ?: return
        updateManager.startDownload(info)
    }

    /** Opens the system PackageInstaller for an already-downloaded APK. */
    fun installDownloadedUpdate() {
        val state = updateManager.downloadState.value
        if (state is UpdateDownloadState.Completed) {
            updateManager.installApk(state.file)
        }
    }

    /** Cancels any active download. */
    fun cancelUpdateDownload() {
        updateManager.cancelDownload()
    }

    /** Clears update info (e.g., after dismissing the banner). */
    fun dismissUpdate() {
        _updateInfo.value = null
        updateManager.resetDownloadState()
    }
}
