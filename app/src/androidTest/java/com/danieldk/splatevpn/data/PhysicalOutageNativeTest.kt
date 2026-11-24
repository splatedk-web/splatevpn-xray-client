package com.danieldk.splatevpn.data

import android.os.Build
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.SplateApplication
import com.danieldk.splatevpn.vpn.SplateVpnService
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

/** Host runner throttles only the disposable emulator, retaining its Android network handle. */
class PhysicalOutageNativeTest {
    @Test fun anAttachedButUnresponsiveNetworkDoesNotQuarantineServersAndRecoveryRechecksIt(): Unit = runBlocking {
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        assumeTrue(InstrumentationRegistry.getArguments().getString("physicalOutageHostControl") == "true")
        assumeTrue(!SplateVpnService.isServiceActive)
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SplateApplication
        val settings = app.settingsManager
        val before = settings.preferenceSnapshot()
        val repository = EntryPointAccessors.fromApplication(app, SubscriptionUpdateWorker.WorkerDependencies::class.java).serverRepository()
        val database = ServerRepository::class.java.getDeclaredField("database").apply { isAccessible = true }.get(repository) as AppDatabase
        val probes = ServerRepository::class.java.getDeclaredField("probes").apply { isAccessible = true }.get(repository) as ServerProbeRepository
        val evidence = ServerProbeRepository::class.java.getDeclaredField("physicalEvidence").apply { isAccessible = true }.get(probes) as PhysicalFailureEvidence
        val nodes = List(3) { ServerNode("outage-${UUID.randomUUID()}", "Outage fixture", "socks", "127.0.0.1", 9) }
        try {
            settings.setChecksWifiOnly(false)
            database.serverDao().insertServers(nodes)
            val network = requireNotNull(probes.currentNetworkKey())
            assertNotEquals(1L, network)
            assertEquals(PhysicalEvidence.REACHABLE, evidence.assess(network))
            Log.i("PhysicalOutage", "READY_THROTTLE")
            delay(20_000)
            assertEquals("The physical network must remain attached", network, probes.currentNetworkKey())
            val connectivity = app.getSystemService(android.net.ConnectivityManager::class.java)
            val attached = connectivity.allNetworks.first { it.networkHandle == network }
            assertTrue("Android must still advertise internet capability despite failed requests",
                connectivity.getNetworkCapabilities(attached)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true)
            assertEquals(PhysicalEvidence.UNCONFIRMED, evidence.assess(network))
            nodes.forEach {
                assertFalse(probes.recordServerFailure(it, network))
                assertFalse(probes.qualityTracker.isQuarantined(it.id))
            }
            Log.i("PhysicalOutage", "OUTAGE_VERIFIED_RESTORE")
            withTimeout(45_000) {
                while (evidence.assess(network) != PhysicalEvidence.REACHABLE) delay(1000)
            }
            assertEquals(network, probes.currentNetworkKey())
            assertTrue(probes.recordServerFailure(nodes.first(), network))
            assertTrue(probes.qualityTracker.isQuarantined(nodes.first().id))
            Log.i("PhysicalOutage", "RECOVERY_VERIFIED")
        } finally {
            nodes.forEach { database.serverDao().deleteServer(it.id); probes.invalidateServerQuality(it.id) }
            settings.restorePreferenceSnapshot(before)
        }
    }
}
