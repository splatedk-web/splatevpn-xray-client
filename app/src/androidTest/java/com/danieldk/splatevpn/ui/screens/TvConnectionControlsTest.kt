package com.danieldk.splatevpn.ui.screens

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.danieldk.splatevpn.ui.theme.SplateVPNTheme
import com.danieldk.splatevpn.vpn.VpnState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
class TvConnectionControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun remoteCenterSelectsTorAndConnectionCanBeCancelled() {
        val tor = mutableStateOf(false)
        val state = mutableStateOf(VpnState.DISCONNECTED)
        var connectActions = 0
        lateinit var inputModeManager: InputModeManager
        compose.setContent {
            inputModeManager = LocalInputModeManager.current
            SplateVPNTheme(languageSetting = "en") {
                TvConnectionControls(state.value, tor.value, "Example server", 35, false,
                    { connectActions++ }, { tor.value = it })
            }
        }
        compose.runOnIdle { inputModeManager.requestInputMode(InputMode.Keyboard) }
        compose.onNodeWithTag("tvMode:VPN").assertIsSelected()
        compose.onNodeWithTag("tvMode:TOR")
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            .assertIsFocused()
            .performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.onNodeWithTag("tvMode:TOR").assertIsSelected()
        compose.onNodeWithTag("tvConnect").performClick()
        compose.runOnIdle { assertEquals(1, connectActions); state.value = VpnState.STARTING_CORE }
        compose.onNodeWithText("Connecting Tor 35%").assertIsDisplayed()
        compose.onNodeWithText("Cancel connection").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(2, connectActions) }
    }

    @Test fun changingModeDisablesRepeatedModeAndConnectActions() {
        compose.setContent {
            SplateVPNTheme(languageSetting = "en") {
                TvConnectionControls(VpnState.DISCONNECTED, false, null, 0, true, {}, {})
            }
        }
        compose.onNodeWithTag("tvMode:VPN").assertIsNotEnabled()
        compose.onNodeWithTag("tvMode:TOR").assertIsNotEnabled()
        compose.onNodeWithTag("tvConnect").assertIsNotEnabled()
    }
}
