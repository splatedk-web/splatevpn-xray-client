package com.danieldk.splatevpn.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.danieldk.splatevpn.data.SubscriptionMetadata
import com.danieldk.splatevpn.ui.theme.SplateVPNTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SubscriptionPublisherDetailsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun showsProviderInformationAndOnlyOpensLinksAfterATap() {
        val opened = mutableListOf<String>()
        val handler = object : UriHandler { override fun openUri(uri: String) { opened += uri } }
        val metadata = SubscriptionMetadata(announcement = "Provider announcement", website = "https://example.com/portal",
            support = "https://example.com/support", updateHours = 12)
        compose.setContent { SplateVPNTheme(languageSetting = "en") {
            CompositionLocalProvider(LocalUriHandler provides handler) { SubscriptionPublisherDetails(metadata.encode()) }
        } }
        compose.onNodeWithText("Provider announcement").assertIsDisplayed()
        compose.onNodeWithText("Server update interval: 12 h").assertIsDisplayed()
        compose.runOnIdle { assertTrue(opened.isEmpty()) }
        compose.onNodeWithText("Website").performClick()
        compose.onNodeWithText("Support").performClick()
        compose.runOnIdle { assertEquals(listOf(metadata.website, metadata.support), opened) }
    }

    @Test fun unsafeLinksFromRestoredMetadataAreNotShown() {
        compose.setContent { SplateVPNTheme(languageSetting = "en") {
            SubscriptionPublisherDetails("{\"website\":\"javascript:alert(1)\",\"support\":\"file:///private\"}")
        } }
        compose.onNodeWithText("Website").assertDoesNotExist()
        compose.onNodeWithText("Support").assertDoesNotExist()
    }
}
