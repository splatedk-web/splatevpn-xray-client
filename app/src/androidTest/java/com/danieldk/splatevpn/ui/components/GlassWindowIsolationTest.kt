package com.danieldk.splatevpn.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GlassWindowIsolationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun dialogDoesNotSampleAnotherWindowButMainCardsStillDo() {
        compose.setContent {
            val view = LocalView.current
            val layer = rememberGraphicsLayer()
            val backdrop = remember(layer, view) { GlassBackdrop(layer, view) }
            CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
                Box(Modifier.fillMaxSize().background(Color.Black)
                    .onGloballyPositioned { coordinates ->
                        val location = IntArray(2)
                        view.getLocationOnScreen(location)
                        backdrop.origin = coordinates.positionInRoot() + Offset(location[0].toFloat(), location[1].toFloat())
                    }.drawWithContent {
                        // A bright source makes a cross-window leak unambiguous.
                        layer.record { drawRect(Color.Red) }
                        drawContent()
                        backdrop.ready = true
                    }) {
                    Box(Modifier.size(80.dp).clipToBounds().sampleGlassBackdrop().testTag("mainCard"))
                    Dialog(onDismissRequest = {}) {
                        Box(Modifier.size(180.dp).background(Color.Black), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(100.dp).clipToBounds().sampleGlassBackdrop().testTag("dialogCard"))
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val main = compose.onNodeWithTag("mainCard").captureToImage().toPixelMap()
        val dialog = compose.onNodeWithTag("dialogCard").captureToImage().toPixelMap()
        assertTrue("Same-window glass must still sample its backdrop", main[main.width / 2, main.height / 2].red > .9f)
        assertTrue("Opaque dialog must hide the main window's backdrop", dialog[dialog.width / 2, dialog.height / 2].red < .1f)
    }
}
