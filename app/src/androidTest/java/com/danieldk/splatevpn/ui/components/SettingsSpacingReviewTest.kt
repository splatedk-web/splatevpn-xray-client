package com.danieldk.splatevpn.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.data.BalancerMode
import com.danieldk.splatevpn.data.BalancerPreferences
import com.danieldk.splatevpn.data.UiPerformanceSettings
import com.danieldk.splatevpn.ui.screens.SettingToggleRow
import com.danieldk.splatevpn.ui.theme.SplateVPNTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SettingsSpacingReviewTest {
    @get:Rule val compose = createComposeRule()
    @Test fun winterDarkSettingsAlignLabelsAndControls() = review("winter", 1f)
    @Test fun winterLightSettingsAlignLabelsAndControls() = review("winter_light", 1f)
    @Test fun largeTextKeepsRadioLabelsAndDescriptionsAligned() = review("winter_light", 1.5f)

    private fun review(theme: String, scale: Float) {
        var value by mutableStateOf(BalancerPreferences())
        var changes = 0
        compose.setContent {
            SplateVPNTheme(themeSetting = theme, languageSetting = "ru",
                performanceSettings = UiPerformanceSettings(animationsEnabled = false)) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                    LiquidAmbientBackground(glassTransparency = 1f) {
                        Column(Modifier.align(Alignment.TopCenter).width(360.dp).fillMaxHeight()
                            .verticalScroll(rememberScrollState()).statusBarsPadding().padding(16.dp)) {
                            GlassSettingsCard {
                                SettingToggleRow(Icons.Default.Bolt, "Автовыбор сервера",
                                    "Автоматически проверять пинг и подключаться к самому быстрому серверу.", true, {})
                                BalancerSettingsCard(value, true, { value = it; changes++ }, embedded = true)
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("balancerExpand").performClick()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.cacheDir, "spacing-balancer-$theme-$scale.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        val toleranceDp = with(compose.density) { 1f.toDp() }.value
        val titleLefts = mutableListOf<Float>()
        // The expanded column can extend below the viewport. Use placed, unclipped
        // geometry for every option in the same scroll position, rather than the
        // zero/partial rectangles returned by boundsInRoot for offscreen children.
        val rows = BalancerMode.entries.map { mode ->
            val tag = "balancerMode:$mode"
            val indicator = compose.onNodeWithTag("$tag:indicator", useUnmergedTree = true).getUnclippedBoundsInRoot()
            val title = compose.onNodeWithTag("$tag:title", useUnmergedTree = true).getUnclippedBoundsInRoot()
            val description = compose.onNodeWithTag("$tag:description", useUnmergedTree = true).getUnclippedBoundsInRoot()
            listOf(indicator, title, description).forEach { bounds ->
                assertTrue("Option geometry must be placed and nonempty", bounds.right > bounds.left && bounds.bottom > bounds.top)
            }
            assertEquals("Radio must align with the first title line", title.top.value, indicator.top.value, toleranceDp)
            assertEquals("Description must start under its title", title.left.value, description.left.value, toleranceDp)
            assertTrue(title.bottom <= description.top)
            titleLefts += title.left.value
            compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        }
        assertTrue("All mode titles must share the same left edge", titleLefts.max() - titleLefts.min() <= toleranceDp)
        rows.zipWithNext().forEach { (first, second) -> assertTrue(first.bottom <= second.top) }
        compose.onNodeWithTag("balancerMode:FAILOVER").performScrollTo().performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(BalancerMode.FAILOVER, value.mode); assertEquals(1, changes) }
    }
}
