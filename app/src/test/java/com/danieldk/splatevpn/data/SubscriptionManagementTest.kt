package com.danieldk.splatevpn.data

import org.junit.Assert.*
import org.junit.Test

class SubscriptionManagementTest {
    private val subscriptions = listOf(
        SubscriptionEntity("auto", "Aurora", "https://example.org/one"),
        SubscriptionEntity("manual", "Manual", "https://provider.test/two", autoUpdate = false),
        SubscriptionEntity("disabled", "Disabled", "https://example.org/three", enabled = false),
        SubscriptionEntity("empty", "Empty", "")
    )

    @Test fun manualRefreshIncludesManualSubscriptionsButSkipsDisabledAndMissingUrls() {
        assertEquals(listOf("auto", "manual"), subscriptionsToUpdate(subscriptions, manual = true).map { it.id })
    }

    @Test fun backgroundRefreshHonorsAutoUpdatePreference() {
        assertEquals(listOf("auto"), subscriptionsToUpdate(subscriptions, manual = false).map { it.id })
    }

    @Test fun searchMatchesNameAndUrlIgnoringCaseAndOuterWhitespace() {
        assertEquals(listOf("auto"), filterSubscriptions(subscriptions, "  AURORA  ").map { it.id })
        assertEquals(listOf("manual"), filterSubscriptions(subscriptions, "PROVIDER.TEST").map { it.id })
    }

    @Test fun searchReturnsNoMatchesAndIncludesDisabledSubscriptions() {
        assertTrue(filterSubscriptions(subscriptions, "zzz_no_match").isEmpty())
        assertEquals(listOf("disabled"), filterSubscriptions(subscriptions, "Disabled").map { it.id })
        assertEquals(subscriptions, filterSubscriptions(subscriptions, "  "))
    }
}
