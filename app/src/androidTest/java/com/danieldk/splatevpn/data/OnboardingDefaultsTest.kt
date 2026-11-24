package com.danieldk.splatevpn.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class OnboardingDefaultsTest {
    private fun settings(block: (SettingsManager) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "setup-defaults-${UUID.randomUUID()}"
        val context = object : ContextWrapper(app) {
            override fun getSharedPreferences(ignored: String?, mode: Int) = app.getSharedPreferences(name, Context.MODE_PRIVATE)
        }
        try { block(SettingsManager(context)) } finally { app.deleteSharedPreferences(name) }
    }

    @Test fun freshSetupEnablesAutomaticSelectionAndAppliesEverySupportedRegion() = settings { manager ->
        for (region in RoutingRegion.entries) {
            manager.completeOnboarding("en", region.id, "smart", false)
            assertEquals(region.id, manager.getRoutingRegion())
            assertEquals("smart", manager.getRoutingMode())
            assertTrue(manager.isAutoSelectServer())
        }
    }

    @Test fun anExplicitManualChoiceSurvivesSetupAndRepeatedTours() = settings { manager ->
        manager.setAutoSelectServer(false)
        manager.completeOnboarding("ru", "ru", "smart", false)
        assertFalse(manager.isAutoSelectServer())
        manager.showOnboarding()
        manager.completeOnboarding("ru", "cn", "smart", false)
        assertFalse(manager.isAutoSelectServer())
    }
}
