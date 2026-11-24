package com.danieldk.splatevpn.ui.screens

import android.os.Build
import android.graphics.Bitmap
import java.io.File
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import org.junit.Assert.assertEquals
import androidx.test.core.app.ActivityScenario
import androidx.lifecycle.ViewModelProvider
import com.danieldk.splatevpn.ui.MainViewModel
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performScrollToNode
import com.danieldk.splatevpn.MainActivity
import com.danieldk.splatevpn.ui.widget.SplateAppWidgetProvider
import com.danieldk.splatevpn.vpn.SplateVpnService
import dagger.hilt.android.EntryPointAccessors
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Exercises the production activity and Hilt view model without saving Tor settings or connecting. */
class MainSettingsRegressionTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun settingsRetapReturnsToCatalogAndTorSaveRemainsReachable() {
        // This integration test temporarily completes onboarding. Never run that
        // setup against a person's physical device, even with preference rollback.
        assumeTrue("This regression test requires an Android emulator", Build.HARDWARE in setOf("ranchu", "goldfish"))
        assumeFalse("Do not interrupt an active VPN", SplateVpnService.isServiceActive)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = EntryPointAccessors.fromApplication(context.applicationContext,
            SplateAppWidgetProvider.WidgetEntryPoint::class.java).settingsManager()
        val before = settings.preferenceSnapshot()
        val neededOnboarding = settings.needsOnboarding.value
        var activity: ActivityScenario<MainActivity>? = null
        try {
            settings.completeOnboarding("en", settings.getRoutingRegion(), "global", false)
            settings.setAppTheme("winter")
            settings.setGlassBlurEnabled(false)
            settings.setUiAnimationsEnabled(false)
            activity = ActivityScenario.launch(MainActivity::class.java)
            lateinit var model: MainViewModel
            activity.onActivity { model = ViewModelProvider(it)[MainViewModel::class.java] }
            compose.waitUntil(timeoutMillis = 30_000) {
                !model.isStartupLoading.value &&
                    compose.onAllNodes(hasTestTag("mainTab:SETTINGS")).fetchSemanticsNodes().isNotEmpty()
            }
            savePreview("winter-home.png")
            compose.onNodeWithTag("mainTab:SETTINGS").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasTestTag("settingsList")).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("settingsList").performScrollToKey("category:CONNECTION")
            compose.onNodeWithTag("settingsCategory:CONNECTION").performClick()
            waitForSubsection("CONNECTION")
            compose.onNodeWithText("All settings").assertIsDisplayed()

            // A second tap of the already-selected tab must leave the subsection.
            compose.onNodeWithTag("mainTab:SETTINGS").performClick()
            waitForCatalog()
            compose.onNodeWithTag("settingsList").performScrollToKey("category:TOR")
            val catalogOffset = scrollOffset()
            compose.onNodeWithTag("settingsCategory:TOR").assertIsDisplayed().performClick()
            waitForSubsection("TOR")
            compose.onNodeWithTag("settingsList").performScrollToKey("tor")
            compose.onNodeWithTag("torAdvanced").performScrollTo()
            if (compose.onAllNodesWithText("Advanced settings").fetchSemanticsNodes().isNotEmpty()) {
                // Lazy content extends under the dock; activate the control itself rather than tapping that overlay.
                compose.onNodeWithTag("torAdvanced").performSemanticsAction(SemanticsActions.OnClick) { it() }
            }
            compose.onNodeWithTag("settingsList").performScrollToNode(hasTestTag("torSave"))
            compose.onNodeWithTag("torSave").assertIsDisplayed().assertIsEnabled()
            val subsectionOffset = scrollOffset()

            // Returning from a subsection and repeatedly tapping the tab preserve the catalog position.
            compose.onNodeWithTag("mainTab:SETTINGS").performClick()
            waitForCatalog()
            assertEquals(catalogOffset, scrollOffset(), .5f)
            repeat(5) { compose.onNodeWithTag("mainTab:SETTINGS").performClick() }
            assertEquals(catalogOffset, scrollOffset(), .5f)
            compose.onNodeWithTag("settingsCategory:TOR").performClick()
            waitForSubsection("TOR")
            assertEquals(subsectionOffset, scrollOffset(), .5f)
            compose.onNodeWithTag("mainTab:SETTINGS").performClick()
            waitForCatalog()
            compose.onNodeWithTag("settingsList").performScrollToKey("category:APPEARANCE")
            val appearanceOffset = scrollOffset()
            compose.onNodeWithTag("settingsCategory:APPEARANCE").performClick()
            waitForSubsection("APPEARANCE")
            compose.onNodeWithTag("settingsList").performScrollToKey("categoryTitle")
            compose.onNodeWithText("All settings").performClick()
            assertEquals(appearanceOffset, scrollOffset(), .5f)
            savePreview("winter-settings.png")
        } catch (failure: Throwable) {
            savePreview("settings-navigation-failure.png")
            throw failure
        } finally {
            activity?.close()
            settings.restorePreferenceSnapshot(before)
            if (neededOnboarding) settings.showOnboarding()
        }
    }

    private fun scrollOffset() = compose.onNodeWithTag("settingsList").fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun savePreview(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    private fun waitForSubsection(category: String) {
        // A restored subsection may be scrolled far below its lazy header.
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("settingsList").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag("settingsCategory:$category").fetchSemanticsNodes().isEmpty()
        }
    }

    private fun waitForCatalog() {
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("settingsList")).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText("All settings").fetchSemanticsNodes().isEmpty()
        }
    }
}
