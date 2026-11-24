package com.danieldk.splatevpn.vpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.VpnService
import android.os.IBinder
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VpnServiceTunLifecycleTest {
    @Test
    fun vpnServicePrepareAndTunEstablishLifecycle() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val prepareIntent = VpnService.prepare(app)
        assertNull("VPN permission should be granted via appops ACTIVATE_VPN", prepareIntent)

        val vpnServiceIntent = Intent(app, AppIdentityTestVpnService::class.java)
        val ready = CountDownLatch(1)
        var service: AppIdentityTestVpnService? = null

        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, b: IBinder?) {
                service = (b as AppIdentityTestVpnService.LocalBinder).service
                ready.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
            }
        }
        assertTrue(app.bindService(vpnServiceIntent, conn, Context.BIND_AUTO_CREATE))
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        val vpn = requireNotNull(service)

        try {
            val access = AppIdentityAccess(10808, setOf(app.packageName), LocalProxyCredentials.generate())
            val dummyConfig = """{"tunnel":{"mtu":1500,"ipv4":"172.19.0.1"},"socks5":{"address":"127.0.0.1","port":10808}}"""
            vpn.startTunnel(dummyConfig, access)
            vpn.stopTunnel()
            vpn.startTunnel(dummyConfig, access)
            vpn.onRevoke()
        } finally {
            vpn.stopTunnel()
            app.unbindService(conn)
        }
    }

    @Test
    fun vpnStatusManagerLifecycleTransitions() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = VpnStatusManager(app)

        assertEquals(VpnState.DISCONNECTED, manager.state.value)
        manager.setState(VpnState.STARTING_CORE)
        assertEquals(VpnState.STARTING_CORE, manager.state.value)
        manager.setState(VpnState.CONNECTED)
        assertEquals(VpnState.CONNECTED, manager.state.value)
        assertEquals(true, manager.state.value == VpnState.CONNECTED)

        manager.setState(VpnState.FAILED, "Port conflict simulation")
        assertEquals(VpnState.FAILED, manager.state.value)
        assertEquals("Port conflict simulation", manager.errorMessage.value)
        assertEquals(false, manager.state.value == VpnState.CONNECTED)

        manager.setState(VpnState.STARTING_CORE)
        assertEquals(VpnState.STARTING_CORE, manager.state.value)
        manager.setState(VpnState.CONNECTED)
        assertEquals(VpnState.CONNECTED, manager.state.value)
        assertEquals(true, manager.state.value == VpnState.CONNECTED)

        manager.setState(VpnState.DISCONNECTED)
        assertEquals(VpnState.DISCONNECTED, manager.state.value)
        assertNull(manager.errorMessage.value)
        assertEquals(false, manager.state.value == VpnState.CONNECTED)
    }
}
