package com.danieldk.splatevpn.ui.screens

import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.danieldk.splatevpn.MainActivity
import com.danieldk.splatevpn.SplateApplication
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import java.io.File

class QuickServerHomeNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun homeCardOpensPickerAndFullListRemainsAvailable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeFalse(isTelevisionDevice(context))
        val settings = (context.applicationContext as SplateApplication).settingsManager
        settings.completeOnboarding("en", "ru", "global", false)
        settings.setInitializedPresetSubscriptions(true)
        settings.setAutoUpdateInterval("off")
        settings.setAutoConnectOnBoot(false)
        settings.setAutoSelectServer(false)
        settings.setUiMode(com.danieldk.splatevpn.data.AppUiMode.PRO)
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(30000) { compose.onAllNodesWithTag("homeServerQuickPick").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("homeServerQuickPick").performScrollTo()
                .performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitUntil(10000) {
                compose.onAllNodesWithTag("quickServerList", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Quick selection").assertIsDisplayed()
            instrumentation.uiAutomation.waitForIdle(400, 5000)
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(context.cacheDir, "quick-server-picker.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            compose.onNodeWithText("All servers").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("serverCategoryTabs").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("quickServerList").assertDoesNotExist()
            compose.onNodeWithTag("serverCategoryTabs").assertIsDisplayed()
        }
    }
}
