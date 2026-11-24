package com.danieldk.splatevpn.vpn

import android.util.Log
import com.danieldk.splatevpn.SplateConstants
import com.danieldk.splatevpn.data.readLimited
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

enum class VpnState {
    DISCONNECTED,
    SELECTING_SERVER,
    STARTING_CORE,
    CONNECTING,
    HEALTH_CHECKING,
    CONNECTED,
    RECONNECTING,
    FAILED
}

@Singleton
class VpnStatusManager @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context
) {
    private val systemUpdates = Channel<Unit>(Channel.CONFLATED)
    private val systemUpdateScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    init {
        systemUpdateScope.launch {
            for (signal in systemUpdates) {
                // STARTING_CORE/CONNECTING/CONNECTED often arrive in one short burst.
                // Widgets read the latest state; queued intermediate broadcasts add UI work.
                delay(75)
                while (systemUpdates.tryReceive().isSuccess) { /* coalesce pending states */ }
                try {
                    com.danieldk.splatevpn.ui.widget.SplateAppWidgetProvider.notifyUpdate(context)
                    com.danieldk.splatevpn.ui.widget.SplateQuickWidgetProvider.notifyUpdate(context)
                    SplateTileService.requestUpdate(context)
                } catch (e: Exception) {
                    Log.w("VpnStatusManager", "System status update failed: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    private val _state = MutableStateFlow(VpnState.DISCONNECTED)
    val state = _state.asStateFlow()
    private val _activeRouting = MutableStateFlow<ActiveRouting?>(null)
    internal val activeRouting = _activeRouting.asStateFlow()
    internal fun confirmRouting(routing: ActiveRouting?) { _activeRouting.value = routing }

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()
    val statusMessage = errorMessage

    private val _uploadSpeed = MutableStateFlow("0 KB/s")
    val uploadSpeed = _uploadSpeed.asStateFlow()

    private val _downloadSpeed = MutableStateFlow("0 KB/s")
    val downloadSpeed = _downloadSpeed.asStateFlow()

    private val _downBytesPerSec = MutableStateFlow(0L)
    val downBytesPerSec = _downBytesPerSec.asStateFlow()

    // Real-time speed history (KB/s) for live waveform chart (last 30 seconds)
    private val _speedHistory = MutableStateFlow<List<Float>>(List(30) { 0f })
    val speedHistory = _speedHistory.asStateFlow()

    private val _countryFlag = MutableStateFlow("🌐")
    val countryFlag = _countryFlag.asStateFlow()

    private val _countryName = MutableStateFlow("")
    val countryName = _countryName.asStateFlow()

    private val _currentIp = MutableStateFlow("")
    val currentIp = _currentIp.asStateFlow()

    private val _isDetectingIp = MutableStateFlow(false)
    val isDetectingIp = _isDetectingIp.asStateFlow()

    private val _rttMs = MutableStateFlow(-1L)
    val rttMs = _rttMs.asStateFlow()
    private val connectionGeneration = AtomicLong()
    internal val sessionToken: Long get() = connectionGeneration.get()
    internal fun invalidateNetworkChecks() { connectionGeneration.incrementAndGet() }
    private val ipRequestGeneration = AtomicLong()

    @Volatile
    var connectedServerId: String? = null
        internal set
    @Volatile
    var connectedServerProfile: com.danieldk.splatevpn.data.ServerNode? = null
        internal set

    // Session start timestamp — set when state transitions to CONNECTED
    private val _sessionStartTime = MutableStateFlow(0L)
    val sessionStartTime = _sessionStartTime.asStateFlow()

    // Session cumulative traffic bytes
    private val _sessionRxBytes = MutableStateFlow(0L)
    val sessionRxBytes = _sessionRxBytes.asStateFlow()

    private val _sessionTxBytes = MutableStateFlow(0L)
    val sessionTxBytes = _sessionTxBytes.asStateFlow()

    @Synchronized fun setState(newState: VpnState, errorMsg: String? = null) {
        if (_state.value != newState) connectionGeneration.incrementAndGet()
        if (newState != VpnState.CONNECTED) { connectedServerId = null; connectedServerProfile = null }
        if (_state.value != newState) {
            Log.i("VpnStatusManager", "VPN State: ${_state.value} → $newState")
            _state.value = newState
        }
        _errorMessage.value = errorMsg

        when (newState) {
            VpnState.SELECTING_SERVER, VpnState.STARTING_CORE -> {
                _sessionStartTime.value = 0L
                _sessionRxBytes.value = 0L
                _sessionTxBytes.value = 0L
                _speedHistory.value = List(30) { 0f }
            }
            VpnState.CONNECTED -> {
                // Mark session start only on fresh connection (not during reconnects)
                if (_sessionStartTime.value == 0L) {
                    _sessionStartTime.value = System.currentTimeMillis()
                    _sessionRxBytes.value = 0L
                    _sessionTxBytes.value = 0L
                }
            }
            VpnState.DISCONNECTED, VpnState.FAILED -> {
                _activeRouting.value = null
                _uploadSpeed.value = "0 KB/s"
                _downloadSpeed.value = "0 KB/s"
                _downBytesPerSec.value = 0L
                _rttMs.value = -1L
                _sessionStartTime.value = 0L
                _sessionRxBytes.value = 0L
                _sessionTxBytes.value = 0L
                _speedHistory.value = List(30) { 0f }
                resetIpDetails()
            }
            else -> {}
        }

        systemUpdates.trySend(Unit)
    }

    fun updateSpeeds(up: String, down: String, downBytesPerSec: Long = 0L) {
        _uploadSpeed.value = up
        _downloadSpeed.value = down
        _downBytesPerSec.value = downBytesPerSec
        val kbps = (downBytesPerSec / 1024f).coerceAtLeast(0f)
        val currentList = _speedHistory.value
        _speedHistory.value = (currentList.drop(1) + kbps)
    }

    @Synchronized fun updateSessionBytes(rxDelta: Long, txDelta: Long) {
        if (rxDelta > 0) _sessionRxBytes.value += rxDelta
        if (txDelta > 0) _sessionTxBytes.value += txDelta
    }

    @Synchronized fun resetIpDetails() {
        ipRequestGeneration.incrementAndGet()
        _countryFlag.value = "🌐"
        _countryName.value = ""
        _currentIp.value = ""
        _isDetectingIp.value = false
    }

    /**
     * Performs a connectivity health check via local Xray SOCKS5 proxy (port ${SplateConstants.XRAY_SOCKS_PORT}).
     * Uses Cloudflare and Google connectivity check endpoints that return HTTP 204.
     */
    suspend fun performHealthCheck(timeoutMs: Int = 3500): Boolean {
        val generation = connectionGeneration.get()
        if (_state.value != VpnState.CONNECTED && _state.value != VpnState.RECONNECTING) return false
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(SplateConstants.XRAY_SOCKS_ADDRESS, LocalProxyAccess.activeSocksPort))
        val ping = TunnelProbe.measure(proxy, com.danieldk.splatevpn.vpn.TunnelProbe.PRIMARY_URL, timeoutMs, priority = true,
            credentials = LocalProxyAccess.active)
        synchronized(this) {
            if (generation != connectionGeneration.get()) return false
            if (_state.value != VpnState.CONNECTED && _state.value != VpnState.RECONNECTING) return false
            _rttMs.value = ping
        }
        com.danieldk.splatevpn.ui.widget.SplateAppWidgetProvider.notifyUpdate(context)
        com.danieldk.splatevpn.ui.widget.SplateQuickWidgetProvider.notifyUpdate(context)
        return ping > 0
    }
    /**
     * Fetches egress IP and geolocation details via Xray SOCKS5 proxy.
     * Tries multiple fallback providers.
     */
    suspend fun fetchCurrentIpDetails() = fetchCurrentIpDetailsUsing { host, path -> fetchJsonOverSocks5(host, path) }

    internal suspend fun fetchCurrentIpDetailsUsing(fetch: suspend (String, String) -> String?) = withContext(Dispatchers.IO) {
        val request = synchronized(this@VpnStatusManager) {
            _isDetectingIp.value = true
            ipRequestGeneration.incrementAndGet()
        }
        try {
            val endpoints = listOf(
                "api.ip.sb" to "/geoip",
                "ipwho.is" to "/",
                "ipinfo.io" to "/json",
                "check.torproject.org" to "/api/ip"
            )
            for ((host, path) in endpoints) {
                ensureActive()
                if (request != ipRequestGeneration.get()) return@withContext
                try {
                    val text = fetch(host, path)
                    ensureActive()
                    if (text.isNullOrBlank()) continue
                    val json = JSONObject(text)
                    if (host == "ipwho.is" && !json.optBoolean("success", false)) continue
                    val ip = json.optString("ip").ifBlank { json.optString("IP") }
                    if (ip.isBlank()) continue
                    val isTor = json.optBoolean("IsTor", false) || host == "check.torproject.org"
                    val country = json.optString("country").ifBlank { if (isTor) "Tor Network" else "" }
                    val code = if (host == "ipinfo.io") country else json.optString("country_code")
                    val city = json.optString("city")
                    val countryName = when {
                        host == "check.torproject.org" -> "Tor Exit Node"
                        host == "ipinfo.io" && city.isNotBlank() -> "$city, $country"
                        city.isNotBlank() && country.isNotBlank() -> "$city, $country"
                        country.isNotBlank() -> country
                        isTor -> "Tor Network"
                        else -> "Protected"
                    }
                    val emoji = when {
                        isTor -> "🧅"
                        else -> json.optJSONObject("flag")?.optString("emoji").orEmpty().ifBlank { countryCodeToEmoji(code) }
                    }
                    synchronized(this@VpnStatusManager) {
                        if (request != ipRequestGeneration.get()) return@withContext
                        _countryName.value = countryName
                        _currentIp.value = ip
                        _countryFlag.value = emoji
                    }
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("VpnStatusManager", "$host failed: ${e.message}")
                }
            }
        } finally {
            synchronized(this@VpnStatusManager) {
                if (request == ipRequestGeneration.get()) _isDetectingIp.value = false
            }
        }
        try { com.danieldk.splatevpn.ui.widget.SplateAppWidgetProvider.notifyUpdate(context) }
        catch (_: Exception) { }
    }
    private fun fetchJsonOverSocks5(host: String, path: String): String? {
        return try {
            val base = okhttp3.OkHttpClient.Builder()
                .connectTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
                .callTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            LocalProxyAccess.socksClient(base).newCall(okhttp3.Request.Builder().url("https://$host$path")
                .header("User-Agent", "SplateVPN/IPCheck").header("Accept", "application/json").build()).execute().use { response ->
                if (response.isSuccessful) response.body?.byteStream()?.use {
                    it.readLimited(1_000_000).toString(Charsets.UTF_8)
                } else null
            }
        } catch (e: Exception) {
            Log.w("VpnStatusManager", "SOCKS5 request error for $host: ${e.message}")
            null
        }
    }

    private fun countryCodeToEmoji(code: String): String {
        if (code.length != 2) return "🌐"
        return try {
            val firstChar = Character.codePointAt(code.uppercase(), 0) - 0x41 + 0x1F1E6
            val secondChar = Character.codePointAt(code.uppercase(), 1) - 0x41 + 0x1F1E6
            String(Character.toChars(firstChar)) + String(Character.toChars(secondChar))
        } catch (e: Exception) {
            "🌐"
        }
    }
}
