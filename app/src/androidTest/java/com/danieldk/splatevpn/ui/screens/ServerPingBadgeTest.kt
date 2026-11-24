package com.danieldk.splatevpn.ui.screens

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.danieldk.splatevpn.data.ServerNode
import com.danieldk.splatevpn.data.SessionPingResults
import com.danieldk.splatevpn.data.withSessionPing
import com.danieldk.splatevpn.ui.theme.SplateVPNTheme
import org.junit.Rule
import org.junit.Test

class ServerPingBadgeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun cachedErrorIsHiddenUntilFreshFailureAndClearsWithNewSession() {
        val historical = ServerNode("node", "Server", "vless", "example.com", 443, ping = -1L)
        val session = mutableStateOf(SessionPingResults())
        compose.setContent {
            val measured by session.value.results.collectAsState()
            SplateVPNTheme(languageSetting = "en") {
                ServerCard(server = historical.withSessionPing(measured), isSelected = false,
                    onSelect = {}, onEdit = {}, onMoreMenu = {})
            }
        }
        compose.onNodeWithContentDescription("No response", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("—").assertIsDisplayed()
        compose.runOnIdle { session.value.record("node", -1L) }
        compose.onNodeWithContentDescription("No response", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { session.value = SessionPingResults() }
        compose.onNodeWithContentDescription("No response", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("—").assertIsDisplayed()
        compose.runOnIdle { session.value.record("node", 42L) }
        compose.onNodeWithText("42 ms").assertIsDisplayed()
    }
}
