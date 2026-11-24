package com.danieldk.splatevpn.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Device-local onboarding progress: these flags never enter exported or transferred settings. */
internal class TvSetupPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("splate_tv_setup", Context.MODE_PRIVATE)
    private val _offerPending = MutableStateFlow(
        preferences.getBoolean(PENDING, false) && !preferences.getBoolean(HANDLED, false)
    )
    val offerPending = _offerPending.asStateFlow()

    /** Call only after onboarding succeeds; replaying onboarding never repeats a handled offer. */
    @Synchronized
    fun afterOnboarding(isTelevision: Boolean) {
        if (!isTelevision || preferences.getBoolean(HANDLED, false)) return
        check(preferences.edit().putBoolean(PENDING, true).commit()) { "Could not save TV setup progress" }
        _offerPending.value = true
    }

    /** Both Receive and Later are explicit choices that finish the one-time offer. */
    @Synchronized
    fun finishOffer() {
        check(preferences.edit().putBoolean(HANDLED, true).remove(PENDING).commit()) { "Could not save TV setup progress" }
        _offerPending.value = false
    }

    private companion object {
        const val PENDING = "phone_transfer_offer_pending_v1"
        const val HANDLED = "phone_transfer_offer_handled_v1"
    }
}

internal fun deferTvSetupConnection(
    isTelevision: Boolean,
    onboardingPending: Boolean,
    offerPending: Boolean,
    startupPending: Boolean = false
): Boolean = isTelevision && (onboardingPending || offerPending || startupPending)
