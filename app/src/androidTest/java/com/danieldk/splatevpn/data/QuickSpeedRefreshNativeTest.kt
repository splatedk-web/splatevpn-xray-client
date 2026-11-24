package com.danieldk.splatevpn.data

import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import com.danieldk.splatevpn.MainActivity
import com.danieldk.splatevpn.ui.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import java.net.InetAddress
import java.net.ServerSocket
import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.SplateApplication
import com.danieldk.splatevpn.vpn.BalancerServiceFailoverTest
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

class QuickSpeedRefreshNativeTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun realHttpsRefreshMeasuresBothCandidatesWithoutChangingSelection(): Unit = runBlocking {
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SplateApplication
        val settings = app.settingsManager
        val before = settings.preferenceSnapshot()
        val repository = EntryPointAccessors.fromApplication(app, SubscriptionUpdateWorker.WorkerDependencies::class.java).serverRepository()
        val database = ServerRepository::class.java.getDeclaredField("database").apply { isAccessible = true }.get(repository) as AppDatabase
        val budget = app.getSharedPreferences("balancer_probe_budget", 0)
        val oldStart = if (budget.contains("started")) budget.getLong("started", 0) else null
        val oldUsed = if (budget.contains("used")) budget.getLong("used", 0) else null
        val network = requireNotNull(app.getSystemService(android.net.ConnectivityManager::class.java).activeNetwork)
        val peers = List(2) { BalancerServiceFailoverTest.LocalSocksPeer(network) }
        val nodes = peers.map { requireNotNull(LinkParser.parse("socks5://127.0.0.1:${it.port}")).copy(id = "speed-${UUID.randomUUID()}") }
        val selected = database.serverDao().getSelectedServerSync()?.id
        fun capture(name: String) {
            requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()).let { image ->
                java.io.File(app.cacheDir, name).outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
            }
        }
        try {
            settings.completeOnboarding("en", "ru", "global", false)
            settings.setInitializedPresetSubscriptions(true)
            settings.setInitializedSponsorSubscription()
            settings.setAutoUpdateInterval("off")
            settings.setAutoConnectOnBoot(false)
            settings.setAutoSelectServer(false)
            settings.setUiMode(AppUiMode.PRO)
            settings.setPingMode("real_delay")
            settings.setChecksWifiOnly(false)
            settings.setBalancerPreferences(BalancerPreferences(metric = BalancerMetric.DOWNLOAD))
            budget.edit().putLong("started", System.currentTimeMillis()).putLong("used", 0).commit()
            database.serverDao().insertServers(nodes)
            settings.storeDisabledServerIds(database.serverDao().getAllServersSync().mapTo(hashSetOf()) { it.id } - nodes.map { it.id }.toSet())
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var model: MainViewModel
                scenario.onActivity { model = ViewModelProvider(it)[MainViewModel::class.java] }
                try {
                    withTimeout(30_000) { while (model.isStartupLoading.value) delay(20) }
                    compose.waitUntil(30_000) { compose.onAllNodesWithTag("homeServerQuickPick").fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithTag("homeServerQuickPick").performScrollTo()
                    compose.onNodeWithText("Auto: off").assertIsDisplayed()
                    capture("quick-auto-off-home.png")
                    ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { blackhole ->
                        val blocked = (1..24).map { ServerNode("busy-speed-fixture-$it", "Pending check", "socks", "127.0.0.1", blackhole.localPort) }
                        scenario.onActivity { model.testServersPing(blocked); assertTrue(model.isPinging.value) }
                        compose.onNodeWithTag("homeServerQuickPick").performSemanticsAction(SemanticsActions.OnClick) { it() }
                        compose.waitUntil(5_000) { compose.onAllNodesWithTag("quickServerList", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
                        delay(1_000)
                        assertTrue("Picker opened while another check was busy", model.isPinging.value)
                        assertTrue(repository.candidateMeasurementSnapshot(nodes).isEmpty())
                        scenario.onActivity { model.cancelPingChecks() }
                        withTimeout(10_000) { while (model.isPinging.value) delay(20) }
                        delay(1_000)
                        assertFalse("Opening the picker did not start new checks", model.isPinging.value)
                        assertTrue(repository.candidateMeasurementSnapshot(nodes).isEmpty())
                        compose.onNodeWithContentDescription("Refresh list").performClick()
                        withTimeout(60_000) {
                            while (repository.candidateMeasurementSnapshot(nodes).values.count { it.freshDownload() != null } < nodes.size) delay(100)
                        }
                    }
                    nodes.forEach { compose.onNodeWithTag("quickServer:${it.id}").assertExists() }
                    assertTrue(compose.onAllNodesWithText("Mbps", substring = true).fetchSemanticsNodes().size >= 3)
                    capture("quick-speed-busy-refresh.png")
                } catch (failure: Throwable) {
                    runCatching { capture("quick-speed-busy-failure.png") }
                    android.util.Log.e("SpeedRefreshQA", "Busy picker regression failed", failure)
                    throw failure
                } finally {
                    scenario.onActivity { model.cancelPingChecks() }
                    withTimeout(10_000) { while (model.isPinging.value) delay(20) }
                }
            }
            val measured = repository.candidateMeasurementSnapshot(nodes)
            nodes.forEach { assertTrue("Each verified quick-list candidate needs a real download sample", requireNotNull(measured[it.id]).freshDownload()!! > 0) }
            assertEquals(selected, database.serverDao().getSelectedServerSync()?.id)
            assertEquals(2L * ProbeTrafficBudget.SAMPLE_BYTES, budget.getLong("used", 0))
        } finally {
            peers.forEach { it.close() }
            nodes.forEach { database.serverDao().deleteServer(it.id) }
            settings.restorePreferenceSnapshot(before)
            budget.edit().apply { if (oldStart == null) remove("started") else putLong("started", oldStart)
                if (oldUsed == null) remove("used") else putLong("used", oldUsed) }.commit()
        }
    }
}
