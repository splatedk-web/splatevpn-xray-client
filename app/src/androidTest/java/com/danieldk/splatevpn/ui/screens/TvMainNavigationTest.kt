package com.danieldk.splatevpn.ui.screens

import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.danieldk.splatevpn.MainActivity
import com.danieldk.splatevpn.SplateApplication
import com.danieldk.splatevpn.data.TvSetupPreferences
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

@OptIn(ExperimentalTestApi::class)
class TvMainNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun activate(tag: String) = compose.onNodeWithTag(tag).performScrollTo()
        .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        .assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }

    @Test fun tvHasRemoteAccessibleBalancerDnsFullSettingsSubscriptionsAndApps() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue("Requires an Android TV image", isTelevisionDevice(context))
        val settings = (context.applicationContext as SplateApplication).settingsManager
        settings.completeOnboarding("en", "ru", "global", false)
        settings.setInitializedPresetSubscriptions(true)
        settings.setAutoUpdateInterval("off")
        settings.setAutoConnectOnBoot(false)
        settings.setAutoSelectServer(false)
        TvSetupPreferences(context).finishOffer()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(30000) { compose.onAllNodesWithTag("tvMainScreen").fetchSemanticsNodes().isNotEmpty() }
            activate("tvAutoBalancer")
            compose.runOnIdle { assertTrue(settings.isAutoSelectServer()) }
            compose.onNodeWithTag("tvAutoBalancer").assertIsOn()
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(context.cacheDir, "tv-dns-qa-main.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            activate("tvAutoBalancer")
            compose.runOnIdle { assertFalse(settings.isAutoSelectServer()) }
            activate("tvNetworkSettings")
            compose.onNodeWithText("Network and DNS").assertIsDisplayed()
            compose.onNodeWithText("All settings").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
                .assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("settingsCategory:CONNECTION").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("settingsCategory:CONNECTION").assertIsFocused()
            activate("settingsCategory:CONNECTION")
            compose.waitUntil(5000) { compose.onAllNodes(hasText("All settings") and isFocused()).fetchSemanticsNodes().isNotEmpty() }
            // Network-privacy controls precede the balancer. Walk through them with the remote.
            for (step in 0 until 16) {
                if (compose.onAllNodes(hasTestTag("settingsAutoBalancer") and isFocused()).fetchSemanticsNodes().isNotEmpty()) break
                compose.onNode(isFocused(), useUnmergedTree = true).performKeyInput { pressKey(Key.DirectionDown) }
            }
            compose.onNodeWithTag("settingsAutoBalancer").assertIsFocused()
                .performKeyInput { pressKey(Key.DirectionCenter) }
            compose.runOnIdle { assertTrue(settings.isAutoSelectServer()) }
            activate("settingsAutoBalancer")
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(context.cacheDir, "tv-dns-qa-settings.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            // Sidebar buttons are outside the content list and stay reachable from every section.
            compose.onNodeWithTag("tvPage:SERVERS").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
                .performKeyInput { pressKey(Key.DirectionCenter) }
            activate("tvManageServers")
            compose.waitUntil(5000) { compose.onAllNodesWithTag("serverCategory:SUBSCRIPTIONS").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("serverCategory:SUBSCRIPTIONS").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
                .assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
            compose.waitUntil(5000) { compose.onAllNodes(hasTestTag("serverCategory:SUBSCRIPTIONS") and isSelected()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("serverCategory:SUBSCRIPTIONS").assertIsSelected()
            compose.onNodeWithTag("tvPage:APPS").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
                .performKeyInput { pressKey(Key.DirectionCenter) }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("appRoutingList").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("appRoutingList").assertIsDisplayed()
        }
    }
}
