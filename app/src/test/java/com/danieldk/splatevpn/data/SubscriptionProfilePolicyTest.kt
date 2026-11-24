package com.danieldk.splatevpn.data

import org.junit.Assert.*
import org.junit.Test
import java.net.URLEncoder

class SubscriptionProfilePolicyTest {
    private fun link(name: String) = "vless://00000000-0000-0000-0000-000000000000@198.51.100.1:443#" +
        URLEncoder.encode(name, "UTF-8")

    @Test fun encodedProviderOutageAndAdvertisingAreNotAcceptedAsWorkingServers() {
        val placeholder = listOf(link("⚠️Подписка не работает!"), link("Новые подписки в @RKP_channel")).joinToString("\n")
        assertThrows(SubscriptionPlaceholderException::class.java) { parseUsableSubscription(placeholder, "test", "Test") }
        assertEquals("Subscription contains only service placeholders", subscriptionFailureLabel(SubscriptionPlaceholderException()))
    }

    @Test fun publisherCreditsAndNormalProfilesRemainImportableAlongsideRejectedNotice() {
        val content = listOf(link("Новые подписки в @RKP_channel"), link("DE @Publisher"), link("Работает в мобильной сети")).joinToString("\n")
        val nodes = parseUsableSubscription(content, "test", "Test")
        assertEquals(listOf("DE @Publisher", "Работает в мобильной сети"), nodes.map { it.name })
        assertTrue(nodes.all { it.subId == "test" })
    }
}
