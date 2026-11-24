package com.danieldk.splatevpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import kotlinx.coroutines.flow.collectLatest
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import com.danieldk.splatevpn.data.AiDnsAutoSelector
import com.danieldk.splatevpn.data.AiDnsChoice
import com.danieldk.splatevpn.data.AiDnsMonitorConfig
import com.danieldk.splatevpn.data.monitorAiDns
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import com.danieldk.splatevpn.data.ServerNode
import com.danieldk.splatevpn.data.currentNetworkFingerprint
import com.danieldk.splatevpn.data.forConnection
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class SplateVpnService : VpnService() {

    @Inject lateinit var trafficHistory: com.danieldk.splatevpn.data.TrafficHistory

    companion object {
        private val vpnMutex = Mutex()
        @Volatile
        var isServiceActive: Boolean = false
            private set
        private val failedUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
        internal var deadServerClock: () -> Long = { android.os.SystemClock.elapsedRealtime() }
        val deadServerIds: Set<String>
            get() {
                val now = deadServerClock()
                return failedUntil.entries.mapNotNullTo(hashSetOf()) { (id, until) ->
                    if (now < until) id else {
                        failedUntil.remove(id, until)
                        null
                    }
                }
            }

        fun markDeadServer(serverId: String) {
            failedUntil[serverId] = deadServerClock() + 60_000L
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
            failedUntil.clear()
            resetAutoSwitchLimits()
        }

        fun unmarkDeadServer(serverId: String) {
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
    @Inject lateinit var temporaryRouting: com.danieldk.splatevpn.data.TemporaryRouting
    @Inject lateinit var networkBindings: com.danieldk.splatevpn.data.NetworkProfileBindings
    @Inject lateinit var adFilters: com.danieldk.splatevpn.data.AdFilterRepository

    @Inject
    lateinit var vpnStatusManager: VpnStatusManager

    @Inject
    lateinit var appLogManager: AppLogManager
    @Inject lateinit var speedtestManager: com.danieldk.splatevpn.data.SpeedtestManager
    @Inject lateinit var aiDnsAutoSelector: AiDnsAutoSelector
    @Inject lateinit var lanShareManager: LanShareManager
    @Inject lateinit var dnsHealthAdvisor: com.danieldk.splatevpn.data.DnsHealthAdvisor
    @Inject lateinit var tunnelHealthAdvisor: com.danieldk.splatevpn.data.TunnelHealthAdvisor
    @Inject lateinit var torRepository: com.danieldk.splatevpn.data.TorRepository
    @Inject lateinit var torRuntime: TorRuntime
    @Inject lateinit var torBridgeProbeRepository: com.danieldk.splatevpn.data.TorBridgeProbeRepository
    @Inject lateinit var liveConnectionTracker: LiveConnectionTracker
    @Inject lateinit var subscriptionDao: com.danieldk.splatevpn.data.SubscriptionDao
    private var awgSocketProtection: AwgSocketProtection? = null
    private var usingTor = false
    private var torNode: ServerNode? = null
    private var preparedLanAccess: LanAccess? = null
    private var preparedLocalProxyAccess: LocalProxyCredentials? = null
    private var preparedSocksPort: Int = SplateConstants.XRAY_SOCKS_PORT
    private var preparedDnsProbePort: Int? = null
    private var preparedAppIdentityAccess: AppIdentityAccess? = null
    private var preparedAppIdentityResolver: TProxyService.FlowIdentityResolver? = null
    private var localProxyRuntimeToken: Long? = null
    private var preparedSubscriptionRules: SubscriptionRulesSnapshot? = null
    @Volatile private var appliedSubscriptionRules: SubscriptionRulesSnapshot? = null
    private var preparedAdvancedRules: List<com.google.gson.JsonObject> = emptyList()
    @Volatile private var appliedAdvancedRules: List<com.google.gson.JsonObject>? = null

    @Volatile private var vpnInterface: ParcelFileDescriptor? = null
    private var tProxyService: TProxyService? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var startupJob: Job? = null
    private val torRecoveryBudget = CoreRestartBudget(maxRetries = 3)
    private var statsJob: Job? = null
    private var balancerJob: Job? = null
    private var standbyJob: Job? = null
    private var optimizationJob: Job? = null
    private var postConnectCheckJob: Job? = null
    private var postConnectDiagnosticJob: Job? = null
    private val autoSwitchMutex = Mutex()
    private var autoSelectObserverJob: Job? = null
    private var aiDnsMonitorJob: Job? = null
    private var torMonitorJob: Job? = null
    private var torHealthJob: Job? = null
    private var recoveryJob: Job? = null
    private val isStopping = java.util.concurrent.atomic.AtomicBoolean(false)

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val physicalNetworkFlow = MutableStateFlow<Network?>(null)
    @Volatile private var networkInterrupted = false
    @Volatile private var currentNetwork: Network? = null
        set(value) {
            if (field != null && value == null) networkInterrupted = true
            val changed = field != value
            field = value
            if (changed && value != null) AmneziaWgRuntime.rebindAll()
            physicalNetworkFlow.value = value
            com.danieldk.splatevpn.data.NetworkAccessPolicy.setVpnUnderlyingNetwork(value)
        }

    override fun onCreate() {
        super.onCreate()
        awgSocketProtection = AwgSocketProtection(this) { fd -> currentNetwork?.bindSocket(fd) }
        createNotificationChannel()
        serviceScope.launch {
            settingsManager.notificationPreferences.collect {
                if (isServiceActive && vpnStatusManager.state.value == VpnState.CONNECTED) {
                    val name = vpnStatusManager.connectedServerProfile?.name.orEmpty()
                    val title = if (name.isNotBlank()) notificationText(com.danieldk.splatevpn.R.string.notification_connected, name) else "SplateVPN"
                    updateNotification(title, "↓ ${vpnStatusManager.downloadSpeed.value} • ↑ ${vpnStatusManager.uploadSpeed.value}", traffic = true)
                }
            }
        }
        serviceScope.launch {
            while (isActive) { delay(1000); temporaryRouting.prune() }
        }
        serviceScope.launch {
            combine(temporaryRouting.entries, physicalNetworkFlow, vpnStatusManager.state) { entries, network, state ->
                Triple(entries, physicalNetworkKind(network), state)
            }.collectLatest { (entries, kind, state) ->
                if (state == VpnState.CONNECTED && !isStopping.get()) {
                    val actualNetwork = currentNetwork
                    val networkChanged = actualNetwork != null &&
                        (networkInterrupted || (appliedPhysicalNetwork != null && actualNetwork != appliedPhysicalNetwork))
                    if (networkChanged) {
                        delay(700)
                        if (currentNetwork != actualNetwork || vpnStatusManager.state.value != VpnState.CONNECTED) return@collectLatest
                    }
                    val routesChanged = appliedTemporaryEntries != entries
                    val kindChanged = kind != null && kind != appliedNetworkKind
                    val profileChanged = kindChanged && networkBindings.apply(requireNotNull(kind))
                    appliedNetworkKind = kind
                    if (profileChanged) appLogManager.i("NetworkProfile", "Applied approved profile: $kind")
                    if (actualNetwork != null) appliedPhysicalNetwork = actualNetwork
                    if (networkChanged) {
                        clearDeadServers()
                        appLogManager.i("NetworkHandover", "Physical network changed; resetting dead server quarantine and reopening sessions")
                    }
                    if (routesChanged || profileChanged || networkChanged) withContext(Dispatchers.Main.immediate) { reloadVpn() }
                }
            }
        }
        serviceScope.launch {
            combine(subscriptionDao.getAllSubscriptions(), serverRepository.selectedServer, vpnStatusManager.state,
                settingsManager.advancedRouting) { subscriptions, node, state, advanced ->
                Triple(state, SubscriptionRulesSnapshot.desired(node, subscriptions),
                    runCatching { com.danieldk.splatevpn.data.AdvancedRoutingRules.compile(com.danieldk.splatevpn.data.AdvancedRoutingRules.decode(advanced)) }.getOrNull())
            }.distinctUntilChanged().collectLatest { (state, desired, desiredAdvanced) ->
                if (state != VpnState.CONNECTED || usingTor || !isServiceActive || isStopping.get() ||
                    desired?.nodeId != vpnStatusManager.connectedServerId ||
                    appliedSubscriptionRules?.needsReload(desired, vpnStatusManager.connectedServerId) != true && appliedAdvancedRules == desiredAdvanced) return@collectLatest
                // Room can emit the subscription and selected-row changes separately. Manual
                // settings actions may already be reloading this same approved revision.
                delay(150)
                startupJob?.join()
                if (usingTor || !isServiceActive || isStopping.get() || vpnStatusManager.state.value != VpnState.CONNECTED) return@collectLatest
                val current = SubscriptionRulesSnapshot.desired(serverRepository.selectedServer.first(), subscriptionDao.getAllSubscriptionsSync())
                val currentAdvanced = runCatching { com.danieldk.splatevpn.data.AdvancedRoutingRules.compile(
                    com.danieldk.splatevpn.data.AdvancedRoutingRules.decode(settingsManager.advancedRoutingJson())) }.getOrNull()
                withContext(Dispatchers.Main.immediate) {
                    if (!usingTor && isServiceActive && !isStopping.get() && vpnStatusManager.state.value == VpnState.CONNECTED &&
                        startupJob?.isActive != true && current?.nodeId == vpnStatusManager.connectedServerId &&
                        (appliedSubscriptionRules?.needsReload(current, vpnStatusManager.connectedServerId) == true || appliedAdvancedRules != currentAdvanced)) {
                        appLogManager.i("SubscriptionRules", "Active subscription routing changed; applying the approved rules")
                        reloadVpn()
                    }
                }
            }
        }
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

    private fun ensureForegroundNotification() {
        val notification = buildNotification("SplateVPN", notificationText(com.danieldk.splatevpn.R.string.notification_starting))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != "STOP") {
            ensureForegroundNotification()
        }
        when (intent?.action) {
            "START", null, SERVICE_INTERFACE -> { hasStartedVpn = true; startVpn() }
            "RELOAD" -> { hasStartedVpn = true; reloadVpn() }
            "RESTART_MODE" -> { hasStartedVpn = true; restartConnectionMode() }
            "STOP" -> { hasStartedVpn = true; stopVpn() }
            "APPLY_DNS" -> {
                hasStartedVpn = true
                val providerKey = intent.getStringExtra("dns_provider")
                if (!providerKey.isNullOrBlank()) {
                    settingsManager.setDnsProvider(providerKey)
                    dnsHealthAdvisor.dismiss()
                    reloadVpn()
                }
            }
            "ENABLE_FAKEDNS" -> {
                hasStartedVpn = true
                settingsManager.setFakeDns(true)
                dnsHealthAdvisor.dismiss()
                reloadVpn()
            }
            "USE_STANDARD_FRAGMENTATION" -> {
                hasStartedVpn = true
                if (tunnelHealthAdvisor.applyStandardFragmentation()) reloadVpn()
            }
            "SWITCH_BETTER_SERVER" -> {
                hasStartedVpn = true
                dnsHealthAdvisor.dismiss()
                serviceScope.launch {
                    val currentId = vpnStatusManager.connectedServerId
                    val candidates = serverRepository.getRankedCandidateServers(excludeIds = setOfNotNull(currentId))
                    val nextServer = candidates.firstOrNull() ?: serverRepository.autoSelectFastestServer(excludeIds = setOfNotNull(currentId), urgent = true)
                    if (nextServer != null) {
                        serverRepository.setActiveServer(nextServer.id)
                        withContext(Dispatchers.Main.immediate) { reloadVpn() }
                    }
                }
            }
            "SET_MTU_1280" -> {
                hasStartedVpn = true
                settingsManager.setMtu(1280)
                dnsHealthAdvisor.dismiss()
                reloadVpn()
            }
            "ADD_DIRECT_EXCEPTION" -> {
                hasStartedVpn = true
                val domain = intent.getStringExtra("domain")
                if (!domain.isNullOrBlank()) {
                    temporaryRouting.add(domain, app = false, outbound = "direct", durationMinutes = 1440)
                    dnsHealthAdvisor.dismiss()
                    reloadVpn()
                }
            }
            "PAUSE_VPN_120S" -> {
                hasStartedVpn = true
                dnsHealthAdvisor.dismiss()
                pauseVpn()
            }
            "OPEN_CAPTIVE_PORTAL" -> {
                val portalUrl = intent.getStringExtra("portal_url") ?: "http://connectivitycheck.gstatic.com/generate_204"
                try {
                    val browserIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(portalUrl)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(browserIntent)
                } catch (e: Exception) {
                    Log.w("SplateVpn", "Failed to open captive portal: ${e.message}")
                }
            }
            "OPEN_DATE_SETTINGS" -> {
                dnsHealthAdvisor.dismiss()
                try {
                    val dateIntent = Intent(android.provider.Settings.ACTION_DATE_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(dateIntent)
                } catch (e: Exception) {
                    Log.w("SplateVpn", "Failed to open date settings: ${e.message}")
                }
            }
            "DISMISS_ALERT" -> {
                dnsHealthAdvisor.dismiss()
            }
        }
        return START_NOT_STICKY
    }

    private fun pauseVpn() {
        if (isStopping.getAndSet(true)) return
        val previousStartup = startupJob
        previousStartup?.cancel()
        startupJob = serviceScope.launch {
            previousStartup?.join()
            vpnMutex.withLock {
                cleanupAndStopLocked(stopService = false, inactiveState = VpnState.DISCONNECTED)
                currentCoroutineContext().ensureActive()
                updateNotification("SplateVPN", notificationText(com.danieldk.splatevpn.R.string.notification_paused))
            }
            delay(120_000L)
            vpnMutex.withLock {
                currentCoroutineContext().ensureActive()
                withContext(Dispatchers.Main.immediate) { isStopping.set(false) }
                torRecoveryBudget.reset()
                startVpnLocked()
            }
        }
    }

    private fun startVpn() {
        val wasStopping = isStopping.get()
        if (!wasStopping && (startupJob?.isActive == true || (isServiceActive && vpnStatusManager.state.value != VpnState.FAILED))) return
        val previousStop = startupJob.takeIf { wasStopping }
        previousStop?.cancel()
        isStopping.set(false)
        // onStartCommand already entered the foreground before dispatching START.
        startupJob = serviceScope.launch {
            previousStop?.join()
            currentCoroutineContext().ensureActive()
            startVpnInternal()
        }
    }

    private suspend fun startVpnInternal() = vpnMutex.withLock {
        torRecoveryBudget.reset()
        startVpnLocked()
    }

    private suspend fun startVpnLocked() {
        try {
            currentCoroutineContext().ensureActive()
            check(settingsManager.canUseNetworkSettings()) { "Open SplateVPN to recover settings before connecting." }
            val storedTorSettings = torRepository.load()
            val torSettings = storedTorSettings
            check(torRepository.storageError.value == null) { "Open Tor settings and recover the saved configuration before connecting." }
            usingTor = torSettings.enabled
            vpnStatusManager.setState(if (!usingTor && settingsManager.isAutoSelectServer()) VpnState.SELECTING_SERVER else VpnState.STARTING_CORE)
            // Ensure any previous tunnel/core/tun are cleaned up before starting
            try {
                tProxyService?.stop()
                tProxyService = null
                if (!isServiceActive) {
                    vpnInterface?.close()
                    vpnInterface = null
                }
                xrayCoreManager.stopCore()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("SplateVpn", "Pre-start cleanup: ${e.message}")
            }

            // Ensure default servers exist if none
            if (!usingTor) serverRepository.initDefaultServers()

            // Auto-select fastest server if enabled
            if (!usingTor && settingsManager.isAutoSelectServer() && settingsManager.areChecksAllowed()) {
                Log.d("SplateVpn", "Auto-select is enabled: testing pings and selecting fastest server")
                appLogManager.i("SplateVpn", "Автовыбор: ожидаем проверку рабочего сервера")
                val selectionStarted = android.os.SystemClock.elapsedRealtime()
                val sourcesBefore = serverRepository.allSubscriptions.first().associate { it.id to it.lastUpdated }
                var verified = serverRepository.autoSelectFastestServer(
                    excludeIds = deadServerIds, reuseRecentSelection = true
                )
                if (verified == null && settingsManager.areChecksAllowed() &&
                    serverRepository.allSubscriptions.first().associate { it.id to it.lastUpdated } != sourcesBefore) {
                    appLogManager.i("SplateVpn", "Подписки изменились во время поиска. Проверяем обновлённые узлы")
                    verified = serverRepository.autoSelectFastestServer(
                        excludeIds = deadServerIds, urgent = true, allowSpeedProbe = false
                    )
                }
                appLogManager.i("SplateVpn", "Автовыбор завершён за ${android.os.SystemClock.elapsedRealtime() - selectionStarted} мс; рабочий сервер найден: ${verified != null}")
                if (verified == null) {
                    appLogManager.w("SplateVpn", "Рабочие серверы не найдены при первичном поиске. Переходим в непрерывный поиск...")
                    vpnStatusManager.setState(VpnState.RECONNECTING, "Сервера не найдены. Поиск…")
                    updateNotification("Splate VPN", "Сервера не найдены. Поиск…")

                    var retryAttempt = 0
                    while (verified == null && !isStopping.get() && currentCoroutineContext().isActive) {
                        retryAttempt++
                        val waitDelay = when {
                            retryAttempt <= 2 -> 3000L
                            retryAttempt <= 5 -> 5000L
                            else -> 10000L
                        }
                        delay(waitDelay)
                        if (isStopping.get() || !currentCoroutineContext().isActive) return

                        if (!settingsManager.isAutoSelectServer()) break
                        if (!canProbePhysicalNetwork()) {
                            vpnStatusManager.setState(VpnState.RECONNECTING, "Ожидание сети. Поиск серверов…")
                            updateNotification("Splate VPN", "Ожидание сети. Поиск серверов…")
                            continue
                        }

                        vpnStatusManager.setState(VpnState.RECONNECTING, "Сервера не найдены. Поиск…")
                        updateNotification("Splate VPN", "Сервера не найдены. Поиск…")

                        if (!settingsManager.isAutoSelectServer()) break

                        val allNodes = serverRepository.allServers.first()
                        if (allNodes.isEmpty()) {
                            delay(2000L)
                            continue
                        }

                        verified = serverRepository.autoSelectFastestServer(
                            excludeIds = deadServerIds,
                            urgent = true,
                            allowSpeedProbe = false
                        )
                    }

                    if (isStopping.get() || !currentCoroutineContext().isActive) return
                    if (verified != null) {
                        appLogManager.i("SplateVpn", "Рабочий сервер найден: ${verified.name} (${verified.ping}ms)")
                    }
                }
            }

            val activeNode = if (usingTor) {
                updateNotification("Tor", "Tor: подключение к сети…")
                val port = torRuntime.start(torSettings)
                val torCreds = torRuntime.socksCredentials
                ServerNode(id = "splate-tor", name = "Tor", protocol = "socks", address = "127.0.0.1", port = port,
                    uuid = torCreds?.password.orEmpty(), encryption = torCreds?.username.orEmpty(), security = "none").also { torNode = it }
            } else {
                torRuntime.stop()
                torNode = null
                serverRepository.getActiveServerSync()
            }
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
            localProxyRuntimeToken = LocalProxyAccess.publish(
                preparedLocalProxyAccess,
                socksPort = preparedSocksPort,
                dnsProbePort = preparedDnsProbePort
            ) { xrayCoreManager.isRunning() }

            // 2. Create hev-socks5-tunnel configuration (configured MTU prevents packet black holes)
            val mtu = settingsManager.getMtu()
            writeHevConfig(mtu)

            // 3. Setup Android TUN interface and start tProxyService
            setupVpnInterface(
                perAppList = settingsManager.getPerAppPackages(),
                isGlobal = settingsManager.isGlobalAppProxy(),
                dnsProvider = settingsManager.getDnsProvider(),
                mtu = mtu
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
            confirmActiveRouting()
            vpnStatusManager.connectedServerProfile = activeNode
            vpnStatusManager.connectedServerId = activeNode.id
            liveConnectionTracker.setActiveServerName(activeNode.name)
            vpnStatusManager.setState(VpnState.CONNECTED)
            if (usingTor) startTorMonitor()
            com.danieldk.splatevpn.ui.widget.SplateAppWidgetProvider.notifyUpdate(this@SplateVpnService)
            com.danieldk.splatevpn.ui.widget.SplateQuickWidgetProvider.notifyUpdate(this@SplateVpnService)
            SplateTileService.requestUpdate(this@SplateVpnService)
            updateNotification(notificationText(com.danieldk.splatevpn.R.string.notification_connected, activeNode.name), notificationText(com.danieldk.splatevpn.R.string.notification_active))
            appLogManager.i("SplateVpn", "VPN-интерфейс создан; проверяем передачу трафика [${activeNode.name}]")
            if (!usingTor) {
                startAutoBalancerJob()
                startAutoSelectObserver()
                startAiDnsMonitor()
            }

            // 7. Perform Health Check & IP details detection in background
            launchPostConnectChecks(activeNode, isSwitch = false)

        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            vpnStatusManager.setState(VpnState.FAILED, torRuntime.state.value.error ?: "Connection timed out")
            cleanupAndStopLocked()
        } catch (e: CancellationException) {
            if (!isServiceActive) {
                withContext(NonCancellable) {
                    try {
                        tProxyService?.stop()
                        tProxyService = null
                        vpnInterface?.close()
                        vpnInterface = null
                        LocalProxyAccess.revoke(localProxyRuntimeToken)
                        localProxyRuntimeToken = null
                        xrayCoreManager.stopCore()
                        if (usingTor) torRuntime.stop()
                    } catch (_: Exception) {}
                }
            }
            throw e
        } catch (e: Exception) {
            // Some blocking libraries wrap an interrupted cancellation as IOException.
            // A superseded startup must not publish FAILED or stop the replacement service.
            currentCoroutineContext().ensureActive()
            appLogManager.e("SplateVpn", "Исключение при старте VPN: ${e.message}", e)
            vpnStatusManager.setState(VpnState.FAILED, e.message)
            cleanupAndStopLocked()
        }
    }

    private fun aiDnsMonitorConfig() = AiDnsMonitorConfig(
        connected = vpnStatusManager.state.value == VpnState.CONNECTED && !isStopping.get(),
        enabled = settingsManager.isAiDnsAutoSwitchEnabled(),
        directSmartDns = settingsManager.isBypassAi() && settingsManager.getAiRoutingMode() == "dns_ai",
        choice = AiDnsChoice(settingsManager.getAiDnsProvider(), settingsManager.isAiTlsFragmentEnabled()),
        network = currentNetwork?.toString(),
        serverId = vpnStatusManager.connectedServerId,
        viaVpn = settingsManager.isBypassAi() && settingsManager.getAiRoutingMode() == "dns_vpn",
        vpnRoute = vpnStatusManager.connectedServerProfile?.let { com.danieldk.splatevpn.data.AiDnsVpnRoute.capture(it, settingsManager, serverRepository.requiresTlsFragment(it.id)) }
    )

    private fun startTorMonitor() {
        torMonitorJob?.cancel()
        torHealthJob?.cancel()
        torRecoveryBudget.started()
        torHealthJob = serviceScope.launch(Dispatchers.IO) {
            val health = TorTrafficHealthCounter<Network>()
            var probeIntervalMs = 15_000L
            while (isActive && usingTor && !isStopping.get()) {
                delay(probeIntervalMs)
                val network = currentNetwork
                if (network == null || !settingsManager.areChecksAllowed() || vpnStatusManager.state.value != VpnState.CONNECTED) {
                    health.reset()
                    probeIntervalMs = 15_000L
                    continue
                }
                if (vpnStatusManager.downBytesPerSec.value >= 16_384L) {
                    health.reset()
                    probeIntervalMs = 15_000L
                    continue
                }
                val healthy = try { vpnStatusManager.performHealthCheck(timeoutMs = 15_000) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
                probeIntervalMs = if (healthy) 15_000L else 5_000L
                if (health.record(network, currentNetwork, healthy)) {
                    appLogManager.w("Tor", "Three HTTPS checks failed; rebuilding Tor connection")
                    torRuntime.reportTrafficFailure()
                    return@launch
                }
            }
        }
        torMonitorJob = serviceScope.launch(Dispatchers.Main.immediate) {
            torRuntime.state.first { it.phase == TorPhase.FAILED || it.phase == TorPhase.STOPPED }
            if (!usingTor || isStopping.get() || !isServiceActive) return@launch
            val previousStartup = startupJob
            startupJob = serviceScope.launch {
                previousStartup?.join()
                vpnMutex.withLock {
                    if (!usingTor || isStopping.get() || !isServiceActive) return@withLock
                    val node = torNode ?: return@withLock
                    if (!torRecoveryBudget.crashed()) {
                        vpnStatusManager.setState(VpnState.FAILED, "Tor reconnect limit reached")
                        cleanupAndStopLocked()
                        return@withLock
                    }
                    val settings = torRepository.load()
                    if (!settings.enabled || torRepository.storageError.value != null) {
                        vpnStatusManager.setState(VpnState.FAILED, "Tor configuration changed")
                        cleanupAndStopLocked()
                        return@withLock
                    }
                    vpnStatusManager.setState(VpnState.RECONNECTING)
                    updateNotification("Tor", notificationText(com.danieldk.splatevpn.R.string.notification_starting))
                    appLogManager.w("Tor", "Recovering Tor without releasing the VPN interface")
                    try {
                        delay(1_000L * torRecoveryBudget.failures)
                        val newTorPort = torRuntime.start(settings)
                        val newTorCreds = torRuntime.socksCredentials
                        torNode = node.copy(
                            port = newTorPort,
                            uuid = newTorCreds?.password.orEmpty(),
                            encryption = newTorCreds?.username.orEmpty()
                        )
                        reloadVpnLocked()
                        if (vpnStatusManager.state.value == VpnState.CONNECTED && usingTor) startTorMonitor()
                    } catch (error: Exception) {
                        currentCoroutineContext().ensureActive()
                        if (error is CancellationException && error !is TimeoutCancellationException) throw error
                        appLogManager.w("Tor", "Tor recovery failed: ${error.javaClass.simpleName}")
                        vpnStatusManager.setState(VpnState.FAILED, torRuntime.state.value.error ?: "Tor reconnect failed")
                        cleanupAndStopLocked()
                    }
                }
            }
        }
    }

    private fun startAiDnsMonitor() {
        aiDnsMonitorJob?.cancel()
        if (usingTor) return
        aiDnsMonitorJob = serviceScope.launch {
            val states = combine(vpnStatusManager.state, settingsManager.networkPreferences, physicalNetworkFlow) { _, _, _ ->
                aiDnsMonitorConfig()
            }.distinctUntilChanged()
            monitorAiDns(states, check = { expected ->
                try {
                    val node = serverRepository.getActiveServerSync()
                    if (node == null ||
                        expected.viaVpn && (expected.serverId != node.id || expected.vpnRoute == null ||
                            expected.vpnRoute.node != com.danieldk.splatevpn.data.QuickServerSelection.profile(node))) null
                    else {
                        appLogManager.d("AiDnsMonitor", "Проверка SmartDNS: ${expected.choice.providerKey}, VPN=${expected.viaVpn}")
                        aiDnsAutoSelector.findFailover(expected.choice, viaVpn = expected.viaVpn, vpnRoute = expected.vpnRoute)
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    appLogManager.w("AiDnsMonitor", "Проверка SmartDNS прервана; настройки сохранены")
                    null
                }
            }, apply = { expected, replacement ->
                withContext(Dispatchers.Main.immediate) {
                    ensureActive()
                    if (aiDnsMonitorConfig() == expected && !aiDnsAutoSelector.state.value.running &&
                        settingsManager.canUseNetworkSettings()) {
                        settingsManager.setAiSmartDnsChoice(replacement.choice)
                        aiDnsAutoSelector.clearAutomaticSwitchResult()
                        appLogManager.i("AiDnsMonitor", "Автопереключение SmartDNS: ${expected.choice.providerKey} → ${replacement.choice.providerKey}, TLS=${replacement.choice.tlsFragment}")
                        reloadVpn()
                    }
                }
            })
        }
    }

    private fun startAutoSelectObserver() {
        autoSelectObserverJob?.cancel()
        if (usingTor) return
        autoSelectObserverJob = serviceScope.launch {
            combine(settingsManager.isAutoSelectFlow, settingsManager.balancerPreferences) { enabled, prefs -> enabled to prefs }
                .collect { (isEnabled, prefs) ->
                if (vpnStatusManager.state.value == VpnState.CONNECTED) startAutoBalancerJob()

            }
        }
    }

    private suspend fun handleNodeUnhealthy(node: ServerNode, reason: String): Boolean {
        if (!autoSwitchMutex.tryLock()) return false
        try { return handleNodeUnhealthyLocked(node, reason) }
        finally { autoSwitchMutex.unlock() }
    }

    private suspend fun handleNodeUnhealthyLocked(node: ServerNode, reason: String): Boolean {
        val session = vpnStatusManager.sessionToken
        val selectionRevision = settingsManager.selectionRevision()
        val network = currentNetwork
        fun stillCurrent() = canRecoverServers(VpnState.CONNECTED, session, selectionRevision) && currentNetwork == network

        if (!stillCurrent()) return false
        if (serverRepository.selectedServer.first()?.let { com.danieldk.splatevpn.data.QuickServerSelection.profile(it) } !=
            com.danieldk.splatevpn.data.QuickServerSelection.profile(node)) return false
        appLogManager.w("SplateVpn", "⚠️ Узел '${node.name}' не отвечает на проверку связи ($reason)!")

        // Проверяем: доступен ли физический интернет на устройстве
        if (!canProbePhysicalNetwork()) {
            appLogManager.w("SplateVpn", "🌐 Физический интернет недоступен на устройстве. Смена узла '${node.name}' отложена.")
            return false
        }

        // Circuit Breaker: для аварийного переключения мертвого узла проверяем только минимальный интервал кулдауна
        if (!canAutoSwitch(isDeadNode = true)) {
            appLogManager.w("SplateVpn", "⚡ Автопереключение узла временно отложено (кулдаун активен). Повтор в следующем цикле...")
            return false
        }

        if (!stillCurrent()) return false
        // Recovery owns probe capacity; background optimization/reserve refresh must yield.
        standbyJob?.cancel()
        optimizationJob?.cancel()
        val nextBest = serverRepository.autoSelectFastestServer(
            excludeIds = deadServerIds + node.id,
            urgent = true,
            commitSelection = false,
            allowSpeedProbe = false
        )

        if (!stillCurrent()) return false
        // Try a verified standby before spending time diagnosing the failed node.
        // When no replacement works, preserve actionable DNS/fragmentation repairs.
        if (!stillCurrent()) return false
        if (node.id !in deadServerIds) {
            val attributed = serverRepository.recordServerFailure(node)
            if (!stillCurrent()) return false
            if (attributed) markDeadServer(node.id)
            else appLogManager.i("SplateVpn", "Доступность сети вне VPN не подтверждена. Поиск продолжается без карантина узла.")
        }
        serverRepository.updateServerPingIfUnchanged(node, -1L)
        if (nextBest != null && serverRepository.activateAutoCandidate(nextBest, node, ::stillCurrent)) {
            return reloadAfterAutoSwitch(::stillCurrent,
                "Аварийное переключение с узла '${node.name}' на '${nextBest.name}'")
        }
        withContext(Dispatchers.Main.immediate) {
            if (stillCurrent()) {
                appLogManager.w("SplateVpn", "Альтернативных рабочих узлов не найдено на данный момент.")
                vpnStatusManager.setState(VpnState.RECONNECTING, "Сервера не найдены. Поиск…")
                updateNotification("Splate VPN", "Сервера не найдены. Поиск…")
                startBackgroundServerRecovery()
                postConnectDiagnosticJob?.cancel()
                postConnectDiagnosticJob = serviceScope.launch {
                    if (!tunnelHealthAdvisor.checkDnsFailure()) tunnelHealthAdvisor.checkFragmentationFailure()
                }
            }
        }
        return false
    }

    private fun canRecoverServers(state: VpnState, session: Long, selectionRevision: Long): Boolean =
        !isStopping.get() && isServiceActive && vpnStatusManager.state.value == state &&
            vpnStatusManager.sessionToken == session && settingsManager.selectionRevision() == selectionRevision &&
            settingsManager.isAutoSelectServer() && settingsManager.getBalancerPreferences().monitorsConnection &&
            settingsManager.areChecksAllowed()

    private fun startBackgroundServerRecovery() {
        if (recoveryJob?.isActive == true || isStopping.get() || !isServiceActive) return
        fun recovering() = !isStopping.get() && isServiceActive &&
            vpnStatusManager.state.value == VpnState.RECONNECTING

        recoveryJob = serviceScope.launch {
            appLogManager.i("SplateVpn", "Запущен фоновый поиск серверов для восстановления связи...")
            var attempt = 0
            while (isActive && recovering()) {
                attempt++
                delay(when {
                    attempt <= 2 -> 3_000L
                    attempt <= 5 -> 5_000L
                    else -> 10_000L
                })
                if (!recovering()) break

                // A handover invalidates the in-flight probe, but recovery still owns the next attempt.
                val session = vpnStatusManager.sessionToken
                val revision = settingsManager.selectionRevision()
                val network = currentNetwork
                fun stillCurrent() = canRecoverServers(VpnState.RECONNECTING, session, revision) && currentNetwork == network
                val internetAvailable = canProbePhysicalNetwork()
                vpnMutex.withLock {
                    if (recovering() && vpnStatusManager.sessionToken == session && currentNetwork == network) {
                        val message = if (internetAvailable) "Сервера не найдены. Поиск…" else "Ожидание сети. Поиск серверов…"
                        vpnStatusManager.setState(VpnState.RECONNECTING, message)
                        updateNotification("Splate VPN", message)
                    }
                }
                if (!internetAvailable || !stillCurrent()) continue

                try {
                    val current = serverRepository.selectedServer.first()
                    val found = serverRepository.autoSelectFastestServer(
                        excludeIds = deadServerIds,
                        urgent = true,
                        commitSelection = false,
                        allowSpeedProbe = false
                    )
                    if (found != null && serverRepository.activateAutoCandidate(found, current, ::stillCurrent) &&
                        reloadAfterAutoSwitch(::stillCurrent, "Найден рабочий сервер: '${found.name}' (${found.ping}ms)")) break
                } catch (_: com.danieldk.splatevpn.data.WifiRequiredException) {
                    continue
                } catch (cancelled: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    if (stillCurrent()) throw cancelled
                } catch (error: Exception) {
                    appLogManager.w("SplateVpn", "Ошибка попытки восстановления: ${error.javaClass.simpleName}. Поиск будет повторён.")
                    Log.w("SplateVpn", "Recovery attempt failed", error)
                }
            }
        }
    }

    private suspend fun reloadAfterAutoSwitch(stillCurrent: () -> Boolean, message: String): Boolean =
        withContext(Dispatchers.Main.immediate) {
            if (!stillCurrent()) return@withContext false
            recordAutoSwitch()
            appLogManager.i("SplateVpn", message)
            reloadVpn()
            true
        }

    private fun reloadVpn() {
        if (isStopping.get()) return
        // A reload that replaces startup must join the same cancellable command chain.
        if (startupJob?.isActive == true) {
            restartConnectionMode()
            return
        }
        startupJob = serviceScope.launch {
            reloadVpnInternal()
        }
    }

    private fun restartConnectionMode() {
        isStopping.set(true)
        val previousStartup = startupJob
        previousStartup?.cancel()
        startupJob = serviceScope.launch {
            previousStartup?.join()
            vpnMutex.withLock {
                currentCoroutineContext().ensureActive()
                cleanupAndStopLocked(stopService = false)
                currentCoroutineContext().ensureActive()
                isStopping.set(false)
                torRecoveryBudget.reset()
                startVpnLocked()
            }
        }
    }

    private suspend fun reloadVpnInternal() = vpnMutex.withLock {
        reloadVpnLocked()
    }

    private suspend fun reloadVpnLocked() {
        if (isStopping.get()) return
        if (torRepository.load().enabled != usingTor) {
            cleanupAndStopLocked(stopService = false)
            currentCoroutineContext().ensureActive()
            isStopping.set(false)
            startVpnLocked()
            return
        }

        // If TUN interface is not established or tProxyService is dead, perform a fresh start
        if (vpnInterface == null || tProxyService == null) {
            appLogManager.i("SplateVpn", "Туннель не активен, выполняется полная инициализация...")
            startVpnLocked()
            return
        }

        vpnStatusManager.setState(VpnState.STARTING_CORE)

        try {
            val activeNode = if (usingTor) torNode else serverRepository.getActiveServerSync()
            if (activeNode == null) {
                appLogManager.e("SplateVpn", "Сервер не выбран! Остановка VPN.")
                cleanupAndStopLocked()
                return
            }

            val configJson = generateXrayConfig(activeNode, isReload = true)
            val mtu = settingsManager.getMtu()
            writeHevConfig(mtu)

            // 1. Reconfigure Android TUN interface with any updated routing/DNS/App parameters
            setupVpnInterface(
                perAppList = settingsManager.getPerAppPackages(),
                isGlobal = settingsManager.isGlobalAppProxy(),
                dnsProvider = settingsManager.getDnsProvider(),
                mtu = mtu
            )

            val vpnFd = vpnInterface
            if (vpnFd == null) {
                appLogManager.e("SplateVpn", "Ошибка: TUN интерфейс не был создан при переключении узла!")
                vpnStatusManager.setState(VpnState.FAILED, "TUN interface creation failed during reload")
                cleanupAndStopLocked()
                return
            }

            // 2. Stop current Xray core (guaranteeing port is freed)
            LocalProxyAccess.revoke(localProxyRuntimeToken)
            localProxyRuntimeToken = null
            xrayCoreManager.stopCore()

            // 3. Start Xray with the updated configuration
            val started = xrayCoreManager.startCore(configJson)
            if (!started) {
                appLogManager.e("SplateVpn", "Не удалось перезапустить ядро Xray при смене узла!")
                cleanupAndStopLocked()
                return
            }

            localProxyRuntimeToken = LocalProxyAccess.publish(
                preparedLocalProxyAccess,
                socksPort = preparedSocksPort,
                dnsProbePort = preparedDnsProbePort
            ) { xrayCoreManager.isRunning() }
            confirmActiveRouting()
            vpnStatusManager.connectedServerProfile = activeNode
            vpnStatusManager.connectedServerId = activeNode.id
            liveConnectionTracker.setActiveServerName(activeNode.name)
            vpnStatusManager.setState(VpnState.CONNECTED)
            com.danieldk.splatevpn.ui.widget.SplateAppWidgetProvider.notifyUpdate(this)
            com.danieldk.splatevpn.ui.widget.SplateQuickWidgetProvider.notifyUpdate(this)
            SplateTileService.requestUpdate(this)
            startAutoBalancerJob()

            // 4. Reset and refresh IP geolocation details and ping
            startStatsMonitor(activeNode.name)
            updateNotification(notificationText(com.danieldk.splatevpn.R.string.notification_connected, activeNode.name), notificationText(com.danieldk.splatevpn.R.string.notification_changed))
            vpnStatusManager.resetIpDetails()
            launchPostConnectChecks(activeNode, isSwitch = true)
            appLogManager.i("SplateVpn", "Узел успешно переключен: [${activeNode.name}]")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            appLogManager.e("SplateVpn", "Ошибка при переключении узла: ${e.message}", e)
            vpnStatusManager.setState(VpnState.FAILED, e.message)
            cleanupAndStopLocked()
        }
    }

    private var preparedTemporaryEntries: List<com.danieldk.splatevpn.data.TemporaryException> = emptyList()
    private var appliedTemporaryEntries: List<com.danieldk.splatevpn.data.TemporaryException> = emptyList()
    private var appliedNetworkKind: String? = null
    private var appliedPhysicalNetwork: Network? = null
    private var preparedPhysicalNetwork: Network? = null
    private var preparedDomainRules: String = "[]"
    private var preparedNetworkPreferences: Map<String, *>? = null
    private var preparedIncludedApps: Int? = null
    private var preparedExcludedApps = 0

    private fun confirmActiveRouting() {
        networkInterrupted = false
        appliedSubscriptionRules = preparedSubscriptionRules
        appliedAdvancedRules = preparedAdvancedRules
        appliedTemporaryEntries = preparedTemporaryEntries
        appliedPhysicalNetwork = preparedPhysicalNetwork
        val prepared = preparedNetworkPreferences
        vpnStatusManager.confirmRouting(prepared?.takeIf {
            it == com.danieldk.splatevpn.data.NetworkPreferences.capture(settingsManager.preferenceSnapshot())
        }?.let { ActiveRouting(it, preparedIncludedApps, preparedExcludedApps, preparedLanAccess?.bindAddress, preparedDomainRules, xrayCoreManager.activeAssetDirectory?.absolutePath) })
    }

    private fun writeHevConfig(mtu: Int) {
        val credentials = (preparedAppIdentityAccess?.fallback ?: preparedLocalProxyAccess)?.hevYaml().orEmpty()
        File(filesDir, "hev-socks5-tunnel.yaml").writeText(buildString {
            appendLine("tunnel:")
            appendLine("  mtu: $mtu")
            appendLine("  ipv4: 172.19.0.1")
            appendLine("  ipv6: 'fd00::1'")
            appendLine("socks5:")
            appendLine("  port: ${preparedAppIdentityAccess?.port ?: preparedSocksPort}")
            appendLine("  address: ${SplateConstants.XRAY_SOCKS_ADDRESS}")
            appendLine("  udp: 'udp'")
            if (credentials.isNotEmpty()) appendLine(credentials)
            appendLine("misc:")
            appendLine("  connect-timeout: 5000")
            appendLine("  tcp-read-write-timeout: 300000")
            appendLine("  udp-read-write-timeout: 60000")
            appendLine("  limit-nofile: 65535")
            appendLine("  log-level: warn")
        })
    }

    private fun allocateEphemeralLoopbackPort(excluded: Set<Int>): Int {
        repeat(32) {
            val candidate = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
            if (candidate !in excluded) return candidate
        }
        return java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
    }

    private suspend fun generateXrayConfig(activeNode: ServerNode, isReload: Boolean): String {
        if (!isReload) {
            appliedNetworkKind = physicalNetworkKind(getPhysicalNetwork())
            appliedNetworkKind?.let { networkBindings.apply(it) }
        }
        preparedTemporaryEntries = temporaryRouting.entries.value
        preparedPhysicalNetwork = getPhysicalNetwork()
        preparedNetworkPreferences = com.danieldk.splatevpn.data.NetworkPreferences.capture(settingsManager.preferenceSnapshot())
        val routingMode = if (usingTor) "global" else settingsManager.getRoutingMode()
        val dnsProvider = if (usingTor) "cloudflare" else settingsManager.getDnsProvider()
        val isFakeDns = usingTor || settingsManager.isFakeDns()
        val xhttpMode = settingsManager.getXhttpMode()
        val bypassAi = !usingTor && settingsManager.isBypassAi()
        val customRoutes = if (usingTor) emptyList() else temporaryRouting.domainRoutes(settingsManager.getCustomRoutes())
        preparedLocalProxyAccess = settingsManager.localProxyAccess()
        val wellKnownPorts = setOf(SplateConstants.XRAY_SOCKS_PORT, SplateConstants.DNS_CHECK_SOCKS_PORT, SplateConstants.XRAY_LAN_HTTP_PORT)
        if (!isReload || preparedSocksPort in wellKnownPorts) {
            preparedSocksPort = allocateEphemeralLoopbackPort(wellKnownPorts)
        }
        preparedDnsProbePort = if (usingTor) null else allocateEphemeralLoopbackPort(wellKnownPorts + preparedSocksPort)
        val subscriptionRules = if (usingTor) emptyList() else com.danieldk.splatevpn.data.SubscriptionRoutingRules.activeFor(
            activeNode, subscriptionDao.getAllSubscriptionsSync())
        preparedSubscriptionRules = if (usingTor) null else SubscriptionRulesSnapshot.applied(activeNode.id, subscriptionRules)
        val advancedRules = if (usingTor) emptyList() else com.danieldk.splatevpn.data.AdvancedRoutingRules.decode(settingsManager.advancedRoutingJson())
        preparedAdvancedRules = com.danieldk.splatevpn.data.AdvancedRoutingRules.compile(advancedRules)
        val packages = com.danieldk.splatevpn.data.AdvancedRoutingRules.packageNames(advancedRules).toSet()
        AppFlowIdentityResolver.validatePackages(this, packages)
        preparedAppIdentityAccess = if (packages.isEmpty()) null else {
            check(TProxyService.identityApiVersion() >= 1) {
                "TUN-модуль не поддерживает правила приложений; установите полную совместимую сборку Splate"
            }
            AppIdentityCoreCompatibility.ensureCompatible(this, requireNotNull(xrayCoreManager.getXrayBinary()) {
                "Не найдено ядро Xray для проверки правил приложений"
            })
            val port = allocateEphemeralLoopbackPort(wellKnownPorts + setOfNotNull(preparedSocksPort, preparedDnsProbePort))
            AppIdentityAccess(port, packages, LocalProxyCredentials.generate())
        }
        preparedAppIdentityResolver = preparedAppIdentityAccess?.let { AppFlowIdentityResolver(this, it) {
            appLogManager.w("AppRouting", "Android не смог однозначно определить владельца соединения. " +
                "Такой поток отклонён, чтобы не обходить правила приложений.")
        } }
        preparedLanAccess = if (settingsManager.isShareLanEnabled()) {
            val interfaces = lanShareManager.getActiveLanEndpoints()
            val selectedInterface = settingsManager.getLanInterface()
            val endpoint = if (selectedInterface.isBlank()) interfaces.firstOrNull()
                else interfaces.firstOrNull { it.interfaceName == selectedInterface }
            if (endpoint == null) appLogManager.w("LAN", "Selected LAN interface is unavailable; sharing is disabled for this connection")
            endpoint?.let { LanAccess(it.ipAddress, "splate", settingsManager.getLanPassword(), settingsManager.isLanAuthenticationEnabled()) }
        } else null

        val logPrefix = if (isReload) "Бесшовное переключение ядра на узел" else "Запуск VPN: Узел"
        appLogManager.i(
            "SplateVpn",
            "$logPrefix: '${activeNode.name}' [${activeNode.address}:${activeNode.port}], " +
                    "Протокол='${activeNode.protocol}/${activeNode.network}', Безопасность='${activeNode.security}', " +
                    "SNI='${activeNode.sni}', Host='${activeNode.host}', Path='${activeNode.path}', " +
                    "Режим='$routingMode', DNS='$dnsProvider', FakeDNS=$isFakeDns, BypassAI=$bypassAi, CustomRoutes=${customRoutes.size}"
        )

        val effectiveTlsFragment = !usingTor && (
            settingsManager.isTlsFragmentEnabled() || serverRepository.requiresTlsFragment(activeNode.id)
        )
        val fragmentLength = settingsManager.getTlsFragmentLength()
        val fragmentInterval = settingsManager.getTlsFragmentInterval()
        val fragmentPackets = settingsManager.getTlsFragmentPackets()

        return xrayConfigGenerator.generateConfig(
            node = activeNode,
            mode = routingMode,
            routingRegion = settingsManager.getRoutingRegion(),
            socksPort = preparedSocksPort,
            dnsProbePort = preparedDnsProbePort,
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
            enableTlsFragment = effectiveTlsFragment,
            tlsFragmentLength = fragmentLength,
            tlsFragmentInterval = fragmentInterval,
            tlsFragmentPackets = fragmentPackets,
            tlsMixedSni = !usingTor && settingsManager.isTlsMixedSniEnabled(),
            enableMux = !usingTor && settingsManager.isMuxEnabled(),
            muxConcurrency = settingsManager.getMuxConcurrency(),
            shareLan = preparedLanAccess != null,
            lanAccess = preparedLanAccess,
            localProxyAccess = preparedLocalProxyAccess,
            subscriptionRules = subscriptionRules,
            advancedRules = advancedRules,
            appIdentityAccess = preparedAppIdentityAccess,
            allAvailableServers = if (usingTor) emptyList() else serverRepository.allServers.first()
        ).let { if (usingTor) TorRoutingPolicy.apply(it) else it }
            .also { generated ->
                val config = com.google.gson.JsonParser.parseString(generated).asJsonObject
                val proxy = config.getAsJsonArray("outbounds").first { it.asJsonObject["tag"]?.asString == "proxy" }.asJsonObject
                val stream = proxy.getAsJsonObject("streamSettings")
                val reality = stream?.getAsJsonObject("realitySettings")
                appLogManager.i("SplateVpn", "Эффективный выход Xray: " +
                    "Источник=${if (activeNode.fullConfigJson.isNullOrBlank()) "параметры узла" else "JSON"}, " +
                    "Протокол=${proxy["protocol"]?.asString}, " +
                    "Транспорт=${stream?.get("network")?.asString ?: "tcp"}, " +
                    "Безопасность=${stream?.get("security")?.asString ?: "none"}, " +
                    "Fingerprint=${reality?.get("fingerprint")?.asString ?: "default"}, " +
                    "REALITY-key=${reality?.has("publicKey") == true || reality?.has("password") == true}, " +
                    "Mux=${proxy.getAsJsonObject("mux")?.get("enabled")?.asBoolean == true}")
                preparedDomainRules = config.getAsJsonObject("routing").getAsJsonArray("rules").toString()
            }
    }

    private fun launchPostConnectChecks(activeNode: ServerNode, isSwitch: Boolean) {
        postConnectCheckJob?.cancel()
        postConnectDiagnosticJob?.cancel()
        postConnectDiagnosticJob = null
        if (!settingsManager.areChecksAllowed()) return
        if (usingTor) {
            postConnectCheckJob = serviceScope.launch { vpnStatusManager.fetchCurrentIpDetails() }
            return
        }
        val expectedSession = vpnStatusManager.sessionToken
        fun current() = !isStopping.get() && isServiceActive && vpnStatusManager.state.value == VpnState.CONNECTED &&
            vpnStatusManager.connectedServerId == activeNode.id && vpnStatusManager.sessionToken == expectedSession
        postConnectCheckJob = serviceScope.launch(Dispatchers.IO) {
            suspend fun diagnosticStage(label: String, check: suspend () -> Boolean): Boolean {
                val started = android.os.SystemClock.elapsedRealtime()
                appLogManager.i("ConnectionCheck", "$label: проверка начата")
                var outcome = "прервана"
                try {
                    return check().also { outcome = if (it) "причина подтверждена" else "причина не подтверждена" }
                } finally {
                    appLogManager.i("ConnectionCheck", "$label: $outcome, ${android.os.SystemClock.elapsedRealtime() - started} мс")
                }
            }
            try {
                delay(1000)
                val confirmations = settingsManager.getBalancerPreferences().failureConfirmations
                repeat(confirmations) { attempt ->
                    settingsManager.requireChecksNetwork()
                    if (!current() || serverRepository.getActiveServerSync()?.let { com.danieldk.splatevpn.data.QuickServerSelection.profile(it) } !=
                        com.danieldk.splatevpn.data.QuickServerSelection.profile(activeNode)) return@launch
                    val started = android.os.SystemClock.elapsedRealtime()
                    val healthy = com.danieldk.splatevpn.data.NetworkAccessPolicy.withChecks(this@SplateVpnService) {
                        vpnStatusManager.performHealthCheck()
                    }
                    appLogManager.i("ConnectionCheck", "Проверка связи ${attempt + 1}/$confirmations: ${if (healthy) "успешна" else "ответ не получен"}, ${android.os.SystemClock.elapsedRealtime() - started} мс")
                    if (!current()) return@launch
                    if (healthy) {
                        tunnelHealthAdvisor.clearUnverifiedConnectivity(activeNode.id, expectedSession)
                        val realDelay = vpnStatusManager.rttMs.value
                        if (realDelay > 0) {
                            serverRepository.recordTunnelSuccess(activeNode, realDelay)
                            serverRepository.updateServerPingIfUnchanged(activeNode, realDelay)
                        }
                        unmarkDeadServer(activeNode.id)
                        delay(400)
                        if (current()) vpnStatusManager.fetchCurrentIpDetails()
                        return@launch
                    }
                    if (attempt + 1 < confirmations) delay(if (attempt == 0) 350 else 500L)
                }
                appLogManager.w("SplateVpn", "Проверки связи через VPN не прошли; причина пока не установлена.")
                tunnelHealthAdvisor.reportUnverifiedConnectivity(activeNode.id, expectedSession)
                if (settingsManager.isAutoSelectServer()) {
                    if (canProbePhysicalNetwork()) handleNodeUnhealthy(activeNode, "Проверки связи после подключения не прошли")
                    else appLogManager.w("SplateVpn", "Физическая сеть недоступна. Ожидание восстановления связи.")
                }
                // Неблокирующая диагностика: выполняется в фоне, чтобы не задерживать аварийную смену сервера
                if (isPhysicalInternetAlive()) {
                    postConnectDiagnosticJob?.cancel()
                    postConnectDiagnosticJob = serviceScope.launch(Dispatchers.IO) {
                        try {
                            if (!current()) return@launch
                            val isDnsDead = diagnosticStage("DNS", tunnelHealthAdvisor::checkDnsFailure)
                            if (!current()) return@launch
                            if (isDnsDead) return@launch
                            diagnosticStage("Фрагментация", tunnelHealthAdvisor::checkFragmentationFailure)
                        } catch (c: CancellationException) {
                            throw c
                        } catch (e: Exception) {
                            appLogManager.w("ConnectionCheck", "Фоновая диагностика завершилась ошибкой: ${e.message}")
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                appLogManager.i("ConnectionCheck", "Проверка подключения отменена при смене или остановке соединения")
                throw cancelled
            } catch (_: com.danieldk.splatevpn.data.WifiRequiredException) {
                appLogManager.i("ConnectionCheck", "Проверки отложены до подключения Wi-Fi")
            } catch (error: Exception) {
                appLogManager.w("ConnectionCheck", "Проверка подключения прервана: ${error.javaClass.simpleName}")
                if (current()) tunnelHealthAdvisor.reportUnverifiedConnectivity(activeNode.id, expectedSession)
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

    private fun physicalNetworkKind(network: Network?): String? {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val caps = network?.let(cm::getNetworkCapabilities) ?: return null
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> null
        }
    }

    private fun networkCandidate(network: Network, caps: NetworkCapabilities, blocked: Boolean = false) =
        PhysicalNetworkCandidate(network,
            internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            unrestricted = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED),
            notVpn = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN),
            validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI), blocked = blocked)

    @Suppress("DEPRECATION")
    private fun getPhysicalNetwork(excluded: Network? = null): Network? {
        val cm = connectivityManager ?: (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager) ?: return null
        return try {
            val candidates = cm.allNetworks.filter { it != excluded }.mapNotNull { network ->
                cm.getNetworkCapabilities(network)?.let { networkCandidate(network, it) }
            }
            selectPhysicalNetwork(candidates, cm.activeNetwork)
        } catch (e: Exception) {
            Log.w("SplateVpn", "Error querying physical networks: ${e.message}")
            null
        }
    }
    /**
     * Проверяет, доступен ли физический интернет на самом устройстве (Wi-Fi или сотовая связь)
     * в обход VPN-туннеля. Это позволяет балансировщику строго отличать падение связи на телефоне
     * от гибели конкретного сервера VPN.
     */
    /** A provider blocking Android's direct check endpoints must not prevent testing VPN servers. */
    private fun canProbePhysicalNetwork(): Boolean {
        if (!settingsManager.areChecksAllowed()) return false
        val cm = connectivityManager ?: getSystemService(ConnectivityManager::class.java) ?: return false
        val network = getPhysicalNetwork() ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
    }

    private suspend fun isPhysicalInternetAlive(): Boolean = withContext(Dispatchers.IO) {
        val cm = connectivityManager ?: (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
        val physNet = getPhysicalNetwork() ?: return@withContext false
        val caps = cm?.getNetworkCapabilities(physNet) ?: return@withContext false
        if (!settingsManager.areChecksAllowed()) {
            return@withContext caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }

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
            if (!settingsManager.areChecksAllowed()) return@withContext false
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
                private val candidates = linkedMapOf<Network, PhysicalNetworkCandidate<Network>>()

                private fun updateUnderlyingNetwork() {
                    if (networkCallback !== this || isStopping.get() || !isServiceActive) return
                    val best = selectPhysicalNetwork(candidates.values.toList(), currentNetwork)
                    if (best == currentNetwork) return
                    try {
                        // Empty means no upstream; null would mean the system default network.
                        if (!setUnderlyingNetworks(best?.let { arrayOf(it) } ?: emptyArray())) return
                        currentNetwork = best
                        com.danieldk.splatevpn.data.NetworkAccessPolicy.setVpnUnderlyingNetwork(best)
                        vpnStatusManager.invalidateNetworkChecks()
                        tunnelHealthAdvisor.onNetworkChanged()
                        torBridgeProbeRepository.clearCache()
                        clearDeadServers()
                        if (best == null) {
                            appLogManager.w("SplateVpn", "Нет доступных физических сетей, ожидание подключения...")
                        } else {
                            appLogManager.i("SplateVpn", "Физическая сеть подключена: $best")
                        }
                    } catch (e: Exception) {
                        appLogManager.w("SplateVpn", "Не удалось обновить физическую сеть: ${e.message}")
                    }
                }

                // API 26+ delivers capabilities immediately after onAvailable. Use that
                // ordered snapshot instead of synchronous queries racing the callback.
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    candidates[network] = networkCandidate(network, caps, candidates[network]?.blocked ?: false)
                    tunnelHealthAdvisor.inspectNetworkCapabilities(caps)
                    updateUnderlyingNetwork()
                }

                override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                    candidates[network]?.let { candidates[network] = it.copy(blocked = blocked) }
                    updateUnderlyingNetwork()
                }

                override fun onLost(network: Network) {
                    candidates.remove(network)
                    if (network != currentNetwork) return
                    appLogManager.w("SplateVpn", "Основная физическая сеть отключена: $network")
                    updateUnderlyingNetwork()
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
        com.danieldk.splatevpn.data.NetworkAccessPolicy.setVpnUnderlyingNetwork(null)
    }

    private fun setupVpnInterface(perAppList: Set<String>, isGlobal: Boolean, dnsProvider: String, mtu: Int) {
        val builder = Builder()
            .setSession("SplateVPN")
            .setMtu(mtu)
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
        // Tor must also capture LAN DNS and UDP; its own UID is excluded below.
        val ipv4Routes = if (usingTor) listOf("0.0.0.0" to 0) else publicCidrs
        for ((ip, prefix) in ipv4Routes) {
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

        val bypass = temporaryRouting.bypassApps()
        val include = !isGlobal && settingsManager.getPerAppMode() == "include"
        val effectivePackages = if (isGlobal) bypass else if (include) (perAppList - bypass).ifEmpty { perAppList } else perAppList + bypass
        val effectiveMode = if (include) "include" else "exclude"
        val plan = PerAppRoutingPlan.resolve(isGlobal && bypass.isEmpty(), effectiveMode, effectivePackages, packageName) { appPackage ->
            try { packageManager.getApplicationInfo(appPackage, 0); true }
            catch (_: android.content.pm.PackageManager.NameNotFoundException) { false }
        }
        if (plan.allowed.isNotEmpty()) {
            var installedCount = 0
            plan.allowed.forEach { appPackage ->
                try { builder.addAllowedApplication(appPackage); installedCount++ }
                catch (_: android.content.pm.PackageManager.NameNotFoundException) { /* Uninstalled after validation. */ }
            }
            check(installedCount > 0) { "Selected VPN applications were removed. Review Applications settings." }
            preparedIncludedApps = installedCount
            preparedExcludedApps = 0
        } else {
            preparedIncludedApps = null
            preparedExcludedApps = 0
            plan.excluded.forEach { appPackage ->
                try {
                    builder.addDisallowedApplication(appPackage)
                    if (appPackage != packageName) preparedExcludedApps++
                }
                catch (_: android.content.pm.PackageManager.NameNotFoundException) { /* No remaining traffic for this package. */ }
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
                val guardUser = preparedAppIdentityAccess?.fallback?.username ?: preparedLocalProxyAccess?.username
                val effectiveResolver = if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    TProxyService.identityApiVersion() >= 1 &&
                    guardUser != null &&
                    SplitTunnelFlowGuard.shouldActivate(plan, packageName)
                ) {
                    SplitTunnelFlowGuard(
                        context = this,
                        plan = plan,
                        allowedUsername = guardUser,
                        delegate = preparedAppIdentityResolver,
                        onBlockedFlow = { source ->
                            appLogManager.w("SplitTunnel", "Заблокирована попытка обхода Split Tunneling ($source) через привязку к VPN Network.")
                        }
                    )
                } else {
                    preparedAppIdentityResolver
                }
                tProxyService = TProxyService(newInterface)
                tProxyService?.start(hevConfigFile.absolutePath, effectiveResolver)
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
                    tunnelHealthAdvisor.inspectTraffic(rxDelta, txDelta, downBytesPerSec, upBytesPerSec, isPhysicalInternetAlive())
                }
                lastSampleTime = sampleTime

                tick++
                if (tick % 3 == 0 && !isStopping.get() && vpnStatusManager.state.value == VpnState.CONNECTED) {
                    val nodeTitle = if (serverName.isNotBlank()) notificationText(com.danieldk.splatevpn.R.string.notification_connected, serverName) else notificationText(com.danieldk.splatevpn.R.string.notification_active)
                    val currentPing = vpnStatusManager.rttMs.value
                    val subText = if (currentPing > 0) "⚡ $currentPing ms • ↓ $downSpeedStr" else "↓ $downSpeedStr"
                    updateNotification(nodeTitle, "↓ $downSpeedStr  •  ↑ $upSpeedStr", subText, traffic = true)
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
        if (usingTor) return
        if (settingsManager.isAutoSelectServer() && settingsManager.getBalancerPreferences().maintainsStandby) standbyJob = serviceScope.launch(Dispatchers.IO) {
            delay(2000)
            var nextSpeedSampleAt = 0L
            while (isActive && !isStopping.get() && settingsManager.isAutoSelectServer() && settingsManager.getBalancerPreferences().maintainsStandby) {
                try {
                    if (!speedtestManager.isRunning && serverRepository.activePingIds.value.isEmpty() &&
                        vpnStatusManager.state.value == VpnState.CONNECTED && canProbePhysicalNetwork()) {
                        val prefs = settingsManager.getBalancerPreferences()
                        val now = System.nanoTime() / 1_000_000
                        val sampleDownload = prefs.metric == com.danieldk.splatevpn.data.BalancerMetric.DOWNLOAD &&
                            now >= nextSpeedSampleAt && optimizationJob?.isActive != true &&
                            vpnStatusManager.downBytesPerSec.value < 100_000L
                        if (sampleDownload) nextSpeedSampleAt = now + prefs.optimizationMinutes * 60_000L
                        serverRepository.refreshStandbyServers(deadServerIds.toSet(), sampleDownload)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("SplateVpn", "Standby check: ${e.message}")
                }
                delay(30_000)
            }
        }
        if (!settingsManager.isAutoSelectServer() || !settingsManager.getBalancerPreferences().monitorsConnection) return

        balancerJob = serviceScope.launch(Dispatchers.IO) {
            delay(1000)
            var baselinePing = vpnStatusManager.rttMs.value.takeIf { it > 0 }
                ?: vpnStatusManager.connectedServerProfile?.ping?.takeIf { it > 0 }
                ?: -1L
            var degradedSamples = 0
            var failedSamples = 0
            var nextGoalAttemptAt = 0L
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
                if (!settingsManager.areChecksAllowed()) {
                    failedSamples = 0
                    degradedSamples = 0
                    delay(prefs.healthIntervalSeconds * 1000L)
                    continue
                }
                try {
                    val node = vpnStatusManager.connectedServerProfile
                    if (node != null && node.id == vpnStatusManager.connectedServerId) {
                        val budget = if (baselinePing > 0) {
                            (baselinePing * 3).coerceIn(4500L, 8000L).toInt()
                        } else {
                            5000
                        }
                        val ping = serverRepository.testPingViaXray(timeoutMs = budget)
                        if (ping > 0) {
                            failedSamples = 0
                            tunnelHealthAdvisor.clearUnverifiedConnectivity(node.id, vpnStatusManager.sessionToken)
                            serverRepository.recordTunnelSuccess(node, ping)
                            serverRepository.updateServerPingIfUnchanged(node, ping)
                            val severelyDegraded = baselinePing > 0 && ping > 350L && ping > baselinePing * 2.2
                            degradedSamples = if (severelyDegraded) degradedSamples + 1 else 0
                            if (!severelyDegraded) baselinePing = if (baselinePing <= 0) ping else (baselinePing * 0.85 + ping * 0.15).toLong()
                            val now = System.nanoTime() / 1_000_000
                            val periodic = now >= nextOptimizationAt
                            if (periodic) nextOptimizationAt = now + prefs.optimizationMinutes * 60_000L
                            val goalPending = prefs.goal != com.danieldk.splatevpn.data.BalancerGoal.GENERAL &&
                                !serverRepository.qualifiesForGoal(node) && now >= nextGoalAttemptAt
                            if (goalPending) nextGoalAttemptAt = now + 30_000L
                            if ((prefs.optimizesConnection || goalPending) && !speedtestManager.isRunning && (degradedSamples >= 3 || periodic || goalPending) &&
                                vpnStatusManager.downBytesPerSec.value < 100_000L &&
                                canAutoSwitch(isDeadNode = false, optimizeCooldownMs = prefs.cooldownSeconds * 1000L) && optimizationJob?.isActive != true) {
                                optimizationJob = serviceScope.launch(Dispatchers.IO) {
                                    val session = vpnStatusManager.sessionToken
                                    val selectionRevision = settingsManager.selectionRevision()
                                    val network = currentNetwork
                                    fun stillCurrent() = canRecoverServers(VpnState.CONNECTED, session, selectionRevision) &&
                                        currentNetwork == network && !speedtestManager.isRunning
                                    try {
                                        val better = serverRepository.findBetterServerIfAvailable(node, ping, deadServerIds.toSet(), minImprovementRatio = prefs.improvementPercent / 100f, verifyGoalOnly = goalPending)
                                        autoSwitchMutex.withLock {
                                            val latest = settingsManager.getBalancerPreferences()
                                            if (better != null && stillCurrent() && latest == prefs &&
                                                (latest.optimizesConnection || goalPending) &&
                                                canAutoSwitch(isDeadNode = false, optimizeCooldownMs = latest.cooldownSeconds * 1000L) &&
                                                serverRepository.activateAutoCandidate(better, node, ::stillCurrent)) {
                                                reloadAfterAutoSwitch(::stillCurrent,
                                                    "Балансировщик: переход на проверенный стабильный узел '${better.name}' (${better.ping}ms)")
                                            }
                                        }
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        Log.w("SplateVpn", "Optimization: ${e.message}")
                                    }
                                }
                                degradedSamples = 0
                            }
                        } else if (canProbePhysicalNetwork()) {
                            failedSamples++
                            if (failedSamples < prefs.failureConfirmations - 1) {
                                delay(minOf(1000L, prefs.healthIntervalSeconds * 1000L))
                                continue
                            }
                            // Independent endpoints plus a second, more generous check avoid switching on one timeout.
                            // When failureConfirmations <= 2, wait 1s so a brief mobile micro-drop does not cause a false failover.
                            val confirmDelayMs = if (prefs.failureConfirmations <= 2) {
                                minOf(1000L, prefs.healthIntervalSeconds * 1000L)
                            } else {
                                200L
                            }
                            delay(confirmDelayMs)
                            if (!isActive || isStopping.get() || !settingsManager.isAutoSelectServer()) break
                            val confirmedTimeout = if (baselinePing > 0) (baselinePing * 4).coerceIn(6000L, 10000L).toInt() else 6000
                            val confirmed = serverRepository.testPingViaXray(timeoutMs = confirmedTimeout)
                            if (confirmed > 0) {
                                failedSamples = 0
                                tunnelHealthAdvisor.clearUnverifiedConnectivity(node.id, vpnStatusManager.sessionToken)
                                serverRepository.recordTunnelSuccess(node, confirmed)
                                serverRepository.updateServerPingIfUnchanged(node, confirmed)
                            } else if (handleNodeUnhealthy(node, "${prefs.failureConfirmations} проверок реального трафика через туннель не прошли")) {
                                break
                            }
                        } else {
                            failedSamples = 0
                            degradedSamples = 0
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
        aiDnsMonitorJob?.cancel()
        aiDnsMonitorJob = null
        val previousStartup = startupJob
        previousStartup?.cancel()
        startupJob = serviceScope.launch {
            previousStartup?.join()
            currentCoroutineContext().ensureActive()
            stopVpnInternal()
        }
    }

    private suspend fun stopVpnInternal() = vpnMutex.withLock {
        cleanupAndStopLocked()
    }

    private suspend fun cleanupAndStopLocked(
        stopService: Boolean = true,
        inactiveState: VpnState = if (stopService) VpnState.DISCONNECTED else VpnState.RECONNECTING
    ) {
        val callerContext = currentCoroutineContext()
        withContext(NonCancellable) {
            try {
                torMonitorJob?.cancel()
                torMonitorJob = null
                torHealthJob?.cancel()
                torHealthJob = null
                aiDnsMonitorJob?.cancel()
                aiDnsMonitorJob = null
                appLogManager.i("SplateVpn", "Остановка службы VPN и освобождение ресурсов...")
                isServiceActive = false
                preparedSubscriptionRules = null
                appliedSubscriptionRules = null
                preparedAdvancedRules = emptyList()
                appliedAdvancedRules = null
                preparedAppIdentityAccess = null
                preparedAppIdentityResolver = null
                com.danieldk.splatevpn.ui.widget.SplateAppWidgetProvider.notifyUpdate(this@SplateVpnService)
                com.danieldk.splatevpn.ui.widget.SplateQuickWidgetProvider.notifyUpdate(this@SplateVpnService)
                SplateTileService.requestUpdate(this@SplateVpnService)
                statsJob?.cancel()
                statsJob = null
                balancerJob?.cancel()
                balancerJob = null
                recoveryJob?.cancel()
                recoveryJob = null
                standbyJob?.cancel()
                standbyJob = null
                optimizationJob?.cancel()
                optimizationJob = null
                postConnectCheckJob?.cancel()
                postConnectCheckJob = null
                postConnectDiagnosticJob?.cancel()
                postConnectDiagnosticJob = null
                autoSelectObserverJob?.cancel()
                autoSelectObserverJob = null
                resetAutoSwitchLimits()
                if (vpnStatusManager.state.value != VpnState.FAILED) {
                    vpnStatusManager.setState(inactiveState)
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
                    LocalProxyAccess.revoke(localProxyRuntimeToken)
                    localProxyRuntimeToken = null
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

                torRuntime.stop()
                torNode = null
                usingTor = false

                dnsHealthAdvisor.dismiss()
                // Main also delivers new service commands. Check ownership and stop together so
                // a cancelled cleanup cannot stop a newer startup after releasing resources.
                withContext(Dispatchers.Main.immediate) {
                    if (stopService && callerContext.isActive) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
                Log.d("SplateVpn", "VPN stopped completely.")
            } catch (e: Exception) {
                Log.e("SplateVpn", "Error stopping VPN", e)
            }
        }
    }

    override fun onRevoke() {
        Log.w("SplateVpn", "VPN permission revoked by system (e.g. another VPN started)")
        appLogManager.w("SplateVpn", "VPN разрешение отозвано системой (активирован другой VPN). Служба останавливается.")
        isServiceActive = false
        vpnStatusManager.setState(VpnState.DISCONNECTED)
        isStopping.set(true)
        val previous = startupJob
        previous?.cancel()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch(NonCancellable) {
            previous?.join()
            vpnMutex.withLock {
                cleanupAndStopLocked(stopService = true)
            }
        }
        super.onRevoke()
    }

    override fun onDestroy() {
        super.onDestroy()
        val socketProtection = awgSocketProtection
        socketProtection?.close()
        awgSocketProtection = null
        // A status-only binding must never connect/disconnect a VPN or reset its UI state.
        if (!hasStartedVpn) {
            serviceScope.cancel()
            return
        }
        LocalProxyAccess.revoke(localProxyRuntimeToken)
        localProxyRuntimeToken = null
        isServiceActive = false
        dnsHealthAdvisor.dismiss()
        if (vpnStatusManager.state.value != VpnState.FAILED) vpnStatusManager.setState(VpnState.DISCONNECTED)
        statsJob?.cancel()
        statsJob = null
        unregisterNetworkCallback()
        serviceScope.cancel()
        // Reserve teardown before another service instance can start the process-wide tunnel.
        CoroutineScope(Dispatchers.Main.immediate).launch(start = CoroutineStart.UNDISPATCHED) {
            vpnMutex.withLock {
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { socketProtection?.awaitClosed() }
                        .onFailure { Log.w("SplateVpn", "Socket protection teardown failed", it) }
                    LocalProxyAccess.revoke(localProxyRuntimeToken)
                    localProxyRuntimeToken = null
                    runCatching { tProxyService?.stop() }
                        .onFailure { Log.w("SplateVpn", "TUN teardown failed", it) }
                    tProxyService = null
                    runCatching { vpnInterface?.close() }
                        .onFailure { Log.w("SplateVpn", "TUN descriptor close failed", it) }
                    vpnInterface = null
                    runCatching { xrayCoreManager.stopCore() }
                        .onFailure { Log.w("SplateVpn", "Xray teardown failed", it) }
                    runCatching { torRuntime.stop() }
                        .onFailure { Log.w("SplateVpn", "Tor teardown failed", it) }
                }
            }
        }
    }

    private fun notificationText(id: Int, vararg args: Any): String {
        val locale = com.danieldk.splatevpn.ui.theme.appLocale(settingsManager.getAppLanguage(), resources.configuration.locales[0])
        return com.danieldk.splatevpn.ui.theme.localizedResources(this, locale).getString(id, *args)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "splate_vpn_channel_v2",
                notificationText(com.danieldk.splatevpn.R.string.notification_channel),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = notificationText(com.danieldk.splatevpn.R.string.notification_description)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, text: String? = null, subText: String? = null, traffic: Boolean = false): Notification {
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
        val preferences = settingsManager.getNotificationPreferences()
        val connected = vpnStatusManager.state.value == VpnState.CONNECTED

        val details = if (connected || traffic) {
            val ping = vpnStatusManager.rttMs.value
            val latency = if (ping > 0) notificationText(com.danieldk.splatevpn.R.string.notification_ping, ping)
                else notificationText(com.danieldk.splatevpn.R.string.notification_ping_unknown)
            if (preferences.showSpeed && !text.isNullOrBlank()) "$latency • $text" else latency
        } else com.danieldk.splatevpn.data.ConnectionStatusText.localize(text,
            com.danieldk.splatevpn.ui.theme.localizedResources(this,
                com.danieldk.splatevpn.ui.theme.appLocale(settingsManager.getAppLanguage(), java.util.Locale.getDefault())))
            ?: notificationText(com.danieldk.splatevpn.R.string.notification_active)

        val builder = NotificationCompat.Builder(this, "splate_vpn_channel_v2")
            .setContentTitle(if (preferences.showServer) title else "SplateVPN")
            .setContentText(details)
            .setStyle(NotificationCompat.BigTextStyle().bigText(details))
            .setSmallIcon(com.danieldk.splatevpn.R.drawable.ic_qs_vpn)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH) // Whitelisted by OxygenOS/ColorOS for Fluid Cloud capsule
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                notificationText(com.danieldk.splatevpn.R.string.notification_disconnect),
                disconnectIntent
            )

        // OnePlus OxygenOS 14/15 Fluid Cloud / Live Capsule metadata
        builder.extras.putString("android.substName", "SplateVPN")
        builder.extras.putBoolean("oplus_capsule", true)
        builder.extras.putString("oplus_view_type", "capsule")
        builder.extras.putString("oplus.view.type", "capsule")

        if (sessionStart > 0L && preferences.showDuration) {
            builder.setUsesChronometer(true)
            builder.setWhen(sessionStart)
            builder.setShowWhen(true)
        } else {
            builder.setShowWhen(false)
        }

        if (preferences.showSpeed && !subText.isNullOrBlank()) {
            builder.setSubText(subText)
        }

        return builder.build()
    }

    private fun updateNotification(title: String, text: String? = null, subText: String? = null, traffic: Boolean = false) {
        if (isStopping.get()) return
        try {
            val notification = buildNotification(title, text, subText, traffic)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.notify(1, notification)
        } catch (e: Exception) {
            // ignore
        }
    }
}
