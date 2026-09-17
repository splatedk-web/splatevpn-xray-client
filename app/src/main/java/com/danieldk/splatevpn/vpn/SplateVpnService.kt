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
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.danieldk.splatevpn.data.ServerRepository
import com.danieldk.splatevpn.data.SettingsManager
import com.danieldk.splatevpn.data.AppLogManager
import com.v2ray.ang.service.TProxyService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class SplateVpnService : VpnService() {

    companion object {
        @Volatile
        var isServiceActive: Boolean = false
            private set
    }

    @Inject
    lateinit var xrayCoreManager: XrayCoreManager

    @Inject
    lateinit var xrayConfigGenerator: XrayConfigGenerator

    @Inject
    lateinit var serverRepository: ServerRepository

    @Inject
    lateinit var settingsManager: SettingsManager

    @Inject
    lateinit var vpnStatusManager: VpnStatusManager

    @Inject
    lateinit var appLogManager: AppLogManager

    private var vpnInterface: ParcelFileDescriptor? = null
    private var tProxyService: TProxyService? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var statsJob: Job? = null
    private val isStopping = java.util.concurrent.atomic.AtomicBoolean(false)
    private val vpnMutex = Mutex()

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var currentNetwork: Network? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START" -> startVpn()
            "RELOAD" -> reloadVpn()
            "STOP" -> stopVpn()
        }
        return START_NOT_STICKY
    }

    private fun startVpn() {
        isStopping.set(false)

        val notification = createNotification("SplateVPN is running - Protected")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }

        serviceScope.launch {
            startVpnInternal()
        }
    }

    private suspend fun startVpnInternal() = vpnMutex.withLock {
        try {
            // Ensure any previous tunnel/core/tun are cleaned up before starting
            try {
                tProxyService?.stop()
                tProxyService = null
                xrayCoreManager.stopCore()
                vpnInterface?.close()
                vpnInterface = null
            } catch (e: Exception) {
                Log.w("SplateVpn", "Pre-start cleanup: ${e.message}")
            }

            // Ensure default servers exist if none
            serverRepository.initDefaultServers()

            // Auto-select fastest server if enabled
            if (settingsManager.isAutoSelectServer()) {
                Log.d("SplateVpn", "Auto-select is enabled: testing pings and selecting fastest server")
                serverRepository.autoSelectFastestServer()
            }

            val activeNode = serverRepository.getActiveServerSync()
            val routingMode = settingsManager.getRoutingMode()
            val isGlobal = settingsManager.isGlobalAppProxy()
            val perAppPackages = settingsManager.getPerAppPackages()
            val blockQuic = settingsManager.isBlockQuic()
            val blockIpv6 = settingsManager.isBlockIpv6()
            val enableSniffing = settingsManager.isEnableSniffing()
            val routeOnlySniffing = settingsManager.isRouteOnlySniffing()
            val dnsProvider = settingsManager.getDnsProvider()
            val isFakeDns = settingsManager.isFakeDns()
            val xhttpMode = settingsManager.getXhttpMode()

            appLogManager.i("SplateVpn", "Запуск VPN: Узел='${activeNode?.name}', Адрес='${activeNode?.address}:${activeNode?.port}', Протокол='${activeNode?.protocol}/${activeNode?.network}', Безопасность='${activeNode?.security}', SNI='${activeNode?.sni}', Host='${activeNode?.host}', Path='${activeNode?.path}', Режим='$routingMode', DNS='$dnsProvider', FakeDNS=$isFakeDns")

            val configJson = xrayConfigGenerator.generateConfig(
                node = activeNode,
                mode = routingMode,
                enableSniffing = enableSniffing,
                routeOnlySniffing = routeOnlySniffing,
                blockQuic = blockQuic,
                blockIpv6 = blockIpv6,
                dnsProvider = dnsProvider,
                isFakeDns = isFakeDns,
                xhttpModeOverride = xhttpMode
            )

            // 1. Start Xray Core Process
            val started = xrayCoreManager.startCore(configJson)
            if (!started) {
                appLogManager.e("SplateVpn", "Не удалось запустить ядро Xray, отмена запуска VPN")
                stopVpnInternal()
                return@withLock
            }

            // 2. Setup Android TUN interface
            setupVpnInterface(perAppPackages, isGlobal, dnsProvider)

            val vpnFd = vpnInterface
            if (vpnFd == null) {
                appLogManager.e("SplateVpn", "Ошибка: TUN интерфейс не был создан!")
                stopVpnInternal()
                return@withLock
            }
            appLogManager.i("SplateVpn", "TUN интерфейс успешно создан (FD=${vpnFd.fd})")

            // 3. Create hev-socks5-tunnel configuration (MTU 1400 prevents packet black holes)
            val hevConfigFile = File(filesDir, "hev-socks5-tunnel.yaml")
            val hevConfigContent = """
                tunnel:
                  mtu: 1400
                  ipv4: 172.19.0.1
                  ipv6: 'fd00::1'
                socks5:
                  port: 10808
                  address: 127.0.0.1
                  udp: 'udp'
                misc:
                  tcp-read-write-timeout: 20000
                  udp-read-write-timeout: 15000
                  log-level: warn
            """.trimIndent()
            hevConfigFile.writeText(hevConfigContent)

            // 4. Start TProxy Service
            tProxyService = TProxyService(vpnFd)
            tProxyService?.start(hevConfigFile.absolutePath)
            appLogManager.i("SplateVpn", "hev-socks5-tunnel запущен, VPN активен")
            isServiceActive = true
            vpnStatusManager.setConnected(true)

            // 5. Register network callback to track active network & handle auto-recovery
            registerNetworkCallback()

            // 6. Start IP details detection in background
            serviceScope.launch {
                delay(2000)
                vpnStatusManager.fetchCurrentIpDetails()
            }

            // 7. Start Traffic Stats Monitor
            startStatsMonitor()
            appLogManager.i("SplateVpn", "Защищенное соединение установлено! [${activeNode?.name}]")

        } catch (e: Exception) {
            appLogManager.e("SplateVpn", "Исключение при старте VPN: ${e.message}", e)
            stopVpnInternal()
        }
    }

    private fun reloadVpn() {
        if (isStopping.get()) return
        serviceScope.launch {
            reloadVpnInternal()
        }
    }

    private suspend fun reloadVpnInternal() = vpnMutex.withLock {
        if (isStopping.get()) return@withLock

        // If TUN interface is not established or tProxyService is dead, perform a fresh start
        if (vpnInterface == null || tProxyService == null) {
            appLogManager.i("SplateVpn", "Туннель не активен, выполняется полная инициализация...")
            startVpnInternal()
            return@withLock
        }

        try {
            val activeNode = serverRepository.getActiveServerSync()
            val routingMode = settingsManager.getRoutingMode()
            val blockQuic = settingsManager.isBlockQuic()
            val blockIpv6 = settingsManager.isBlockIpv6()
            val enableSniffing = settingsManager.isEnableSniffing()
            val routeOnlySniffing = settingsManager.isRouteOnlySniffing()
            val dnsProvider = settingsManager.getDnsProvider()
            val isFakeDns = settingsManager.isFakeDns()
            val xhttpMode = settingsManager.getXhttpMode()

            appLogManager.i("SplateVpn", "Бесшовное переключение ядра на узел: '${activeNode?.name}' [${activeNode?.address}:${activeNode?.port}], Протокол='${activeNode?.protocol}/${activeNode?.network}', Безопасность='${activeNode?.security}', SNI='${activeNode?.sni}', Host='${activeNode?.host}', Path='${activeNode?.path}'")

            val configJson = xrayConfigGenerator.generateConfig(
                node = activeNode,
                mode = routingMode,
                enableSniffing = enableSniffing,
                routeOnlySniffing = routeOnlySniffing,
                blockQuic = blockQuic,
                blockIpv6 = blockIpv6,
                dnsProvider = dnsProvider,
                isFakeDns = isFakeDns,
                xhttpModeOverride = xhttpMode
            )

            // 1. Stop current Xray core (guaranteeing port 10808 is freed)
            xrayCoreManager.stopCore()

            // 2. Start Xray with the updated configuration
            val started = xrayCoreManager.startCore(configJson)
            if (!started) {
                appLogManager.e("SplateVpn", "Не удалось перезапустить ядро Xray при смене узла!")
                stopVpnInternal()
                return@withLock
            }

            // 3. Reset and refresh IP geolocation details
            vpnStatusManager.resetIpDetails()
            serviceScope.launch {
                delay(1500)
                vpnStatusManager.fetchCurrentIpDetails()
            }
            appLogManager.i("SplateVpn", "Узел успешно переключен: [${activeNode?.name}]")
        } catch (e: Exception) {
            appLogManager.e("SplateVpn", "Ошибка при переключении узла: ${e.message}", e)
        }
    }

    private val defaultNetworkRequest by lazy {
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
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
                    val prev = currentNetwork
                    currentNetwork = network
                    Log.d("SplateVpn", "Physical network available: $network (was: $prev)")
                    appLogManager.i("SplateVpn", "Физическая сеть подключена: $network")

                    // Bind VPN tunnel outbound to this physical network
                    setUnderlyingNetworks(arrayOf(network))

                    // Update IP location details in background on network change
                    if (vpnStatusManager.isConnected.value && !isStopping.get()) {
                        appLogManager.i("SplateVpn", "Смена сети, выполняю мягкий перезапуск VPN для применения маршрутов...")
                        reloadVpn()
                        
                        serviceScope.launch {
                            delay(1500)
                            vpnStatusManager.fetchCurrentIpDetails()
                        }
                    }
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    val isValidated = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    Log.d("SplateVpn", "Network capabilities: $network (hasInternet=$hasInternet, isValidated=$isValidated)")
                    if (hasInternet) {
                        currentNetwork = network
                        setUnderlyingNetworks(arrayOf(network))
                    }
                }

                override fun onLost(network: Network) {
                    Log.d("SplateVpn", "Physical network lost: $network")
                    appLogManager.w("SplateVpn", "Физическая сеть отключена: $network")
                    if (currentNetwork == network) {
                        currentNetwork = null
                        setUnderlyingNetworks(null)
                    }
                }
            }

            networkCallback = callback
            try {
                cm.requestNetwork(defaultNetworkRequest, callback)
            } catch (e: Exception) {
                Log.w("SplateVpn", "requestNetwork fallback to registerDefaultNetworkCallback: ${e.message}")
                cm.registerDefaultNetworkCallback(callback)
            }
            Log.d("SplateVpn", "Network callback registered successfully")
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
        currentNetwork = null
    }

    private fun setupVpnInterface(perAppList: Set<String>, isGlobal: Boolean, dnsProvider: String) {
        val builder = Builder()
            .setSession("SplateVPN")
            .setMtu(1400)
            .addAddress("172.19.0.1", 30)

        // Configure DNS on TUN interface matching provider
        when (dnsProvider.lowercase()) {
            "google" -> {
                builder.addDnsServer("8.8.8.8")
                builder.addDnsServer("8.8.4.4")
            }
            "quad9" -> {
                builder.addDnsServer("9.9.9.9")
                builder.addDnsServer("149.112.112.112")
            }
            "adguard" -> {
                builder.addDnsServer("94.140.14.14")
                builder.addDnsServer("94.140.15.15")
            }
            else -> { // cloudflare
                builder.addDnsServer("1.1.1.1")
                builder.addDnsServer("1.0.0.1")
            }
        }

        builder.addRoute("0.0.0.0", 0)

        // Only configure IPv6 on TUN if blockIpv6 is disabled (i.e. user wants IPv6).
        // If blockIpv6 is true, having no IPv6 on TUN tells Android OS that the VPN is IPv4-only,
        // so Android and apps connect via IPv4 immediately without 20-second IPv6 timeouts.
        if (!settingsManager.isBlockIpv6()) {
            try {
                builder.addAddress("fd00::1", 126)
                builder.addRoute("::", 0)
            } catch (e: Exception) {
                Log.w("SplateVpn", "Could not add IPv6 route: ${e.message}")
            }
        }

        // Per-app routing vs Global routing
        // NOTE: In Android VpnService.Builder, cannot mix addAllowedApplication and addDisallowedApplication!
        if (!isGlobal && perAppList.isNotEmpty()) {
            perAppList.forEach { appPackage ->
                try {
                    builder.addAllowedApplication(appPackage)
                } catch (e: Exception) {
                    Log.w("SplateVpn", "Package not found: $appPackage")
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

        vpnInterface?.close()
        vpnInterface = builder.establish()
    }

    private fun startStatsMonitor() {
        statsJob?.cancel()
        statsJob = serviceScope.launch {
            var lastRx = TrafficStats.getTotalRxBytes()
            var lastTx = TrafficStats.getTotalTxBytes()

            while (isActive) {
                delay(1000)
                val currentRx = TrafficStats.getTotalRxBytes()
                val currentTx = TrafficStats.getTotalTxBytes()

                val rxSpeed = (currentRx - lastRx).coerceAtLeast(0)
                val txSpeed = (currentTx - lastTx).coerceAtLeast(0)

                lastRx = currentRx
                lastTx = currentTx

                vpnStatusManager.updateSpeeds(
                    up = formatSpeed(txSpeed),
                    down = formatSpeed(rxSpeed)
                )
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
        serviceScope.launch {
            stopVpnInternal()
        }
    }

    private suspend fun stopVpnInternal() = vpnMutex.withLock {
        try {
            appLogManager.i("SplateVpn", "Остановка службы VPN и освобождение ресурсов...")
            isServiceActive = false
            statsJob?.cancel()
            statsJob = null
            vpnStatusManager.setConnected(false)

            unregisterNetworkCallback()

            tProxyService?.stop()
            tProxyService = null

            xrayCoreManager.stopCore()

            vpnInterface?.close()
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
        vpnStatusManager.setConnected(false)
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceActive = false
        vpnStatusManager.setConnected(false)
        statsJob?.cancel()
        statsJob = null
        unregisterNetworkCallback()
        try {
            tProxyService?.stop()
            tProxyService = null
            runBlocking(Dispatchers.IO) {
                vpnMutex.withLock {
                    xrayCoreManager.stopCore()
                }
            }
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: Exception) {
            Log.e("SplateVpn", "Error in onDestroy cleanup", e)
        }
        serviceScope.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "splate_vpn_channel",
                "VPN Status",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, "splate_vpn_channel")
            .setContentTitle("SplateVPN")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_secure)
            .build()
    }
}
