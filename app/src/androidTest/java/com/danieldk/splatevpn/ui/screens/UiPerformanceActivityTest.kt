package com.danieldk.splatevpn.ui.screens

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.MainActivity
import com.danieldk.splatevpn.data.UiPerformanceSettings
import com.danieldk.splatevpn.ui.widget.SplateAppWidgetProvider
import com.danieldk.splatevpn.vpn.SplateVpnService
import com.danieldk.splatevpn.vpn.VpnState
import dagger.hilt.android.EntryPointAccessors
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Exercises the actual activity's recomposer, Hilt state and recreation, without connecting. */
class UiPerformanceActivityTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun lightweightUiCanBeReenabledAndSurvivesActivityRecreation() {
        assumeTrue("Only use an isolated Android emulator", Build.HARDWARE in setOf("ranchu", "goldfish"))
        assumeFalse("Do not interrupt an active VPN", SplateVpnService.isServiceActive)
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val dependencies = EntryPointAccessors.fromApplication(app.applicationContext,
            SplateAppWidgetProvider.WidgetEntryPoint::class.java)
        val settings = dependencies.settingsManager()
        assumeTrue("Do not interrupt an in-progress connection", dependencies.vpnStatusManager().state.value in
            setOf(VpnState.DISCONNECTED, VpnState.FAILED))
        val before = settings.preferenceSnapshot()
        val neededOnboarding = settings.needsOnboarding.value
        val systemScale = shell("settings get global animator_duration_scale").trim().toFloatOrNull()
        // The production recomposer obeys Android's clock. Use Android's standard reduced-motion
        // setting for deterministic Espresso idling while toggling the app's own preferences.
        shell("settings put global animator_duration_scale 0")
        var activity: ActivityScenario<MainActivity>? = null
        try {
            // Avoid subscription requests while exercising appearance on this emulator.
            settings.setAutoUpdateInterval("off")
            settings.completeOnboarding("en", settings.getRoutingRegion(), "global", false)
            settings.setAppTheme("dark")
            settings.setLightUiMode()
            activity = ActivityScenario.launch(MainActivity::class.java)
            waitForDock()
            openAppearance()
            assertLightModeControls()

            clickAboveDock("performance:restoreEffects")
            compose.waitUntil(10_000) {
                settings.performanceSettings.value == UiPerformanceSettings() && settings.glassBlurEnabled.value
            }
            waitForPerformanceControls()
            compose.onNodeWithTag("performance:animations").performScrollTo().assertIsOn()
            compose.onNodeWithTag("performance:background").performScrollTo().assertIsOn()
            compose.onNodeWithTag("performance:highlights").performScrollTo().assertIsOn()
            compose.onNodeWithTag("performance:lightMode").assertIsEnabled()
            clickAboveDock("performance:lightMode")
            compose.waitUntil(10_000) {
                settings.performanceSettings.value == UiPerformanceSettings(false, false, false) && !settings.glassBlurEnabled.value
            }

            activity.recreate()
            waitForDock()
            openAppearance()
            assertLightModeControls()
            compose.onNodeWithTag("performanceSettings").performScrollTo()
            val screenshot = compose.onRoot().captureToImage().asAndroidBitmap()
            File(app.getExternalFilesDir(null) ?: app.filesDir, "ui-performance-appearance-qa.png").outputStream().use {
                check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            compose.onNodeWithTag("mainTab:SERVERS").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasTestTag("mainTab:SERVERS") and isSelected()).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(UiPerformanceSettings(false, false, false), settings.performanceSettings.value)
            assertFalse(settings.glassBlurEnabled.value)
        } catch (error: Throwable) {
            val directory = app.getExternalFilesDir(null) ?: app.filesDir
            runCatching { File(directory, "ui-performance-failed-tree.txt").writeText(compose.onRoot().printToString()) }
            runCatching {
                val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                File(directory, "ui-performance-failed.png").outputStream().use {
                    screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                screenshot.recycle()
            }
            throw error
        } finally {
            activity?.close()
            if (systemScale == null) shell("settings delete global animator_duration_scale")
            else shell("settings put global animator_duration_scale $systemScale")
            settings.restorePreferenceSnapshot(before)
            if (neededOnboarding) settings.showOnboarding()
        }
    }

    private fun waitForDock() {
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasTestTag("mainTab:SETTINGS")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun openAppearance() {
        // Retapping Settings opens its catalog even when recreation restored a subsection.
        compose.onNodeWithTag("mainTab:SETTINGS").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("settingsCategory:CONNECTION")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("settingsList").performScrollToKey("category:APPEARANCE")
        clickAboveDock("settingsCategory:APPEARANCE")
        // Appearance restores its scroll position after recreation, so its page title can
        // legitimately be outside the lazy viewport. Wait for its actual controls instead.
        waitForPerformanceControls()
        compose.onNodeWithTag("settingsList").performScrollToKey("appearance:0")
        waitForPerformanceControls()
    }

    private fun waitForPerformanceControls() {
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("performance:animations")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun assertLightModeControls() {
        compose.onNodeWithTag("performance:animations").performScrollTo().assertIsOff()
        compose.onNodeWithTag("performance:background").performScrollTo().assertIsOff()
        compose.onNodeWithTag("performance:highlights").performScrollTo().assertIsOff()
        compose.onNodeWithTag("performance:lightMode").performScrollTo().assertIsNotEnabled()
    }

    private fun clickAboveDock(tag: String) {
        val target = compose.onNodeWithTag(tag)
        target.performScrollTo()
        // The list deliberately extends under the floating dock. ScrollTo only knows the
        // list viewport, so lift an action above the dock before sending a real touch.
        val dockTop = compose.onNodeWithTag("mainTab:SETTINGS").fetchSemanticsNode().boundsInRoot.top
        val required = target.fetchSemanticsNode().boundsInRoot.bottom - dockTop + 32f
        if (required > 0f) compose.onNodeWithTag("settingsList")
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, required) }
        compose.waitUntil(10_000) { target.fetchSemanticsNode().boundsInRoot.bottom < dockTop }
        target.performClick()
    }

    private fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }
}
