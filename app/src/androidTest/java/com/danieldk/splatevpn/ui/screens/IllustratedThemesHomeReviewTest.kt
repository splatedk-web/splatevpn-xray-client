package com.danieldk.splatevpn.ui.screens

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.MainActivity
import com.danieldk.splatevpn.SplateApplication
import com.danieldk.splatevpn.ui.MainViewModel
import com.danieldk.splatevpn.vpn.SplateVpnService
import java.io.File
import org.junit.Assume.*
import org.junit.Rule
import org.junit.Test

/** Review the complete production UI and preserve the emulator's original settings. */
class IllustratedThemesHomeReviewTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun fullHomeRemainsUsableInBothWinterModesAndOcean() {
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        assumeFalse(SplateVpnService.isServiceActive)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val settings = (context.applicationContext as SplateApplication).settingsManager
        val before = settings.preferenceSnapshot()
        val neededOnboarding = settings.needsOnboarding.value
        try {
            settings.completeOnboarding("ru", settings.getRoutingRegion(), "global", false)
            settings.restoreFullUiEffects()
            settings.setGlassBlurEnabled(false)
            settings.setGlassTransparency(.95f)
            settings.setAutoSelectServer(false)
            settings.setAutoConnectOnBoot(false)
            settings.setAutoUpdateInterval("off")
            settings.setAppTheme("winter")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var model: MainViewModel
                scenario.onActivity { model = ViewModelProvider(it)[MainViewModel::class.java]; model.selectTab(AppTab.HOME) }
                compose.waitUntil(30000) { compose.onAllNodesWithTag("mainTab:HOME").fetchSemanticsNodes().isNotEmpty() }
                compose.mainClock.autoAdvance = false
                val requested = InstrumentationRegistry.getArguments().getString("reviewThemes")?.split(",")
                listOf("cyberpunk", "winter", "winter_light", "ocean").filter { requested == null || it in requested }.forEach { theme ->
                    compose.runOnIdle { settings.setAppTheme(theme) }
                    val artworkTag = when (theme) {
                        "cyberpunk" -> "themeLayerScene"
                        "ocean" -> "themeLayerScene"
                        "winter" -> "winterLayerScene:dark"
                        else -> "winterLayerScene:light"
                    }
                    compose.waitUntil(20000) {
                        compose.mainClock.advanceTimeByFrame()
                        compose.onAllNodesWithTag(artworkTag, useUnmergedTree = true)
                            .fetchSemanticsNodes().isNotEmpty()
                    }
                    compose.mainClock.advanceTimeBy(1200)
                    compose.onNodeWithTag("homeServerQuickPick").assertIsDisplayed()
                    compose.onNodeWithTag("mainTab:SETTINGS").assertIsDisplayed().assertIsEnabled()
                    instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                        File(context.cacheDir, "glass-home-$theme.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                    }
                    compose.runOnIdle { model.selectTab(AppTab.SERVERS) }
                    compose.waitUntil(10000) {
                        compose.mainClock.advanceTimeBy(32)
                        compose.onAllNodesWithTag("serverCategoryTabs").fetchSemanticsNodes().isNotEmpty() &&
                            compose.onNodeWithTag("serverCategoryTabs").isDisplayed()
                    }
                    compose.mainClock.advanceTimeBy(400)
                    compose.onNodeWithTag("serverCategoryTabs").assertIsDisplayed()
                    instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                        File(context.cacheDir, "glass-servers-$theme.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                    }
                    compose.runOnIdle { model.selectTab(AppTab.HOME) }
                    compose.waitUntil(10000) {
                        compose.mainClock.advanceTimeBy(32)
                        compose.onAllNodesWithTag("homeServerQuickPick").fetchSemanticsNodes().isNotEmpty() &&
                            compose.onNodeWithTag("homeServerQuickPick").isDisplayed()
                    }
                    compose.mainClock.advanceTimeBy(400)
                }
                compose.mainClock.autoAdvance = true
                compose.runOnIdle { model.selectTab(AppTab.SETTINGS) }
                compose.waitUntil(10000) { compose.mainClock.advanceTimeBy(32); compose.onAllNodesWithTag("settingsList").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("settingsList").performScrollToNode(hasTestTag("settingsCategory:ABOUT"))
                compose.onNodeWithTag("settingsCategory:ABOUT").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
                compose.mainClock.advanceTimeBy(400)
                compose.waitForIdle()
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(context.cacheDir, "support-project.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                compose.onRoot().printToLog("DonationQA")
                compose.onNodeWithTag("settingsList").performScrollToNode(hasTestTag("settingsDonate"))
                compose.onNodeWithTag("settingsDonate").assertIsDisplayed().assertIsEnabled()
            }
        } finally {
            settings.restorePreferenceSnapshot(before)
            if (neededOnboarding) settings.showOnboarding()
        }
    }
}
