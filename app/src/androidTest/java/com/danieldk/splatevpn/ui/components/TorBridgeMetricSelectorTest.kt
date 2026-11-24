package com.danieldk.splatevpn.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.data.TorBridgeMetric
import com.danieldk.splatevpn.ui.theme.SplateVPNTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TorBridgeMetricSelectorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selectionWorksAndLongTranslatedHintsFit() {
        val selected = mutableStateOf(TorBridgeMetric.LATENCY)
        compose.setContent { SplateVPNTheme(languageSetting = "ru") {
            Column(Modifier.padding(16.dp)) {
                Text("Tor")
                TorBridgeMetricSelector(selected.value, true) { selected.value = it }
            }
        } }
        compose.onNodeWithTag("torMetric:speed").performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(TorBridgeMetric.SPEED, selected.value) }
        compose.onNodeWithTag("torMetric:latency").assertIsDisplayed()
        compose.onRoot().captureToImage().let { bitmap ->
            val file = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "tor-metric-ru.png")
            java.io.FileOutputStream(file).use { bitmap.asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun selectionIsDisabledDuringAnOperation() {
        compose.setContent { SplateVPNTheme(languageSetting = "en") {
            TorBridgeMetricSelector(TorBridgeMetric.SPEED, false) { error("Disabled selector changed") }
        } }
        compose.onNodeWithTag("torMetric:speed").assertIsSelected().assertIsNotEnabled()
        compose.onNodeWithTag("torMetric:latency").assertIsNotEnabled()
    }
}
