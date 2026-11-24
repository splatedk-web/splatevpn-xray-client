package com.danieldk.splatevpn.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.data.UiPerformanceSettings
import com.danieldk.splatevpn.ui.components.LiquidAmbientBackground
import com.danieldk.splatevpn.ui.theme.SplateVPNTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class OnboardingTourTest {
    @get:Rule val compose = createComposeRule()

    @Test fun guidedPracticeRequiresActionsAndCompletesWithoutNetworkOrCamera() {
        var finished = false
        compose.setContent {
            SplateVPNTheme(themeSetting = "winter", languageSetting = "ru",
                performanceSettings = UiPerformanceSettings(animationsEnabled = false)) {
                LiquidAmbientBackground {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState())) {
                        OnboardingTour(onFinished = { finished = true })
                    }
                }
            }
        }
        compose.onNodeWithTag("tour_next").performScrollTo().assertIsNotEnabled()
        savePreview("winter-training.png")
        click("tour_connect"); next()
        click("tour_server")
        compose.onNodeWithTag("tour_choose_1").performClick()
        next()
        click("tour_nav_1"); click("tour_add"); click("tour_import_link")
        compose.onNodeWithTag("tour_import_confirm").performClick()
        compose.onNodeWithTag("tour_imported").assertExists()
        next()
        click("tour_tor_top100")
        compose.waitUntil(5000) { compose.onAllNodesWithTag("tour_next").filter(isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        next()
        click("tour_auto"); click("tour_speed"); next()
        click("tour_smart"); next()
        click("tour_nav_2"); click("tour_app"); next()
        click("tour_nav_3"); click("tour_appearance"); click("tour_winter"); next()
        compose.onNodeWithText("192.0.2.24:443", useUnmergedTree = true).assertDoesNotExist()
        click("serverAddressSpoiler")
        compose.onNodeWithText("192.0.2.24:443", useUnmergedTree = true).assertExists()
        next()
        compose.runOnIdle { assertTrue(finished) }
    }

    private fun click(tag: String) { compose.onNodeWithTag(tag).performScrollTo().performClick() }
    private fun next() { compose.onNodeWithTag("tour_next").performScrollTo().assertIsEnabled().performClick() }
    private fun savePreview(name: String) {
        // Return to the beginning after testing the bottom action's initial disabled state.
        compose.onNodeWithTag("onboardingTrainer").performScrollTo()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), name).outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
