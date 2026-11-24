package com.danieldk.splatevpn.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.danieldk.splatevpn.data.*
import com.danieldk.splatevpn.ui.theme.SplateVPNTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DnsProviderChoicesTest {
    @get:Rule val compose = createComposeRule()
    @Test fun showsDnsAnswersWithoutAutomaticallyChangingProviderAndAllowsCancellation() {
        var selected by mutableStateOf("cloudflare")
        var running by mutableStateOf(false)
        var cancelled = false
        compose.setContent {
            SplateVPNTheme(themeSetting = "sunset", languageSetting = "ru") {
                Box(Modifier.padding(18.dp)) {
                    DnsProviderChoices(selected, "", DnsProviderCheckState(running, mapOf(
                        "google" to DnsProviderCheckResult(DnsProviderCheckStatus.AVAILABLE, 42),
                        "yandex" to DnsProviderCheckResult(DnsProviderCheckStatus.FAILED))), true,
                        onSelect = { selected = it }, onCustomUrl = {}, onCheck = { running = true },
                        onCancel = { running = false; cancelled = true })
                }
            }
        }
        compose.onNodeWithTag("dnsResult:google").assertTextEquals("✓ 42 мс").assertContentDescriptionEquals("Отвечает · 42 мс")
        compose.onNodeWithTag("dnsResult:yandex").assertTextEquals("Нет ответа")
        compose.runOnIdle { assertEquals("cloudflare", selected) }
        compose.onNodeWithText("Проверить DNS-провайдеры").performClick()
        compose.onNodeWithText("Отмена").performClick()
        compose.runOnIdle { assertTrue(cancelled) }
        compose.onNodeWithTag("dnsProvider:google").performClick()
        compose.runOnIdle { assertEquals("google", selected) }
    }
}
