package com.danieldk.splatevpn.data

import java.io.IOException
import javax.net.ssl.SSLException

internal fun isRetryableSubscriptionFailure(error: Throwable): Boolean {
    val causes = generateSequence(error) { it.cause }.take(12).toList()
    if (causes.any { it is WifiRequiredException }) return true
    if (causes.any { it is SubscriptionHwidException }) return false
    if (causes.any { it is SubscriptionDowngradeException }) return false
    val http = causes.filterIsInstance<SubscriptionHttpException>().firstOrNull()
    if (http != null) return http.status in setOf(408, 425, 429) || http.status in 500..599
    if (causes.any { it is IllegalArgumentException }) return false
    if (causes.any { it is SSLException || it is java.security.cert.CertificateException }) return false
    return causes.any { it is IOException }
}

/** Persist only categories, never a URL containing a private subscription token. */
internal fun subscriptionFailureLabel(error: Throwable): String {
    val causes = generateSequence(error) { it.cause }.take(12).toList()
    if (causes.any { it is WifiRequiredException }) return "Wi-Fi required"
    causes.filterIsInstance<SubscriptionHwidException>().firstOrNull()?.let {
        return when (it.reason) {
            SubscriptionHwidFailure.REQUIRED -> "HWID required"
            SubscriptionHwidFailure.DEVICE_LIMIT -> "HWID device limit reached"
        }
    }
    if (causes.any { it is SubscriptionDowngradeException }) return "HTTPS downgrade rejected"
    causes.filterIsInstance<SubscriptionHttpException>().firstOrNull()?.let { return "HTTP ${it.status}" }
    if (causes.any { it is SubscriptionPlaceholderException }) return "Subscription contains only service placeholders"
    if (causes.any { it is IllegalArgumentException }) return "Invalid subscription data"
    if (causes.any { it is SSLException || it is java.security.cert.CertificateException }) return "TLS certificate error"
    return if (causes.any { it is IOException }) "Network error" else "Invalid subscription data"
}
