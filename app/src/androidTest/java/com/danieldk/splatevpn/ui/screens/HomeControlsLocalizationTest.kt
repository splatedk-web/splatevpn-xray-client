package com.danieldk.splatevpn.ui.screens

import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.MainActivity
import com.danieldk.splatevpn.data.*
import com.danieldk.splatevpn.ui.MainViewModel
import com.danieldk.splatevpn.ui.widget.SplateAppWidgetProvider
import com.danieldk.splatevpn.vpn.SplateVpnService
import com.danieldk.splatevpn.vpn.VpnState
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HomeControlsLocalizationTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun foldersRefreshAndPingWhileStatusFollowsTheSelectedLanguage() = runBlocking {
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        assumeFalse(SplateVpnService.isServiceActive)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val dependencies = EntryPointAccessors.fromApplication(app, SplateAppWidgetProvider.WidgetEntryPoint::class.java)
        val settings = dependencies.settingsManager()
        val status = dependencies.vpnStatusManager()
        val repository = EntryPointAccessors.fromApplication(app, SubscriptionUpdateWorker.WorkerDependencies::class.java).serverRepository()
        val database = ServerRepository::class.java.getDeclaredField("database").apply { isAccessible = true }.get(repository) as AppDatabase
        val before = settings.preferenceSnapshot()
        val selectedBefore = database.serverDao().getSelectedServerSync()?.id
        val neededOnboarding = settings.needsOnboarding.value
        val subId = "home-qa-${UUID.randomUUID()}"
        val tcp = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val tcpWorker = Executors.newSingleThreadExecutor()
        tcpWorker.submit {
            while (!tcp.isClosed) runCatching { tcp.accept().close() }
        }
        val personal = requireNotNull(LinkParser.parse("socks5://127.0.0.1:${tcp.localPort}#QA%20personal")).copy(ping = 42)
        val subscribed = personal.copy(id = UUID.randomUUID().toString(), name = "QA old", subId = subId)
        val http = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val worker = Executors.newSingleThreadExecutor()
        val allowRefreshResponse = CountDownLatch(1)
        worker.submit {
            while (!http.isClosed) runCatching {
                http.accept().use { socket ->
                    socket.soTimeout = 3_000
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    check(allowRefreshResponse.await(10, TimeUnit.SECONDS))
                    val body = "socks5://127.0.0.1:1#Updated%20fixture".toByteArray()
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray() + body)
                }
            }
        }
        try {
            settings.completeOnboarding("en", settings.getRoutingRegion(), "global", false)
            settings.setInitializedPresetSubscriptions(true)
            settings.setInitializedSponsorSubscription()
            settings.setAutoUpdateInterval("off")
            settings.setAutoConnectOnOpen(false)
            settings.setAutoSelectServer(false)
            settings.setChecksWifiOnly(false)
            settings.setSourceDownloadsViaVpn(false)
            settings.setPingMode("tcp")
            settings.setUiMode(AppUiMode.SIMPLE)
            settings.setLightUiMode()
            database.subscriptionDao().insertSubscription(SubscriptionEntity(subId, "QA subscription", "http://127.0.0.1:${http.localPort}/list"))
            database.serverDao().insertServers(listOf(personal, subscribed))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var model: MainViewModel
                scenario.onActivity { model = ViewModelProvider(it)[MainViewModel::class.java]; model.selectTab(AppTab.HOME) }
                compose.waitUntil(30_000) { compose.onAllNodesWithTag("simpleHomeServerList").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("homeSwitchToPro").assertDoesNotExist()
                compose.onNodeWithTag("simpleFolderRefresh:sub:$subId").performScrollTo().performClick()
                compose.waitUntil(5_000) { model.updatingSubscriptionIds.value == setOf(subId) }
                allowRefreshResponse.countDown()
                compose.waitUntil(15_000) {
                    runBlocking { database.serverDao().getServersBySubIdSync(subId).any { it.name == "Updated fixture" } }
                }
                compose.onNodeWithTag("simpleFolderPing:__personal__").performScrollTo().performClick()
                compose.waitUntil(15_000) {
                    runBlocking { (database.serverDao().getServerById(personal.id)?.ping ?: -1L) > 0 }
                }
                compose.runOnIdle { model.setPingMode("real_delay") }
                compose.waitUntil(10_000) { !model.isPinging.value }
                assertEquals(-1L, repository.testRealDelay(personal, timeoutMs = 1_000))
                assertEquals(-1L, database.serverDao().getServerById(personal.id)?.ping)
                compose.runOnIdle { model.cancelPingChecks() }
                compose.waitUntil(10_000) { !model.isPinging.value }
                val unrelatedDisabledId = "unrelated-disabled-fixture"
                compose.runOnIdle {
                    settings.setUiMode(AppUiMode.PRO)
                    settings.setServersEnabled(setOf(unrelatedDisabledId), false)
                    model.setPersonalFolderEnabled(false)
                    settings.setUiMode(AppUiMode.SIMPLE)
                }
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithTag("simpleFolder:__personal__", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
                }
                assertTrue(unrelatedDisabledId in settings.getDisabledServerIds())
                compose.runOnIdle { model.setPersonalFolderEnabled(true) }
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithTag("simpleFolder:__personal__", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                }
                assertTrue(unrelatedDisabledId in settings.getDisabledServerIds())
                compose.runOnIdle { settings.setUiMode(AppUiMode.PRO); status.setState(VpnState.CONNECTED) }
                val timer = SemanticsMatcher("session timer") { node ->
                    node.config.getOrNull(SemanticsProperties.Text).orEmpty().any {
                        Regex("\\d{2}:\\d{2}(?::\\d{2})?").matches(it.text)
                    }
                }
                compose.waitUntil(10_000) {
                    compose.onAllNodes(timer).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNode(timer).assertIsDisplayed()
                assertEquals(1, compose.onAllNodesWithText("connected", substring = true, ignoreCase = true).fetchSemanticsNodes().size)
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(app.cacheDir, "home-timer-only.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                compose.runOnIdle { status.setState(VpnState.RECONNECTING, "Сервера не найдены. Поиск…") }
                compose.waitUntil(10_000) { compose.onAllNodesWithText("No servers found. Searching…").fetchSemanticsNodes().isNotEmpty() }
                compose.runOnIdle { settings.setAppLanguage("zh-Hans") }
                compose.waitUntil(10_000) { compose.onAllNodesWithText("未找到服务器，正在搜索…").fetchSemanticsNodes().isNotEmpty() }
                compose.runOnIdle { settings.setAppLanguage("fa") }
                compose.waitUntil(10_000) { compose.onAllNodesWithText("سروری پیدا نشد. در حال جستجو…").fetchSemanticsNodes().isNotEmpty() }

                compose.runOnIdle {
                    status.setState(VpnState.DISCONNECTED)
                    settings.setAppLanguage("en")
                    settings.setUiMode(AppUiMode.SIMPLE)
                }
                compose.waitUntil(10_000) { compose.onAllNodesWithText("Personal servers").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Personal servers").performScrollTo().performClick()
                compose.onNodeWithTag("simpleServerItem:${personal.id}").performScrollTo().performClick()
                compose.waitUntil(10_000) { model.selectedServer.value?.id == personal.id }
                compose.onNodeWithTag("simpleServerDelete:${personal.id}").performClick()
                compose.onNodeWithText("Delete server?").assertIsDisplayed()
                compose.onNodeWithText("Delete").performClick()
                compose.waitUntil(10_000) {
                    runBlocking { database.serverDao().getServerById(personal.id) == null }
                }
            }
        } finally {
            status.setState(VpnState.DISCONNECTED)
            allowRefreshResponse.countDown()
            http.close()
            worker.shutdownNow()
            tcp.close()
            tcpWorker.shutdownNow()
            database.serverDao().deleteServer(personal.id)
            if (selectedBefore != null) database.serverDao().setActiveServer(selectedBefore)
            database.serverDao().deleteServersBySubId(subId)
            database.subscriptionDao().deleteUpdateHistory(subId)
            database.subscriptionDao().deleteSubscription(subId)
            settings.restorePreferenceSnapshot(before)
            if (neededOnboarding) settings.showOnboarding()
        }
    }

    @Test fun builtinSubscriptionLabelsFollowTheLocaleAndCustomNamesAreKept() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        for (language in listOf("en", "ru", "zh-Hans", "fa")) {
            val resources = com.danieldk.splatevpn.ui.theme.localizedResources(app,
                com.danieldk.splatevpn.ui.theme.appLocale(language, java.util.Locale.ENGLISH))
            val strings = when (language) {
                "en" -> com.danieldk.splatevpn.ui.theme.EnglishAppStrings()
                "ru" -> com.danieldk.splatevpn.ui.theme.RussianAppStrings()
                else -> com.danieldk.splatevpn.ui.theme.ResourceAppStrings(resources)
            }
            val name = subscriptionDisplayName(com.danieldk.splatevpn.SplateConstants.PRESET_SUB_LTE_ID,
                com.danieldk.splatevpn.SplateConstants.PRESET_SUB_LTE_NAME, strings)
            if (language != "ru") assertFalse(name.contains("Белый"))
            assertEquals("My custom name", subscriptionDisplayName(
                com.danieldk.splatevpn.SplateConstants.PRESET_SUB_LTE_ID, "My custom name", strings))
        }
    }

    @Test fun updateDialogShowsTranslationAndCanSwitchBackToOriginal() {
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        assumeFalse(SplateVpnService.isServiceActive)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val settings = EntryPointAccessors.fromApplication(app,
            SplateAppWidgetProvider.WidgetEntryPoint::class.java).settingsManager()
        val before = settings.preferenceSnapshot()
        val neededOnboarding = settings.needsOnboarding.value
        try {
            settings.completeOnboarding("en", settings.getRoutingRegion(), "global", false)
            settings.setAutoConnectOnOpen(false)
            settings.setAutoUpdateInterval("off")
            settings.setLightUiMode()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val model = ViewModelProvider(activity)[MainViewModel::class.java]
                    activity.setContent {
                        com.danieldk.splatevpn.ui.theme.SplateVPNTheme(themeSetting = "dark", languageSetting = "en") {
                            UpdateDialog(model, UpdateInfo("9.9.9", "Исправлены ошибки подключения.",
                                "https://example.com/app.apk", "https://example.com/release"), {})
                        }
                    }
                }
                compose.waitUntil(30_000) {
                    compose.onAllNodesWithText("Automatic translation").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText("Show original").performClick()
                compose.onNodeWithText("Исправлены ошибки подключения.").assertIsDisplayed()
                compose.onNodeWithText("Show translation").performClick()
                compose.onNodeWithText("Исправлены ошибки подключения.").assertDoesNotExist()
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(app.cacheDir, "release-notes-translated.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
        } finally {
            settings.restorePreferenceSnapshot(before)
            if (neededOnboarding) settings.showOnboarding()
        }
    }
}
