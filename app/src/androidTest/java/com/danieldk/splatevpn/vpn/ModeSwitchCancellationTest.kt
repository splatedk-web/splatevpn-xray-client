package com.danieldk.splatevpn.vpn

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.data.TorSettings
import com.danieldk.splatevpn.data.TorSubscriptionWorker
import com.danieldk.splatevpn.ui.widget.SplateAppWidgetProvider
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentLinkedQueue

/** Exercises real service intents and real child processes; no public relay or VPN interface is used. */
class ModeSwitchCancellationTest {
    @Test fun rapidRestartsAndStopDoNotPublishFailureOrLeaveTorProcesses() = runBlocking {
        assumeTrue("Only use the isolated Android emulator", Build.HARDWARE in setOf("ranchu", "goldfish"))
        assumeFalse("Do not interrupt an active connection", SplateVpnService.isServiceActive)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dependencies = EntryPointAccessors.fromApplication(context.applicationContext,
            SplateAppWidgetProvider.WidgetEntryPoint::class.java)
        val settings = dependencies.settingsManager()
        val status = dependencies.vpnStatusManager()
        assumeTrue("Do not interrupt an in-progress connection", status.state.value in setOf(VpnState.DISCONNECTED, VpnState.FAILED))
        assumeTrue("Do not interrupt another Tor client", torChildren().isEmpty())
        assumeTrue("The emulator settings must be ready to use", settings.canUseNetworkSettings())
        val tor = EntryPointAccessors.fromApplication(context.applicationContext,
            TorSubscriptionWorker.Dependencies::class.java).torRepository()
        val previousSettings = settings.preferenceSnapshot()
        val previousTor = tor.load()
        assumeTrue("Do not replace unreadable Tor settings", tor.storageError.value == null)
        val previousState = status.state.value
        val previousError = status.errorMessage.value
        val neededOnboarding = settings.needsOnboarding.value
        val logFile = File(context.filesDir, "splate_debug.log")
        val previousLog = logFile.takeIf { it.isFile }?.readText().orEmpty()
        val states = ConcurrentLinkedQueue<VpnState>()
        val observer = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            status.state.drop(1).collect { states += it }
        }
        var host: ActivityScenario<ComponentActivity>? = null
        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { bridge ->
            try {
                settings.completeOnboarding("en", settings.getRoutingRegion(), "global", false)
                settings.setAutoSelectServer(false)
                tor.save(TorSettings(enabled = true, useBridges = true,
                    bridges = listOf("127.0.0.1:${bridge.localPort} ${"A".repeat(40)}")))
                // A blank foreground host avoids MainActivity's subscription/update initialization.
                host = ActivityScenario.launch(ComponentActivity::class.java)
                status.setState(VpnState.DISCONNECTED)

                command(context, "START", foreground = true)
                val first = awaitNewTorPid(context, status, emptySet())
                command(context, "PAUSE_VPN_120S")
                withTimeout(25_000) {
                    while (torChildren().isNotEmpty() || status.state.value != VpnState.DISCONNECTED) delay(25)
                }
                assertTrue("The foreground service must survive the pause", vpnServiceRunning(context))
                val afterPause = awaitNewTorPid(context, status, setOf(first), timeoutMs = 145_000)
                assertNotEquals(first, afterPause)

                command(context, "RESTART_MODE")
                val replacement = awaitNewTorPid(context, status, setOf(first, afterPause))
                assertNotEquals(afterPause, replacement)

                // Queue replacements and cancel their startup, including the authentication window.
                command(context, "RESTART_MODE")
                command(context, "RESTART_MODE")
                command(context, "STOP")
                awaitStopped(context, status)

                // The stopped generation must not call stopSelf against a subsequent start.
                command(context, "START", foreground = true)
                val afterStop = awaitNewTorPid(context, status, setOf(first, afterPause, replacement))
                assertNotEquals(replacement, afterStop)

                // A new START supersedes a STOP even while that STOP is still tearing down Tor.
                // Do not wait for service destruction here: the previous cleanup must not run
                // after the new child is launched or call stopSelf against the replacement.
                command(context, "STOP")
                command(context, "START", foreground = true)
                val immediateRestart = awaitNewTorPid(context, status, setOf(first, afterPause, replacement, afterStop))
                assertNotEquals(afterStop, immediateRestart)
                repeat(15) {
                    delay(100)
                    assertTrue("A stale STOP must not destroy the replacement service", vpnServiceRunning(context))
                    assertTrue("A stale cleanup must not terminate the replacement Tor child", immediateRestart in torChildren())
                    assertEquals("The inert bridge must leave the replacement normally connecting", VpnState.STARTING_CORE, status.state.value)
                }

                command(context, "RESTART_MODE")
                command(context, "STOP")
                awaitStopped(context, status)
                delay(400) // AppLogManager publishes/writes asynchronously.

                assertFalse("Cancelling or replacing startup must never publish FAILED", states.contains(VpnState.FAILED))
                assertEquals(VpnState.DISCONNECTED, status.state.value)
                assertNull(status.errorMessage.value)
                assertTrue("All Tor children must be gone after STOP", torChildren().isEmpty())
                val currentLog = logFile.takeIf { it.isFile }?.readText().orEmpty()
                val newLog = if (currentLog.startsWith(previousLog)) currentLog.substring(previousLog.length) else currentLog
                assertFalse("Cancelled startup must not be logged as an application error",
                    newLog.lineSequence().any { "[ERROR/SplateVpn]" in it })
                assertFalse("The control library's translated cancellation must not leak as an IOException",
                    "java.io.IOException: Interrupted" in newLog)
            } finally {
                withContext(NonCancellable) {
                    if (vpnServiceRunning(context) || torChildren().isNotEmpty()) runCatching { command(context, "STOP") }
                    withTimeout(25_000) { while (vpnServiceRunning(context) || torChildren().isNotEmpty()) delay(50) }
                    observer.cancelAndJoin()
                    tor.save(previousTor)
                    settings.restorePreferenceSnapshot(previousSettings)
                    if (neededOnboarding) settings.showOnboarding()
                    status.setState(previousState, previousError)
                    host?.close()
                }
            }
        }
    }

    private fun command(context: Context, action: String, foreground: Boolean = false) {
        val intent = Intent(context, SplateVpnService::class.java).setAction(action)
        if (foreground) ContextCompat.startForegroundService(context, intent) else context.startService(intent)
    }

    private suspend fun awaitNewTorPid(
        context: Context, status: VpnStatusManager, old: Set<Int>, timeoutMs: Long = 20_000
    ): Int = withTimeout(timeoutMs) {
        val pidFile = File(context.noBackupFilesDir, "tor/tor.pid")
        var result: Int? = null
        while (result == null) {
            assertNotEquals("A replaced startup must not fail before control is ready", VpnState.FAILED, status.state.value)
            val pid = runCatching { pidFile.readText().trim().toInt() }.getOrNull()
            if (pid != null && pid !in old && pid in torChildren()) result = pid else delay(if (timeoutMs > 20_000) 100 else 5)
        }
        requireNotNull(result)
    }

    private suspend fun awaitStopped(context: Context, status: VpnStatusManager) = withTimeout(25_000) {
        while (vpnServiceRunning(context) || torChildren().isNotEmpty()) {
            assertNotEquals("Cancellation must preserve a normal disconnected state", VpnState.FAILED, status.state.value)
            delay(25)
        }
        assertEquals(VpnState.DISCONNECTED, status.state.value)
    }

    @Suppress("DEPRECATION")
    private fun vpnServiceRunning(context: Context): Boolean =
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == SplateVpnService::class.java.name }

    private fun torChildren(): Set<Int> {
        val owner = android.os.Process.myPid().toString()
        return File("/proc").listFiles().orEmpty().mapNotNull { entry ->
            val pid = entry.name.toIntOrNull() ?: return@mapNotNull null
            val args = runCatching { File(entry, "cmdline").readText().split('\u0000') }.getOrDefault(emptyList())
            pid.takeIf { args.firstOrNull()?.endsWith("/libtor.so") == true &&
                args.zipWithNext().any { (key, value) -> key == "--__OwningControllerProcess" && value == owner } }
        }.toSet()
    }
}
