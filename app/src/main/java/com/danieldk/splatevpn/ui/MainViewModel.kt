package com.danieldk.splatevpn.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danieldk.splatevpn.data.ServerNode
import com.danieldk.splatevpn.data.ServerRepository
import com.danieldk.splatevpn.data.SettingsManager
import com.danieldk.splatevpn.data.AppLogManager
import com.danieldk.splatevpn.data.LogEntry
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
import kotlinx.coroutines.flow.stateIn
import com.danieldk.splatevpn.data.SpeedtestManager
import com.danieldk.splatevpn.data.SpeedtestState
import com.danieldk.splatevpn.data.BackupManager
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
    private val settingsManager: SettingsManager,
    private val vpnStatusManager: VpnStatusManager,
    private val appLogManager: AppLogManager,
    private val updateManager: UpdateManager,
    private val speedtestManager: SpeedtestManager,
    private val backupManager: BackupManager,
    private val autoBackupManager: com.danieldk.splatevpn.data.AutoBackupManager,
    private val liveConnectionTracker: com.danieldk.splatevpn.vpn.LiveConnectionTracker,
    private val lanShareManager: com.danieldk.splatevpn.vpn.LanShareManager,
    private val trafficHistory: com.danieldk.splatevpn.data.TrafficHistory,
    private val connectionChecks: com.danieldk.splatevpn.data.ConnectionChecks,
    private val aiDnsAutoSelector: com.danieldk.splatevpn.data.AiDnsAutoSelector,
    private val adFilters: com.danieldk.splatevpn.data.AdFilterRepository
) : ViewModel() {
    val trafficDays = trafficHistory.days
    val serviceChecks = connectionChecks.services
    val diagnostics = connectionChecks.diagnostics
    private var serviceCheckJob: Job? = null
    private var diagnosticsJob: Job? = null
    fun clearTrafficHistory() = trafficHistory.clear()
    fun checkServices() {
        if (serviceCheckJob?.isActive == true) return
        serviceCheckJob = viewModelScope.launch { connectionChecks.checkServices() }
    }
    fun diagnoseConnection() {
        if (diagnosticsJob?.isActive == true) return
        val node = selectedServer.value
        diagnosticsJob = viewModelScope.launch { connectionChecks.diagnose(node) }
    }
    fun cancelServiceChecks() { serviceCheckJob?.cancel() }
    fun cancelDiagnostics() { diagnosticsJob?.cancel() }

    val isShareLan: StateFlow<Boolean> = settingsManager.isShareLanFlow

    fun setShareLan(enabled: Boolean) {
        settingsManager.setShareLanEnabled(enabled)
        if (isConnected.value) viewModelScope.launch { _reconnectEvent.emit(Unit) }
    }

    fun getActiveLanEndpoints(): List<com.danieldk.splatevpn.vpn.LanEndpoint> {
        return lanShareManager.getActiveLanEndpoints()
    }

    val liveConnections: StateFlow<List<com.danieldk.splatevpn.vpn.LiveConnection>> = liveConnectionTracker.connections
    val liveConnectionsTotalCount: StateFlow<Int> = liveConnectionTracker.totalCount
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

    // Полноэкранная заставка инициализации требуется исключительно при первом холодном запуске после установки,
    // пока предустановленные узлы распаковываются в базу данных Room.
    // Если база данных уже заполнена или туннель активен, экран загрузки не показывается, и главный экран открывается мгновенно.
    private val isInitialSetupNeeded: Boolean =
        !settingsManager.hasInitializedPresetSubscriptions() ||
        !settingsManager.hasInitializedUniversalWhitelist()

    private val _isStartupLoading = MutableStateFlow(isInitialSetupNeeded)
    val isStartupLoading: StateFlow<Boolean> = _isStartupLoading.asStateFlow()

    fun selectTab(tab: com.danieldk.splatevpn.ui.screens.AppTab) {
        _selectedTab.value = tab
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
            speedtestManager.startSpeedtest()
        }
    }

    fun resetSpeedtest() {
        speedtestManager.reset()
    }

    suspend fun exportBackup(uri: android.net.Uri): Result<Int> = backupManager.exportBackup(uri)
    suspend fun importBackup(uri: android.net.Uri, mode: BackupManager.ImportMode = BackupManager.ImportMode.MERGE): Result<Int> {
        onManualAiSettingsChange()
        val result = backupManager.importBackup(uri, mode)
        if (result.isSuccess) {
            _perAppPackages.value = settingsManager.getPerAppPackages()
            _dnsProvider.value = settingsManager.getDnsProvider()
            _xhttpMode.value = settingsManager.getXhttpMode()
            _autoUpdateInterval.value = settingsManager.getAutoUpdateInterval()
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

    val allServers: StateFlow<List<ServerNode>> = serverRepository.allServers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allSubscriptions = serverRepository.allSubscriptions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val selectedServer: StateFlow<ServerNode?> = serverRepository.selectedServer
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

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
    fun setGlassBlurEnabled(enabled: Boolean) = settingsManager.setGlassBlurEnabled(enabled)
    fun previewGlassTransparency(value: Float) = settingsManager.setGlassTransparency(value, persist = false)
    fun setGlassTransparency(value: Float) = settingsManager.setGlassTransparency(value)
    fun commitGlassTransparency() = settingsManager.setGlassTransparency(glassTransparency.value)
    fun setBalancerPreferences(value: com.danieldk.splatevpn.data.BalancerPreferences) = settingsManager.setBalancerPreferences(value)

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

    private val _isCheckingUpdate = MutableStateFlow(false)
    val isCheckingUpdate: StateFlow<Boolean> = _isCheckingUpdate.asStateFlow()

    /** Download progress/state from UpdateManager */
    val updateDownloadState: StateFlow<UpdateDownloadState> = updateManager.downloadState

    fun loadInstalledApps(context: android.content.Context, forceReload: Boolean = false) {
        if (_installedApps.value.size > 1 && !forceReload) return
        viewModelScope.launch(Dispatchers.IO) {
            _isLoadingApps.value = true
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
                        val hasLauncher = pm.getLaunchIntentForPackage(appInfo.packageName) != null

                        // Show all non-system apps or apps with launcher UI (Chrome, YouTube, etc.)
                        hasLauncher || !isSystem || isUpdatedSystem
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
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Error loading apps: ${e.message}", e)
            } finally {
                _isLoadingApps.value = false
            }
        }
    }

    private val _perAppPackages = MutableStateFlow(settingsManager.getPerAppPackages())
    val perAppPackages: StateFlow<Set<String>> = _perAppPackages.asStateFlow()

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

    private val _autoUpdateInterval = MutableStateFlow(settingsManager.getAutoUpdateInterval())
    val autoUpdateInterval: StateFlow<String> = _autoUpdateInterval.asStateFlow()

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
    val aiDnsSelection = aiDnsAutoSelector.state
    private var aiDnsSelectionJob: Job? = null
    private var aiSettingsRevision = 0

    fun startAiDnsSelection() {
        if (aiDnsSelectionJob?.isActive == true) return
        val revision = aiSettingsRevision
        aiDnsSelectionJob = viewModelScope.launch {
            aiDnsAutoSelector.run { choice ->
                if (revision != aiSettingsRevision || !isBypassAi.value || aiRoutingMode.value == "vpn") false
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
        settingsManager.setPingMode(mode)
    }

    private val _isRouteOnlySniffing = MutableStateFlow(settingsManager.isRouteOnlySniffing())
    val isRouteOnlySniffing: StateFlow<Boolean> = _isRouteOnlySniffing.asStateFlow()

    val isAutoConnectOnBoot: StateFlow<Boolean> = settingsManager.isAutoConnectOnBootFlow

    fun setAutoConnectOnBoot(enabled: Boolean) {
        settingsManager.setAutoConnectOnBoot(enabled)
    }

    init {
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

    fun selectServer(id: String) {
        val currentId = selectedServer.value?.id
        android.util.Log.d("MainViewModel", "selectServer: requested id=$id, currentId=$currentId")
        if (currentId == id) {
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
            android.util.Log.d("MainViewModel", "selectServer: executing selection for $id")
            com.danieldk.splatevpn.vpn.SplateVpnService.unmarkDeadServer(id)
            serverRepository.updateServerPing(id, 0L)
            serverRepository.setActiveServer(id)
            if (isConnected.value) {
                _reconnectEvent.emit(Unit)
            }
        }
    }

    fun updateServer(server: ServerNode) {
        viewModelScope.launch {
            serverRepository.updateServer(server)
            if (selectedServer.value?.id == server.id && isConnected.value) {
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

    fun addServerFromLink(link: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val success = serverRepository.addServerFromLink(link)
            onResult(success)
        }
    }

    fun addServersFromLinks(links: String, onResult: (Int) -> Unit) {
        viewModelScope.launch {
            val trimmed = links.trim()
            if (com.danieldk.splatevpn.data.LinkParser.normalizeSubscriptionUrl(trimmed) != null ||
                com.danieldk.splatevpn.data.LinkParser.isEncryptedSubscriptionLink(trimmed)
            ) {
                val res = serverRepository.importSubscription(trimmed, null)
                onResult(res.getOrDefault(0))
            } else {
                val count = serverRepository.addServersFromLinks(
                    com.danieldk.splatevpn.data.LinkParser.unwrapImportLink(trimmed) ?: links
                )
                onResult(count)
            }
        }
    }

    fun importSubscription(url: String, title: String? = null, onResult: (Result<Int>) -> Unit) {
        viewModelScope.launch {
            val res = serverRepository.importSubscription(url, title)
            onResult(res)
        }
    }

    fun updateSubscription(subId: String, onResult: (Result<Int>) -> Unit) {
        viewModelScope.launch {
            val res = serverRepository.updateSubscription(subId)
            onResult(res)
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
        viewModelScope.launch {
            if (_isPinging.value) { onResult(null); return@launch }
            _isPinging.value = true
            val best = try {
                if (enableAutoBalancer) setAutoSelect(true)
                serverRepository.autoSelectFastestServer(excludeIds = com.danieldk.splatevpn.vpn.SplateVpnService.deadServerIds)
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
        _autoUpdateInterval.value = interval
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

    fun testAllPings() {
        viewModelScope.launch {
            if (_isPinging.value) return@launch
            _isPinging.value = true
            try {
                com.danieldk.splatevpn.vpn.SplateVpnService.clearDeadServers()
                if (settingsManager.getPingMode().lowercase() in listOf("real_delay", "http", "real")) {
                    serverRepository.testAllRealDelays()
                } else {
                    serverRepository.testAllPings()
                }
            } finally { _isPinging.value = false }
        }
    }

    fun testSubscriptionPings(subId: String, onFinished: (Int) -> Unit = {}) {
        viewModelScope.launch {
            if (_isPinging.value) { onFinished(0); return@launch }
            _isPinging.value = true
            val count = try { serverRepository.testSubscriptionPings(subId) }
                finally { _isPinging.value = false }
            onFinished(count)
        }
    }

    fun testServersPing(servers: List<ServerNode>, onFinished: (Int) -> Unit = {}) {
        viewModelScope.launch {
            if (servers.isEmpty() || _isPinging.value) { onFinished(0); return@launch }
            _isPinging.value = true
            try {
                if (settingsManager.getPingMode().lowercase() in listOf("real_delay", "http", "real")) {
                    serverRepository.testServersRealDelay(servers)
                } else {
                    serverRepository.testServersPing(servers)
                }
            } finally { _isPinging.value = false }
            onFinished(servers.size)
        }
    }

    fun testServerPing(server: ServerNode, onResult: (Long) -> Unit) {
        viewModelScope.launch { onResult(serverRepository.testPing(server)) }
    }

    fun testServerRealDelay(server: ServerNode, onResult: (Long) -> Unit) {
        viewModelScope.launch { onResult(serverRepository.testRealDelay(server)) }
    }

    fun testAllRealDelays(onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            if (_isPinging.value) return@launch
            _isPinging.value = true
            try {
                com.danieldk.splatevpn.vpn.SplateVpnService.clearDeadServers()
                serverRepository.testAllRealDelays()
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
        val server = selectedServer.value
        val ipDet = if (currentIp.value.isNotBlank()) "${countryFlag.value} ${countryName.value} (${currentIp.value})" else null
        return appLogManager.generateDiagnosticReport(
            activeServerName = server?.name,
            activeServerProtocol = "${server?.protocol}/${server?.network}",
            routingMode = routingMode.value,
            dnsProvider = dnsProvider.value,
            vpnConnected = isConnected.value,
            ipDetails = ipDet
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
                val info = updateManager.checkUpdate()
                if (info != null) {
                    _updateInfo.value = info
                }
            } finally {
                _isCheckingUpdate.value = false
            }
        }
    }

    /** Starts background download + auto-prompts install when done. */
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
