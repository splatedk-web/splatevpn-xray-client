package com.danieldk.splatevpn.data

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class SubscriptionUpdatePolicyTest {
    private val now = TimeUnit.DAYS.toMillis(27)
    private val old = SubscriptionEntity("old", "Public", "https://example.org/sub", lastUpdated = now - TimeUnit.DAYS.toMillis(4))

    @Test fun supportsHourlySchedulesWithoutChangingUnknownLegacyDefault() {
        listOf("1h" to 1L, "3h" to 3L, "6h" to 6L, "12h" to 12L, "24h" to 24L).forEach { (value, hours) ->
            assertEquals(hours, requireNotNull(SubscriptionUpdatePolicy.intervalHours(value)))
        }
        assertEquals(24L, requireNotNull(SubscriptionUpdatePolicy.intervalHours("unknown")))
        assertNull(SubscriptionUpdatePolicy.intervalHours("off"))
    }

    @Test fun globalOffOnboardingAndUnresolvedRecoveryPreventAutomaticNetworkUse() {
        assertFalse(SubscriptionUpdatePolicy.canRun("off", false, true))
        assertFalse(SubscriptionUpdatePolicy.canRun("1h", true, true))
        assertFalse(SubscriptionUpdatePolicy.canRun("1h", false, false))
        assertTrue(SubscriptionUpdatePolicy.canRun("1h", false, true))
    }

    @Test fun catchesFourDayOldSourcesButSkipsFreshDataUntilTheChosenInterval() {
        assertTrue(SubscriptionUpdatePolicy.isDue(old, "24h", now))
        val fresh = old.copy(lastUpdated = now - TimeUnit.MINUTES.toMillis(59))
        assertFalse(SubscriptionUpdatePolicy.isDue(fresh, "1h", now))
        assertTrue(SubscriptionUpdatePolicy.isDue(fresh, "1h", now + TimeUnit.MINUTES.toMillis(1)))
        assertFalse(SubscriptionUpdatePolicy.isDue(fresh, "3h", now + TimeUnit.MINUTES.toMillis(1)))
    }

    @Test fun bundledSeedIsDueButDisabledAndManualSourcesRemainExcluded() {
        assertTrue(SubscriptionUpdatePolicy.isDue(old.copy(lastUpdated = 0), "24h", now))
        assertFalse(SubscriptionUpdatePolicy.isDue(old.copy(enabled = false), "1h", now))
        assertFalse(SubscriptionUpdatePolicy.isDue(old.copy(autoUpdate = false), "1h", now))
        assertFalse(SubscriptionUpdatePolicy.isDue(old.copy(url = ""), "1h", now))
        assertFalse(SubscriptionUpdatePolicy.isDue(old, "off", now))
    }

    @Test fun reopeningAfterARecoverableFailureDoesNotBypassRetryCooldown() {
        val failed = old.copy(lastAttemptAt = now, lastError = "Network error", lastFailureRetryable = true)
        assertFalse(SubscriptionUpdatePolicy.isDue(failed, "1h", now))
        assertFalse(SubscriptionUpdatePolicy.isDue(failed, "1h", now + SubscriptionUpdatePolicy.retryDelayMillis - 1))
        assertTrue(SubscriptionUpdatePolicy.isDue(failed, "1h", now + SubscriptionUpdatePolicy.retryDelayMillis))
    }

    @Test fun permanentFailuresWaitForTheConfiguredPeriodRatherThanEveryResume() {
        val denied = old.copy(lastAttemptAt = now, lastError = "HTTP 403", lastFailureRetryable = false)
        assertFalse(SubscriptionUpdatePolicy.isDue(denied, "3h", now + SubscriptionUpdatePolicy.retryDelayMillis))
        assertTrue(SubscriptionUpdatePolicy.isDue(denied, "3h", now + TimeUnit.HOURS.toMillis(3)))
    }

    @Test fun aClockMovingBackwardsDoesNotCauseRepeatedAutomaticRequests() {
        assertFalse(SubscriptionUpdatePolicy.isDue(old.copy(lastUpdated = now + 1), "1h", now))
        assertFalse(SubscriptionUpdatePolicy.isDue(old.copy(lastAttemptAt = now + 1,
            lastError = "Network error", lastFailureRetryable = true), "1h", now))
    }
}
