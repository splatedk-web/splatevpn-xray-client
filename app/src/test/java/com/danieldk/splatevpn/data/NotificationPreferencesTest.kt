package com.danieldk.splatevpn.data

import org.junit.Assert.*
import org.junit.Test

class NotificationPreferencesTest {
    @Test fun defaultsPreserveExistingAlertsAndSilentDnsAdvice() {
        val p = NotificationPreferences()
        HealthIssueKind.entries.forEach { assertEquals(it.toString(), it != HealthIssueKind.DNS_BLOCKED, p.allows(it)) }
        assertTrue(p.showSpeed && p.showServer && p.showDuration)
    }
    @Test fun masterDisablesEverySystemAlert() {
        val p = NotificationPreferences(healthAlerts = false, dnsIssues = true)
        assertTrue(HealthIssueKind.entries.none(p::allows))
    }
    @Test fun categoriesAreIndependentAndRoundTripAllChoices() {
        val p = NotificationPreferences(connectionIssues = false, dnsIssues = true, accessIssues = false, deviceIssues = false, showSpeed = false, showServer = false, showDuration = false)
        assertEquals(listOf(HealthIssueKind.DNS_BLOCKED), HealthIssueKind.entries.filter(p::allows))
        assertEquals(p, NotificationPreferences.read { key, default -> p.values()[key] ?: default })
        assertEquals(NotificationPreferences(), NotificationPreferences.read { _, default -> default })
    }
}
