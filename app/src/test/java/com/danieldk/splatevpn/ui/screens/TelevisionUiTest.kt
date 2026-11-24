package com.danieldk.splatevpn.ui.screens

import android.content.res.Configuration
import org.junit.Assert.*
import org.junit.Test

class TelevisionUiTest {
    @Test fun detectsTelevisionWithoutConfusingNightModeWithDeviceType() {
        assertTrue(isTelevisionUi(Configuration.UI_MODE_TYPE_TELEVISION or Configuration.UI_MODE_NIGHT_YES, false))
        assertTrue(isTelevisionUi(Configuration.UI_MODE_TYPE_TELEVISION or Configuration.UI_MODE_NIGHT_NO, false))
        assertFalse(isTelevisionUi(Configuration.UI_MODE_TYPE_NORMAL or Configuration.UI_MODE_NIGHT_YES, false))
        assertFalse(isTelevisionUi(Configuration.UI_MODE_TYPE_CAR or Configuration.UI_MODE_NIGHT_YES, false))
    }

    @Test fun leanbackFeatureSupportsTvLaunchersThatReportNormalUiMode() {
        assertTrue(isTelevisionUi(Configuration.UI_MODE_TYPE_NORMAL, true))
        assertFalse(isTelevisionUi(Configuration.UI_MODE_TYPE_NORMAL, false))
    }
}
