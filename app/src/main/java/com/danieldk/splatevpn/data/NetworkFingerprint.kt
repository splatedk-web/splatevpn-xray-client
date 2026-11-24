package com.danieldk.splatevpn.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.TelephonyManager

/**
 * Возвращает детерминированный отпечаток текущей сети:
 *  - `"wifi_<hash>"` — подключено по Wi-Fi, SSID доступен (хэш SHA-1 первые 8 символов hex).
 *  - `"wifi_unknown"` — Wi-Fi, но SSID недоступен (нет разрешений / скрытая сеть).
 *  - `"mobile_<MCC><MNC>"` — мобильная сеть с известным оператором.
 *  - `"mobile_unknown"` — мобильная сеть без информации об операторе.
 *  - `"none"` — нет активного подключения.
 *
 * Использует только ConnectivityManager и WifiManager. Не требует root.
 * Требует разрешения ACCESS_NETWORK_STATE и ACCESS_WIFI_STATE.
 */
fun Context.currentNetworkFingerprint(): String = runCatching {
    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return@runCatching "none"

    val activeNetwork = cm.activeNetwork ?: return@runCatching "none"
    val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return@runCatching "none"

    when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
            buildWifiFingerprint()

        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
            buildCellularFingerprint()

        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
            "ethernet"

        else -> "none"
    }
}.getOrDefault("none")

private fun Context.buildWifiFingerprint(): String {
    val ssid = resolveWifiSsid() ?: return "wifi_unknown"
    // Хэшируем SSID, чтобы не хранить PII в логах / профилях
    val hash = ssid.toByteArray(Charsets.UTF_8).sha1Hex().take(8)
    return "wifi_$hash"
}

private fun Context.resolveWifiSsid(): String? {
    return runCatching {
        resolveWifiSsidLegacy()
    }.getOrNull()
}

@Suppress("DEPRECATION")
private fun Context.resolveWifiSsidLegacy(): String? {
    return runCatching {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return null
        val info = wm.connectionInfo ?: return null
        val raw = info.ssid ?: return null
        // WifiManager возвращает SSID в кавычках либо "<unknown ssid>"
        if (raw == "<unknown ssid>" || raw == WifiManager.UNKNOWN_SSID) return null
        raw.removeSurrounding("\"")
    }.getOrNull()
}

private fun Context.buildCellularFingerprint(): String {
    return runCatching {
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return "mobile_unknown"
        val mcc = tm.networkCountryIso.takeIf { it.isNotBlank() }
        val mnc = runCatching { tm.networkOperator.takeIf { it.length >= 5 } }.getOrNull()
        if (mnc != null) "mobile_$mnc" else if (mcc != null) "mobile_${mcc}" else "mobile_unknown"
    }.getOrDefault("mobile_unknown")
}

/** SHA-1 байт → hex строка. */
private fun ByteArray.sha1Hex(): String {
    val digest = java.security.MessageDigest.getInstance("SHA-1").digest(this)
    return digest.joinToString("") { "%02x".format(it) }
}
