package com.danieldk.splatevpn.data

fun filterSubscriptions(subscriptions: List<SubscriptionEntity>, query: String): List<SubscriptionEntity> {
    val term = query.trim()
    return if (term.isEmpty()) subscriptions else subscriptions.filter {
        it.name.contains(term, ignoreCase = true) || it.url.contains(term, ignoreCase = true)
    }
}

fun subscriptionsToUpdate(subscriptions: List<SubscriptionEntity>, manual: Boolean): List<SubscriptionEntity> =
    subscriptions.filter { it.enabled && it.url.isNotBlank() && (manual || it.autoUpdate) }

data class SubscriptionUpdateReport(
    val updated: Int,
    val failures: List<Pair<String, String>>
)
