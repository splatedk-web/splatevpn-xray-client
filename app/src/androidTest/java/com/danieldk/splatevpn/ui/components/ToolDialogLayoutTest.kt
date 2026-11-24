package com.danieldk.splatevpn.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.danieldk.splatevpn.ui.theme.SplateVPNTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ToolDialogLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun shortPanelDoesNotPushItsCloseButtonToTheBottomOfTheScreen() {
        compose.setContent {
            SplateVPNTheme(languageSetting = "en") {
                ToolDialog({}, Modifier.testTag("toolPanel")) {
                    Text("Connection tools")
                    Spacer(Modifier.height(12.dp))
                    SplateButton({}, Modifier.testTag("toolClose")) { Text("Close") }
                }
            }
        }
        compose.onNodeWithTag("toolClose").assertIsDisplayed()
        val viewport = compose.onNodeWithTag("toolDialogViewport").fetchSemanticsNode().boundsInRoot
        val panel = compose.onNodeWithTag("toolPanel").fetchSemanticsNode().boundsInRoot
        val close = compose.onNodeWithTag("toolClose").fetchSemanticsNode().boundsInRoot
        assertTrue("Short tool content must wrap rather than fill the screen", panel.height < viewport.height * .5f)
        assertTrue("Close action should stay with the short content", close.bottom < viewport.bottom - viewport.height * .15f)
    }

    @Test fun longContentScrollsWithoutMovingTheFooterOutOfReachAtLargeFontScale() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                SplateVPNTheme(languageSetting = "en") {
                    ToolDialog({}, Modifier.testTag("toolPanel")) {
                        Text("Connection tools")
                        Column(Modifier.weight(1f, fill = false).fillMaxWidth()
                            .verticalScroll(rememberScrollState())) {
                            repeat(60) { index -> Text("Connection $index", Modifier.testTag("toolLine:$index")) }
                        }
                        SplateButton({}, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("toolClose")) {
                            Text("Close")
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("toolClose").assertIsDisplayed()
        compose.onNodeWithTag("toolLine:59").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("toolClose").assertIsDisplayed()
        val viewport = compose.onNodeWithTag("toolDialogViewport").fetchSemanticsNode().boundsInRoot
        val panel = compose.onNodeWithTag("toolPanel").fetchSemanticsNode().boundsInRoot
        val close = compose.onNodeWithTag("toolClose").fetchSemanticsNode().boundsInRoot
        assertTrue(panel.top >= viewport.top && panel.bottom <= viewport.bottom)
        assertTrue("Footer should keep an inset from the screen edge", close.bottom < viewport.bottom)
    }
}
