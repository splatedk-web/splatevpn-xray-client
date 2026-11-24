package com.danieldk.splatevpn.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.data.ServerNode
import com.danieldk.splatevpn.data.SubscriptionEntity
import com.danieldk.splatevpn.data.SubscriptionMetadata
import com.danieldk.splatevpn.ui.theme.SplateVPNTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import java.io.File

class NodeGroupTest {
    @get:Rule val compose = createComposeRule()
    @Test fun collapsedSubscriptionShowsQuotaExpiryAndActions() {
        val enabled = mutableStateOf(true)
        var details = 0
        val sub = SubscriptionEntity("sub", "Personal subscription", "https://example.com", totalBytes = 10L*1024*1024*1024,
            downloadBytes = 2L*1024*1024*1024, expireTimestamp = 1893456000L)
        compose.setContent {
            SplateVPNTheme(languageSetting = "en") {
                Column {
                    SubscriptionGroupHeader(ServerGroup("sub", sub.name, false, false, sub.id,
                        listOf(ServerNode("n", "Server", "vless", "example.com", 443))), true, enabled.value,
                        false, true, {}, {}, { enabled.value = it }, subscription = sub, onDetails = { details++ })
                }
            }
        }
        compose.onNodeWithText("Personal subscription").assertIsDisplayed()
        compose.onNodeWithText("8.0 GB", substring = true).assertIsDisplayed()
        compose.onNodeWithText("01.01.2030", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Folder options").performClick()
        compose.onNodeWithText("Edit").performClick()
        compose.runOnIdle { assertEquals(1, details) }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.cacheDir, "node-group-review.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun folderCheckIsVisibleAndRemainsCancellableDuringBatch() {
        val checking = mutableStateOf(false)
        var starts = 0
        var stops = 0
        val group = ServerGroup("folder", "Subscription", false, false, "folder",
            listOf(ServerNode("n", "Server", "vless", "example.com", 443)))
        compose.setContent {
            SplateVPNTheme(languageSetting = "en") {
                SubscriptionGroupHeader(group, isCollapsed = true, isEnabled = true,
                    isPinging = checking.value, pingEnabled = !checking.value,
                    onPing = { starts++; checking.value = true }, onToggleCollapse = {},
                    onStopPing = { stops++; checking.value = false })
            }
        }
        compose.onNodeWithTag("folderPing:folder").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Stop checks").assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("Check servers").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, starts); assertEquals(1, stops) }
    }

    @Test fun publisherInformationAppearsWhenFolderIsExpanded() {
        val collapsed = mutableStateOf(true)
        val sub = SubscriptionEntity("source", "Subscription", "https://example.com",
            publisherMetadataJson = SubscriptionMetadata(announcement = "Provider announcement",
                website = "https://example.com", support = "https://example.com/support").encode())
        val group = ServerGroup(sub.id, sub.name, false, false, sub.id, emptyList())
        compose.setContent {
            SplateVPNTheme(languageSetting = "en") {
                SubscriptionGroupHeader(group, collapsed.value, true, false, true, {},
                    onToggleCollapse = { collapsed.value = !collapsed.value }, subscription = sub)
            }
        }
        compose.onNodeWithText("Provider announcement").assertDoesNotExist()
        compose.onNodeWithText("Subscription").performClick()
        compose.onNodeWithText("Provider announcement").assertIsDisplayed()
        compose.onNodeWithText("Website").assertIsDisplayed()
        compose.onNodeWithText("Support").assertIsDisplayed()
    }
}
