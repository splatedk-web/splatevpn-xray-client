package com.danieldk.splatevpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import android.app.PendingIntent
import com.danieldk.splatevpn.MainActivity
import com.danieldk.splatevpn.SplateConstants
import com.danieldk.splatevpn.data.ServerRepository
import com.danieldk.splatevpn.data.SettingsManager
import com.danieldk.splatevpn.data.AppLogManager
import com.v2ray.ang.service.TProxyService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.combine
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import com.danieldk.splatevpn.data.ServerNode
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class SplateVpnService : VpnService() {

    @Inject lateinit var trafficHistory: com.danieldk.splatevpn.data.TrafficHistory

    companion object {
        @Volatile
        var isServiceActive: Boolean = false
            private set
        private val failedServerIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        private val failedUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
        val deadServerIds: MutableSet<String>
            get() {
                val now = System.nanoTime() / 1_000_000
                failedUntil.forEach { (id, until) ->
                    if (now >= until && failedUntil.remove(id, until)) failedServerIds.remove(id)
                }
                return failedServerIds
            }

        fun markDeadServer(serverId: String) {
            failedServerIds.add(serverId)
            failedUntil[serverId] = System.nanoTime() / 1_000_000 + 60_000L
        }

        @Volatile
        var lastAutoSwitchTimestamp: Long = 0L
            internal set

        private val switchTimestamps = java.util.concurrent.ConcurrentLinkedQueue<Long>()
        private const val SLIDING_WINDOW_MS = 300_000L // 5 минут скользящее окно
        private const val MAX_OPTIMIZE_SWITCHES_IN_WINDOW = 4 // максимум 4 фоновых оптимизации за 5 минут
        private const val DEAD_NODE_COOLDOWN_MS = 1_000L // 1 секунда минимальная пауза между переключениями мертвых узлов
        private const val OPTIMIZE_COOLDOWN_MS = 60_000L // 60 секунд для фоновой оптимизации качества

        val consecutiveAutoSwitchCount: Int
            get() = cleanAndGetWindowCount()

        private fun cleanAndGetWindowCount(): Int {
            val now = System.currentTimeMillis()
            while (true) {
                val oldest = switchTimestamps.peek() ?: break
                if (now - oldest > SLIDING_WINDOW_MS) {
                    switchTimestamps.poll()
                } else {
                    break
                }
            }
            return switchTimestamps.size
        }

        fun canAutoSwitch(isDeadNode: Boolean, optimizeCooldownMs: Long = OPTIMIZE_COOLDOWN_MS): Boolean {
            val now = System.currentTimeMillis()
            val minCooldown = if (isDeadNode) DEAD_NODE_COOLDOWN_MS else optimizeCooldownMs
            if (now - lastAutoSwitchTimestamp < minCooldown) {
                return false
            }
            if (!isDeadNode) {
                val count = cleanAndGetWindowCount()
                if (count >= MAX_OPTIMIZE_SWITCHES_IN_WINDOW) {
                    return false
                }
            }
            return true
        }

        fun recordAutoSwitch() {
            val now = System.currentTimeMillis()
            lastAutoSwitchTimestamp = now
            switchTimestamps.add(now)
        }

        fun resetAutoSwitchLimits() {
            switchTimestamps.clear()
            lastAutoSwitchTimestamp = 0L
        }

        fun clearDeadServers() {
            failedServerIds.clear()
            failedUntil.clear()
            resetAutoSwitchLimits()
        }

        fun unmarkDeadServer(serverId: String) {
            failedServerIds.remove(serverId)
            failedUntil.remove(serverId)
        }
    }

    @Inject
    lateinit var xrayCoreManager: XrayCoreManager

    @Inject
    lateinit var xrayConfigGenerator: XrayConfigGenerator

    @Inject
    lateinit var serverRepository: ServerRepository

    @Inject
    lateinit var settingsManager: SettingsManager
    @Inject lateinit var adFilters: com.danieldk.splatevpn.data.AdFilterRepository

    @Inject
    lateinit var vpnStatusManager: VpnStatusManager

    @Inject
    lateinit var appLogManager: AppLogManager
    @Inject lateinit var speedtestManager: com.danieldk.splatevpn.data.SpeedtestManager

    @Volatile private var vpnInterface: ParcelFileDescriptor? = null
    private var tProxyService: TProxyService? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var startupJob: Job? = null
    private var statsJob: Job? = null
    private var balancerJob: Job? = null
    private var standbyJob: Job? = null
    private var optimizationJob: Job? = null
    private var postConnectCheckJob: Job? = null
    private val autoSwitchMutex = Mutex()
    private var autoSelectObserverJob: Job? = null
    private val isStopping = java.util.concurrent.atomic.AtomicBoolean(false)
    private val vpnMutex = Mutex()

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var currentNetwork: Network? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private var hasStartedVpn = false

    internal inner class ProtectionBinder : android.os.Binder() {
        fun readStatus(): VpnProtectionStatus = readProtectionStatus()
    }

    private fun readProtectionStatus(): VpnProtectionStatus {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return VpnProtectionStatus()
        return try { VpnProtectionStatus.fromSystem(isAlwaysOn, isLockdownEnabled, vpnInterface != null) }
        catch (_: Exception) { VpnProtectionStatus() }
    }

    override fun onBind(intent: Intent?): android.os.IBinder? =
        if (intent?.action == "$packageName.READ_PROTECTION") ProtectionBinder() else super.onBind(intent)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START", null, SERVICE_INTERFACE -> { hasStartedVpn = true; startVpn() }
            "RELOAD" -> { hasStartedVpn = true; reloadVpn() }
            "STOP" -> { hasStartedVpn = true; stopVpn() }
        }
        return START_NOT_STICKY
    }

    private fun startVpn() {
        if (startupJob?.isActive == true || (isServiceActive && vpnStatusManager.state.value != VpnState.FAILED)) return
        isStopping.set(false)

        val notification = buildNotification("SplateVPN", "Инициализация защищенного туннеля...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }

        startupJob = serviceScope.launch {
            startVpnInternal()
        }
    }

    private suspend fun startVpnInternal() = vpnMutex.withLock {
        startVpnLocked()
    }

    private suspend fun startVpnLocked() {
        try {
            vpnStatusManager.setState(if (settingsManager.isAutoSelectServer()) VpnState.SELECTING_SERVER else VpnState.STARTING_CORE)
            // Ensure any previous tunnel/core/tun are cleaned up before starting
            try {
                tProxyService?.stop()
                tProxyService = null
                xrayCoreManager.stopCore()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("SplateVpn", "Pre-start cleanup: ${e.message}")
            }

            // Ensure default servers exist if none
            serverRepository.initDefaultServers()

            // Auto-select fastest server if enabled
            if (settingsManager.isAutoSelectServer()) {
                Log.d("SplateVpn", "Auto-select is enabled: testing pings and selecting fastest server")
                appLogManager.i("SplateVpn", "Автовыбор: ожидаем проверку рабочего сервера")
                val selectionStarted = android.os.SystemClock.elapsedRealtime()
                val verified = serverRepository.autoSelectFastestServer(excludeIds = deadServerIds, reuseRecentSelection = true)
                appLogManager.i("SplateVpn", "Автовыбор завершён за ${android.os.SystemClock.elapsedRealtime() - selectionStarted} мс; рабочий сервер найден: ${verified != null}")
                if (verified == null) {
                    vpnStatusManager.setState(VpnState.FAILED, "Не найден сервер, пропускающий реальный трафик")
                    cleanupAndStopLocked()
                    return
                }
            }

            val activeNode = serverRepository.getActiveServerSync()
            if (activeNode == null) {
                appLogManager.e("SplateVpn", "Сервер не выбран! Добавьте или выберите узел перед подключением.")
                cleanupAndStopLocked()
                return
            }

            val configJson = generateXrayConfig(activeNode, isReload = false)

            // 1. Start Xray Core Process
            vpnStatusManager.setState(VpnState.STARTING_CORE)
            val started = xrayCoreManager.startCore(configJson)
            if (!started) {
                appLogManager.e("SplateVpn", "Не удалось запустить ядро Xray, отмена запуска VPN")
                vpnStatusManager.setState(VpnState.FAILED, vpnStatusManager.errorMessage.value ?: "Xray core failed to start")
                cleanupAndStopLocked()
                return
            }
            vpnStatusManager.setState(VpnState.CONNECTING)

            // 2. Create hev-socks5-tunnel configuration (MTU 1400 prevents packet black holes)
            val hevConfigFile = File(filesDir, "hev-socks5-tunnel.yaml")
            val hevConfigContent = """
                tunnel:
                  mtu: 1400
                  ipv4: 172.19.0.1
                  ipv6: 'fd00::1'
                socks5:
                  port: ${SplateConstants.XRAY_SOCKS_PORT}
                  address: ${SplateConstants.XRAY_SOCKS_ADDRESS}
                  udp: 'udp'
                misc:
                  connect-timeout: 5000
                  tcp-read-write-timeout: 300000
                  udp-read-write-timeout: 60000
                  limit-nofile: 65535
                  log-level: warn
            """.trimIndent()
            hevConfigFile.writeText(hevConfigContent)

            // 3. Setup Android TUN interface and start tProxyService
            setupVpnInterface(
                perAppList = settingsManager.getPerAppPackages(),
                isGlobal = settingsManager.isGlobalAppProxy(),
                dnsProvider = settingsManager.getDnsProvider()
            )

            val vpnFd = vpnInterface
            if (vpnFd == null) {
                appLogManager.e("SplateVpn", "Ошибка: TUN интерфейс не был создан!")
                vpnStatusManager.setState(VpnState.FAILED, "TUN interface creation failed")
                cleanupAndStopLocked()
                return
            }
            appLogManager.i("SplateVpn", "TUN интерфейс успешно создан (FD=${vpnFd.fd})")
            isServiceActive = true

            // 4. Register network callback to track active physical network
            registerNetworkCallback()

            // 5. Start Traffic Stats Monitor with node info
            startStatsMonitor(activeNode.name)

            // 6. Transition to CONNECTED immediately (< 200ms)
            vpnStatusManager.connectedServerId = activeNode.id
            vpnStatusManager.setState(VpnState.CONNECTED)
            com.danieldk.splatevpn.ui.widget.SplateAppWidgetProvider.notifyUpdate(this)
            com.danieldk.splatevpn.ui.widget.SplateQuickWidgetProvider.notifyUpdate(this)
            SplateTileService.requestUpdate(this)
            updateNotification("Подключено: ${activeNode.name}", "Защищенное соединение установлено")
            appLogManager.i("SplateVpn", "Защищенное соединение установлено! [${activeNode.name}]")
            startAutoBalancerJob()
            startAutoSelectObserver()

            // 7. Perform Health Check & IP details detection in background
            launchPostConnectChecks(activeNode, isSwitch = false)

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            appLogManager.e("SplateVpn", "Исключение при старте VPN: ${e.message}", e)
            vpnStatusManager.setState(VpnState.FAILED, e.message)
            cleanupAndStopLocked()
        }
    }

    private fun startAutoSelectObserver() {
        autoSelectObserverJob?.cancel()
        autoSelectObserverJob = serviceScope.launch {
            combine(settingsManager.isAutoSelectFlow, settingsManager.balancerPreferences) { enabled, prefs -> enabled to prefs }
                .collect { (isEnabled, prefs) ->
                if (isEnabled && prefs.monitorsConnection) {
                    if (vpnStatusManager.state.value == VpnState.CONNECTED) {
                        appLogManager.i("SplateVpn", "Автовыбор сервера включен: запуск балансировщика")
                        startAutoBalancerJob()
                    }
                } else {
                    appLogManager.i("SplateVpn", "Автовыбор сервера отключен: остановка балансировщика")
                    balancerJob?.cancel()
                    balancerJob = null
                    standbyJob?.cancel()
                    standbyJob = null
                    optimizationJob?.cancel()
                    optimizationJob = null
                }
            }
        }
    }

    private suspend fun handleNodeUnhealthy(node: ServerNode, reason: String): Boolean {
        if (!autoSwitchMutex.tryLock()) return false
        try { return handleNodeUnhealthyLocked(node, reason) }
        finally { autoSwitchMutex.unlock() }
    }

    private suspend fun handleNodeUnhealthyLocked(node: ServerNode, reason: String): Boolean {
        if (serverRepository.getActiveServerSync()?.id != node.id) return false
        appLogManager.w("SplateVpn", "⚠️ Узел '${node.name}' не отвечает на проверку связи ($reason)!")

        if (!settingsManager.isAutoSelectServer() || !settingsManager.getBalancerPreferences().monitorsConnection || isStopping.get() || !isServiceActive) {
            appLogManager.w("SplateVpn", "Автопереключение отключено. Удерживаем выбранный узел '${node.name}'.")
            return false
        }

        // Проверяем: доступен ли физический интернет на устройстве
        if (!isPhysicalInternetAlive()) {
            appLogManager.w("SplateVpn", "🌐 Физический интернет недоступен на устройстве. Смена узла '${node.name}' отложена.")
            return false
        }

        // Circuit Breaker: для аварийного переключения мертвого узла проверяем только минимальный интервал кулдауна
        if (!canAutoSwitch(isDeadNode = true)) {
            appLogManager.w("SplateVpn", "⚡ Автопереключение узла временно отложено (кулдаун активен). Повтор в следующем цикле...")
            return false
        }

        if (node.id !in deadServerIds) {
            markDeadServer(node.id)
            serverRepository.recordServerFailure(node.id)
        }
        serverRepository.updateServerPing(node.id, -1L)
        val nextBest = serverRepository.autoSelectFastestServer(
            excludeIds = deadServerIds + node.id,
            urgent = true,
            commitSelection = false
        )

        if (nextBest != null && nextBest.id != node.id && !isStopping.get() && settingsManager.isAutoSelectServer() &&
            settingsManager.getBalancerPreferences().monitorsConnection &&
            serverRepository.activateAutoCandidate(nextBest, node.id)) {
            recordAutoSwitch()
            appLogManager.i("SplateVpn", "⚡ Аварийное переключение с нерабочего узла: '${node.name}' ➔ '${nextBest.name}'")
            reloadVpn()
            return true
        } else {
            appLogManager.w("SplateVpn", "Альтернативных рабочих узлов не найдено на данный момент.")
            return false
        }
    }

    private fun reloadVpn() {
        if (isStopping.get()) return
        serviceScope.launch {
            reloadVpnInternal()
        }
    }

    private suspend fun reloadVpnInternal() = vpnMutex.withLock {
        reloadVpnLocked()
    }

    private suspend fun reloadVpnLocked() {
        if (isStopping.get()) return

        // If TUN interface is not established or tProxyService is dead, perform a fresh start
        if (vpnInterface == null || tProxyService == null) {
            appLogManager.i("SplateVpn", "Туннель не активен, выполняется полная инициализация...")
            startVpnLocked()
            return
        }

        vpnStatusManager.setState(VpnState.STARTING_CORE)

        try {
            val activeNode = serverRepository.getActiveServerSync()
            if (activeNode == null) {
                appLogManager.e("SplateVpn", "Сервер не выбран! Остановка VPN.")
                cleanupAndStopLocked()
                return
            }

            val configJson = generateXrayConfig(activeNode, isReload = true)

            // 1. Reconfigure Android TUN interface with any updated routing/DNS/App parameters
            setupVpnInterface(
                perAppList = settingsManager.getPerAppPackages(),
                isGlobal = settingsManager.isGlobalAppProxy(),
                dnsProvider = settingsManager.getDnsProvider()
            )

            val vpnFd = vpnInterface
            if (vpnFd == null) {
                appLogManager.e("SplateVpn", "Ошибка: TUN интерфейс не был создан при переключении узла!")
                vpnStatusManager.setState(VpnState.FAILED, "TUN interface creation failed during reload")
                cleanupAndStopLocked()
                return
            }

            // 2. Stop current Xray core (guaranteeing port is freed)
            xrayCoreManager.stopCore()

            // 3. Start Xray with the updated configuration
            val started = xrayCoreManager.startCore(configJson)
            if (!started) {
                appLogManager.e("SplateVpn", "Не удалось перезапустить ядро Xray при смене узла!")
                cleanupAndStopLocked()
                return
            }

            vpnStatusManager.connectedServerId = activeNode.id
            vpnStatusManager.setState(VpnState.CONNECTED)
            com.danieldk.splatevpn.ui.widget.SplateAppWidgetProvider.notifyUpdate(this)
            com.danieldk.splatevpn.ui.widget.SplateQuickWidgetProvider.notifyUpdate(this)
            SplateTileService.requestUpdate(this)
            startAutoBalancerJob()

            // 4. Reset and refresh IP geolocation details and ping
            startStatsMonitor(activeNode.name)
            updateNotification("Подключено: ${activeNode.name}", "Узел переключен")
            vpnStatusManager.resetIpDetails()
            launchPostConnectChecks(activeNode, isSwitch = true)
            appLogManager.i("SplateVpn", "Узел успешно переключен: [${activeNode.name}]")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            appLogManager.e("SplateVpn", "Ошибка при переключении узла: ${e.message}", e)
            vpnStatusManager.setState(VpnState.FAILED, e.message)
        }
    }

    private fun generateXrayConfig(activeNode: ServerNode, isReload: Boolean): String {
        val routingMode = settingsManager.getRoutingMode()
        val dnsProvider = settingsManager.getDnsProvider()
        val isFakeDns = settingsManager.isFakeDns()
        val xhttpMode = settingsManager.getXhttpMode()
        val bypassAi = settingsManager.isBypassAi()
        val customRoutes = settingsManager.getCustomRoutes()

        val logPrefix = if (isReload) "Бесшовное переключение ядра на узел" else "Запуск VPN: Узел"
        appLogManager.i(
            "SplateVpn",
            "$logPrefix: '${activeNode.name}' [${activeNode.address}:${activeNode.port}], " +
                    "Протокол='${activeNode.protocol}/${activeNode.network}', Безопасность='${activeNode.security}', " +
                    "SNI='${activeNode.sni}', Host='${activeNode.host}', Path='${activeNode.path}', " +
                    "Режим='$routingMode', DNS='$dnsProvider', FakeDNS=$isFakeDns, BypassAI=$bypassAi, CustomRoutes=${customRoutes.size}"
        )

        return xrayConfigGenerator.generateConfig(
            node = activeNode,
            mode = routingMode,
            enableSniffing = settingsManager.isEnableSniffing(),
            routeOnlySniffing = settingsManager.isRouteOnlySniffing(),
            blockQuic = settingsManager.isBlockQuic(),
            blockIpv6 = settingsManager.isBlockIpv6(),
            adBlocker = settingsManager.isAdBlocker(),
            adBlockMatcher = if (settingsManager.isAdBlocker()) adFilters.prepareMatcher() else null,
            adBlockAllowlist = settingsManager.getAdBlockAllowlist(),
            dnsProvider = dnsProvider,
            customDohUrl = settingsManager.getCustomDohUrl(),
            isFakeDns = isFakeDns,
            xhttpModeOverride = xhttpMode,
            bypassAi = bypassAi,
            aiRoutingMode = settingsManager.getAiRoutingMode(),
            aiDnsProvider = settingsManager.getAiDnsProvider(),
            aiTlsFragment = settingsManager.isAiTlsFragmentEnabled(),
            customRoutes = customRoutes,
            enableTlsFragment = settingsManager.isTlsFragmentEnabled(),
            tlsFragmentLength = settingsManager.getTlsFragmentLength(),
            tlsFragmentInterval = settingsManager.getTlsFragmentInterval(),
            tlsFragmentPackets = settingsManager.getTlsFragmentPackets(),
            enableMux = settingsManager.isMuxEnabled(),
            muxConcurrency = settingsManager.getMuxConcurrency(),
            shareLan = settingsManager.isShareLanEnabled()
        )
    }

    private fun launchPostConnectChecks(activeNode: ServerNode, isSwitch: Boolean) {
        postConnectCheckJob?.cancel()
        postConnectCheckJob = serviceScope.launch(Dispatchers.IO) {
            delay(1000) // Give the newly started core time to accept traffic.
            if (isStopping.get() || !isServiceActive || serverRepository.getActiveServerSync()?.id != activeNode.id) return@launch

            val isHealthy = vpnStatusManager.performHealthCheck()
            if (isHealthy) {
                appLogManager.i("SplateVpn", "Проверка связи успешна [${activeNode.name}]")
                val realDelay = vpnStatusManager.rttMs.value
                if (realDelay > 0) {
                    serverRepository.recordTunnelSuccess(activeNode.id, realDelay)
                    serverRepository.updateServerPing(activeNode.id, realDelay)
                }
                unmarkDeadServer(activeNode.id)
                delay(400)
                vpnStatusManager.fetchCurrentIpDetails()
            } else {
                // Первая попытка не удалась, даем еще 2 секунды на стабилизацию
                delay(350)
                if (isStopping.get() || !isServiceActive || serverRepository.getActiveServerSync()?.id != activeNode.id) return@launch

                val retryHealthy = vpnStatusManager.performHealthCheck()
                if (retryHealthy) {
                    appLogManager.i("SplateVpn", "Проверка связи успешна со второй попытки [${activeNode.name}]")
                    val realDelay = vpnStatusManager.rttMs.value
                    if (realDelay > 0) {
                        serverRepository.recordTunnelSuccess(activeNode.id, realDelay)
                    serverRepository.updateServerPing(activeNode.id, realDelay)
                    }
                    unmarkDeadServer(activeNode.id)
                    delay(400)
                    vpnStatusManager.fetchCurrentIpDetails()
                } else {
                    // Respect custom failure confirmation count at startup as well as in the monitor.
                    repeat((settingsManager.getBalancerPreferences().failureConfirmations - 2).coerceAtLeast(0)) {
                        delay(settingsManager.getBalancerPreferences().healthIntervalSeconds * 1000L)
                        if (isStopping.get() || !isServiceActive || serverRepository.getActiveServerSync()?.id != activeNode.id) return@launch
                        if (vpnStatusManager.performHealthCheck()) {
                            unmarkDeadServer(activeNode.id)
                            return@launch
                        }
                    }
                    appLogManager.w("SplateVpn", "Узел '${activeNode.name}' не пропускает трафик после подключения.")
                    if (settingsManager.isAutoSelectServer()) {
                        val physAlive = isPhysicalInternetAlive()
                        if (physAlive) {
                            appLogManager.w("SplateVpn", "Физический интернет в норме, но узел '${activeNode.name}' не ответил. Немедленное переключение...")
                            handleNodeUnhealthy(activeNode, "Узел не пропустил трафик при первичном подключении")
                        } else {
                            appLogManager.w("SplateVpn", "Физическая сеть недоступна. Ожидание восстановления связи...")
                        }
                    }
                }
            }
        }
    }

    private val defaultNetworkRequest by lazy {
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun getPhysicalNetwork(): Network? {
        val cm = connectivityManager ?: (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager) ?: return null
        try {
            // First check activeNetwork directly (modern API 23+, fast path without allNetworks)
            val active = cm.activeNetwork
            if (active != null) {
                val caps = cm.getNetworkCapabilities(active)
                if (caps != null &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                ) {
                    return active
                }
            }

            val all = cm.allNetworks
            // 1. Prefer Wi-Fi with validated Internet
            for (net in all) {
                val caps = cm.getNetworkCapabilities(net) ?: continue
                val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val notVpn = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                val notRestricted = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                if (hasInternet && notVpn && notRestricted && isWifi) {
                    return net
                }
            }
            // 2. Fallback to any non-VPN network with Internet (Cellular, Ethernet)
            for (net in all) {
                val caps = cm.getNetworkCapabilities(net) ?: continue
                val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val notVpn = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                val notRestricted = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                if (hasInternet && notVpn && notRestricted) {
                    return net
                }
            }
            // 3. Fallback to Wi-Fi even if Internet validation is pending
            for (net in all) {
                val caps = cm.getNetworkCapabilities(net) ?: continue
                val notVpn = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                val notRestricted = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                if (notVpn && notRestricted && isWifi) {
                    return net
                }
            }
            // 4. Fallback to Cellular even if Internet validation is pending
            for (net in all) {
                val caps = cm.getNetworkCapabilities(net) ?: continue
                val notVpn = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                val notRestricted = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                val isCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                if (notVpn && notRestricted && isCellular) {
                    return net
                }
            }
        } catch (e: Exception) {
            Log.w("SplateVpn", "Error querying physical networks: ${e.message}")
        }
        return null
    }

    /**
     * Проверяет, доступен ли физический интернет на самом устройстве (Wi-Fi или сотовая связь)
     * в обход VPN-туннеля. Это позволяет балансировщику строго отличать падение связи на телефоне
     * от гибели конкретного сервера VPN.
     */
    private suspend fun isPhysicalInternetAlive(): Boolean = withContext(Dispatchers.IO) {
        val cm = connectivityManager ?: (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
        val physNet = getPhysicalNetwork() ?: return@withContext false
        val caps = cm?.getNetworkCapabilities(physNet) ?: return@withContext false

        // 1. Проверяем базовое наличие возможности выхода в интернет
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return@withContext false
        }

        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return@withContext true

        // 2. Прямой зонд вне VPN-интерфейса через сокет, привязанный к физической сети
        val probeTargets = listOf(
            "http://connectivitycheck.gstatic.com/generate_204",
            "http://cp.cloudflare.com/generate_204"
        )

        for (target in probeTargets) {
            var conn: HttpURLConnection? = null
            try {
                val url = URL(target)
                conn = (physNet.openConnection(url) as HttpURLConnection).apply {
                    connectTimeout = 2000
                    readTimeout = 2000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile)")
                }
                val code = conn.responseCode
                if (code == HttpURLConnection.HTTP_NO_CONTENT) {
                    return@withContext true
                }
            } catch (_: Exception) {
                // Пробуем следующий endpoint
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }
        }

        // 3. Fallback: если HTTP 204 блокирован локально провайдером, но ОС Android валидировала сеть
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            return@withContext true
        }

        false
    }

    private fun registerNetworkCallback() {
        try {
            if (connectivityManager == null) {
                connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            }
            val cm = connectivityManager ?: return

            unregisterNetworkCallback()

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val caps = cm.getNetworkCapabilities(network) ?: return
                    val isNotVpn = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    val notRestricted = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                    val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    if (!isNotVpn || !notRestricted || !hasInternet) return

                    val bestNet = getPhysicalNetwork() ?: network
                    val prev = currentNetwork
                    if (prev == bestNet) return

                    currentNetwork = bestNet
                    Log.d("SplateVpn", "Physical network available: $bestNet (was: $prev)")
                    appLogManager.i("SplateVpn", "Физическая сеть подключена: $bestNet")

                    // Bind VPN tunnel outbound to this physical network seamlessly without restarting Xray or recreating TUN
                    setUnderlyingNetworks(arrayOf(bestNet))
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    val isNotVpn = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    val notRestricted = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                    if (hasInternet && isNotVpn && notRestricted) {
                        val bestNet = getPhysicalNetwork() ?: network
                        if (currentNetwork != bestNet) {
                            val prev = currentNetwork
                            currentNetwork = bestNet
                            setUnderlyingNetworks(arrayOf(bestNet))
                            Log.d("SplateVpn", "Underlying network updated via capabilities: $bestNet (was: $prev)")
                        }
                    }
                }

                override fun onLost(network: Network) {
                    Log.d("SplateVpn", "Physical network lost: $network")

                    // CRITICAL FIX: Only react if the lost network is ACTUALLY the one currently in use by the VPN.
                    // Modern Android (especially Android 14-16 on OnePlus/Oppo/Xiaomi/Samsung) frequently spins up
                    // and tears down ephemeral auxiliary network interfaces (IMS, VoLTE, network slices, Wi-Fi probes).
                    // Reacting to those auxiliary networks causes catastrophic infinite reconnect loops!
                    if (network != currentNetwork) {
                        Log.d("SplateVpn", "Ignoring non-active network disconnect: $network (active: $currentNetwork)")
                        return
                    }

                    appLogManager.w("SplateVpn", "Основная физическая сеть отключена: $network")
                    val fallback = getPhysicalNetwork()
                    if (fallback != null && fallback != network) {
                        currentNetwork = fallback
                        appLogManager.i("SplateVpn", "Переключение на резервную физическую сеть: $fallback")
                        setUnderlyingNetworks(arrayOf(fallback))
                    } else {
                        currentNetwork = null
                        setUnderlyingNetworks(null)
                        appLogManager.w("SplateVpn", "Нет доступных физических сетей, ожидание подключения...")
                    }
                }
            }

            networkCallback = callback
            cm.registerNetworkCallback(defaultNetworkRequest, callback)
            Log.d("SplateVpn", "Default network callback registered successfully")
        } catch (e: Exception) {
            Log.e("SplateVpn", "Failed to register network callback", e)
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            networkCallback?.let {
                connectivityManager?.unregisterNetworkCallback(it)
            }
        } catch (e: Exception) {
            Log.w("SplateVpn", "Failed to unregister network callback: ${e.message}")
        }
        networkCallback = null
    }

    private fun setupVpnInterface(perAppList: Set<String>, isGlobal: Boolean, dnsProvider: String) {
        val builder = Builder()
            .setSession("SplateVPN")
            .setMtu(1400)
            .addAddress("172.19.0.1", 30)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        // Advertise our virtual resolver, not a public DNS server. Android's automatic
        // Private DNS otherwise upgrades public servers to DoT and bypasses Xray's
        // port-53 interception, including FakeDNS and per-domain Smart DNS selection.
        // The selected upstream provider is configured in XrayConfigGenerator.
        builder.addDnsServer(SplateConstants.TUN_DNS_ADDRESS)

        // Route public internet through VPN, bypassing private local networks (LAN).
        // This prevents router probes (e.g. 192.168.0.1:7) from entering the VPN tunnel
        // and causing 30-60s network validation stalls on Android.
        val publicCidrs = listOf(
            "1.0.0.0" to 8,
            "2.0.0.0" to 7,
            "4.0.0.0" to 6,
            "8.0.0.0" to 7,
            "11.0.0.0" to 8,
            "12.0.0.0" to 6,
            "16.0.0.0" to 4,
            "32.0.0.0" to 3,
            "64.0.0.0" to 2,
            "128.0.0.0" to 3,
            "160.0.0.0" to 5,
            "168.0.0.0" to 6,
            "170.0.0.0" to 7,
            "172.0.0.0" to 12,
            "172.32.0.0" to 11,
            "172.64.0.0" to 10,
            "172.128.0.0" to 9,
            "173.0.0.0" to 8,
            "174.0.0.0" to 7,
            "176.0.0.0" to 4,
            "192.0.0.0" to 9,
            "192.128.0.0" to 11,
            "192.160.0.0" to 13,
            "192.169.0.0" to 16,
            "192.170.0.0" to 15,
            "192.172.0.0" to 14,
            "192.176.0.0" to 12,
            "192.192.0.0" to 10,
            "193.0.0.0" to 8,
            "194.0.0.0" to 7,
            "196.0.0.0" to 6,
            "200.0.0.0" to 5,
            "208.0.0.0" to 4
        )
        for ((ip, prefix) in publicCidrs) {
            try {
                builder.addRoute(ip, prefix)
            } catch (e: Exception) {
                Log.w("SplateVpn", "Could not add route $ip/$prefix: ${e.message}")
            }
        }

        // Explicitly route FakeDNS pool (198.18.0.0/15) into TUN interface
        try {
            builder.addRoute("198.18.0.0", 15)
        } catch (e: Exception) {
            Log.w("SplateVpn", "Could not add FakeDNS route: ${e.message}")
        }

        // CRITICAL SECURITY FIX: Capture all IPv6 traffic (::/0) into TUN interface.
        // If blockIpv6 is true, Xray core blackholes all IPv6 packets (::/0 -> block)
        // and queryStrategy=UseIPv4 prevents IPv6 DNS resolution.
        // If TUN omitted IPv6, Android OS would route IPv6 directly outside the VPN
        // through mobile operator (MTS/MegaFon/Beeline/Tele2), completely leaking real IP!
        try {
            builder.addAddress("fd00::1", 126)
            builder.addRoute("::", 0)
        } catch (e: Exception) {
            Log.w("SplateVpn", "Could not add IPv6 blackhole route: ${e.message}")
        }

        // Per-app routing vs Global routing
        // NOTE: In Android VpnService.Builder, cannot mix addAllowedApplication and addDisallowedApplication!
        val perAppMode = settingsManager.getPerAppMode()
        if (!isGlobal) {
            if (perAppMode == SettingsManager.PER_APP_MODE_EXCLUDE) {
                // Blacklist mode: route everything EXCEPT selected packages
                // Always disallow self to prevent routing loops
                try {
                    builder.addDisallowedApplication(packageName)
                } catch (e: Exception) {
                    Log.w("SplateVpn", "Could not disallow self: ${e.message}")
                }
                perAppList.forEach { appPackage ->
                    try {
                        builder.addDisallowedApplication(appPackage)
                    } catch (e: Exception) {
                        Log.w("SplateVpn", "Package not found to disallow: $appPackage")
                    }
                }
            } else {
                // Whitelist mode: route ONLY selected packages
                if (perAppList.isNotEmpty()) {
                    perAppList.forEach { appPackage ->
                        try {
                            builder.addAllowedApplication(appPackage)
                        } catch (e: Exception) {
                            Log.w("SplateVpn", "Package not found to allow: $appPackage")
                        }
                    }
                } else {
                    // Empty whitelist: disallow self so VPN doesn't loop
                    try {
                        builder.addDisallowedApplication(packageName)
                    } catch (e: Exception) {
                        Log.w("SplateVpn", "Could not disallow self: ${e.message}")
                    }
                }
            }
        } else {
            // Global mode: Exclude our own app package to prevent routing loops
            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: Exception) {
                Log.w("SplateVpn", "Could not disallow self: ${e.message}")
            }
        }

        try {
            tProxyService?.stop()
        } catch (e: Exception) {
            Log.w("SplateVpn", "Error stopping previous tProxyService: ${e.message}")
        }
        tProxyService = null

        // Keep the old TUN route installed while HEV is stopped: packets cannot fall
        // through to the physical network during reconfiguration. Android replaces
        // the interface when establish() succeeds; only then release the old descriptor.
        val previousInterface = vpnInterface
        val physicalNet = getPhysicalNetwork()
        val replacement = builder.establish()
            ?: throw IllegalStateException("VPN interface could not be established")
        vpnInterface = replacement
        try {
            previousInterface?.close()
        } catch (e: Exception) {
            Log.w("SplateVpn", "Could not close replaced VPN descriptor: ${e.message}")
        }

        // Bind underlying network immediately so Android networking stack doesn't stall
        physicalNet?.let { activeNet ->
            setUnderlyingNetworks(arrayOf(activeNet))
            currentNetwork = activeNet
            appLogManager.i("SplateVpn", "Привязка VPN к физической сети: $activeNet")
        }

        vpnInterface?.let { newInterface ->
            try {
                val hevConfigFile = File(filesDir, "hev-socks5-tunnel.yaml")
                tProxyService = TProxyService(newInterface)
                tProxyService?.start(hevConfigFile.absolutePath)
                appLogManager.i("SplateVpn", "TUN интерфейс установлен (FD=${newInterface.fd}), hev-socks5-tunnel запущен")
            } catch (e: Exception) {
                appLogManager.e("SplateVpn", "Ошибка при запуске tProxyService: ${e.message}", e)
                throw e
            }
        }
    }

    private fun startStatsMonitor(serverName: String = "") {
        statsJob?.cancel()
        statsJob = serviceScope.launch {
            val initialStats = tProxyService?.getStats()
            val trafficCounter = com.danieldk.splatevpn.data.NativeTrafficCounter(initialStats)
            var lastSampleTime = android.os.SystemClock.elapsedRealtime()

            var tick = 0

            try { while (isActive) {
                delay(1000)
                val stats = tProxyService?.getStats()
                // Use the same native byte deltas for speed, session totals and history.
                // UID fallback includes unrelated app traffic and can replay bytes when JNI recovers.
                val (rxDelta, txDelta) = trafficCounter.sample(stats)
                val sampleTime = android.os.SystemClock.elapsedRealtime()
                val elapsedMs = (sampleTime - lastSampleTime).coerceAtLeast(1)
                val downBytesPerSec = (rxDelta * (1000.0 / elapsedMs)).toLong()
                val upBytesPerSec = (txDelta * (1000.0 / elapsedMs)).toLong()

                val downSpeedStr = formatSpeed(downBytesPerSec)
                val upSpeedStr = formatSpeed(upBytesPerSec)

                vpnStatusManager.updateSpeeds(
                    up = upSpeedStr,
                    down = downSpeedStr,
                    downBytesPerSec = downBytesPerSec
                )

                // Accumulate session bytes for the session traffic display
                vpnStatusManager.updateSessionBytes(rxDelta = rxDelta, txDelta = txDelta)

                if (vpnStatusManager.state.value == com.danieldk.splatevpn.vpn.VpnState.CONNECTED) {
                    trafficHistory.record(System.currentTimeMillis(), elapsedMs, rxDelta, txDelta)
                }
                lastSampleTime = sampleTime

                tick++
                if (tick % 3 == 0 && !isStopping.get()) {
                    val nodeTitle = if (serverName.isNotBlank()) "Подключено: $serverName" else "SplateVPN: Подключено"
                    val currentPing = vpnStatusManager.rttMs.value
                    val subText = if (currentPing > 0) "⚡ $currentPing ms • ↓ $downSpeedStr" else "↓ $downSpeedStr"
                    updateNotification(nodeTitle, "↓ $downSpeedStr  •  ↑ $upSpeedStr", subText)
                    com.danieldk.splatevpn.ui.widget.SplateAppWidgetProvider.notifyUpdate(this@SplateVpnService)
                    com.danieldk.splatevpn.ui.widget.SplateQuickWidgetProvider.notifyUpdate(this@SplateVpnService)
                }
            } } finally { trafficHistory.flush() }
        }
    }

    /** Health detection is independent of throughput and background optimization work. */
    private fun startAutoBalancerJob() {
        balancerJob?.cancel()
        standbyJob?.cancel()
        optimizationJob?.cancel()
        if (!settingsManager.isAutoSelectServer() || !settingsManager.getBalancerPreferences().monitorsConnection) return

        standbyJob = serviceScope.launch(Dispatchers.IO) {
            delay(2000)
            while (isActive && !isStopping.get() && settingsManager.isAutoSelectServer()) {
                try {
                    if (vpnStatusManager.state.value == VpnState.CONNECTED && isPhysicalInternetAlive()) {
                        serverRepository.refreshStandbyServers(deadServerIds.toSet())
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("SplateVpn", "Standby check: ${e.message}")
                }
                delay(20_000)
            }
        }

        balancerJob = serviceScope.launch(Dispatchers.IO) {
            delay(1000)
            var baselinePing = -1L
            var degradedSamples = 0
            var failedSamples = 0
            var nextOptimizationAt = System.nanoTime() / 1_000_000 + settingsManager.getBalancerPreferences().optimizationMinutes * 60_000L

            while (isActive && !isStopping.get()) {
                if (vpnStatusManager.state.value == VpnState.RECONNECTING) {
                    delay(500)
                    continue
                }
                if (vpnStatusManager.state.value != VpnState.CONNECTED) break
                if (!settingsManager.isAutoSelectServer()) break
                val prefs = settingsManager.getBalancerPreferences()
                if (!prefs.monitorsConnection) break
                try {
                    val node = serverRepository.getActiveServerSync()
                    if (node != null && node.id == vpnStatusManager.connectedServerId) {
                        val budget = (baselinePing.coerceAtLeast(0) * 4).coerceIn(1500L, 3000L).toInt()
                        val ping = serverRepository.testPingViaXray(timeoutMs = budget)
                        if (ping > 0) {
                            failedSamples = 0
                            serverRepository.recordTunnelSuccess(node.id, ping)
                            serverRepository.updateServerPing(node.id, ping)
                            val severelyDegraded = baselinePing > 0 && ping > 350L && ping > baselinePing * 2.2
                            degradedSamples = if (severelyDegraded) degradedSamples + 1 else 0
                            if (!severelyDegraded) baselinePing = if (baselinePing <= 0) ping else (baselinePing * 0.85 + ping * 0.15).toLong()
                            val now = System.nanoTime() / 1_000_000
                            val periodic = now >= nextOptimizationAt
                            if (periodic) nextOptimizationAt = now + prefs.optimizationMinutes * 60_000L
                            if (prefs.optimizesConnection && !speedtestManager.isRunning && (degradedSamples >= 3 || periodic) &&
                                vpnStatusManager.downBytesPerSec.value < 100_000L &&
                                canAutoSwitch(false, prefs.cooldownSeconds * 1000L) && optimizationJob?.isActive != true) {
                                optimizationJob = serviceScope.launch(Dispatchers.IO) {
                                    try {
                                        val better = serverRepository.findBetterServerIfAvailable(node, ping, deadServerIds.toSet(), minImprovementRatio = prefs.improvementPercent / 100f)
                                        if (better != null && !isStopping.get() && settingsManager.isAutoSelectServer() &&
                                            settingsManager.getBalancerPreferences().optimizesConnection && !speedtestManager.isRunning &&
                                            canAutoSwitch(false, settingsManager.getBalancerPreferences().cooldownSeconds * 1000L) &&
                                            serverRepository.activateAutoCandidate(better, node.id)) {
                                            recordAutoSwitch()
                                            appLogManager.i("SplateVpn", "Балансировщик: переход на проверенный стабильный узел '${better.name}' (${better.ping}ms)")
                                            reloadVpn()
                                        }
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        Log.w("SplateVpn", "Optimization: ${e.message}")
                                    }
                                }
                                degradedSamples = 0
                            }
                        } else if (isPhysicalInternetAlive()) {
                            failedSamples++
                            if (failedSamples < prefs.failureConfirmations - 1) {
                                delay(prefs.healthIntervalSeconds * 1000L)
                                continue
                            }
                            // Independent endpoints plus a second, more generous check avoid switching on one timeout.
                            delay(200)
                            if (!isActive || isStopping.get() || !settingsManager.isAutoSelectServer()) break
                            val confirmed = serverRepository.testPingViaXray(timeoutMs = 3000)
                            if (confirmed > 0) {
                                failedSamples = 0
                                serverRepository.recordTunnelSuccess(node.id, confirmed)
                                serverRepository.updateServerPing(node.id, confirmed)
                            } else if (handleNodeUnhealthy(node, "${prefs.failureConfirmations} проверок реального трафика не прошли при рабочем интернете")) {
                                break
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("SplateVpn", "Balancer check: ${e.message}")
                }
                delay(settingsManager.getBalancerPreferences().healthIntervalSeconds * 1000L)
            }
        }
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        return when {
            bytesPerSec < 1024 -> "$bytesPerSec B/s"
            bytesPerSec < 1024 * 1024 -> String.format(Locale.US, "%.1f KB/s", bytesPerSec / 1024.0)
            else -> String.format(Locale.US, "%.1f MB/s", bytesPerSec / (1024.0 * 1024.0))
        }
    }

    private fun stopVpn() {
        isStopping.set(true)
        startupJob?.cancel()
        serviceScope.launch {
            stopVpnInternal()
        }
    }

    private suspend fun stopVpnInternal() = vpnMutex.withLock {
        cleanupAndStopLocked()
    }

    private suspend fun cleanupAndStopLocked() {
        try {
            appLogManager.i("SplateVpn", "Остановка службы VPN и освобождение ресурсов...")
            isServiceActive = false
            com.danieldk.splatevpn.ui.widget.SplateAppWidgetProvider.notifyUpdate(this)
            com.danieldk.splatevpn.ui.widget.SplateQuickWidgetProvider.notifyUpdate(this)
            SplateTileService.requestUpdate(this)
            statsJob?.cancel()
            statsJob = null
            balancerJob?.cancel()
            balancerJob = null
            standbyJob?.cancel()
            standbyJob = null
            optimizationJob?.cancel()
            optimizationJob = null
            postConnectCheckJob?.cancel()
            postConnectCheckJob = null
            autoSelectObserverJob?.cancel()
            autoSelectObserverJob = null
            resetAutoSwitchLimits()
            if (vpnStatusManager.state.value != VpnState.FAILED) {
                vpnStatusManager.setState(VpnState.DISCONNECTED)
            }

            unregisterNetworkCallback()
            currentNetwork = null

            try {
                tProxyService?.stop()
            } catch (e: Exception) {
                Log.w("SplateVpn", "Error stopping tProxy: ${e.message}")
            }
            tProxyService = null

            try {
                xrayCoreManager.stopCore()
            } catch (e: Exception) {
                Log.w("SplateVpn", "Error stopping Xray core: ${e.message}")
            }

            try {
                vpnInterface?.close()
            } catch (e: Exception) {
                Log.w("SplateVpn", "Error closing vpnInterface: ${e.message}")
            }
            vpnInterface = null

            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            Log.d("SplateVpn", "VPN stopped completely.")
        } catch (e: Exception) {
            Log.e("SplateVpn", "Error stopping VPN", e)
        }
    }

    override fun onRevoke() {
        Log.w("SplateVpn", "VPN permission revoked by system (e.g. another VPN started)")
        appLogManager.w("SplateVpn", "VPN разрешение отозвано системой (активирован другой VPN). Служба останавливается.")
        isServiceActive = false
        vpnStatusManager.setState(VpnState.DISCONNECTED)
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        super.onDestroy()
        // A status-only binding must never connect/disconnect a VPN or reset its UI state.
        if (!hasStartedVpn) {
            serviceScope.cancel()
            return
        }
        isServiceActive = false
        vpnStatusManager.setState(VpnState.DISCONNECTED)
        statsJob?.cancel()
        statsJob = null
        unregisterNetworkCallback()
        try {
            tProxyService?.stop()
        } catch (e: Exception) {
            Log.w("SplateVpn", "onDestroy tProxy stop error: ${e.message}")
        }
        tProxyService = null

        // Fire-and-forget Xray core shutdown on IO thread to avoid ANR from blocking Main Thread
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                xrayCoreManager.stopCore()
            } catch (e: Exception) {
                Log.w("SplateVpn", "onDestroy xray stop error: ${e.message}")
            }
        }

        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.w("SplateVpn", "onDestroy vpnInterface close error: ${e.message}")
        }
        vpnInterface = null

        serviceScope.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "splate_vpn_channel_v2",
                "VPN Status & Fluid Cloud",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Live VPN status, speed telemetry, and OnePlus Fluid Cloud capsule"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, text: String? = null, subText: String? = null): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val disconnectIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, SplateVpnService::class.java).apply {
                action = "STOP"
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val sessionStart = vpnStatusManager.sessionStartTime.value

        val builder = NotificationCompat.Builder(this, "splate_vpn_channel_v2")
            .setContentTitle(title)
            .setContentText(text ?: "SplateVPN активен — трафик защищён")
            .setSmallIcon(com.danieldk.splatevpn.R.drawable.ic_qs_vpn)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH) // Whitelisted by OxygenOS/ColorOS for Fluid Cloud capsule
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Отключить",
                disconnectIntent
            )

        // OnePlus OxygenOS 14/15 Fluid Cloud / Live Capsule metadata
        builder.extras.putString("android.substName", "SplateVPN")
        builder.extras.putBoolean("oplus_capsule", true)
        builder.extras.putString("oplus_view_type", "capsule")
        builder.extras.putString("oplus.view.type", "capsule")

        if (sessionStart > 0L) {
            builder.setUsesChronometer(true)
            builder.setWhen(sessionStart)
            builder.setShowWhen(true)
        } else {
            builder.setShowWhen(false)
        }

        if (!subText.isNullOrBlank()) {
            builder.setSubText(subText)
        }

        return builder.build()
    }

    private fun updateNotification(title: String, text: String? = null, subText: String? = null) {
        if (isStopping.get()) return
        try {
            val notification = buildNotification(title, text, subText)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.notify(1, notification)
        } catch (e: Exception) {
            // ignore
        }
    }
}
