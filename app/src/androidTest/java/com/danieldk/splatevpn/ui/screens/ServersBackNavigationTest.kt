package com.danieldk.splatevpn.ui.screens

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.MainActivity
import com.danieldk.splatevpn.SplateApplication
import com.danieldk.splatevpn.data.ServersListPreferences
import com.danieldk.splatevpn.ui.MainViewModel
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test

class ServersBackNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun backReturnsFromCategoryToNodesAndThenHome() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeFalse(isTelevisionDevice(context))
        val settings = (context.applicationContext as SplateApplication).settingsManager
        val previousList = settings.getServersListPreferences()
        settings.completeOnboarding("en", "ru", "global", false)
        settings.setInitializedPresetSubscriptions(true)
        settings.setAutoUpdateInterval("off")
        settings.setServersListPreferences(ServersListPreferences())
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity {
                    val model = ViewModelProvider(it)[MainViewModel::class.java]
                    model.browseServerMode(false)
                    model.selectTab(AppTab.SERVERS)
                }
                compose.waitUntil(10000) { compose.onAllNodesWithTag("serverCategoryTabs").fetchSemanticsNodes().isNotEmpty() }
                compose.waitUntil(10000) {
                    compose.onAllNodes(hasTestTag("mainTab:SERVERS") and isSelected()).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithTag("mainTab:SERVERS").assertIsDisplayed()
                compose.onNodeWithTag("serversNavigateBack").assertDoesNotExist()
                compose.onNodeWithTag("serverCategory:PERSONAL").performClick()
                compose.onNodeWithTag("serverCategory:PERSONAL").assertIsSelected()
                compose.onNodeWithTag("serversNavigateBack").assertDoesNotExist()
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                compose.waitUntil(5000) {
                    compose.onAllNodes(hasTestTag("serverCategory:ALL") and isSelected()).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithTag("serverCategory:ALL").assertIsSelected()
                compose.onNodeWithTag("serversNavigateBack").assertDoesNotExist()
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                compose.waitUntil(5000) { compose.onAllNodesWithTag("homeServerQuickPick").fetchSemanticsNodes().isNotEmpty() }
                scenario.onActivity { assertFalse(it.isFinishing) }
            }
        } finally {
            settings.setServersListPreferences(previousList)
        }
    }
}
