package com.danieldk.splatevpn.ui.components

import com.danieldk.splatevpn.vpn.LanEndpoint

internal enum class LanGuidePlatform(val label: String) {
    ANDROID("Android"), IOS("iPhone / iPad"), WINDOWS("Windows"), MACOS("macOS"), LINUX("Linux"), APPS("SOCKS5")
}

internal enum class LanLinuxMode { GNOME, KDE, TERMINAL }

internal data class LanGuideStep(val title: String, val description: String)

internal fun lanGuideEndpoint(endpoints: List<LanEndpoint>, selectedIp: String?) =
    endpoints.firstOrNull { it.ipAddress == selectedIp } ?: endpoints.firstOrNull()

internal fun lanWindowsEnableCommand(endpoint: LanEndpoint): String {
    val path = "HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"
    return "Set-ItemProperty -Path '$path' -Name ProxyServer -Value '${endpoint.windowsHttpProxyString}'\n" +
        "Set-ItemProperty -Path '$path' -Name ProxyEnable -Value 1"
}

internal val lanWindowsDisableCommand =
    "Set-ItemProperty -Path 'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings' -Name ProxyEnable -Value 0"

internal fun lanShellEnableCommand(endpoint: LanEndpoint) =
    "export http_proxy='http://${endpoint.httpProxyString}'\n" +
        "export https_proxy='http://${endpoint.httpProxyString}'\n" +
        "export all_proxy='socks5h://${endpoint.socksProxyString}'"

internal const val LAN_SHELL_DISABLE_COMMAND = "unset http_proxy https_proxy all_proxy"

internal fun lanCurlCheckCommand(endpoint: LanEndpoint, windows: Boolean = false) =
    "${if (windows) "curl.exe" else "curl"} --proxy http://${endpoint.httpProxyString} --connect-timeout 5 --max-time 15 https://example.com/"
