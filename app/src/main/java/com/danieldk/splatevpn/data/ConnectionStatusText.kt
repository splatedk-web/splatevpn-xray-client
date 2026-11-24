package com.danieldk.splatevpn.data

import android.content.res.Resources
import com.danieldk.splatevpn.R

/** Translate service-owned messages at display time, including after an in-session language change. */
internal object ConnectionStatusText {
    fun localize(message: String?, resources: Resources): String? {
        val id = when (message) {
            "Сервера не найдены. Поиск…", "No servers found. Searching…" -> R.string.connection_searching
            "Ожидание сети. Поиск серверов…", "Waiting for network. Searching for servers…" -> R.string.connection_waiting_network
            "Xray core failed to start" -> R.string.connection_core_failed
            "TUN interface creation failed", "TUN interface creation failed during reload" -> R.string.connection_tun_failed
            "Connection timed out" -> R.string.connection_timeout
            "Tor reconnect limit reached" -> R.string.connection_tor_limit
            "Tor configuration changed" -> R.string.connection_tor_changed
            "Tor reconnect failed" -> R.string.connection_tor_failed
            else -> return message
        }
        return resources.getString(id)
    }
}
