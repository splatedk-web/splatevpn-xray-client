package com.danieldk.splatevpn.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.app.NotificationCompat
import com.danieldk.splatevpn.MainActivity
import com.danieldk.splatevpn.ui.theme.ResourceAppStrings
import com.danieldk.splatevpn.ui.theme.appLocale
import com.danieldk.splatevpn.ui.theme.localized
import com.danieldk.splatevpn.ui.theme.localizedResources
import com.danieldk.splatevpn.vpn.SplateVpnService
import com.danieldk.splatevpn.vpn.VpnState
import com.danieldk.splatevpn.vpn.VpnStatusManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

enum class HealthIssueKind {
    NETWORK_UNAVAILABLE,
    CONNECTIVITY_UNVERIFIED,
    DNS_BLOCKED,
    SILENT_BLACKHOLE,
    REALITY_TLS_FAILURE,
    CAPTIVE_PORTAL,
    MTU_FRAGMENTATION,
    TLS_FRAGMENTATION,
    GEO_BLOCKED_DOMAIN,
    TIME_DESYNC,
    ROUTING_LOOP
}

data class TunnelHealthIssue(
    val kind: HealthIssueKind,
    val title: String,
    val description: String,
    val primaryActionLabel: String? = null,
    val primaryActionId: String? = null,
    val primaryActionExtra: String? = null,
    val secondaryActionLabel: String? = null,
    val secondaryActionId: String? = null,
    val secondaryActionExtra: String? = null,
    val isChecking: Boolean = false,
    val dnsIssue: DnsHealthIssue? = null
)

