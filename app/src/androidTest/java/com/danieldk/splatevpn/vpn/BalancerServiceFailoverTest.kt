package com.danieldk.splatevpn.vpn

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.SplateApplication
import com.danieldk.splatevpn.data.*
import com.danieldk.splatevpn.ui.widget.SplateAppWidgetProvider
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.Closeable
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Real service, TUN, HEV and bundled Xray against local SOCKS/HTTP protocol fixtures. */
class BalancerServiceFailoverTest {
    @Suppress("DEPRECATION")
    private fun serviceRunning(context: Context) =
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == SplateVpnService::class.java.name }

    @Test fun deadServerSwitchesToVerifiedBackupAndManualChoiceSurvivesRecovery() = runBlocking {
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val soakMillis = InstrumentationRegistry.getArguments().getString("failoverSoakMillis")?.let {
            requireNotNull(it.toLongOrNull()) { "failoverSoakMillis must be an integer" }
                .also { value -> require(value in 0L..600_000L) }
        } ?: 0L
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val dependencies = EntryPointAccessors.fromApplication(app, SplateAppWidgetProvider.WidgetEntryPoint::class.java)
        val status = dependencies.vpnStatusManager()
        assumeTrue(status.state.value in setOf(VpnState.DISCONNECTED, VpnState.FAILED))
        assumeFalse(SplateVpnService.isServiceActive)
        assertNull("Grant ACTIVATE_VPN on the isolated QA emulator", VpnService.prepare(app))
        val settings = (app.applicationContext as SplateApplication).settingsManager
        val tor = EntryPointAccessors.fromApplication(app, TorSubscriptionWorker.Dependencies::class.java).torRepository()
        assumeFalse(tor.load().enabled)
        val repository = EntryPointAccessors.fromApplication(app, SubscriptionUpdateWorker.WorkerDependencies::class.java).serverRepository()
        val database = ServerRepository::class.java.getDeclaredField("database").apply { isAccessible = true }.get(repository) as AppDatabase
        val previousSettings = settings.preferenceSnapshot()
        val previousSelection = database.serverDao().getSelectedServerSync()?.id
        val previousState = status.state.value
        val previousError = status.errorMessage.value
        val neededOnboarding = settings.needsOnboarding.value
        val suffix = UUID.randomUUID().toString()
        val physical = requireNotNull(app.getSystemService(android.net.ConnectivityManager::class.java).activeNetwork)
        val firstPeer = LocalSocksPeer(physical)
        val backupPeer = LocalSocksPeer(physical)
        val manualPeer = LocalSocksPeer(physical)
        val nodes = listOf(firstPeer, backupPeer, manualPeer).mapIndexed { index, peer ->
            requireNotNull(LinkParser.parse("socks5://127.0.0.1:${peer.port}"))
                .copy(id = "failover-$suffix-$index", name = "Local failover $index")
        }
        var host: ActivityScenario<ComponentActivity>? = null
        try {
            settings.completeOnboarding("en", "ru", "global", false)
            settings.setInitializedPresetSubscriptions(true)
            settings.setInitializedSponsorSubscription()
            settings.setInitializedUniversalWhitelist()
            settings.setInitializedExpandedWhitelists()
            settings.setInitializedBlackLists()
            settings.setAutoUpdateInterval("off")
            settings.setAutoConnectOnBoot(false)
            settings.setChecksWifiOnly(false)
            settings.setAutoSelectServer(false)
            settings.setMtu(1280)
            settings.setAdBlocker(false)
            settings.setBalancerPreferences(BalancerPreferences(mode = BalancerMode.FAILOVER,
                healthIntervalSeconds = if (soakMillis > 0) 10 else 2,
                failureConfirmations = if (soakMillis > 0) 3 else 2,
                metric = BalancerMetric.DOWNLOAD, warmStandby = false))
            database.withTransaction {
                database.serverDao().insertServers(nodes)
                settings.storeDisabledServerIds(database.serverDao().getAllServersSync().mapTo(hashSetOf()) { it.id } - nodes.take(2).map { it.id }.toSet())
                database.serverDao().setActiveServer(nodes.first().id)
            }
            SplateVpnService.clearDeadServers()
            host = ActivityScenario.launch(ComponentActivity::class.java)
            ContextCompat.startForegroundService(app, Intent(app, SplateVpnService::class.java).setAction("START"))
            awaitCondition(30_000, { "Initial VPN state: ${status.state.value}, local replies: ${firstPeer.responses.get()}" }) {
                status.state.value == VpnState.CONNECTED && firstPeer.responses.get() > 0
            }
            assertEquals(nodes.first().id, status.connectedServerId)
            val connectivity = app.getSystemService(android.net.ConnectivityManager::class.java)
            awaitCondition(5_000, { "No VPN network with MTU 1280 appeared" }) {
                connectivity.allNetworks.any { network ->
                    connectivity.getNetworkCapabilities(network)?.hasTransport(
                        android.net.NetworkCapabilities.TRANSPORT_VPN
                    ) == true && connectivity.getLinkProperties(network)?.interfaceName
                        ?.let { java.net.NetworkInterface.getByName(it)?.mtu } == 1280
                }
            }
            assertTrue(java.io.File(app.filesDir, "hev-socks5-tunnel.yaml").readLines().contains("  mtu: 1280"))
            if (soakMillis > 0) {
                settings.setAutoSelectServer(true)
                val until = android.os.SystemClock.elapsedRealtime() + soakMillis
                while (android.os.SystemClock.elapsedRealtime() < until) {
                    delay(minOf(30_000L, until - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(1L))
                    assertEquals(VpnState.CONNECTED, status.state.value)
                    assertEquals(nodes.first().id, status.connectedServerId)
                    android.util.Log.i("BalancerSoak", "Healthy soak: ${soakMillis - (until - android.os.SystemClock.elapsedRealtime())} ms")
                }
            }
            val probes = ServerRepository::class.java.getDeclaredField("probes").apply { isAccessible = true }
                .get(repository) as ServerProbeRepository
            probes.currentNetworkKey()
            repeat(5) { probes.qualityTracker.recordFailure(nodes[1].id) }
            assertTrue(probes.qualityTracker.isQuarantined(nodes[1].id))
            // The reserve has recovered while its old five-minute quarantine still exists.
            delay(10_100)
            assertTrue("A quarantined reserve must be probed while normal candidates still remain",
                repository.getRankedCandidateServers().any { it.id == nodes[1].id })
            settings.setAutoSelectServer(true)
            assertTrue(repository.testPingViaXray(timeoutMs = 8000) > 0)
            val failedAt = android.os.SystemClock.elapsedRealtime()
            android.util.Log.i("BalancerSoak", "Inject primary traffic blackhole; TCP listener stays open")
            firstPeer.blackhole()
            Socket("127.0.0.1", firstPeer.port).use { assertTrue(it.isConnected) }
            awaitCondition(65_000, { "Emergency switch state: ${status.state.value}, backup replies: ${backupPeer.responses.get()}" }) {
                status.state.value == VpnState.CONNECTED && status.connectedServerId == nodes[1].id
            }
            assertTrue("The replacement must carry real HTTP through Xray", backupPeer.responses.get() > 0)
            assertFalse("Verified reserve success must close the half-open recovery probe",
                probes.qualityTracker.isQuarantined(nodes[1].id))
            assertEquals(nodes[1].id, database.serverDao().getSelectedServerSync()?.id)
            assertTrue("A failed server must remain quarantined after switching", nodes.first().id in SplateVpnService.deadServerIds)
            assertTrue("Main replacement tunnel must pass HTTPS", repository.testPingViaXray(timeoutMs = 8000) > 0)
            android.util.Log.i("BalancerSoak", "Replacement CONNECTED and HTTPS verified after ${android.os.SystemClock.elapsedRealtime() - failedAt} ms")

            android.util.Log.i("BalancerSoak", "Inject backup traffic blackhole; no enabled reserve remains")
            backupPeer.blackhole()
            awaitCondition(65_000, { "The service did not enter recovery: ${status.state.value}" }) {
                status.state.value == VpnState.RECONNECTING
            }
            settings.setServersEnabled(setOf(nodes[2].id), true)
            // The physical-network callback invalidates probes when the upstream changes.
            status.invalidateNetworkChecks()
            awaitCondition(45_000, { "Search stopped after network invalidation: ${status.state.value}" }) {
                status.state.value == VpnState.CONNECTED && status.connectedServerId == nodes[2].id
            }
            assertTrue("Recovered tunnel must pass HTTPS", repository.testPingViaXray(timeoutMs = 8000) > 0)
            manualPeer.blackhole()
            awaitCondition(65_000, { "Expected recovery before manual selection" }) {
                status.state.value == VpnState.RECONNECTING
            }
            firstPeer.restore()
            repository.setActiveServer(nodes.first().id)
            settings.setAutoSelectServer(false)
            app.startService(Intent(app, SplateVpnService::class.java).setAction("RELOAD"))
            awaitCondition(30_000, { "Manual reload state: ${status.state.value}" }) {
                status.state.value == VpnState.CONNECTED && status.connectedServerId == nodes.first().id
            }
            delay(4_000)
            assertEquals(nodes.first().id, database.serverDao().getSelectedServerSync()?.id)
            assertEquals(nodes.first().id, status.connectedServerId)
            assertFalse(settings.isAutoSelectServer())
            assertTrue(manualPeer.responses.get() > 0)
        } finally {
            withContext(NonCancellable) {
                app.startService(Intent(app, SplateVpnService::class.java).setAction("STOP"))
                withTimeout(25_000) { while (serviceRunning(app)) delay(50) }
                firstPeer.close(); backupPeer.close(); manualPeer.close()
                database.withTransaction {
                    nodes.forEach { database.serverDao().deleteServer(it.id) }
                    previousSelection?.let { database.serverDao().setActiveServer(it) }
                }
                settings.restorePreferenceSnapshot(previousSettings)
                if (neededOnboarding) settings.showOnboarding()
                status.setState(previousState, previousError)
                SplateVpnService.clearDeadServers()
                host?.close()
            }
        }
    }

    private suspend fun awaitCondition(timeoutMs: Long, message: () -> String, reached: () -> Boolean) {
        val complete = withTimeoutOrNull(timeoutMs) {
            while (!reached()) delay(100)
            true
        }
        assertTrue(message(), complete == true)
    }

    internal class LocalSocksPeer(private val physical: android.net.Network) : Closeable {
        private val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val clients = ConcurrentHashMap.newKeySet<Socket>()
        val port = listener.localPort
        val responses = AtomicInteger()
        @Volatile private var blocked = false
        fun blackhole() { blocked = true; clients.forEach { runCatching { it.close() } } }
        fun restore() { blocked = false }

        init {
            Thread {
                while (!listener.isClosed) {
                    val socket = try { listener.accept() } catch (_: java.io.IOException) { break }
                    clients.add(socket)
                    Thread {
                        try { socket.use(::respond) } catch (_: java.io.IOException) { }
                        finally { clients.remove(socket) }
                    }.apply { isDaemon = true; start() }
                }
            }.apply { isDaemon = true; start() }
        }

        private fun respond(socket: Socket) {
            socket.soTimeout = 5_000
            val input = DataInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            if (input.readUnsignedByte() != 5) return
            input.readFully(ByteArray(input.readUnsignedByte()))
            output.write(byteArrayOf(5, 0)); output.flush()
            if (input.readUnsignedByte() != 5 || input.readUnsignedByte() != 1) return
            input.readUnsignedByte()
            val host = when (input.readUnsignedByte()) {
                1 -> InetAddress.getByAddress(ByteArray(4).also(input::readFully)).hostAddress!!
                3 -> ByteArray(input.readUnsignedByte()).also(input::readFully).toString(Charsets.UTF_8)
                4 -> InetAddress.getByAddress(ByteArray(16).also(input::readFully)).hostAddress!!
                else -> return
            }
            val port = input.readUnsignedShort()
            if (blocked) return
            Socket().use { upstream ->
                clients.add(upstream)
                try {
                    physical.bindSocket(upstream)
                    upstream.connect(java.net.InetSocketAddress(host, port), 5000)
                    upstream.soTimeout = 10_000
                    output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 0)); output.flush()
                    val upload = Thread {
                        runCatching { input.copyTo(upstream.getOutputStream()); upstream.shutdownOutput() }
                    }.apply { isDaemon = true; start() }
                    try {
                        val buffer = ByteArray(16 * 1024)
                        val remote = upstream.getInputStream()
                        while (!blocked) {
                            val count = remote.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count); output.flush()
                            responses.incrementAndGet()
                        }
                    } finally { socket.close(); upstream.close(); upload.join(1000) }
                } finally { clients.remove(upstream) }
            }
        }

        override fun close() {
            listener.close()
            clients.forEach { runCatching { it.close() } }
        }
    }
}
