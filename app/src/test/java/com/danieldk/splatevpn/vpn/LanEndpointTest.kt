package com.danieldk.splatevpn.vpn

import org.junit.Assert.*
import org.junit.Test

class LanEndpointTest {
    @Test fun windowsHttpsUsesHttpPortRatherThanSocksPort() {
        val endpoint = LanEndpoint("test", "Test", "192.0.2.1")
        assertEquals("http=192.0.2.1:10809;https=192.0.2.1:10809", endpoint.windowsHttpProxyString)
        assertEquals(endpoint.windowsHttpProxyString, endpoint.windowsDualProxyString)
    }

    @Test fun customEndpointPortsAreRetained() {
        val endpoint = LanEndpoint("test", "Test", "192.0.2.2", httpPort = 18080, socksPort = 11080)
        assertEquals("http=192.0.2.2:18080;https=192.0.2.2:18080", endpoint.windowsHttpProxyString)
        assertEquals("192.0.2.2:11080", endpoint.socksProxyString)
    }
}
