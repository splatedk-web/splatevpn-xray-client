package com.danieldk.splatevpn.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.danieldk.splatevpn.ui.theme.SplateVPNTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ConnectionModeRowTest {
    @get:Rule val compose = createComposeRule()
    @Test fun choicesShowCurrentModeAndKeepSelectionAsADraft() {
        val draft = mutableStateOf("smart")
        var selections = 0
        compose.setContent {
            SplateVPNTheme(languageSetting = "en") {
                Box(Modifier.width(340.dp)) {
                    RoutingModeOptions(draft.value, "smart", true) { draft.value = it; selections++ }
                }
            }
        }
        compose.onNodeWithTag("routingChoice:smart").assertIsSelected()
        compose.onNodeWithTag("routingChoice:global").assertIsNotSelected().performClick()
        compose.onNodeWithTag("routingChoice:global").assertIsSelected()
        compose.onNodeWithTag("routingChoice:smart").assertIsNotSelected()
        compose.onNodeWithText("Current mode").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, selections); assertEquals("global", draft.value) }
    }
    @Test fun modeFitsOneCompactRowAndOpensSettingsOnTap() {
        var clicks = 0
        compose.setContent {
            SplateVPNTheme(languageSetting = "en") {
                Box(Modifier.width(340.dp)) { ConnectionModeRow(true, false, true) { clicks++ } }
            }
        }
        compose.onNodeWithTag("connectionMode").assertHeightIsEqualTo(56.dp).performClick()
        compose.onNodeWithText("Smart routing").assertIsDisplayed()
        compose.onNodeWithText("Change").assertIsDisplayed()
        compose.onNodeWithText("DNS:", substring = true).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test fun largeRussianFontKeepsTheActionVisible() {
        compose.setContent {
            SplateVPNTheme(languageSetting = "ru") {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                    Box(Modifier.width(300.dp)) { ConnectionModeRow(true, false, true) {} }
                }
            }
        }
        compose.onNodeWithText("Изменить").assertIsDisplayed()
        compose.onNodeWithTag("connectionMode").assertHeightIsAtLeast(56.dp)
    }
}
