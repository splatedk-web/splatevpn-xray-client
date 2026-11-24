package com.danieldk.splatevpn.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.ui.theme.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ThemeMotionReviewTest {
    @get:Rule val compose = createComposeRule()
    @Test fun aurora() = review("aurora")
    @Test fun ocean() = review("ocean")
    @Test fun winterNight() = review("winter")
    @Test fun winterDay() = review("winter_light")
    @Test fun winterDayReducedMotion() = review("winter_light", animate = false)
    @Test fun red() = review("oneplus")

    private fun review(theme: String, animate: Boolean = true) {
        compose.mainClock.autoAdvance = false
        val loaded = AtomicBoolean(false)
        compose.setContent {
            SplateVPNTheme(themeSetting = theme, languageSetting = "ru",
                performanceSettings = com.danieldk.splatevpn.data.UiPerformanceSettings(animationsEnabled = animate)) {
                val c = LocalAppThemeColors.current
                val resources = if (c.backdrop == BackdropStyle.WINTER) winterArtworkFor(c.isDark).let { listOf(it.environment, it.forest) }
                    else listOf(themeLayerArtFor(c.backdrop).resource)
                val images = resources.map { rememberThemeBitmap(it, false).value }
                SideEffect { loaded.set(images.all { it != null }) }
                if (images.all { it != null }) LiquidAmbientBackground(glassTransparency = .65f) {
                    Column(Modifier.align(Alignment.TopStart).padding(20.dp)) {
                        Text(theme, color = c.textPrimary)
                    }
                    Column(Modifier.align(Alignment.Center).width(230.dp).liquidGlass(cornerRadius = 20).padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("SplateVPN", color = c.textPrimary)
                        Text("Стеклянная карточка", color = c.textSecondary)
                        SplateButton(onClick = {}) { Text("Подключить") }
                    }
                }
            }
        }
        compose.waitUntil(15000) {
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle { loaded.get() }
        }
        compose.mainClock.advanceTimeBy(1500)
        compose.waitForIdle()
        val palette = resolveThemeColors(theme, true)
        val resources = if (palette.backdrop == BackdropStyle.WINTER) winterArtworkFor(palette.isDark).let { listOf(it.environment, it.forest) }
            else listOf(themeLayerArtFor(palette.backdrop).resource)
        resources.forEachIndexed { index, resource ->
            val atlas = androidx.compose.ui.graphics.ImageBitmap.imageResource(
                InstrumentationRegistry.getInstrumentation().targetContext.resources, resource).toPixelMap()
            var clear = 0; var solid = 0
            for (y in 0 until atlas.height step 16) for (x in 0 until atlas.width step 16) {
                if (atlas[x, y].alpha < .01f) clear++ else solid++
            }
            if (theme in listOf("winter", "winter_light", "ocean")) {
                assertTrue("$theme must have genuine transparent layer separation", clear > solid / 6)
            }
            if (palette.backdrop == BackdropStyle.WINTER && index == 0) {
                var ground = 0; var samples = 0
                for (x in 0 until atlas.width step 8) { samples++; if (atlas[x, atlas.height - 2].alpha > .9f) ground++ }
                assertTrue("Winter ground must fill the bottom edge, without floating cutout islands", ground > samples * .9f)
            }
        }
        val first = compose.onRoot().captureToImage()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val label = theme + if (animate) "" else "-static"
        File(context.cacheDir, "theme-review-$label.png").outputStream().use {
            first.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.mainClock.advanceTimeBy(4500)
        val second = compose.onRoot().captureToImage()
        File(context.cacheDir, "theme-review-$label-motion.png").outputStream().use {
            second.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        val a = first.toPixelMap(); val b = second.toPixelMap()
        var changed = 0
        for (y in 0 until a.height step 12) for (x in 0 until a.width step 12) {
            val before = a[x, y]; val after = b[x, y]
            if (abs(before.red - after.red) + abs(before.green - after.green) + abs(before.blue - after.blue) > .04f) changed++
        }
        if (animate) assertTrue("$theme should have visible independent motion", changed > 30)
        else assertEquals("Disabling motion must freeze all generated objects and weather", 0, changed)
    }
}
