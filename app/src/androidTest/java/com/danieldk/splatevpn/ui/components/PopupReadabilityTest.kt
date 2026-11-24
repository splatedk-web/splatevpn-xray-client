package com.danieldk.splatevpn.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.data.TrafficDay
import com.danieldk.splatevpn.ui.screens.TrafficStatisticsSheet
import com.danieldk.splatevpn.ui.theme.SplateVPNTheme
import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PopupReadabilityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sunsetWithBlur() = checkPopup("sunset", true)
    @Test fun sunsetWithoutBlur() = checkPopup("sunset", false)
    @Test fun lightWithBlur() = checkPopup("light", true)
    @Test fun lightWithoutBlur() = checkPopup("light", false)

    private fun checkPopup(theme: String, blur: Boolean) {
        compose.setContent {
            SplateVPNTheme(themeSetting = theme, languageSetting = "ru") {
                CompositionLocalProvider(LocalGlassTransparency provides 1f, LocalGlassBlurEnabled provides blur) {
                    Box(Modifier.fillMaxSize().background(Color.Red)) {
                        Text("BACKGROUND TEXT MUST NOT SHOW THROUGH THE POPUP")
                        TrafficStatisticsSheet(listOf(TrafficDay(LocalDate.now(), 14_000_000, 17_000_000, 3_000_000)), {}, {})
                    }
                }
            }
        }
        compose.waitForIdle()
        val pixels = compose.onNodeWithTag("toolsSheet").captureToImage().toPixelMap()
        // Exclude rounded corners; transparent gaps between cards exposed the underlying settings.
        for (y in pixels.height / 8 until pixels.height * 7 / 8 step 17) {
            for (x in pixels.width / 8 until pixels.width * 7 / 8 step 17) {
                assertTrue("Popup must hide underlying text even at maximum transparency ($theme, blur=$blur)",
                    pixels[x, y].alpha > .99f)
            }
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.cacheDir, "popup-$theme-$blur.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
    }
}
