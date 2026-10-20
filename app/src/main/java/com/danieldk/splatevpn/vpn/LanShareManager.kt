package com.danieldk.splatevpn.vpn

import android.content.Context
import com.danieldk.splatevpn.SplateConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton

data class LanEndpoint(
    val interfaceName: String,
    val displayName: String,
    val ipAddress: String,
    val httpPort: Int = SplateConstants.XRAY_LAN_HTTP_PORT,
    val socksPort: Int = SplateConstants.XRAY_LAN_SOCKS_PORT
) {
    val httpProxyString: String get() = "$ipAddress:$httpPort"
    val socksProxyString: String get() = "$ipAddress:$socksPort"
    val windowsDualProxyString: String get() = windowsHttpProxyString
    val windowsHttpProxyString: String get() = "http=$ipAddress:$httpPort;https=$ipAddress:$httpPort"
}

@Singleton
class LanShareManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * Сканирует активные сетевые интерфейсы устройства и возвращает список
     * доступных IP-адресов точки доступа Wi-Fi, USB-модема и локальной сети.
     */
    fun getActiveLanEndpoints(): List<LanEndpoint> {
        val endpoints = mutableListOf<LanEndpoint>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name.lowercase()

                // Игнорируем туннели VPN и сотовые интерфейсы
                if (name.startsWith("tun") || name.startsWith("rmnet") || name.startsWith("dummy") || name.startsWith("p2p")) {
                    continue
                }

                val addresses = iface.inetAddresses
                for (addr in addresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        // Игнорируем link-local (169.254.x.x)
                        if (host.startsWith("169.254.")) continue

                        val displayName = when {
                            name.startsWith("rndis") || name.startsWith("usb") || name.startsWith("ncm") -> {
                                "USB-модем ($name)"
                            }
                            name.startsWith("ap") || name.contains("softap") || host.startsWith("192.168.43.") || host.startsWith("192.168.50.") -> {
                                "Wi-Fi Точка доступа ($name)"
                            }
                            name.startsWith("wlan") -> {
                                "Сеть Wi-Fi ($name)"
                            }
                            name.startsWith("eth") -> {
                                "Ethernet ($name)"
                            }
                            else -> {
                                "Интерфейс $name"
                            }
                        }

                        endpoints.add(
                            LanEndpoint(
                                interfaceName = name,
                                displayName = displayName,
                                ipAddress = host
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            // Защитное логирование сетевых интерфейсов
        }

        // Сортируем: сначала Точки доступа и USB, затем остальное
        return endpoints.sortedWith(
            compareBy<LanEndpoint> {
                when {
                    it.displayName.contains("Точка") -> 0
                    it.displayName.contains("USB") -> 1
                    it.displayName.contains("Wi-Fi") -> 2
                    else -> 3
                }
            }.thenBy { it.ipAddress }
        )
    }
}
