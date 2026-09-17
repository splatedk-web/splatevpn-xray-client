package com.danieldk.splatevpn.vpn

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.InetSocketAddress
import java.net.Proxy
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VpnStatusManager @Inject constructor() {
    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    private val _uploadSpeed = MutableStateFlow("0 KB/s")
    val uploadSpeed = _uploadSpeed.asStateFlow()

    private val _downloadSpeed = MutableStateFlow("0 KB/s")
    val downloadSpeed = _downloadSpeed.asStateFlow()

    private val _countryFlag = MutableStateFlow("🌐")
    val countryFlag = _countryFlag.asStateFlow()

    private val _countryName = MutableStateFlow("")
    val countryName = _countryName.asStateFlow()

    private val _currentIp = MutableStateFlow("")
    val currentIp = _currentIp.asStateFlow()

    private val _isDetectingIp = MutableStateFlow(false)
    val isDetectingIp = _isDetectingIp.asStateFlow()

    fun setConnected(connected: Boolean) {
        _isConnected.value = connected
        if (!connected) {
            _uploadSpeed.value = "0 KB/s"
            _downloadSpeed.value = "0 KB/s"
            resetIpDetails()
        }
    }

    fun updateSpeeds(up: String, down: String) {
        _uploadSpeed.value = up
        _downloadSpeed.value = down
    }

    fun resetIpDetails() {
        _countryFlag.value = "🌐"
        _countryName.value = ""
        _currentIp.value = ""
        _isDetectingIp.value = false
    }

    suspend fun fetchCurrentIpDetails() = withContext(Dispatchers.IO) {
        _isDetectingIp.value = true
        var resolved = false
        val socksProxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 10808))

        // Attempt 1: ipwho.is through SOCKS proxy
        try {
            val url = URL("https://ipwho.is/")
            val conn = url.openConnection(socksProxy) as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            if (conn.responseCode == 200) {
                val text = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                val json = JSONObject(text)
                if (json.optBoolean("success", false)) {
                    val country = json.optString("country")
                    val countryCode = json.optString("country_code")
                    val ip = json.optString("ip")
                    val emoji = json.optJSONObject("flag")?.optString("emoji") ?: countryCodeToEmoji(countryCode)

                    _countryName.value = country
                    _currentIp.value = ip
                    _countryFlag.value = if (emoji.isNotBlank()) emoji else countryCodeToEmoji(countryCode)
                    Log.d("VpnStatusManager", "Detected IP: $ip, Country: $country ($countryCode), Flag: ${_countryFlag.value}")
                    resolved = true
                }
            }
        } catch (e: Exception) {
            Log.w("VpnStatusManager", "ipwho.is via proxy failed: ${e.message}")
        }

        // Attempt 2: api.ip.sb/geoip through SOCKS proxy
        if (!resolved) {
            try {
                val url = URL("https://api.ip.sb/geoip")
                val conn = url.openConnection(socksProxy) as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                if (conn.responseCode == 200) {
                    val text = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                    val json = JSONObject(text)
                    val country = json.optString("country")
                    val countryCode = json.optString("country_code")
                    val ip = json.optString("ip")

                    _countryName.value = country
                    _currentIp.value = ip
                    _countryFlag.value = countryCodeToEmoji(countryCode)
                    Log.d("VpnStatusManager", "Detected IP via ip.sb: $ip, Country: $country ($countryCode)")
                    resolved = true
                }
            } catch (e: Exception) {
                Log.w("VpnStatusManager", "api.ip.sb via proxy failed: ${e.message}")
            }
        }

        _isDetectingIp.value = false
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
