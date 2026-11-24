package com.danieldk.splatevpn.ui.screens

import android.app.NotificationManager
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.MainActivity
import com.danieldk.splatevpn.SplateApplication
import com.danieldk.splatevpn.data.*
import com.danieldk.splatevpn.ui.widget.SplateAppWidgetProvider
import com.danieldk.splatevpn.vpn.*
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class AutoEntryNotificationTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun entryConnectsOnceAndNotificationContainsMeasuredLatency() = runBlocking {
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SplateApplication
        val settings = app.settingsManager
        val repository = EntryPointAccessors.fromApplication(app, SubscriptionUpdateWorker.WorkerDependencies::class.java).serverRepository()
        val database = ServerRepository::class.java.getDeclaredField("database").apply { isAccessible = true }.get(repository) as AppDatabase
        val status = EntryPointAccessors.fromApplication(app, SplateAppWidgetProvider.WidgetEntryPoint::class.java).vpnStatusManager()
        val tor = EntryPointAccessors.fromApplication(app, TorSubscriptionWorker.Dependencies::class.java).torRepository()
        val previousSettings = settings.preferenceSnapshot()
        val previousTor = tor.load()
        val selected = database.serverDao().getSelectedServerSync()?.id
        val network = requireNotNull(app.getSystemService(ConnectivityManager::class.java).activeNetwork)
        suspend fun awaitState(state: VpnState) = withTimeout(40_000) { while (status.state.value != state) delay(50) }
        fun stop() { app.startService(Intent(app, SplateVpnService::class.java).setAction("STOP")) }
        BalancerServiceFailoverTest.LocalSocksPeer(network).use { peer ->
            val node = requireNotNull(LinkParser.parse("socks5://127.0.0.1:${peer.port}")).copy(id = "entry-${UUID.randomUUID()}")
            try {
                assertEquals(VpnState.DISCONNECTED, status.state.value)
                settings.completeOnboarding("en", "ru", "global", false)
                settings.setInitializedPresetSubscriptions(true)
                settings.setInitializedSponsorSubscription()
                settings.setAutoUpdateInterval("off")
                settings.setAutoConnectOnBoot(false)
                settings.setAutoSelectServer(false)
                settings.setChecksWifiOnly(false)
                settings.setAutoConnectOnOpen(true)
                tor.save(TorSettings(enabled = false))
                database.serverDao().insertServer(node)
                database.serverDao().setActiveServer(node.id)
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    awaitState(VpnState.CONNECTED)
                    assertEquals(node.id, status.connectedServerId)
                    withTimeout(20_000) {
                        while (!status.performHealthCheck(timeoutMs = 10_000)) delay(200)
                    }
                    val notifications = app.getSystemService(NotificationManager::class.java)
                    withTimeout(10_000) {
                        while (notifications.activeNotifications.none { n ->
                            n.id == 1 && Regex("Latency: [0-9]+ ms").containsMatchIn(n.notification.extras.getCharSequence("android.text").toString())
                        }) delay(100)
                    }
                    val session = status.sessionToken
                    stop(); awaitState(VpnState.DISCONNECTED)
                    scenario.recreate()
                    delay(1_000)
                    assertEquals("Recreation must preserve the user's manual disconnect", VpnState.DISCONNECTED, status.state.value)
                    scenario.moveToState(Lifecycle.State.CREATED)
                    scenario.moveToState(Lifecycle.State.RESUMED)
                    awaitState(VpnState.CONNECTED)
                    assertNotEquals(session, status.sessionToken)
                    compose.onNodeWithTag("mainTab:SETTINGS").performSemanticsAction(SemanticsActions.OnClick) { it() }
                    compose.waitUntil(10_000) { compose.onAllNodesWithTag("settingsList").fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithTag("settingsList").performScrollToNode(hasTestTag("settingsCategory:CONNECTION"))
                    compose.onNodeWithTag("settingsCategory:CONNECTION").performSemanticsAction(SemanticsActions.OnClick) { it() }
                    compose.waitUntil(10_000) { compose.onAllNodesWithText("Network and checks").fetchSemanticsNodes().isNotEmpty() }
                    InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { image ->
                        File(app.cacheDir, "connection-network-settings.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                        image.recycle()
                    }
                    compose.onNodeWithTag("settingsList").performScrollToNode(hasTestTag("settingsAutoConnectOnOpen"))
                    compose.onNodeWithTag("settingsAutoConnectOnOpen").assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick) { it() }
                    assertFalse(settings.isAutoConnectOnOpen())
                    compose.waitUntil(5_000) {
                        compose.onNodeWithTag("settingsAutoConnectOnOpen").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ToggleableState] == androidx.compose.ui.state.ToggleableState.Off
                    }
                    compose.onNodeWithTag("settingsAutoConnectOnOpen").assertIsOff()
                    compose.onNodeWithTag("settingsList").performScrollToNode(hasText("Auto-connect on Boot"))
                    compose.onNodeWithTag("settingsAutoConnectOnOpen").assertIsDisplayed().assertIsOff()
                    InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { image ->
                        File(app.cacheDir, "connection-entry-settings.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                        image.recycle()
                    }
                    scenario.moveToState(Lifecycle.State.CREATED)
                    stop(); awaitState(VpnState.DISCONNECTED)
                    scenario.moveToState(Lifecycle.State.RESUMED)
                    delay(1_500)
                    assertEquals(VpnState.DISCONNECTED, status.state.value)
                }
            } finally {
                settings.setAutoConnectOnOpen(false)
                if (SplateVpnService.isServiceActive) { stop(); awaitState(VpnState.DISCONNECTED) }
                database.serverDao().deleteServer(node.id)
                database.serverDao().clearSelection()
                selected?.let { database.serverDao().selectServer(it) }
                tor.save(previousTor)
                settings.restorePreferenceSnapshot(previousSettings)
            }
        }
    }
}
