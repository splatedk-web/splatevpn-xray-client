package com.danieldk.splatevpn.vpn

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.SplateApplication
import com.danieldk.splatevpn.data.TorSettings
import com.danieldk.splatevpn.data.TorSubscriptionWorker
import com.danieldk.splatevpn.ui.widget.SplateAppWidgetProvider
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in public Tor test, restricted to an isolated emulator. */
class TorServiceRecoveryTest {
    @Suppress("DEPRECATION")
    private fun serviceRunning(context: Context) =
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == SplateVpnService::class.java.name }

    @Test fun lostTorProcessReconnectsWhileKeepingTheVpnInterface() = exerciseRecovery(false)
    @Test fun liveButStalledTorRecoversWithoutDroppingTheVpnInterface() = exerciseRecovery(true)

    private fun exerciseRecovery(stallTraffic: Boolean) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveTor") == "true")
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val status = EntryPointAccessors.fromApplication(app, SplateAppWidgetProvider.WidgetEntryPoint::class.java).vpnStatusManager()
        assumeTrue(status.state.value in setOf(VpnState.DISCONNECTED, VpnState.FAILED))
        assumeFalse(SplateVpnService.isServiceActive)
        assertNull(VpnService.prepare(app))
        val settings = (app.applicationContext as SplateApplication).settingsManager
        val tor = EntryPointAccessors.fromApplication(app, TorSubscriptionWorker.Dependencies::class.java).torRepository()
        val previousSettings = settings.preferenceSnapshot()
        val previousTor = tor.load()
        assumeTrue(tor.storageError.value == null)
        val previousState = status.state.value
        val previousError = status.errorMessage.value
        val neededOnboarding = settings.needsOnboarding.value
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val pidFile = File(app.noBackupFilesDir, "tor/tor.pid")
        var host: ActivityScenario<ComponentActivity>? = null
        try {
            settings.completeOnboarding("en", "ru", "global", false)
            settings.setAutoUpdateInterval("off")
            settings.setAutoConnectOnBoot(false)
            settings.setAutoSelectServer(false)
            settings.setChecksWifiOnly(false)
            val torSettings = if (stallTraffic) {
                val document = com.danieldk.splatevpn.data.SubscriptionTransport().fetch(
                    com.danieldk.splatevpn.data.TOR_TOP100_SUBSCRIPTION, "SplateVPN/TorRecoveryDiagnostic")
                assertTrue("Public bridge subscription unavailable", document.isSuccessful)
                val bridges = com.danieldk.splatevpn.data.TorConfigParser.parseSubscription(document.content).bridges
                assertTrue(bridges.isNotEmpty())
                TorSettings(enabled = true, useBridges = true, bridges = bridges)
            } else TorSettings(enabled = true)
            tor.save(torSettings)
            host = ActivityScenario.launch(ComponentActivity::class.java)
            ContextCompat.startForegroundService(app, Intent(app, SplateVpnService::class.java).setAction("START"))
            withTimeout(150_000) {
                while (status.state.value != VpnState.CONNECTED) {
                    assertNotEquals("Initial Tor bootstrap failed", VpnState.FAILED, status.state.value)
                    delay(100)
                }
            }
            assertTrue("Initial Tor must carry real HTTP", status.performHealthCheck(timeoutMs = 20_000))
            val pid = pidFile.readText().trim().toInt()
            val session = status.sessionToken
            val network = requireNotNull(cm.allNetworks.firstOrNull {
                cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            })
            assertTrue(cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true)
            if (stallTraffic) {
                val directory = File(app.noBackupFilesDir, "tor")
                val port = File(directory, "control-port").readLines().firstNotNullOf { line ->
                    Regex("PORT=127\\.0\\.0\\.1:(\\d+)").matchEntire(line.trim())?.groupValues?.get(1)?.toIntOrNull()
                }
                java.net.Socket("127.0.0.1", port).use { socket ->
                    socket.soTimeout = 3_000
                    val secret = File(directory, "control-cookie").readBytes()
                    try {
                        val output = socket.getOutputStream().bufferedWriter(Charsets.US_ASCII)
                        val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                        output.write("AUTHENTICATE " + secret.joinToString("") { "%02x".format(it) } + "\r\n"); output.flush()
                        assertEquals("250 OK", input.readLine())
                        output.write("SETCONF DisableNetwork=1\r\n"); output.flush()
                        assertEquals("250 OK", input.readLine())
                    } finally { secret.fill(0) }
                }
                android.system.Os.kill(pid, 0)
            } else android.os.Process.killProcess(pid)
            withTimeout(if (stallTraffic) 55_000L else 15_000L) {
                while (status.state.value != VpnState.RECONNECTING) {
                    assertNotEquals(VpnState.FAILED, status.state.value)
                    delay(20)
                }
            }
            if (stallTraffic) {
                delay(3_500)
                if (status.state.value == VpnState.RECONNECTING) {
                    val notification = app.getSystemService(android.app.NotificationManager::class.java).activeNotifications.first { it.id == 1 }.notification
                    assertFalse("Traffic updates must not label a reconnecting Tor as connected",
                        notification.extras.getCharSequence("android.title").toString().contains("Connected", ignoreCase = true))
                }
            }
            assertTrue("Tor recovery must keep the established TUN", cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true)
            withTimeout(150_000) {
                while (status.state.value != VpnState.CONNECTED) {
                    val phase = status.state.value
                    assertNotEquals("Tor recovery failed", VpnState.FAILED, phase)
                    if (phase == VpnState.RECONNECTING) {
                        assertTrue("The TUN must stay up during Tor bootstrap",
                            cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true)
                    }
                    delay(100)
                }
            }
            assertNotEquals(pid, pidFile.readText().trim().toInt())
            assertNotEquals(session, status.sessionToken)
            assertEquals("splate-tor", status.connectedServerId)
            assertTrue("Recovered Tor must carry real HTTP", status.performHealthCheck(timeoutMs = 20_000))
        } finally {
            withContext(NonCancellable) {
                app.startService(Intent(app, SplateVpnService::class.java).setAction("STOP"))
                withTimeout(25_000) {
                    while (serviceRunning(app)) delay(50)
                }
                tor.save(previousTor)
                settings.restorePreferenceSnapshot(previousSettings)
                if (neededOnboarding) settings.showOnboarding()
                status.setState(previousState, previousError)
                host?.close()
            }
        }
    }
}
