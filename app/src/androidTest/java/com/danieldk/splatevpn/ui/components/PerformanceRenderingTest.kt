package com.danieldk.splatevpn.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.danieldk.splatevpn.data.UiPerformanceSettings
import com.danieldk.splatevpn.ui.theme.LocalAppThemeColors
import com.danieldk.splatevpn.ui.theme.LocalUiPerformanceSettings
import com.danieldk.splatevpn.ui.theme.SplateVPNTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

class PerformanceRenderingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun changingBackgroundAndBlurPreservesScreenState() {
        val settings = mutableStateOf(UiPerformanceSettings(false, false, false))
        val blur = mutableStateOf(false)
        compose.setContent {
            SplateVPNTheme(themeSetting = "dark", languageSetting = "en", performanceSettings = settings.value) {
                LiquidAmbientBackground(Modifier.size(280.dp, 400.dp), glassTransparency = .8f,
                    glassBlurEnabled = blur.value) {
                    var count by remember { mutableIntStateOf(0) }
                    Text("$count", Modifier.align(Alignment.Center).testTag("retainedScreenState")
                        .tactileClickable { count++ }.padding(24.dp))
                }
            }
        }
        compose.onNodeWithTag("retainedScreenState").performClick().assertTextEquals("1")
        compose.runOnIdle { settings.value = settings.value.copy(decorativeBackgroundEnabled = true) }
        compose.onNodeWithTag("retainedScreenState").assertTextEquals("1")
        compose.runOnIdle { blur.value = true }
        compose.onNodeWithTag("retainedScreenState").assertTextEquals("1")
        compose.runOnIdle { settings.value = settings.value.copy(decorativeBackgroundEnabled = false); blur.value = false }
        compose.onNodeWithTag("retainedScreenState").performClick().assertTextEquals("2")
    }

    @Test fun lightRenderingUsesFlatThemeColorAndKeepsActionsAvailableWithoutBlurSources() {
        compose.mainClock.autoAdvance = false
        var clicks = 0
        var themeBackground = Color.Transparent
        var ambientSource: GlassBackdrop? = null
        var dockSource: GlassBackdrop? = null
        compose.setContent {
            SplateVPNTheme(themeSetting = "ocean", languageSetting = "en") {
                CompositionLocalProvider(LocalUiPerformanceSettings provides UiPerformanceSettings(false, false, false)) {
                    themeBackground = LocalAppThemeColors.current.background
                    LiquidAmbientBackground(Modifier.size(280.dp, 400.dp).testTag("flatBackdrop"),
                        glassTransparency = .8f, glassBlurEnabled = false) {
                        val ambient = LocalGlassBackdrop.current
                        val (dock, _) = rememberDockBackdrop()
                        SideEffect { ambientSource = ambient; dockSource = dock }
                        Box(Modifier.align(Alignment.BottomCenter).padding(12.dp).tactileClickable { clicks++ }
                            .testTag("flatAction").liquidGlass().padding(16.dp)) { Text("Action") }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        val first = compose.onNodeWithTag("flatBackdrop").captureToImage().toPixelMap()
        compose.runOnIdle { assertNull(ambientSource); assertNull(dockSource) }
        for (y in 10 until first.height * 2 / 3 step 29) for (x in 10 until first.width - 10 step 31) {
            assertEquals(themeBackground.red, first[x, y].red, .01f)
            assertEquals(themeBackground.green, first[x, y].green, .01f)
            assertEquals(themeBackground.blue, first[x, y].blue, .01f)
        }
        compose.mainClock.advanceTimeBy(8_000)
        val second = compose.onNodeWithTag("flatBackdrop").captureToImage().toPixelMap()
        assertEquals(0, changedPixels(first, second))
        compose.onNodeWithTag("flatAction").performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test fun turningAnimationsOffKeepsDecorativeArtworkStillAndTurningThemOnResumesMotion() {
        compose.mainClock.autoAdvance = false
        val settings = mutableStateOf(UiPerformanceSettings(animationsEnabled = false))
        compose.setContent {
            SplateVPNTheme(themeSetting = "amoled", languageSetting = "en") {
                CompositionLocalProvider(LocalUiPerformanceSettings provides settings.value) {
                    LiquidAmbientBackground(Modifier.size(280.dp, 400.dp).testTag("motionBackdrop"), glassBlurEnabled = false) { }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        val still = compose.onNodeWithTag("motionBackdrop").captureToImage().toPixelMap()
        compose.mainClock.advanceTimeBy(8_000)
        assertEquals(0, changedPixels(still, compose.onNodeWithTag("motionBackdrop").captureToImage().toPixelMap()))
        compose.runOnIdle { settings.value = settings.value.copy(animationsEnabled = true) }
        compose.mainClock.advanceTimeByFrame()
        val start = compose.onNodeWithTag("motionBackdrop").captureToImage().toPixelMap()
        compose.mainClock.advanceTimeBy(8_000)
        val moved = compose.onNodeWithTag("motionBackdrop").captureToImage().toPixelMap()
        assertTrue("Enabling effects must restore visible background motion", changedPixels(start, moved) > 10)
    }

    private fun changedPixels(a: androidx.compose.ui.graphics.PixelMap, b: androidx.compose.ui.graphics.PixelMap): Int {
        assertEquals(a.width, b.width)
        assertEquals(a.height, b.height)
        var changed = 0
        for (y in 4 until a.height step 4) for (x in 4 until a.width step 4) {
            if (abs(a[x, y].red - b[x, y].red) + abs(a[x, y].green - b[x, y].green) +
                abs(a[x, y].blue - b[x, y].blue) > .02f) changed++
        }
        return changed
    }
}
