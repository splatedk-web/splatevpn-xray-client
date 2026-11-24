package com.danieldk.splatevpn.data

import android.os.Build
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.SplateApplication
import com.danieldk.splatevpn.vpn.SplateVpnService
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

class ServerEvidenceNetworkRaceTest {
    @Test fun aFailureQueuedInRoomCannotQuarantineTheNextNetwork(): Unit = runBlocking {
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        assumeTrue(!SplateVpnService.isServiceActive)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        assumeTrue(shell("settings get global wifi_on").trim() == "1")
        val app = instrumentation.targetContext.applicationContext as SplateApplication
        val settings = app.settingsManager
        val previousSettings = settings.preferenceSnapshot()
        val repository = EntryPointAccessors.fromApplication(app,
            SubscriptionUpdateWorker.WorkerDependencies::class.java).serverRepository()
        val database = ServerRepository::class.java.getDeclaredField("database").apply { isAccessible = true }.get(repository) as AppDatabase
        val probes = ServerRepository::class.java.getDeclaredField("probes").apply { isAccessible = true }.get(repository) as ServerProbeRepository
        val node = ServerNode("network-race-${UUID.randomUUID()}", "Network race fixture", "socks", "127.0.0.1", 9090)
        val previousUnderlying = NetworkAccessPolicy.awgUnderlyingNetwork()
        val release = CompletableDeferred<Unit>()
        var blocker: Job? = null
        var failure: Job? = null
        try {
            settings.setChecksWifiOnly(false)
            NetworkAccessPolicy.setVpnUnderlyingNetwork(null)
            val oldNetwork = requireNotNull(probes.currentNetworkKey())
            assumeTrue(oldNetwork != 1L)
            database.serverDao().insertServer(node)
            val entered = CompletableDeferred<Unit>()
            blocker = launch(Dispatchers.IO) { database.withTransaction { entered.complete(Unit); release.await() } }
            withTimeout(5000) { entered.await() }
            failure = launch(Dispatchers.IO) { probes.recordServerFailure(node, oldNetwork) }
            delay(200)
            assertTrue("Failure must still be waiting for the held database transaction", failure.isActive)
            shell("svc wifi disable")
            withTimeout(15_000) { while (probes.currentNetworkKey() == oldNetwork) delay(50) }
            release.complete(Unit)
            withTimeout(5000) { failure.join(); blocker.join() }
            assertFalse("A result from the previous network must not quarantine a node on the next network",
                probes.qualityTracker.isQuarantined(node.id))
        } finally {
            withContext(NonCancellable) {
                release.complete(Unit)
                failure?.cancelAndJoin(); blocker?.cancelAndJoin()
                shell("svc wifi enable")
                database.serverDao().deleteServer(node.id)
                probes.invalidateServerQuality(node.id)
                NetworkAccessPolicy.setVpnUnderlyingNetwork(previousUnderlying)
                settings.restorePreferenceSnapshot(previousSettings)
            }
        }
    }
}
