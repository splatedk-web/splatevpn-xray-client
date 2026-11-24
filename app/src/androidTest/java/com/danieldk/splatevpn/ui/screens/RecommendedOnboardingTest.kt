package com.danieldk.splatevpn.ui.screens

import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.danieldk.splatevpn.MainActivity
import com.danieldk.splatevpn.ui.widget.SplateAppWidgetProvider
import dagger.hilt.android.EntryPointAccessors
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.data.SettingsManager
import androidx.compose.ui.graphics.asAndroidBitmap

class RecommendedOnboardingTest {
    private lateinit var settings: SettingsManager
    private var before: Map<String, *> = emptyMap<String, Any>()
    private var needed = false
    @get:Rule(order = 0) val environment = object : ExternalResource() {
        override fun before() {
            assumeTrue(Build.HARDWARE in setOf("ranchu","goldfish"))
            val app = InstrumentationRegistry.getInstrumentation().targetContext
            settings = EntryPointAccessors.fromApplication(app, SplateAppWidgetProvider.WidgetEntryPoint::class.java).settingsManager()
            before = settings.preferenceSnapshot(); needed = settings.needsOnboarding.value
            // Software-rendered emulator: test navigation without unrelated ambient graphics.
            settings.setLightUiMode(); settings.setGlassBlurEnabled(false)
        }
        override fun after() {
            if (needed) settings.showOnboarding() else settings.completeOnboarding(settings.getAppLanguage(), settings.getRoutingRegion(), settings.getRoutingMode(), settings.regionalPresetsEnabled())
            settings.restorePreferenceSnapshot(before)
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    @Test fun skippingTheWizardStillAppliesTheConfirmedRegionAndAutoSelection() {
        compose.runOnUiThread {
            val fresh = settings.preferenceSnapshot().toMutableMap()
            fresh["onboarding_version"] = 0
            fresh["routing_mode"] = "smart"
            fresh["regional_presets_enabled"] = false
            fresh.remove("auto_select_server")
            settings.restorePreferenceSnapshot(fresh)
            settings.showOnboarding()
        }
        compose.waitUntil(15000) { compose.onAllNodesWithTag("onboardingNext").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("onboardingBackOrSkip").performClick()
        compose.onNodeWithTag("onboardingRegion:cn").performScrollTo().performClick()
        compose.onNodeWithTag("onboardingNext").onChildren().filter(hasClickAction()).onFirst().performClick()
        compose.waitUntil(15000) { !settings.needsOnboarding.value }
        assertEquals("cn", settings.getRoutingRegion())
        assertEquals("smart", settings.getRoutingMode())
        assertTrue(settings.isAutoSelectServer())
    }

    @Test fun optionalAccessStepCanBeSkippedWithoutRequestingPermissions() {
        assumeTrue(Build.HARDWARE in setOf("ranchu","goldfish"))
        compose.runOnUiThread { settings.showOnboarding() }
        compose.waitUntil(15000) { compose.onAllNodesWithTag("onboardingNext").fetchSemanticsNodes().isNotEmpty() }
        repeat(4) { compose.onNodeWithTag("onboardingNext").onChildren().filter(hasClickAction()).onFirst().performClick(); compose.waitForIdle() }
        compose.onNodeWithTag("recommended:notifications").performScrollTo().assertIsDisplayed()
        compose.onRoot().captureToImage().let { image -> java.io.File(compose.activity.cacheDir,"onboarding-recommended.png").outputStream().use { image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } }
        compose.onNodeWithTag("onboardingNext").onChildren().filter(hasClickAction()).onFirst().performClick()
        compose.waitForIdle()
        compose.onAllNodesWithTag("onboardingNext").assertCountEquals(0)
        compose.onNodeWithTag("onboardingShowTour").assertIsDisplayed()
        compose.onNodeWithTag("onboardingSkipTour").performClick()
        compose.onNodeWithTag("onboardingNext").assertExists()
    }
}
