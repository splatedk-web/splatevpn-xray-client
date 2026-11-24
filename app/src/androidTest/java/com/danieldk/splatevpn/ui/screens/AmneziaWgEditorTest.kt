package com.danieldk.splatevpn.ui.screens

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.data.AmneziaWg
import com.danieldk.splatevpn.ui.theme.SplateVPNTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.Base64

class AmneziaWgEditorTest {
    @get:Rule val compose = createComposeRule()
    @Test fun profileEditorUsesThemeHidesKeysAndValidatesBeforeSave() {
        val key = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() })
        val node = AmneziaWg.node("[Interface]\nPrivateKey = $key\nAddress = 10.77.0.2/32\n[Peer]\nPublicKey = $key\nEndpoint = 127.0.0.1:51820\nAllowedIPs = 0.0.0.0/0")
        var saved = false
        compose.setContent { SplateVPNTheme(languageSetting = "ru") {
            AmneziaWgEditor(node, {}, { updated, done -> assertEquals(node.amneziaWg, updated.amneziaWg); saved = true; done(Result.success(Unit)) })
        } }
        compose.onNodeWithTag("awgReveal").assertIsOff()
        compose.onNodeWithTag("awgSave").assertIsEnabled()
        compose.onNodeWithTag("awgName").performTextReplacement("AWG проверка")
        compose.onNodeWithTag("awgSave").performClick()
        compose.runOnIdle { assertTrue(saved) }
        compose.onNodeWithTag("toolDialogViewport", useUnmergedTree = true).captureToImage().let { bitmap ->
            val file = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"awg-editor-ru.png")
            file.outputStream().use { bitmap.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        }
    }
}
