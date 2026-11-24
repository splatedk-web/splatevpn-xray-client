package com.danieldk.splatevpn.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.data.NotificationPreferences
import com.danieldk.splatevpn.ui.theme.SplateVPNTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NotificationSettingsUiTest {
    @get:Rule val compose = createComposeRule()
    private fun screenshot(name: String) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onRoot().captureToImage().let { image -> java.io.File(app.cacheDir,name).outputStream().use { image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } }
    }
    @Test fun togglesKeepIndependentChoicesAndMasterOnlyDisablesAlertControls() {
        val p = mutableStateOf(NotificationPreferences())
        compose.setContent { SplateVPNTheme(languageSetting = "ru") { Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
            NotificationSettingsContent(p.value, { p.value = it }, {})
        } } }
        compose.onNodeWithTag("notification:dns").performScrollTo().assertIsOff().performClick().assertIsOn()
        compose.onNodeWithTag("notification:health").performScrollTo().performClick().assertIsOff()
        compose.onNodeWithTag("notification:connection").assertIsNotEnabled()
        compose.onNodeWithTag("notification:dns").assertIsNotEnabled()
        compose.onNodeWithTag("notification:speed").performScrollTo().assertIsEnabled().performClick().assertIsOff()
        compose.runOnIdle { assertTrue(p.value.dnsIssues); assertFalse(p.value.showSpeed) }
        compose.onNodeWithTag("notification:health").performScrollTo()
        screenshot("notification-settings-ru.png")
    }
    @Test fun recommendedPermissionsStayOptionalAndActionsAreAccessible() {
        var actions = 0
        compose.setContent { SplateVPNTheme(languageSetting = "ru") { Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
            RecommendedAccessContent(RecommendedAccessState(false,false,true), { actions++ }, { actions++ }, { actions++ }, { actions++ })
        } } }
        for (tag in listOf("notifications","battery","background","vpn")) compose.onNodeWithTag("recommended:$tag").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(4,actions) }
        compose.onNodeWithTag("recommended:notifications").performScrollTo()
        screenshot("recommended-access-ru.png")
    }
}
