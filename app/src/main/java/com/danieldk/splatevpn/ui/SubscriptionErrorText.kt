package com.danieldk.splatevpn.ui

import android.content.res.Resources
import com.danieldk.splatevpn. R
import com.danieldk.splatevpn.data.SubscriptionHwidException
import com.danieldk.splatevpn.data.SubscriptionHwidFailure

internal fun subscriptionErrorText(resources: Resources, error: SubscriptionHwidException): String = resources.getString(
    when (error.reason) {
        SubscriptionHwidFailure.REQUIRED -> R.string.subscription_hwid_required
        SubscriptionHwidFailure.DEVICE_LIMIT -> R.string.subscription_hwid_device_limit
    }
)

internal fun subscriptionErrorText(resources: Resources, label: String): String = when (label) {
    "HWID required" -> resources.getString(R.string.subscription_hwid_required)
    "HWID device limit reached" -> resources.getString(R.string.subscription_hwid_device_limit)
    else -> label
}
