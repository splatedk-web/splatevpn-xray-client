package com.danieldk.splatevpn.ui.screens

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.ui.components.LocalGlassTransparency
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.danieldk.splatevpn.ui.theme.LocalAppStrings
import com.danieldk.splatevpn.ui.theme.LocalAppThemeColors
import com.danieldk.splatevpn.ui.theme.SplateVPNTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DockNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun transparentDockPaddingBlocksUnderlyingContentAndTabsStillWork() {
        val selected = mutableStateOf(AppTab.HOME)
        var contentClicks = 0
        compose.setContent {
            SplateVPNTheme(themeSetting = "dark", languageSetting = "en") {
                CompositionLocalProvider(LocalGlassTransparency provides 1f) {
                    Box(Modifier.fillMaxSize().background(Color(0xFF24434A))) {
                        Column(Modifier.fillMaxSize().testTag("dockTestContent").clickable { contentClicks++ }) {
                            repeat(40) { Text("Server $it · 58 Mbps · 745 ms", color = Color.White) }
                        }
                        Text(List(8) { "Server behind navigation · 745 ms" }.joinToString("\n"),
                            Modifier.align(Alignment.BottomStart), color = Color.White)
                        Box(Modifier.align(Alignment.BottomCenter)) {
                            FloatingLiquidDock(selected.value, { selected.value = it },
                                LocalAppThemeColors.current, LocalAppStrings.current,
                                listOf(AppTab.HOME, AppTab.SETTINGS))
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("dockTestContent").performTouchInput { click(Offset(10f, 150f)) }
        compose.runOnIdle { assertEquals(1, contentClicks) }
        compose.onNodeWithTag("bottomDockArea").performTouchInput {
            click(Offset(1f, center.y))
            click(Offset(center.x, height - 1f))
        }
        compose.runOnIdle { assertEquals(1, contentClicks) }
        compose.onNodeWithTag("mainTab:SETTINGS").performTouchInput { click() }.assertIsSelected()
        compose.runOnIdle { assertEquals(1, contentClicks) }
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "dock-transparent-scrim.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun everyTabSupportsSemanticClicksAndRetappingSettings() {
        val selected = mutableStateOf(AppTab.HOME)
        val visits = mutableListOf<AppTab>()
        compose.setContent {
            SplateVPNTheme(languageSetting = "en") {
                FloatingLiquidDock(selected.value, {
                    selected.value = it
                    visits += it
                }, LocalAppThemeColors.current, LocalAppStrings.current)
            }
        }
        AppTab.entries.forEach { tab ->
            compose.onNodeWithTag("mainTab:${tab.name}").performClick().assertIsSelected()
        }
        compose.onNodeWithTag("mainTab:SETTINGS").performClick()
        compose.runOnIdle {
            assertEquals(AppTab.entries.toList() + AppTab.SETTINGS, visits)
        }
    }

    @Test fun physicalTapSelectsOneTabExactlyOnce() {
        val selected = mutableStateOf(AppTab.HOME)
        val visits = mutableListOf<AppTab>()
        compose.setContent {
            SplateVPNTheme(languageSetting = "en") {
                FloatingLiquidDock(selected.value, {
                    selected.value = it
                    visits += it
                }, LocalAppThemeColors.current, LocalAppStrings.current)
            }
        }
        compose.onNodeWithTag("mainTab:SETTINGS").performTouchInput { click() }
        compose.onNodeWithTag("mainTab:SETTINGS").assertIsSelected()
        compose.runOnIdle { assertEquals(listOf(AppTab.SETTINGS), visits) }
    }
}
