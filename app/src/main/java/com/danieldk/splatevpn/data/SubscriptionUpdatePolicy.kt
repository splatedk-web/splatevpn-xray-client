package com.danieldk.splatevpn.data

import java.util.concurrent.TimeUnit

/** A successful download and a failed attempt have different freshness semantics. */
internal object SubscriptionUpdatePolicy {
    const val RETRY_DELAY_MINUTES = 15L
    val retryDelayMillis: Long = TimeUnit.MINUTES.toMillis(RETRY_DELAY_MINUTES)

    fun intervalHours(interval: String): Long? = when (interval) {
        "off" -> null
        "1h" -> 1L
        "3h" -> 3L
        "6h" -> 6L
        "12h" -> 12L
        else -> 24L
    }

    fun canRun(interval: String, onboardingPending: Boolean, networkSettingsReady: Boolean): Boolean =
        intervalHours(interval) != null && !onboardingPending && networkSettingsReady

    fun isDue(sub: SubscriptionEntity, interval: String, now: Long): Boolean {
        val period = intervalHours(interval)?.let(TimeUnit.HOURS::toMillis) ?: return false
        if (!sub.enabled || !sub.autoUpdate || sub.url.isBlank()) return false
        if (sub.lastUpdated > 0 && age(now, sub.lastUpdated) < period) return false
        if (sub.lastError.isNotBlank() && sub.lastAttemptAt > 0) {
            val cooldown = if (sub.lastFailureRetryable) retryDelayMillis else period
            if (age(now, sub.lastAttemptAt) < cooldown) return false
        }
        return true
    }

    private fun age(now: Long, previous: Long): Long = if (previous >= now) 0 else now - previous
}