@Singleton
class TunnelHealthAdvisor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dnsProviderChecker: DnsProviderChecker,
    private val settingsManager: SettingsManager,
    private val vpnStatusManager: VpnStatusManager,
    private val probesProvider: javax.inject.Provider<ServerProbeRepository>
) {
    companion object {
        const val NOTIFICATION_ID_HEALTH_ISSUE = 1002
        const val CHANNEL_ID_HEALTH_ALERTS = "splate_vpn_alerts"


    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _issue = MutableStateFlow<TunnelHealthIssue?>(null)
    val issue: StateFlow<TunnelHealthIssue?> = _issue.asStateFlow()

    // For backwards compatibility with existing DNS banner consumers
    private val _dnsIssue = MutableStateFlow<DnsHealthIssue?>(null)
    val dnsIssue: StateFlow<DnsHealthIssue?> = _dnsIssue.asStateFlow()

    @Volatile
    private var dismissedServerId: String? = null
    private val lock = Any()
    private var postedNotificationKind: HealthIssueKind? = null
    private var lastHealthNotificationAt = -120_000L

    // Sliding timestamps for detectors
    private val dnsErrorTimestamps = mutableListOf<Long>()
    private val tlsErrorTimestamps = mutableListOf<Long>()
    private val rstErrorTimestamps = mutableListOf<Long>()
    private val mtuErrorTimestamps = mutableListOf<Long>()
    private var silentTxConsecutiveSeconds = 0
    private var offlineSamples = 0
    private var routingLoopConsecutiveSeconds = 0
    private var lastTimeCheckTimestamp = 0L

    private var scanJob: Job? = null
    private var timeCheckJob: Job? = null
    private val fragmentMutex = kotlinx.coroutines.sync.Mutex()
    @Volatile private var nextFragmentCheckAt = 0L
    private val dnsMutex = kotlinx.coroutines.sync.Mutex()
    private data class DnsContext(val server: String, val token: Long, val key: String,
        val custom: String, val fakeDns: Boolean, val fragment: FragmentationProfile)
    private data class DnsDiagnosisCache(val context: DnsContext, val checkedAt: Long, val issue: DnsHealthIssue?)
    @Volatile private var dnsDiagnosisCache: DnsDiagnosisCache? = null
    private var confirmedFragmentProfile: FragmentationProfile? = null
    private var unverifiedConnectivitySession: Pair<String, Long>? = null
    private var unverifiedConnectivityJob: Job? = null

    /** Failed health probes establish an unverified connection, not its underlying cause. */
    fun reportUnverifiedConnectivity(expectedServerId: String, expectedSession: Long) {
        synchronized(lock) {
            if (!isCurrentConnection(expectedServerId, expectedSession) || dismissedServerId == expectedServerId || hasSpecificIssue()) return
            val expected = expectedServerId to expectedSession
            if (_issue.value?.kind == HealthIssueKind.CONNECTIVITY_UNVERIFIED && unverifiedConnectivitySession == expected) return
            val resources = localizedResources(context, appLocale(settingsManager.getAppLanguage(), Locale.getDefault()))
            unverifiedConnectivitySession = expected
            setIssue(TunnelHealthIssue(
                kind = HealthIssueKind.CONNECTIVITY_UNVERIFIED,
                title = resources.getString(com.danieldk.splatevpn.R.string.health_connectivity_unverified_title),
                description = resources.getString(com.danieldk.splatevpn.R.string.health_connectivity_unverified_description)
            ))
            startUnverifiedConnectivityChecks(expectedServerId, expectedSession)
        }
    }

    /** A successful probe may clear only the neutral warning from this same session. */
    fun clearUnverifiedConnectivity(expectedServerId: String, expectedSession: Long) {
        synchronized(lock) {
            val currentIssue = _issue.value
            if (!isCurrentConnection(expectedServerId, expectedSession) ||
                unverifiedConnectivitySession != (expectedServerId to expectedSession) ||
                currentIssue?.kind != HealthIssueKind.CONNECTIVITY_UNVERIFIED) return
            if (_issue.compareAndSet(currentIssue, null)) {
                unverifiedConnectivitySession = null
                unverifiedConnectivityJob?.cancel()
                unverifiedConnectivityJob = null
                cancelNotification()
            }
        }
    }

    private fun isCurrentConnection(server: String, session: Long) = vpnStatusManager.state.value == VpnState.CONNECTED &&
        vpnStatusManager.connectedServerId == server && vpnStatusManager.sessionToken == session

    private fun hasSpecificIssue() = _issue.value?.kind?.let { it != HealthIssueKind.CONNECTIVITY_UNVERIFIED } == true

    private fun startUnverifiedConnectivityChecks(server: String, session: Long) {
        unverifiedConnectivityJob?.cancel()
        unverifiedConnectivityJob = scope.launch {
            fun current() = synchronized(lock) {
                isCurrentConnection(server, session) && dismissedServerId != server &&
                    unverifiedConnectivitySession == (server to session) &&
                    _issue.value?.kind == HealthIssueKind.CONNECTIVITY_UNVERIFIED
            }
            while (isActive) {
                delay(30_000)
                if (!current()) return@launch
                if (!settingsManager.areChecksAllowed()) continue
                val healthy = try { permittedHealthCheck() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                if (!current()) return@launch
                if (healthy == true) {
                    clearUnverifiedConnectivity(server, session)
                    return@launch
                }
            }
        }
    }

    /** Called after checking DNS and before node replacement; no settings are changed. */
    suspend fun checkFragmentationFailure(): Boolean {
        if (!settingsManager.areChecksAllowed()) return false
        fragmentMutex.lock()
        try {
            val server = vpnStatusManager.connectedServerId ?: return false
            val token = vpnStatusManager.sessionToken
            val active = FragmentationProfile.read(settingsManager)
            fun current() = vpnStatusManager.state.value in setOf(VpnState.CONNECTED, VpnState.RECONNECTING) &&
                vpnStatusManager.connectedServerId == server && vpnStatusManager.sessionToken == token &&
                FragmentationProfile.read(settingsManager) == active && dismissedServerId != server
                && _issue.value?.kind != HealthIssueKind.NETWORK_UNAVAILABLE
            if (!current() || !active.enabled || active == FragmentationProfile()) return false
            if (_issue.value?.kind == HealthIssueKind.TLS_FRAGMENTATION && confirmedFragmentProfile == active) return true
            val now = android.os.SystemClock.elapsedRealtime()
            if (now < nextFragmentCheckAt) return false
            nextFragmentCheckAt = now + 60_000
            val mismatch = try { probesProvider.get().diagnoseFragmentation(server, active, ::current) }
                catch (_: WifiRequiredException) { return false }
            if (!mismatch || !current()) return false
            confirmedFragmentProfile = active
            _dnsIssue.value = null

            setIssue(TunnelHealthIssue(
                kind = HealthIssueKind.TLS_FRAGMENTATION,
                title = text("Текущая фрагментация мешает подключению", "Current fragmentation prevents connectivity"),
                description = text("Две проверки того же сервера не прошли с текущими параметрами, но прошли со стандартными. Попробуйте стандартную фрагментацию.", "Two checks of the same server failed with current parameters and passed with standard fragmentation. Try the standard preset."),
                primaryActionLabel = text("Стандартная фрагментация", "Use standard fragmentation"),
                primaryActionId = "USE_STANDARD_FRAGMENTATION"
            ))
            return true
        } finally { fragmentMutex.unlock() }
    }

    fun applyStandardFragmentation(): Boolean {
        if (vpnStatusManager.state.value != VpnState.CONNECTED || _issue.value?.kind != HealthIssueKind.TLS_FRAGMENTATION ||
            confirmedFragmentProfile != FragmentationProfile.read(settingsManager)) return false
        settingsManager.setTlsFragmentPreset("standard")
        dismiss()
        return true
    }

    fun onNetworkChanged() {
        synchronized(lock) {
            dismissedServerId = null
            nextTransportCheckAt = 0L
            dnsErrorTimestamps.clear()
            tlsErrorTimestamps.clear()
            rstErrorTimestamps.clear()
            mtuErrorTimestamps.clear()
            silentTxConsecutiveSeconds = 0
            offlineSamples = 0
            routingLoopConsecutiveSeconds = 0
            nextFragmentCheckAt = 0L
            dnsDiagnosisCache = null
            confirmedFragmentProfile = null
            unverifiedConnectivitySession = null
            unverifiedConnectivityJob?.cancel()
            unverifiedConnectivityJob = null
            transportCheckJob?.cancel()
            scanJob?.cancel()
            timeCheckJob?.cancel()
            _issue.value = null
            _dnsIssue.value = null
            cancelNotification()
        }
    }

    init {
        createNotificationChannel()
        scope.launch {
            settingsManager.notificationPreferences.collect { preferences ->
                synchronized(lock) {
                    if (postedNotificationKind?.let { !preferences.allows(it) } != false) cancelNotification()
                }
            }
        }
        scope.launch {
            vpnStatusManager.state.collect { state ->
                if (state != VpnState.CONNECTED) {
                    onNetworkChanged()
                } else {
                    // Trigger Time Desync check once connected
                    triggerTimeDesyncCheck()
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel = NotificationChannel(
                CHANNEL_ID_HEALTH_ALERTS,
                text("Оповещения и сбои туннеля", "Tunnel Alerts & Diagnostics"),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = text("Оповещения об ошибках соединения с рекомендациями решений", "Tunnel health notifications with suggested fixes")
                setShowBadge(true)
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

    private fun text(ru: String, en: String): String {
        val locale = appLocale(settingsManager.getAppLanguage(), Locale.getDefault())
        return when (locale.language) {
            "ru" -> ru
            "zh", "fa" -> ResourceAppStrings(localizedResources(context, locale)).localized(ru, en)
            else -> en
        }
    }

    /**
     * Inspects every line from Xray core and access logs.
     */
    fun inspectXrayLog(rawLine: String) {
        if (vpnStatusManager.state.value != VpnState.CONNECTED) return
        val currentServer = vpnStatusManager.connectedServerId
        if (dismissedServerId != null && dismissedServerId == currentServer) return

        val lower = rawLine.lowercase()

        // 1. DNS Failures
        inspectDnsErrors(lower)

        // 2. Reality SNI & TLS Failures
        inspectTlsErrors(lower)

        // 3. Silent Blackholing & TCP RST Injection
        inspectRstErrors(lower)

        // 4. MTU / Fragmentation
        inspectMtuErrors(lower)

        // 5. Russian Services Geo-Bypass Collisions
        // Access-log errors alone cannot establish geographic blocking of a website.

        // 6. Routing Loop in log
        inspectRoutingLoopErrors(lower)
    }

    private fun inspectDnsErrors(lower: String) {
        val isDnsFailure = "app/dns:" in lower && (
            "context deadline exceeded" in lower ||
            "failed to retrieve response" in lower ||
            "failed to read response length" in lower ||
            "out of bound: 18516" in lower ||
            "closed pipe" in lower ||
            "timeout" in lower
        )
        if (!isDnsFailure) return

        val now = System.currentTimeMillis()
        val shouldTrigger = synchronized(lock) {
            dnsErrorTimestamps.removeAll { now - it > 45_000 }
            dnsErrorTimestamps.add(now)
            dnsErrorTimestamps.size >= 5 && !hasSpecificIssue() && scanJob?.isActive != true
        }

        if (shouldTrigger) {
            startDnsDiagnosis()
        }
    }

    private fun inspectTlsErrors(lower: String) {
        val isTlsFailure = (
            "tls: handshake failure" in lower ||
            "remote error: tls: handshake failure" in lower ||
            "tls: bad certificate" in lower ||
            "x509: certificate signed by unknown authority" in lower ||
            "first record does not look like a tls handshake" in lower ||
            ("failed to handler mux client" in lower && "tls:" in lower)
        )
        if (!isTlsFailure) return

        val now = System.currentTimeMillis()
        val shouldTrigger = synchronized(lock) {
            tlsErrorTimestamps.removeAll { now - it > 20_000 }
            tlsErrorTimestamps.add(now)
            tlsErrorTimestamps.size >= 2 && !hasSpecificIssue()
        }

        if (shouldTrigger) confirmTunnelFailure()
    }

    private fun inspectRstErrors(lower: String) {
        if (!isRelevantProxyReset(lower)) return
        val now = android.os.SystemClock.elapsedRealtime()
        val shouldTrigger = synchronized(lock) {
            rstErrorTimestamps.removeAll { now - it > 25_000 }
            rstErrorTimestamps.add(now)
            rstErrorTimestamps.size >= 3 && !hasSpecificIssue()
        }
        if (shouldTrigger) confirmTunnelFailure()
    }

    private var transportCheckJob: Job? = null
    private var nextTransportCheckAt = 0L

    private fun confirmTunnelFailure() {
        if (!settingsManager.areChecksAllowed()) return
        val server = vpnStatusManager.connectedServerId ?: return
        val token = vpnStatusManager.sessionToken
        synchronized(lock) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (transportCheckJob?.isActive == true || now < nextTransportCheckAt || dismissedServerId == server) return
            nextTransportCheckAt = now + 60_000
            transportCheckJob = scope.launch {
                fun current() = vpnStatusManager.state.value == VpnState.CONNECTED &&
                    vpnStatusManager.connectedServerId == server && vpnStatusManager.sessionToken == token && dismissedServerId != server &&
                    settingsManager.areChecksAllowed()
                if (!current() || (permittedHealthCheck() ?: return@launch)) return@launch
                delay(1000)
                if (!current() || (permittedHealthCheck() ?: return@launch)) return@launch
                if (checkDnsFailure() || !current() || checkFragmentationFailure() || !current()) return@launch
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                @Suppress("DEPRECATION")
                val physicalAlive = cm?.allNetworks?.any { network ->
                    cm.getNetworkCapabilities(network)?.let {
                        it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    } == true
                } == true
                synchronized(lock) {
                    if (!current() || !physicalAlive || hasSpecificIssue()) return@launch

                    setIssue(TunnelHealthIssue(
                        kind = HealthIssueKind.SILENT_BLACKHOLE,
                        title = text("Не удалось подтвердить связь через VPN", "VPN connectivity checks failed"),
                        description = text("Две проверки туннеля не прошли при доступной сети. Причина не установлена; попробуйте другой сервер.", "Two tunnel checks failed while the network was available. The cause is unknown; try another server."),
                        primaryActionLabel = text("Сменить сервер", "Switch Server"),
                        primaryActionId = "SWITCH_BETTER_SERVER"))
                    nextTransportCheckAt = android.os.SystemClock.elapsedRealtime() + 600_000
                }
            }
        }
    }

    private fun inspectMtuErrors(lower: String) {
        val isMtu = "packet too large" in lower || "message too long" in lower
        if (!isMtu) return

        val currentMtu = settingsManager.getMtu()
        if (currentMtu <= 1280) return

        val now = System.currentTimeMillis()
        val shouldTrigger = synchronized(lock) {
            mtuErrorTimestamps.removeAll { now - it > 30_000 }
            mtuErrorTimestamps.add(now)
            mtuErrorTimestamps.size >= 1 && !hasSpecificIssue()
        }

        if (shouldTrigger) {

            val issue = TunnelHealthIssue(
                kind = HealthIssueKind.MTU_FRAGMENTATION,
                title = text("Ошибка размера пакета", "Packet size error"),
                description = text("Обнаружена ошибка размера пакета. Попробуйте MTU 1280; источник ограничения не установлен.", "A packet size error was reported. Try MTU 1280; the source of the limit is unknown."),
                primaryActionLabel = text("Установить MTU 1280", "Set MTU 1280"),
                primaryActionId = "SET_MTU_1280"
            )
            setIssue(issue)
        }
    }

    private fun inspectRoutingLoopErrors(lower: String) {
        if ("routing loop" in lower || ("too many open files" in lower && "tun" in lower)) {

            val issue = TunnelHealthIssue(
                kind = HealthIssueKind.ROUTING_LOOP,
                title = text("Возможная петля маршрутизации", "Possible routing loop"),
                description = text("В журнале обнаружены признаки проблемы маршрутизации. Автоматическое устранение не подтверждено.", "Logs suggest a routing problem. Automatic recovery has not been confirmed."),
                primaryActionLabel = text("Понятно", "Dismiss"),
                primaryActionId = "DISMISS_ALERT"
            )
            setIssue(issue)
        }
    }

    /**
     * Inspects traffic throughput samples from SplateVpnService stats loop (every 1s).
     */
    fun inspectTraffic(rxDelta: Long, txDelta: Long, downBytesPerSec: Long, upBytesPerSec: Long, isPhysicalAlive: Boolean) {
        if (vpnStatusManager.state.value != VpnState.CONNECTED) return
        if (isPhysicalAlive || rxDelta > 0) {
            offlineSamples = 0
            if (_issue.value?.kind == HealthIssueKind.NETWORK_UNAVAILABLE) {
                _issue.value = null
                cancelNotification()
            }
        } else {
            offlineSamples++
            if (offlineSamples >= 3 && dismissedServerId != vpnStatusManager.connectedServerId &&
                _issue.value?.kind !in setOf(HealthIssueKind.NETWORK_UNAVAILABLE, HealthIssueKind.CAPTIVE_PORTAL)) {
                scanJob?.cancel()
                transportCheckJob?.cancel()
                _dnsIssue.value = null

                setIssue(TunnelHealthIssue(
                    kind = HealthIssueKind.NETWORK_UNAVAILABLE,
                    title = text("Доступ к интернету не подтверждён", "Internet access could not be verified"),
                    description = text("VPN запущен, но проверки внешней сети не проходят и входящий трафик отсутствует. Проверьте Wi-Fi или мобильный интернет. Менять маршруты сайтов не требуется.", "VPN is running, but external network checks failed and no incoming traffic was detected. Check Wi-Fi or mobile data. No website route changes are needed.")
                ))
            }
            return
        }
        if (hasSpecificIssue()) return

        // 1. Silent Blackholing: TX sent but 0 RX over 12 seconds
        if (isPhysicalAlive && txDelta > 15_000L && rxDelta == 0L) {
            silentTxConsecutiveSeconds++
            if (silentTxConsecutiveSeconds >= 12) {
                silentTxConsecutiveSeconds = 0
                confirmTunnelFailure()
            }
        } else {
            silentTxConsecutiveSeconds = 0
        }

        // High bidirectional throughput alone does not establish a routing loop.
    }

    /**
     * Inspects network capabilities updates from ConnectivityManager.
     */
    fun inspectNetworkCapabilities(caps: NetworkCapabilities) {
        if (vpnStatusManager.state.value != VpnState.CONNECTED || hasSpecificIssue()) return

        val isCaptive = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
        if (isCaptive) {

            val issue = TunnelHealthIssue(
                kind = HealthIssueKind.CAPTIVE_PORTAL,
                title = text("Требуется вход в Wi-Fi", "Wi-Fi sign-in required"),
                description = text("Сеть требует авторизации в браузере. Можно временно приостановить VPN для входа.", "Network requires browser sign-in. You can temporarily pause VPN to sign in."),
                primaryActionLabel = text("Приостановить на 2 мин", "Pause 2 min"),
                primaryActionId = "PAUSE_VPN_120S",
                secondaryActionLabel = text("Войти в сеть", "Open Login"),
                secondaryActionId = "OPEN_CAPTIVE_PORTAL"
            )
            setIssue(issue)
        }
    }

    /**
     * Checks if device system time is desynchronized relative to remote HTTP Date header.
     */
    fun triggerTimeDesyncCheck() {
        if (!settingsManager.areChecksAllowed()) return
        val now = System.currentTimeMillis()
        if (now - lastTimeCheckTimestamp < 900_000L) return // check at most once every 15 min
        lastTimeCheckTimestamp = now

        timeCheckJob?.cancel()
        timeCheckJob = scope.launch {
            try {
                val dateStr = NetworkAccessPolicy.withChecks(context) {
                    val client = okhttp3.OkHttpClient.Builder().callTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                        .followRedirects(false).build()
                    com.danieldk.splatevpn.vpn.LocalProxyAccess.socksClient(client)
                        .newCall(okhttp3.Request.Builder().url("https://cp.cloudflare.com/generate_204").head().build())
                        .awaitConsumedResponse { it.header("Date") }
                }

                if (!dateStr.isNullOrBlank()) {
                    val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("GMT")
                    }
                    val serverEpoch = format.parse(dateStr)?.time ?: return@launch
                    val diffMs = abs(System.currentTimeMillis() - serverEpoch)
                    if (diffMs > 45_000L) {
                        val skewSeconds = diffMs / 1000L

                        val issue = TunnelHealthIssue(
                            kind = HealthIssueKind.TIME_DESYNC,
                            title = String.format(text("Время отличается на %d с", "Clock differs by %d s"), skewSeconds),
                            description = text("Время устройства отличается от времени проверочного сервера. Проверьте автоматическую синхронизацию часов.", "Device time differs from the check server. Check automatic clock synchronization."),
                            primaryActionLabel = text("Настройки даты", "Date Settings"),
                            primaryActionId = "OPEN_DATE_SETTINGS"
                        )
                        setIssue(issue)
                    }
                }
            } catch (_: Exception) {
                // Ignore probe timeouts
            }
        }
    }

    private fun startDnsDiagnosis() {
        if (!settingsManager.areChecksAllowed()) return
        if (scanJob?.isActive == true) return
        scanJob = scope.launch { checkDnsFailure() }
    }

    /** A confirmed resolver failure must not turn into repeated server replacements. */
    suspend fun checkDnsFailure(): Boolean = dnsMutex.withLock {
        if (!settingsManager.areChecksAllowed()) return@withLock false
        val snapshot = DnsContext(vpnStatusManager.connectedServerId ?: return@withLock false,
            vpnStatusManager.sessionToken, settingsManager.getDnsProvider(), settingsManager.getCustomDohUrl(),
            settingsManager.isFakeDns(), FragmentationProfile.read(settingsManager))
        fun current() = vpnStatusManager.state.value in setOf(VpnState.CONNECTED, VpnState.RECONNECTING) &&
            vpnStatusManager.connectedServerId == snapshot.server && vpnStatusManager.sessionToken == snapshot.token &&
            settingsManager.getDnsProvider() == snapshot.key && settingsManager.getCustomDohUrl() == snapshot.custom &&
            settingsManager.isFakeDns() == snapshot.fakeDns && FragmentationProfile.read(settingsManager) == snapshot.fragment &&
            settingsManager.areChecksAllowed()
        if (!current()) return@withLock false
        val cached = dnsDiagnosisCache
        if (cached?.context == snapshot && android.os.SystemClock.elapsedRealtime() - cached.checkedAt < 60_000) {
            return@withLock cached.issue?.candidate != null
        }
        val endpoints = DnsProviderCatalog.endpoints(snapshot.custom)
        val selected = endpoints.firstOrNull { it.key == snapshot.key } ?: return@withLock false
        // Literal-IP standard endpoints avoid bootstrapping candidates through the failed DNS.
        val comparison = confirmDnsFailure(selected, DnsProviderCatalog.standard, ::current, ::permittedDnsCheck)
        if (!current()) return@withLock false
        val result = comparison?.let {
            DnsHealthIssue(snapshot.key, selected.label.ifBlank { snapshot.key }, candidate = it.endpoint,
                candidateLatencyMs = it.latencyMs, isFakeDnsOff = !snapshot.fakeDns)
        }
        dnsDiagnosisCache = DnsDiagnosisCache(snapshot, android.os.SystemClock.elapsedRealtime(), result)
        if (result != null && dismissedServerId != snapshot.server) {
            _dnsIssue.value = result
            if (_issue.value?.kind in listOf(null, HealthIssueKind.CONNECTIVITY_UNVERIFIED, HealthIssueKind.DNS_BLOCKED,
                    HealthIssueKind.TLS_FRAGMENTATION, HealthIssueKind.SILENT_BLACKHOLE)) {
                confirmedFragmentProfile = null
                setIssue(dnsFailureAdvice(result, ::text))
            }
        } else if (result == null && _issue.value?.kind == HealthIssueKind.DNS_BLOCKED) {
            _dnsIssue.value = null
            _issue.value = null
            cancelNotification()
        }
        result != null
    }

    private suspend fun permittedHealthCheck(): Boolean? = try {
        NetworkAccessPolicy.withChecks(context) { vpnStatusManager.performHealthCheck() }
    } catch (_: WifiRequiredException) { null }

    private suspend fun permittedDnsCheck(endpoint: DnsProviderEndpoint): DnsProviderCheckResult? = try {
        NetworkAccessPolicy.withChecks(context) { dnsProviderChecker.check(endpoint, viaVpn = true) }
    } catch (_: WifiRequiredException) { null }

    private fun setIssue(issue: TunnelHealthIssue) {
        synchronized(lock) {
            if (issue.kind != HealthIssueKind.CONNECTIVITY_UNVERIFIED) {
                unverifiedConnectivitySession = null
                unverifiedConnectivityJob?.cancel()
                unverifiedConnectivityJob = null
            }
            _issue.value = issue
            val now = android.os.SystemClock.elapsedRealtime()
            if (settingsManager.getNotificationPreferences().allows(issue.kind) && now - lastHealthNotificationAt >= 120_000L) {
                lastHealthNotificationAt = now
                postIssueNotification(issue)
            }
        }
    }

    private fun postIssueNotification(issue: TunnelHealthIssue) {
        if (!settingsManager.getNotificationPreferences().allows(issue.kind)) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val openAppIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID_HEALTH_ALERTS)
            .setSmallIcon(com.danieldk.splatevpn.R.drawable.ic_qs_vpn)
            .setContentTitle(issue.title)
            .setContentText(issue.description)
            .setStyle(NotificationCompat.BigTextStyle().bigText(issue.description))
            .setContentIntent(openAppIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        // Primary Action
        if (!issue.primaryActionId.isNullOrBlank() && !issue.primaryActionLabel.isNullOrBlank()) {
            val primaryIntent = PendingIntent.getService(
                context,
                11,
                Intent(context, SplateVpnService::class.java).apply {
                    action = issue.primaryActionId
                    if (issue.primaryActionExtra != null) {
                        putExtra("dns_provider", issue.primaryActionExtra)
                        putExtra("domain", issue.primaryActionExtra)
                    }
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(android.R.drawable.ic_menu_rotate, issue.primaryActionLabel, primaryIntent)
        }

        // Secondary Action
        if (!issue.secondaryActionId.isNullOrBlank() && !issue.secondaryActionLabel.isNullOrBlank()) {
            val secondaryIntent = PendingIntent.getService(
                context,
                12,
                Intent(context, SplateVpnService::class.java).apply {
                    action = issue.secondaryActionId
                    if (issue.secondaryActionExtra != null) {
                        putExtra("portal_url", issue.secondaryActionExtra)
                    }
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(android.R.drawable.ic_menu_manage, issue.secondaryActionLabel, secondaryIntent)
        }

        try {
            if (settingsManager.getNotificationPreferences().allows(issue.kind)) {
                nm.notify(NOTIFICATION_ID_HEALTH_ISSUE, builder.build())
                postedNotificationKind = issue.kind
            }
        } catch (_: SecurityException) {
            // Permission might be denied on Android 13+
        }
    }

    private fun cancelNotification() {
        postedNotificationKind = null
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.cancel(NOTIFICATION_ID_HEALTH_ISSUE)
    }

    fun applyCandidate(): Boolean {
        val dns = _dnsIssue.value ?: return false
        val cand = dns.candidate ?: return false
        if (settingsManager.getDnsProvider() != dns.failedDnsKey) return false
        settingsManager.setDnsProvider(cand.key)
        dismiss()
        return true
    }

    fun applyFakeDns(): Boolean {
        if (_dnsIssue.value?.isFakeDnsOff != true || settingsManager.isFakeDns()) return false
        settingsManager.setFakeDns(true)
        dismiss()
        return true
    }

    fun dismiss() {
        synchronized(lock) {
            transportCheckJob?.cancel()
            scanJob?.cancel()
            timeCheckJob?.cancel()
            dismissedServerId = vpnStatusManager.connectedServerId
            unverifiedConnectivitySession = null
            unverifiedConnectivityJob?.cancel()
            unverifiedConnectivityJob = null
            _issue.value = null
            _dnsIssue.value = null
            cancelNotification()
        }
    }
}
