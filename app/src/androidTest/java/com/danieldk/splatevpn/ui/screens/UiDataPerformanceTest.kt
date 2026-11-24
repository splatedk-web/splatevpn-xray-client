package com.danieldk.splatevpn.ui.screens

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.MainActivity
import com.danieldk.splatevpn.SplateApplication
import com.danieldk.splatevpn.data.QuickServerSource
import com.danieldk.splatevpn.data.ServerNode
import com.danieldk.splatevpn.ui.MainViewModel
import com.danieldk.splatevpn.vpn.SplateVpnService
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class UiDataPerformanceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as SplateApplication
    private var before: Map<String, *> = emptyMap<String, Any>()
    private var onboarding = false

    @get:Rule(order = 0) val environment = object : ExternalResource() {
        override fun before() {
            assumeTrue("Use an isolated emulator", Build.HARDWARE in setOf("ranchu", "goldfish"))
            assumeFalse("Do not interrupt a VPN", SplateVpnService.isServiceActive)
            before = app.settingsManager.preferenceSnapshot()
            onboarding = app.settingsManager.needsOnboarding.value
            app.settingsManager.apply {
                setAutoUpdateInterval("off")
                completeOnboarding("en", getRoutingRegion(), "global", false)
                setLightUiMode()
                setGlassBlurEnabled(false)
            }
        }
        override fun after() {
            app.settingsManager.restorePreferenceSnapshot(before)
            if (onboarding) app.settingsManager.showOnboarding()
        }
    }
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    private fun withViewModel(block: (MainViewModel) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            lateinit var model: MainViewModel
            activity.onActivity { model = ViewModelProvider(it)[MainViewModel::class.java] }
            block(model)
        }
    }

    @Test fun repeatedAppLoadsShareOnePackageEnumeration() = withViewModel { model ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val held = object : ContextWrapper(app) {
            override fun getPackageManager(): PackageManager {
                calls.incrementAndGet()
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "Package manager test barrier timed out" }
                return super.getPackageManager()
            }
        }
        try {
            instrumentation.runOnMainSync { repeat(5) { model.loadInstalledApps(held, forceReload = true) } }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            // Allow any incorrectly duplicated IO jobs to reach the same held boundary.
            Thread.sleep(150)
            assertEquals(1, calls.get())
            assertTrue(model.isLoadingApps.value)
        } finally { release.countDown() }
        runBlocking { withTimeout(15_000) { while (model.isLoadingApps.value) delay(25) } }
        assertTrue(model.installedApps.value.isNotEmpty())
        assertFalse(model.installedApps.value.any { it.packageName == app.packageName })
        assertEquals(1, calls.get())
    }

    @Test fun quickPickerCalculationsLeaveTheMainLooperResponsive(): Unit = withViewModel { model ->
        runBlocking {
            val entered = CountDownLatch(1)
            val ranOnMain = AtomicBoolean()
            val nodes = object : AbstractList<ServerNode>() {
                override val size = 2000
                override fun get(index: Int): ServerNode {
                    if (Looper.myLooper() == Looper.getMainLooper()) ranOnMain.set(true)
                    if (index == 0) { entered.countDown(); Thread.sleep(350) }
                    return ServerNode("ui-qa-$index", "Node $index", "vless", "example.com", 443)
                }
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            try {
                val result = scope.async {
                    model.quickServerCandidates(nodes, emptyMap(), QuickServerSource.ALL,
                        System.currentTimeMillis(), emptyList()) to model.quickCandidateMeasurements(nodes)
                }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val start = System.nanoTime()
                val heartbeat = CompletableDeferred<Long>()
                Handler(Looper.getMainLooper()).post { heartbeat.complete((System.nanoTime() - start) / 1_000_000) }
                val latency = withTimeout(3000) { heartbeat.await() }
                assertTrue("Main looper delayed $latency ms", latency < 200)
                val (candidates, measured) = withTimeout(5000) { result.await() }
                assertTrue(candidates.isEmpty())
                assertTrue(measured.isEmpty())
                assertFalse(ranOnMain.get())
                android.util.Log.i("VpnResponsiveness", "quickPickerMainHeartbeatMs=$latency nodes=${nodes.size}")
            } finally { scope.cancel() }
        }
    }

    @Test fun realHomePickerStillOpensAndNavigatesToTheFullList() = withViewModel {
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("homeServerQuickPick").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("homeServerQuickPick").performScrollTo()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("quickServerList", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        } catch (error: Throwable) {
            java.io.File(app.cacheDir, "quick-picker-failed-tree.txt").writeText(compose.onAllNodes(isRoot(), useUnmergedTree = true).printToString())
            val shot = instrumentation.uiAutomation.takeScreenshot()
            java.io.File(app.cacheDir, "quick-picker-failed.png").outputStream().use {
                shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            shot.recycle()
            throw error
        }
        compose.onNodeWithText("Quick selection").assertIsDisplayed()
        compose.onNodeWithText("All servers").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("serverCategoryTabs").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("quickServerList", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("serverCategoryTabs").assertIsDisplayed()
    }
}
