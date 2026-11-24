package com.danieldk.splatevpn.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.ui.theme.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class UiStyleConsistencyTest {
    @get:Rule val compose = createComposeRule()
    @Test fun darkActionsUseSentenceCaseAndKeepCallbacks() = actions("dark")
    @Test fun lightActionsUseSentenceCaseAndKeepCallbacks() = actions("light")
    @Test fun darkGlassDialogKeepsScreenContentOut() = dialog("dark")
    @Test fun lightGlassDialogKeepsScreenContentOut() = dialog("light")

    private fun dialog(theme: String) {
        compose.setContent {
            SplateVPNTheme(themeSetting = theme, languageSetting = "ru") {
                CompositionLocalProvider(LocalGlassTransparency provides 1f) {
                    Box(Modifier.fillMaxSize().background(Color.Red)) {
                        SplateAlertDialog(onDismissRequest = {}, modifier = Modifier.testTag("glassDialog"),
                            title = { Text("Параметры подписки") },
                            text = { Text("Срок действия и остаток трафика") },
                            confirmButton = { TextButton(onClick = {}) { Text("Закрыть") } })
                    }
                }
            }
        }
        compose.onNodeWithText("Параметры подписки").assertIsDisplayed()
        val pixels = compose.onNodeWithTag("glassDialog").captureToImage().toPixelMap()
        // The glass rim and sheen must not reintroduce transparency over screen text.
        val center = pixels[pixels.width / 2, pixels.height / 2]
        assertTrue(center.alpha > .99f)
        assertFalse(center.red > .95f && center.green < .05f && center.blue < .05f)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.cacheDir, "ui-dialog-$theme.png").outputStream().use {
            compose.onNodeWithTag("glassDialog").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    private fun actions(theme: String) {
        var count = 0
        compose.setContent {
            SplateVPNTheme(themeSetting = theme, languageSetting = "ru") {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                    val strings = LocalAppStrings.current
                    Column(Modifier.fillMaxWidth().background(LocalAppThemeColors.current.background).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        PageHeader(strings.tabNodes, strings.subscriptionsPageSubtitle)
                        SplateButton(onClick = { count++ }, modifier = Modifier.fillMaxWidth()) { Text(strings.ping) }
                        SplateOutlinedButton(onClick = { count++ }, modifier = Modifier.fillMaxWidth()) { Text(strings.refresh) }
                        SplateTonalButton(onClick = { count++ }, enabled = false, modifier = Modifier.fillMaxWidth()) { Text(strings.save) }
                    }
                }
            }
        }
        compose.onNodeWithText("Пинг").assertIsDisplayed().performClick()
        compose.onNodeWithText("Обновить").assertIsDisplayed().performClick()
        compose.onNodeWithText("Сохранить").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(2, count) }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.cacheDir, "ui-style-$theme.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
