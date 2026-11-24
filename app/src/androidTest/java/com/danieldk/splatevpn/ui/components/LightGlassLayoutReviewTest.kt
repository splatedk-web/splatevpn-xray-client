package com.danieldk.splatevpn.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.data.BalancerPreferences
import com.danieldk.splatevpn.data.ServerNode
import com.danieldk.splatevpn.data.UiPerformanceSettings
import com.danieldk.splatevpn.ui.screens.ServerCard
import com.danieldk.splatevpn.ui.theme.SplateVPNTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LightGlassLayoutReviewTest {
    @get:Rule val compose = createComposeRule()
    @Test fun winterDayAtFullTransparency() = review("winter_light")
    @Test fun winterNightAtFullTransparency() = review("winter")

    @Test fun serverRowsRemainCompactWithAllActionsAndMetadata() {
        compose.setContent {
            SplateVPNTheme(themeSetting = "winter", languageSetting = "en",
                performanceSettings = UiPerformanceSettings(animationsEnabled = false)) {
                Box(Modifier.width(340.dp)) {
                    ServerCard(Modifier.testTag("compactServer"),
                        ServerNode("compact", "Netherlands", "vless", "192.0.2.24", 443,
                            security = "tls", network = "tcp", ping = 42), false,
                        onSelect = {}, onEdit = {}, onMoreMenu = {})
                }
            }
        }
        val height = compose.onNodeWithTag("compactServer").fetchSemanticsNode().boundsInRoot.height
        assertTrue("A complete server row should stay compact", height <= with(compose.density) { 96.dp.toPx() })
        compose.onNodeWithText("192.0.2.24:443", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("42 ms").assertIsDisplayed()
        compose.onNodeWithTag("serverEdit:compact", useUnmergedTree = true).assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("serverMore:compact", useUnmergedTree = true).assertIsDisplayed().assertIsEnabled()
    }

    private fun review(theme: String) {
        var refreshes = 0
        var edits = 0
        var menus = 0
        var checks = 0
        val node = ServerNode("layout", "🇳🇱 Netherlands · Amsterdam", "vless", "long-hostname.example.net", 443,
            security = "tls", network = "tcp", ping = 1350)
        compose.setContent {
            SplateVPNTheme(themeSetting = theme, languageSetting = "en",
                performanceSettings = UiPerformanceSettings(animationsEnabled = false)) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                    LiquidAmbientBackground(glassTransparency = 1f) {
                        Column(Modifier.align(Alignment.TopCenter).width(340.dp).fillMaxHeight().verticalScroll(rememberScrollState())
                            .statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            EgressLocationCard("🇳🇱", "Netherlands", "192.0.2.24", false, { refreshes++ })
                            BalancerQuickControls(BalancerPreferences(), {})
                            ServerCard(Modifier.testTag("reviewServer"), node, false,
                                onTestPing = { checks++ }, onSelect = {}, onEdit = { edits++ }, onMoreMenu = { menus++ })
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("192.0.2.24", useUnmergedTree = true).assertDoesNotExist()
        val hiddenBounds = compose.onNodeWithTag("egressLocationCard").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("egressAddressSpoiler").performClick()
        val country = compose.onNodeWithTag("egressCountry", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val address = compose.onNodeWithText("192.0.2.24", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals("Country and address must share a left edge", country.left, address.left, 1f)
        assertEquals("Revealing the address must not resize its card", hiddenBounds,
            compose.onNodeWithTag("egressLocationCard").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("egressAddressSpoiler").performClick()
        compose.onNodeWithText("long-hostname.example.net:443", useUnmergedTree = true).assertIsDisplayed()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.cacheDir, "glass-layout-$theme.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithTag("egressRefresh").performClick()
        compose.onNodeWithTag("serverEdit:layout", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithTag("serverMore:layout", useUnmergedTree = true).performClick()
        compose.onNodeWithText("1350 ms").performClick()
        compose.runOnIdle {
            assertEquals(1, refreshes); assertEquals(1, edits); assertEquals(1, menus); assertEquals(1, checks)
        }
    }
}
