package com.danieldk.splatevpn.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.danieldk.splatevpn.data.BalancerMode
import com.danieldk.splatevpn.data.BalancerPreferences
import com.danieldk.splatevpn.ui.components.LocalTvControls
import com.danieldk.splatevpn.ui.theme.SplateVPNTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
class TvRemoteControlsTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var input: InputModeManager
    private fun setContent(tv: Boolean = true, content: @Composable () -> Unit) {
        compose.setContent {
            input = LocalInputModeManager.current
            SplateVPNTheme(languageSetting = "en") {
                CompositionLocalProvider(LocalTvControls provides tv) { Surface { content() } }
            }
        }
        compose.runOnIdle { input.requestInputMode(InputMode.Keyboard) }
    }
    private fun focus(tag: String) = compose.onNodeWithTag(tag)
        .performSemanticsAction(SemanticsActions.RequestFocus) { it() }.assertIsFocused()

    @Test fun remoteCanToggleBalancerAndChangeItsModeWithoutTouch() {
        val enabled = mutableStateOf(false)
        val preferences = mutableStateOf(BalancerPreferences())
        setContent { TvBalancerControls(enabled.value, preferences.value, { enabled.value = it }, { preferences.value = it }) }
        focus("tvAutoBalancer").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertTrue(enabled.value) }
        compose.onNodeWithText("Balancer settings").performClick()
        compose.onNodeWithText("On failure only").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(BalancerMode.FAILOVER, preferences.value.mode) }
        focus("tvAutoBalancer").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertFalse(enabled.value) }
    }

    @Test fun focusMovesWithArrowsAndIsVisuallyDistinctFromSelection() {
        setContent { Column(Modifier.padding(8.dp)) {
            TvActionButton("Selected", {}, selected = true, tag = "first")
            TvActionButton("Other", {}, tag = "second")
        } }
        focus("second")
        val idle = compose.onNodeWithTag("first").captureToImage().toPixelMap()
        focus("first")
        val focused = compose.onNodeWithTag("first").captureToImage().toPixelMap()
        val x = focused.width / 2
        // The focused outline must change the selected button's actual painted border.
        assertNotEquals(idle[x, 2], focused[x, 2])
        compose.onNodeWithTag("first").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithTag("second").assertIsFocused()
    }

    @Test fun phoneControlsDoNotAcquireTheTvOutline() {
        setContent(tv = false) { Column {
            TvBalancerControls(false, BalancerPreferences(), {}, {})
            TvActionButton("Other", {}, tag = "other")
        } }
        focus("other")
        focus("tvAutoBalancer")
        val focused = compose.onNodeWithTag("tvAutoBalancer").captureToImage().toPixelMap()
        assertTrue("Phone focus must not gain the TV's high-contrast outline", focused[focused.width / 2, 2].luminance() < 0.6f)
    }
}
