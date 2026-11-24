package com.danieldk.splatevpn.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class UiMotionDurationScaleTest {
    @Test fun applicationOptOutPreservesAndroidScaleWhenAnimationsAreRestored() {
        val scale = UiMotionDurationScale(true, 2f)
        assertEquals(2f, scale.scaleFactor, 0f)
        scale.setAnimationsEnabled(false)
        assertEquals(0f, scale.scaleFactor, 0f)
        scale.setSystemScale(.5f)
        assertEquals(0f, scale.scaleFactor, 0f)
        scale.setAnimationsEnabled(true)
        assertEquals(.5f, scale.scaleFactor, 0f)
        scale.setSystemScale(0f)
        assertEquals(0f, scale.scaleFactor, 0f)
    }
}
