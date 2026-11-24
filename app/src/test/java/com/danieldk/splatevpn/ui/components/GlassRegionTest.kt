package com.danieldk.splatevpn.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.*
import org.junit.Test

class GlassRegionTest {
    @Test fun dockKeepsThreeBlurRadiiOfContextAndAvoidsFullScreenBuffer() {
        val region = dockBlurRegion(Rect(48f, 2200f, 1032f, 2370f), Offset(0f, 24f), Size(1080f, 2400f), 56f)
        assertEquals(Rect(0f, 2008f, 1080f, 2400f), region)
        assertTrue(region.width * region.height < 1080f * 2400f * .20f)
    }
    @Test fun movingOrResizingDockRecomputesCaptureWithoutClippingItsKernel() {
        val region = dockBlurRegion(Rect(110f, 420f, 510f, 500f), Offset(10f, 20f), Size(900f, 900f), 20f)
        assertEquals(Rect(40f, 340f, 560f, 540f), region)
    }
    @Test fun notYetPositionedDockUsesCompleteSource() {
        assertEquals(Rect(0f, 0f, 500f, 900f), dockBlurRegion(null, Offset.Zero, Size(500f, 900f), 28f))
    }
    @Test fun fractionalPixelBoundsRoundOutward() {
        assertEquals(Rect(69f, 169f, 232f, 262f),
            dockBlurRegion(Rect(100.3f, 200.4f, 200.8f, 230.2f), Offset.Zero, Size(500f, 900f), 10.1f))
    }
}
