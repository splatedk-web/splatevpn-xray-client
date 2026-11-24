package com.danieldk.splatevpn.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class TvSetupPreferencesTest {
    @Test fun offerSurvivesRestartButHandledOfferDoesNotRepeatAfterSetup() {
        withIsolatedPreferences { context ->
            val first = TvSetupPreferences(context)
            assertFalse(first.offerPending.value)
            first.afterOnboarding(isTelevision = true)
            assertTrue(first.offerPending.value)

            val restarted = TvSetupPreferences(context)
            assertTrue(restarted.offerPending.value)
            assertTrue("Auto-connect must wait for the TV transfer choice", deferTvSetupConnection(true, false, restarted.offerPending.value))
            restarted.finishOffer()
            assertFalse(restarted.offerPending.value)

            val anotherLaunch = TvSetupPreferences(context)
            anotherLaunch.afterOnboarding(isTelevision = true)
            assertFalse("A completed choice is device-local and must not be offered on every launch", anotherLaunch.offerPending.value)
            assertFalse(deferTvSetupConnection(true, false, anotherLaunch.offerPending.value))
        }
    }

    @Test fun phoneSetupHasNoOfferAndTvCannotConnectBeforeSetupCompletes() {
        withIsolatedPreferences { context ->
            val setup = TvSetupPreferences(context)
            setup.afterOnboarding(isTelevision = false)
            assertFalse(setup.offerPending.value)
            assertTrue(deferTvSetupConnection(true, true, false))
            assertTrue("The gap between saving onboarding and preparing the offer must not allow TV auto-connect",
                deferTvSetupConnection(true, false, false, startupPending = true))
            assertFalse("After Later and startup completion, manual TV connection must remain available",
                deferTvSetupConnection(true, false, false, startupPending = false))
            assertFalse("The TV-only offer must not change phone onboarding connection behaviour", deferTvSetupConnection(false, true, true))
            assertFalse(deferTvSetupConnection(false, false, false, startupPending = true))
        }
    }

    private fun withIsolatedPreferences(block: (Context) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "tv-setup-test-${UUID.randomUUID()}"
        val context = object : ContextWrapper(app) {
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences("$prefix-$name", mode)
        }
        try { block(context) } finally { app.deleteSharedPreferences("$prefix-splate_tv_setup") }
    }
}
