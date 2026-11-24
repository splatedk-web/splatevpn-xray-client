package com.danieldk.splatevpn.ui.components

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.ui.theme.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class InternationalUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun glassContentInheritsReadableTextAcrossAllThemes() {
        compose.setContent {
            androidx.compose.foundation.layout.Column {
                listOf("dark", "amoled", "light", "cyberpunk", "oneplus", "aurora", "sunset", "ocean", "sakura", "pony", "winx").forEach { theme ->
                    SplateVPNTheme(themeSetting = theme, languageSetting = "ru") {
                        assertEquals(theme, LocalAppThemeColors.current.textPrimary, androidx.compose.material3.LocalContentColor.current)
                        GlassSettingsCard {
                            assertEquals(theme, LocalAppThemeColors.current.textPrimary, androidx.compose.material3.LocalContentColor.current)
                            Text(theme)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }
    @Test fun persianLayoutAndStringsFollowChosenLanguage() {
        compose.setContent {
            SplateVPNTheme(languageSetting = "fa") {
                assertEquals(LayoutDirection.Rtl, LocalLayoutDirection.current)
                Text(LocalAppStrings.current.settingsTitle)
            }
        }
        compose.onNodeWithText("تنظیمات").assertIsDisplayed()
    }
    @Test fun chineseResourcesCoverEveryAppString() {
        compose.setContent {
            SplateVPNTheme(languageSetting = "zh-Hans") {
                assertEquals(LayoutDirection.Ltr, LocalLayoutDirection.current)
                val strings = LocalAppStrings.current
                assertEquals("设置", strings.settingsTitle)
                AppStrings::class.java.methods.filter { it.name.startsWith("get") }.forEach { getter ->
                    assertFalse(getter.name, (getter.invoke(strings) as String).isBlank())
                }
                Text(strings.coreConnect)
            }
        }
        compose.onNodeWithText("连接").assertIsDisplayed()
    }
    @Test fun importsQrFromImageWithoutCameraOrGoogleServices() = kotlinx.coroutines.runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val content = "vless://11111111-2222-3333-4444-555555555555@example.com:443?security=tls#Test"
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 800, 800)
        val bitmap = Bitmap.createBitmap(800, 800, Bitmap.Config.ARGB_8888)
        for (y in 0 until 800) for (x in 0 until 800) bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        val file = File.createTempFile("qr-test", ".png", context.cacheDir)
        try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertEquals(listOf(content), decodeQrImages(context, Uri.fromFile(file)))
        } finally { bitmap.recycle(); file.delete() }
    }
}
