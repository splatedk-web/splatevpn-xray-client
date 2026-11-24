package com.danieldk.splatevpn.ui.components

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.danieldk.splatevpn.ui.theme.SplateVPNTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TorModeSelectorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun serversModeSwitchIsPersistentSelectionWithSemanticActions() {
        val selected = mutableStateOf(false)
        val visits = mutableListOf<Boolean>()
        compose.setContent { SplateVPNTheme(languageSetting = "en") {
            TorModeSelector(selected.value, false) { selected.value = it; visits += it }
        } }
        compose.onNodeWithTag("connectionMode:TOR").performClick().assertIsSelected()
        compose.onNodeWithTag("connectionMode:VPN").performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(listOf(true, false), visits) }
    }

    @Test fun modeChangesCannotBeQueuedWhileSaving() {
        compose.setContent { SplateVPNTheme(languageSetting = "en") { TorModeSelector(true, true) {} } }
        compose.onNodeWithTag("connectionMode:TOR").assertIsSelected().assertIsNotEnabled()
        compose.onNodeWithTag("connectionMode:VPN").assertIsNotEnabled()
    }
}
