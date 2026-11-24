package com.danieldk.splatevpn.ui.screens

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.danieldk.splatevpn.ui.theme.SplateVPNTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class TvTransferOfferTest {
    @get:Rule val compose = createComposeRule()

    @Test fun remoteCanChooseLaterWithoutStartingAReceiver() {
        val visible = mutableStateOf(true)
        var receiving = 0
        var skipped = 0
        compose.setContent {
            SplateVPNTheme(languageSetting = "en") {
                if (visible.value) TvTransferOfferDialog(false, null,
                    onReceive = { receiving++; visible.value = false },
                    onLater = { skipped++; visible.value = false })
            }
        }
        compose.onNodeWithTag("tvTransferOffer:receive").assertIsFocused()
        compose.onNodeWithTag("tvTransferOffer:later").performScrollTo()
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            .assertIsFocused()
            .performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.onNodeWithTag("tvTransferOffer").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, receiving); assertEquals(1, skipped) }
    }

    @Test fun receiveActionIsExplicitAndNotTriggeredByShowingTheOffer() {
        var receiving = 0
        var skipped = 0
        compose.setContent {
            SplateVPNTheme(languageSetting = "en") {
                TvTransferOfferDialog(false, null, onReceive = { receiving++ }, onLater = { skipped++ })
            }
        }
        compose.runOnIdle { assertEquals(0, receiving); assertEquals(0, skipped) }
        compose.onNodeWithTag("tvTransferOffer:receive").performClick()
        compose.runOnIdle { assertEquals(1, receiving); assertEquals(0, skipped) }
    }
}
