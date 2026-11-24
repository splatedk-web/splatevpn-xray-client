package com.danieldk.splatevpn.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.danieldk.splatevpn.ui.theme.SplateVPNTheme
import org.junit.Rule
import org.junit.Test
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

class DiagnosticReportPreviewTest {
    @get:Rule val compose = createComposeRule()

    @Test fun longReportKeepsActionsVisibleAtLargeFontSize() {
        compose.setContent {
            SplateVPNTheme(languageSetting = "ru") {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                    DiagnosticReportPreview((1..300).joinToString("\n") { "Diagnostic event $it" }, {})
                }
            }
        }
        compose.onNodeWithText("Копировать").assertIsDisplayed()
        compose.onNodeWithText("Поделиться").assertIsDisplayed()
        compose.onNodeWithText("Закрыть").assertIsDisplayed()
        compose.onNodeWithText("Diagnostic event 1", substring = true).assertIsDisplayed()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            File(instrumentation.targetContext.externalCacheDir, "diagnostic-preview.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
