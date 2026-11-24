package com.danieldk.splatevpn.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.danieldk.splatevpn.ui.theme.SplateVPNTheme
import com.danieldk.splatevpn.data.UiPerformanceSettings
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GlassRenderingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun lightGlassFollowsSliderWithoutAMilkyOpacityFloor() = checkTransparency("winter_light", Color.Black, true)
    @Test fun darkGlassFollowsSliderWithoutAnOpaqueBacking() = checkTransparency("winter", Color.White, false)

    private fun checkTransparency(theme: String, artwork: Color, light: Boolean) {
        var transparency by mutableFloatStateOf(0f)
        compose.setContent {
            SplateVPNTheme(themeSetting = theme, performanceSettings = UiPerformanceSettings(
                animationsEnabled = false, decorativeBackgroundEnabled = false, glassHighlightsEnabled = false)) {
                CompositionLocalProvider(LocalGlassTransparency provides transparency, LocalGlassBlurEnabled provides false) {
                    Box(Modifier.size(200.dp).background(artwork).padding(20.dp)) {
                        Box(Modifier.fillMaxSize().liquidGlass().testTag("sliderMaterial"))
                    }
                }
            }
        }
        fun centerLuminance(): Float {
            val pixels = compose.onNodeWithTag("sliderMaterial").captureToImage().toPixelMap()
            return pixels[pixels.width / 2, pixels.height / 2].luminance()
        }
        val solid = centerLuminance()
        compose.runOnIdle { transparency = 1f }
        val clear = centerLuminance()
        if (light) {
            assertTrue("0% must keep the light material dense", solid > .75f)
            assertTrue("100% must let dark artwork through", clear < .05f)
        } else {
            assertTrue("0% must keep the dark material dense", solid < .2f)
            assertTrue("100% must let bright artwork through", clear > .75f)
        }
    }

    @Test fun publishingNewFrameDoesNotSubscribeProducerToItsOwnGeneration() {
        lateinit var backdrop: GlassBackdrop
        compose.setContent {
            val layer = rememberGraphicsLayer()
            val view = LocalView.current
            backdrop = remember { GlassBackdrop(layer, view) }
        }
        compose.runOnIdle {
            var reads = 0
            Snapshot.observe(readObserver = { reads++ }) { backdrop.markRecorded() }
            assertEquals(0, reads)
            assertEquals(1, backdrop.revision)
            assertTrue(backdrop.ready)
        }
    }

    @Test fun transparencySliderPreviewsAndCommitsDragAndKeepsResetAvailable() {
        var value by mutableFloatStateOf(.3f)
        var commits = 0
        compose.setContent {
            SplateVPNTheme(themeSetting = "dark", languageSetting = "ru") {
                Box(Modifier.width(340.dp).background(Color.Black).padding(12.dp)) {
                    GlassTransparencyControl(value, { value = it }, { commits++ }, { value = 0f }, true, {})
                }
            }
        }
        compose.onNodeWithTag("glassTransparencySlider").performTouchInput { swipeRight() }
        compose.runOnIdle { assertTrue(value > .8f); assertTrue(commits > 0) }
        compose.onNodeWithText("100%").assertIsDisplayed()
    }

    @Test fun croppedDockMatchesFullResolutionBlurIncludingItsEdges() {
        lateinit var backdrop: GlassBackdrop
        compose.setContent {
            SplateVPNTheme(themeSetting = "dark") {
                CompositionLocalProvider(LocalGlassTransparency provides .8f) {
                    val (captured, source) = rememberDockBackdrop()
                    backdrop = requireNotNull(captured)
                    Box(Modifier.width(320.dp).height(600.dp)) {
                        Column(Modifier.fillMaxSize().then(source)
                            .background(Brush.verticalGradient(listOf(Color.Red, Color.Blue, Color.Green)))) {
                            repeat(12) { Text("Sharp foreground $it", modifier = Modifier.height(50.dp), color = Color.White) }
                        }
                        CompositionLocalProvider(LocalGlassBackdrop provides captured) {
                            Box(Modifier.align(Alignment.BottomCenter).padding(12.dp).fillMaxWidth().height(64.dp)
                                .frostedGlass().testTag("testDock"))
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val cropped = compose.onNodeWithTag("testDock").captureToImage().toPixelMap()
        var bounds: Rect? = null
        compose.runOnIdle {
            bounds = backdrop.sampleBounds
            assertNotNull(bounds)
            assertTrue(backdrop.blurred.size.height < 400 * compose.density.density)
            backdrop.sampleBounds = null // The fallback is the previous full-screen blur pipeline.
        }
        compose.waitForIdle()
        val full = compose.onNodeWithTag("testDock").captureToImage().toPixelMap()
        for (y in 15 until full.height - 15 step 11) for (x in 15 until full.width - 15 step 17) {
            val a = cropped[x, y]; val b = full[x, y]
            assertEquals("red at $x,$y", b.red, a.red, .035f)
            assertEquals("green at $x,$y", b.green, a.green, .035f)
            assertEquals("blue at $x,$y", b.blue, a.blue, .035f)
        }
    }
}
