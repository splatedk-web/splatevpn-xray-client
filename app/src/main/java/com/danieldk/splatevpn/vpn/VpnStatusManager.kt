package com.danieldk.splatevpn.vpn

import android.util.Log
import com.danieldk.splatevpn.SplateConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
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
    private val _state = MutableStateFlow(VpnState.DISCONNECTED)
    val state = _state.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()

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

    @Volatile
    var connectedServerId: String? = null
        internal set

    // Session start timestamp — set when state transitions to CONNECTED
    private val _sessionStartTime = MutableStateFlow(0L)
    val sessionStartTime = _sessionStartTime.asStateFlow()

    // Session cumulative traffic bytes
    private val _sessionRxBytes = MutableStateFlow(0L)
    val sessionRxBytes = _sessionRxBytes.asStateFlow()

    private val _sessionTxBytes = MutableStateFlow(0L)
    val sessionTxBytes = _sessionTxBytes.asStateFlow()

    fun setState(newState: VpnState, errorMsg: String? = null) {
        if (newState != VpnState.CONNECTED) connectedServerId = null
        if (_state.value != newState) {
            Log.i("VpnStatusManager", "VPN State: ${_state.value} → $newState")
            _state.value = newState
        }
        if (errorMsg != null) {
            _errorMessage.value = errorMsg
        }

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
                }
                _sessionRxBytes.value = 0L
                _sessionTxBytes.value = 0L
            }
            VpnState.DISCONNECTED, VpnState.FAILED -> {
                _uploadSpeed.value = "0 KB/s"
                _downloadSpeed.value = "0 KB/s"
                _rttMs.value = -1L
                _sessionStartTime.value = 0L
                _sessionRxBytes.value = 0L
                _sessionTxBytes.value = 0L
                _speedHistory.value = List(30) { 0f }
                resetIpDetails()
            }
            else -> {}
        }

        try {
            com.danieldk.splatevpn.ui.widget.SplateAppWidgetProvider.notifyUpdate(context)
            com.danieldk.splatevpn.ui.widget.SplateQuickWidgetProvider.notifyUpdate(context)
            com.danieldk.splatevpn.vpn.SplateTileService.requestUpdate(context)
        } catch (e: Exception) {
            // Ignore widget notification failure
        }
    }

    fun updateSpeeds(up: String, down: String, downBytesPerSec: Long = 0L) {
        _uploadSpeed.value = up
        _downloadSpeed.value = down
        _downBytesPerSec.value = downBytesPerSec
        val kbps = (downBytesPerSec / 1024f).coerceAtLeast(0f)
        val currentList = _speedHistory.value
        _speedHistory.value = (currentList.drop(1) + kbps)
    }

    fun updateSessionBytes(rxDelta: Long, txDelta: Long) {
        if (rxDelta > 0) _sessionRxBytes.value += rxDelta
        if (txDelta > 0) _sessionTxBytes.value += txDelta
    }

    fun resetIpDetails() {
        _countryFlag.value = "🌐"
        _countryName.value = ""
        _currentIp.value = ""
        _isDetectingIp.value = false
    }

    /**
     * Performs a connectivity health check via local Xray SOCKS5 proxy (port ${SplateConstants.XRAY_SOCKS_PORT}).
     * Uses Cloudflare and Google connectivity check endpoints that return HTTP 204.
     */
    suspend fun performHealthCheck(): Boolean {
        if (_state.value != VpnState.CONNECTED && _state.value != VpnState.RECONNECTING) return false
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(SplateConstants.XRAY_SOCKS_ADDRESS, SplateConstants.XRAY_SOCKS_PORT))
        val ping = TunnelProbe.measure(proxy, "http://cp.cloudflare.com/generate_204", 3000, priority = true)
        if (_state.value != VpnState.CONNECTED && _state.value != VpnState.RECONNECTING) return false
        _rttMs.value = ping
        com.danieldk.splatevpn.ui.widget.SplateAppWidgetProvider.notifyUpdate(context)
        com.danieldk.splatevpn.ui.widget.SplateQuickWidgetProvider.notifyUpdate(context)
        return ping > 0
    }
    /**
     * Fetches egress IP and geolocation details via Xray SOCKS5 proxy.
     * Tries multiple fallback providers.
     */
    suspend fun fetchCurrentIpDetails() = withContext(Dispatchers.IO) {
        _isDetectingIp.value = true
        var resolved = false

        // 1. ipwho.is — comprehensive JSON with emoji flag
        try {
            val jsonStr = fetchJsonOverSocks5("ipwho.is", "/")
            if (!jsonStr.isNullOrBlank()) {
                val json = JSONObject(jsonStr)
                if (json.optBoolean("success", false)) {
                    val country = json.optString("country")
                    val countryCode = json.optString("country_code")
                    val ip = json.optString("ip")
                    val emoji = json.optJSONObject("flag")?.optString("emoji") ?: countryCodeToEmoji(countryCode)

                    _countryName.value = country
                    _currentIp.value = ip
                    _countryFlag.value = if (emoji.isNotBlank()) emoji else countryCodeToEmoji(countryCode)
                    Log.d("VpnStatusManager", "IP detected: $ip, $country ($countryCode)")
                    resolved = true
                }
            }
        } catch (e: Exception) {
            Log.w("VpnStatusManager", "ipwho.is failed: ${e.message}")
        }

        // 2. Fallback: api.ip.sb
        if (!resolved) {
            try {
                val jsonStr = fetchJsonOverSocks5("api.ip.sb", "/geoip")
                if (!jsonStr.isNullOrBlank()) {
                    val json = JSONObject(jsonStr)
                    val country = json.optString("country")
                    val countryCode = json.optString("country_code")
                    val ip = json.optString("ip")

                    _countryName.value = country
                    _currentIp.value = ip
                    _countryFlag.value = countryCodeToEmoji(countryCode)
                    Log.d("VpnStatusManager", "IP via ip.sb: $ip, $country ($countryCode)")
                    resolved = true
                }
            } catch (e: Exception) {
                Log.w("VpnStatusManager", "api.ip.sb failed: ${e.message}")
            }
        }

        // 3. Fallback: ipinfo.io
        if (!resolved) {
            try {
                val jsonStr = fetchJsonOverSocks5("ipinfo.io", "/json")
                if (!jsonStr.isNullOrBlank()) {
                    val json = JSONObject(jsonStr)
                    val country = json.optString("country")
                    val ip = json.optString("ip")
                    val city = json.optString("city")

                    _countryName.value = if (city.isNotBlank()) "$city, $country" else country
                    _currentIp.value = ip
                    _countryFlag.value = countryCodeToEmoji(country)
                    Log.d("VpnStatusManager", "IP via ipinfo.io: $ip, $country")
                    resolved = true
                }
            } catch (e: Exception) {
                Log.w("VpnStatusManager", "ipinfo.io failed: ${e.message}")
            }
        }

        _isDetectingIp.value = false
        try {
            com.danieldk.splatevpn.ui.widget.SplateAppWidgetProvider.notifyUpdate(context)
        } catch (e: Exception) {}
    }

    private fun fetchJsonOverSocks5(host: String, path: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(SplateConstants.XRAY_SOCKS_ADDRESS, SplateConstants.XRAY_SOCKS_PORT))
            val url = URL("https://$host$path")
            conn = url.openConnection(proxy) as HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w("VpnStatusManager", "SOCKS5 request error for $host: ${e.message}")
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {}
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
