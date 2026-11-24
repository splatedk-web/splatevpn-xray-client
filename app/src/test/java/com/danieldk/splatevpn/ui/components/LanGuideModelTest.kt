package com.danieldk.splatevpn.ui.components

import com.danieldk.splatevpn.vpn.LanEndpoint
import org.junit.Assert.*
import org.junit.Test

class LanGuideModelTest {
    private val wifi = LanEndpoint("ap0", "Hotspot", "192.0.2.1", 18080, 11080)
    private val usb = LanEndpoint("rndis0", "USB", "192.0.2.2", 28080, 21080)

    @Test fun selectedAddressFollowsAvailableNetworkWithoutInventingAnIp() {
        assertNull(lanGuideEndpoint(emptyList(), wifi.ipAddress))
        assertEquals(usb, lanGuideEndpoint(listOf(wifi, usb), usb.ipAddress))
        assertEquals(wifi, lanGuideEndpoint(listOf(wifi), usb.ipAddress))
        assertEquals(usb, lanGuideEndpoint(listOf(usb), null))
    }

    @Test fun generatedCommandsKeepHttpHttpsAndSocksPortsSeparate() {
        val windows = lanWindowsEnableCommand(wifi)
        assertTrue(windows.contains("http=192.0.2.1:18080;https=192.0.2.1:18080"))
        assertFalse(windows.contains(":11080"))
        val shell = lanShellEnableCommand(usb)
        assertTrue(shell.contains("http_proxy='http://192.0.2.2:28080'"))
        assertTrue(shell.contains("https_proxy='http://192.0.2.2:28080'"))
        assertTrue(shell.contains("all_proxy='socks5h://192.0.2.2:21080'"))
        assertTrue(lanCurlCheckCommand(wifi, true).startsWith("curl.exe --proxy http://192.0.2.1:18080"))
        assertTrue(lanCurlCheckCommand(wifi).startsWith("curl --proxy http://192.0.2.1:18080"))
    }
}
