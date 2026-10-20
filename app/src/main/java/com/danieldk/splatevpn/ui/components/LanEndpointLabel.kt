package com.danieldk.splatevpn.ui.components

import com.danieldk.splatevpn.ui.theme.AppStrings
import com.danieldk.splatevpn.vpn.LanEndpoint

internal fun lanEndpointLabel(endpoint: LanEndpoint, strings: AppStrings): String {
    val name = endpoint.interfaceName.lowercase()
    val format = when {
        name.startsWith("rndis") || name.startsWith("usb") || name.startsWith("ncm") -> strings.lanEndpointUsb
        name.startsWith("ap") || name.contains("softap") || endpoint.ipAddress.startsWith("192.168.43.") || endpoint.ipAddress.startsWith("192.168.50.") -> strings.lanEndpointHotspot
        name.startsWith("wlan") -> strings.lanEndpointWifi
        name.startsWith("eth") -> "Ethernet (%s)"
        else -> strings.lanEndpointOther
    }
    return String.format(format, endpoint.interfaceName)
}
