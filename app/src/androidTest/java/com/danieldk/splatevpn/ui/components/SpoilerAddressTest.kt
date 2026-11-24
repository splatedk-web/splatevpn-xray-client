package com.danieldk.splatevpn.ui.components

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.danieldk.splatevpn.data.UiPerformanceSettings
import com.danieldk.splatevpn.ui.theme.SplateVPNTheme
import com.danieldk.splatevpn.ui.theme.LocalAppThemeColors
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SpoilerAddressTest {
    @get:Rule val compose = createComposeRule()
    private val address = "192.0.2.24:443"

    @Test fun hiddenParticlesMoveAndDisappearWhenAddressIsRevealed() = reviewMotion(true)

    @Test fun reducedMotionKeepsTheSpoilerStill() = reviewMotion(false)

    private fun reviewMotion(animate: Boolean) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            SplateVPNTheme(themeSetting = "sakura", languageSetting = "ru",
                performanceSettings = UiPerformanceSettings(animationsEnabled = animate)) {
                SpoilerAddress(address, Modifier.width(180.dp).background(LocalAppThemeColors.current.surface).padding(8.dp))
            }
        }
        compose.mainClock.advanceTimeBy(150)
        val first = compose.onNodeWithTag("spoilerDust", useUnmergedTree = true).captureToImage()
        compose.mainClock.advanceTimeBy(240)
        val second = compose.onNodeWithTag("spoilerDust", useUnmergedTree = true).captureToImage()
        val a = first.toPixelMap(); val b = second.toPixelMap()
        var changed = 0
        for (y in 0 until a.height step 2) for (x in 0 until a.width step 2) {
            if (abs(a[x, y].red - b[x, y].red) + abs(a[x, y].green - b[x, y].green) + abs(a[x, y].blue - b[x, y].blue) > .04f) changed++
        }
        if (animate) assertTrue("The spoiler should have visibly moving particles", changed > 40)
        else assertEquals("Reduced motion must freeze the spoiler", 0, changed)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        listOf(first, second).forEachIndexed { index, image ->
            File(context.cacheDir, "spoiler-${if (animate) "animated" else "static"}-$index.png").outputStream().use {
                image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        compose.onNodeWithText(address, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("serverAddressSpoiler").performClick()
        compose.mainClock.advanceTimeBy(250)
        compose.onNodeWithTag("spoilerDust", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(address, useUnmergedTree = true).assertExists()
    }

    @Test fun addressIsAbsentFromSemanticsUntilTappedAndHidesAfterTimeout() {
        compose.setContent {
            SplateVPNTheme(languageSetting = "ru", performanceSettings = UiPerformanceSettings(animationsEnabled = false)) {
                SpoilerAddress(address, Modifier.width(180.dp), revealDurationMs = 500)
            }
        }
        compose.onNodeWithText(address, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("serverAddressSpoiler").performClick()
        compose.onNodeWithText(address, useUnmergedTree = true).assertIsDisplayed()
        compose.waitUntil(3000) { compose.onAllNodesWithText(address, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
    }

    @Test fun hidingBackgroundingAndChangingServerNeverPreserveRevealedAddress() {
        var current by mutableStateOf(address)
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            SplateVPNTheme(languageSetting = "ru", performanceSettings = UiPerformanceSettings(animationsEnabled = false)) {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) { SpoilerAddress(current, Modifier.width(180.dp)) }
            }
        }
        compose.onNodeWithTag("serverAddressSpoiler").performClick().performClick()
        compose.onNodeWithText(address, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("serverAddressSpoiler").performClick()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.onNodeWithText(address, useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.onNodeWithTag("serverAddressSpoiler").performClick()
        compose.runOnIdle { current = "198.51.100.1:443" }
        compose.onNodeWithText(address, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("198.51.100.1:443", useUnmergedTree = true).assertDoesNotExist()
    }
}
