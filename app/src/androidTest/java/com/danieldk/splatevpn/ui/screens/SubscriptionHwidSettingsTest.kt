package com.danieldk.splatevpn.ui.screens

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.danieldk.splatevpn.data.SubscriptionEntity
import com.danieldk.splatevpn.ui.theme.SplateVPNTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SubscriptionHwidSettingsTest {
    @get:Rule val compose = createComposeRule()
    private val source = "https://private.example/sub/account"
    private val sub get() = SubscriptionEntity("private", "Private", source)

    @Test fun editingAndCancellingDoesNotChangeTheSavedChoice() {
        var writes = 0
        compose.setContent { SplateVPNTheme(languageSetting = "en") {
            SubscriptionDetailsDialog(sub, "24h", {}, { _, _, done -> done(Result.success(Unit)) },
                hwidEnabled = { false }, onHwidChange = { _, _ -> writes++ })
        } }
        compose.onNodeWithTag("subscription_hwid_switch").performScrollTo().assertIsOff().performClick().assertIsOn()
        compose.runOnIdle { assertEquals(0, writes) }
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(0, writes) }
    }

    @Test fun savePersistsTheChoiceForTheReviewedSourceOnly() {
        var saved: Pair<String, Boolean>? = null
        compose.setContent { SplateVPNTheme(languageSetting = "en") {
            SubscriptionDetailsDialog(sub, "24h", {}, { _, _, done -> done(Result.success(Unit)) },
                hwidEnabled = { false }, onHwidChange = { url, enabled -> saved = url to enabled })
        } }
        compose.onNodeWithTag("subscription_hwid_switch").performScrollTo().performClick()
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle { assertEquals(source to true, saved) }
    }

    @Test fun changingToAnIneligibleSourceHidesBindingAndDoesNotTransferConsent() {
        var writes = 0
        compose.setContent { SplateVPNTheme(languageSetting = "en") {
            SubscriptionDetailsDialog(sub, "24h", {}, { _, _, done -> done(Result.success(Unit)) },
                hwidEnabled = { if (it == source) true else null }, onHwidChange = { _, _ -> writes++ })
        } }
        compose.onNodeWithTag("subscription_hwid_switch").assertExists()
        compose.onAllNodes(hasSetTextAction()).onLast().performScrollTo().performTextReplacement("http://private.example/sub/account")
        compose.onNodeWithTag("subscription_hwid_switch").assertDoesNotExist()
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle { assertEquals(0, writes) }
    }
}
