package com.danieldk.splatevpn.ui.screens

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.MainActivity
import com.danieldk.splatevpn.SplateApplication
import com.danieldk.splatevpn.ui.MainViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ServerModeBrowsingTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun browsingTorAndReturningToSubscriptionsPreservesConnectionAndUpdatePolicy() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = (context.applicationContext as SplateApplication).settingsManager
        settings.completeOnboarding("en", "ru", "global", false)
        settings.setAutoUpdateInterval("off")
        val previousPolicy = settings.isSourceDownloadsViaVpn()
        settings.setSourceDownloadsViaVpn(true)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var model: MainViewModel
                scenario.onActivity {
                    model = ViewModelProvider(it)[MainViewModel::class.java]
                    model.browseServerMode(false)
                    model.selectTab(AppTab.SERVERS)
                }
                compose.waitUntil(10000) { compose.onAllNodesWithTag("serverCategoryTabs").fetchSemanticsNodes().isNotEmpty() }
                val originalMode = model.torSettings.value.enabled
                val originalState = model.vpnState.value
                repeat(3) {
                    compose.onNodeWithTag("connectionMode:TOR").performClick()
                    compose.waitUntil(5000) { compose.onAllNodesWithTag("torConnect").fetchSemanticsNodes().isNotEmpty() }
                    assertEquals(originalMode, model.torSettings.value.enabled)
                    assertEquals(originalState, model.vpnState.value)
                    compose.onNodeWithTag("connectionMode:VPN").performClick()
                    compose.waitUntil(5000) { compose.onAllNodesWithTag("serverCategoryTabs").fetchSemanticsNodes().isNotEmpty() }
                    assertEquals(originalMode, model.torSettings.value.enabled)
                    assertTrue(settings.isSourceDownloadsViaVpn())
                }
            }
        } finally { settings.setSourceDownloadsViaVpn(previousPolicy) }
    }
}
