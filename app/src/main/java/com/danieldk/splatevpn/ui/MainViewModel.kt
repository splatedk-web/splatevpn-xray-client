package com.danieldk.splatevpn.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danieldk.splatevpn.data.ServerNode
import com.danieldk.splatevpn.data.ServerRepository
import com.danieldk.splatevpn.data.SettingsManager
import com.danieldk.splatevpn.data.AppLogManager
import com.danieldk.splatevpn.data.LogEntry
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
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
    private val settingsManager: SettingsManager,
    private val vpnStatusManager: VpnStatusManager,
    private val appLogManager: AppLogManager
) : ViewModel() {

    val logsFlow: StateFlow<List<LogEntry>> = appLogManager.logsFlow

    val isConnected: StateFlow<Boolean> = vpnStatusManager.isConnected
    val uploadSpeed: StateFlow<String> = vpnStatusManager.uploadSpeed
    val downloadSpeed: StateFlow<String> = vpnStatusManager.downloadSpeed
    val countryFlag: StateFlow<String> = vpnStatusManager.countryFlag
    val countryName: StateFlow<String> = vpnStatusManager.countryName
    val currentIp: StateFlow<String> = vpnStatusManager.currentIp
    val isDetectingIp: StateFlow<Boolean> = vpnStatusManager.isDetectingIp

    val allServers: StateFlow<List<ServerNode>> = serverRepository.allServers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allSubscriptions = serverRepository.allSubscriptions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val selectedServer: StateFlow<ServerNode?> = serverRepository.selectedServer
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val routingMode: StateFlow<String> = settingsManager.routingModeFlow
    val isGlobalAppProxy: StateFlow<Boolean> = settingsManager.isGlobalAppProxyFlow
    val isAutoSelect: StateFlow<Boolean> = settingsManager.isAutoSelectFlow

    // Event to trigger VPN reconnect when server changes while connected
    private val _reconnectEvent = MutableSharedFlow<Unit>()
    val reconnectEvent: SharedFlow<Unit> = _reconnectEvent.asSharedFlow()

    private val _perAppPackages = MutableStateFlow(settingsManager.getPerAppPackages())
    val perAppPackages: StateFlow<Set<String>> = _perAppPackages.asStateFlow()

    private val _isPinging = MutableStateFlow(false)
    val isPinging: StateFlow<Boolean> = _isPinging.asStateFlow()

    private val _dnsProvider = MutableStateFlow(settingsManager.getDnsProvider())
    val dnsProvider: StateFlow<String> = _dnsProvider.asStateFlow()

    private val _xhttpMode = MutableStateFlow(settingsManager.getXhttpMode())
    val xhttpMode: StateFlow<String> = _xhttpMode.asStateFlow()

    private val _autoUpdateInterval = MutableStateFlow(settingsManager.getAutoUpdateInterval())
    val autoUpdateInterval: StateFlow<String> = _autoUpdateInterval.asStateFlow()

    private val _isBlockIpv6 = MutableStateFlow(settingsManager.isBlockIpv6())
    val isBlockIpv6: StateFlow<Boolean> = _isBlockIpv6.asStateFlow()

    val isBlockQuic: StateFlow<Boolean> = settingsManager.isBlockQuicFlow
    val isFakeDns: StateFlow<Boolean> = settingsManager.isFakeDnsFlow

    private val _isRouteOnlySniffing = MutableStateFlow(settingsManager.isRouteOnlySniffing())
    val isRouteOnlySniffing: StateFlow<Boolean> = _isRouteOnlySniffing.asStateFlow()

    init {
        viewModelScope.launch {
            serverRepository.initDefaultServers()
        }
    }

    val appTheme: StateFlow<String> = settingsManager.appThemeFlow
    val appLanguage: StateFlow<String> = settingsManager.appLanguageFlow

    private var selectServerJob: Job? = null

    fun selectServer(id: String) {
        val currentId = selectedServer.value?.id
        android.util.Log.d("MainViewModel", "selectServer: requested id=$id, currentId=$currentId")
        if (currentId == id) {
            android.util.Log.d("MainViewModel", "selectServer: server $id is already selected, ignoring")
            return
        }

        // Disable auto-select when user explicitly chooses a server
        if (isAutoSelect.value) {
            setAutoSelect(false)
        }

        selectServerJob?.cancel()
        selectServerJob = viewModelScope.launch {
            android.util.Log.d("MainViewModel", "selectServer: executing selection for $id")
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

    fun updateAllSubscriptions(onResult: (Int) -> Unit) {
        viewModelScope.launch {
            val count = serverRepository.updateAllSubscriptions()
            onResult(count)
        }
    }

    fun autoSelectFastestServer(onResult: (ServerNode?) -> Unit = {}) {
        viewModelScope.launch {
            _isPinging.value = true
            setAutoSelect(true)
            val best = serverRepository.autoSelectFastestServer()
            _isPinging.value = false
            onResult(best)
        }
    }

    fun setAutoSelect(enabled: Boolean) {
        settingsManager.setAutoSelectServer(enabled)
    }

    fun setDnsProvider(provider: String) {
        settingsManager.setDnsProvider(provider)
        _dnsProvider.value = provider
    }

    fun setXhttpMode(mode: String) {
        settingsManager.setXhttpMode(mode)
        _xhttpMode.value = mode
    }

    fun setAutoUpdateInterval(interval: String) {
        settingsManager.setAutoUpdateInterval(interval)
        _autoUpdateInterval.value = interval
    }

    fun setBlockIpv6(block: Boolean) {
        settingsManager.setBlockIpv6(block)
        _isBlockIpv6.value = block
    }

    fun setBlockQuic(block: Boolean) {
        settingsManager.setBlockQuic(block)
    }

    fun setFakeDns(enabled: Boolean) {
        settingsManager.setFakeDns(enabled)
    }

    fun setRouteOnlySniffing(routeOnly: Boolean) {
        settingsManager.setRouteOnlySniffing(routeOnly)
        _isRouteOnlySniffing.value = routeOnly
    }

    fun testAllPings() {
        viewModelScope.launch {
            _isPinging.value = true
            serverRepository.testAllPings()
            _isPinging.value = false
        }
    }

    fun setRoutingMode(mode: String) {
        settingsManager.setRoutingMode(mode)
    }

    fun setGlobalAppProxy(global: Boolean) {
        settingsManager.setGlobalAppProxy(global)
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
        vpnStatusManager.setConnected(false)
    }
}
