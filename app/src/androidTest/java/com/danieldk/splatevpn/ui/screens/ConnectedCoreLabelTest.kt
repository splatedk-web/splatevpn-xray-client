package com.danieldk.splatevpn.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.ui.theme.*
import com.danieldk.splatevpn.vpn.VpnState
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ConnectedCoreLabelTest {
    @get:Rule val compose = createComposeRule()
    @Test fun russianConnectedLabelKeepsOriginalStyle() = checkLabel(1f)
    @Test fun russianConnectedLabelFitsAtLargeFont() = checkLabel(1.5f)
    private fun checkLabel(fontScale: Float) {
        compose.setContent {
            SplateVPNTheme(themeSetting = "dark", languageSetting = "ru") {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    Box(Modifier.background(LocalAppThemeColors.current.background).padding(16.dp)) {
                        CyberPowerCore(true, VpnState.CONNECTED, {})
                    }
                }
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("ПОДКЛЮЧЕНО").assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.cacheDir, "ui-core-connected-$fontScale.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        val layout = layouts.single()
        assertFalse("size=${layout.size}, width=${layout.didOverflowWidth}, height=${layout.didOverflowHeight}, lines=${layout.lineCount}, style=${layout.layoutInput.style}", layout.hasVisualOverflow)
        assertEquals(androidx.compose.ui.text.font.FontFamily.Monospace, layout.layoutInput.style.fontFamily)
        if (fontScale == 1f) {
            assertEquals(11f, layout.layoutInput.style.fontSize.value, .01f)
            assertEquals(1.6f, layout.layoutInput.style.letterSpacing.value, .01f)
        }
    }
}
