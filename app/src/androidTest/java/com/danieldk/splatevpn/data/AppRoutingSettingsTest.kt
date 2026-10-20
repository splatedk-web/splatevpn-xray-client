package com.danieldk.splatevpn.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class AppRoutingSettingsTest {
    @Test fun completeRulePersistsAndEmptyIncludeDoesNotOverwriteIt() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val context = object : ContextWrapper(app) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("app_routing_test_$name", mode)
        }
        try {
            val settings = SettingsManager(context)
            settings.setAppRouting(AppRoutingMode.EXCLUDE, setOf("bank"))
            assertFalse(settings.isGlobalAppProxy())
            assertEquals("exclude", settings.getPerAppMode())
            assertEquals(setOf("bank"), settings.getPerAppPackages())
            try {
                settings.setAppRouting(AppRoutingMode.INCLUDE, emptySet())
                fail("Empty include must be rejected")
            } catch (_: IllegalArgumentException) { }
            val reloaded = SettingsManager(context)
            assertFalse(reloaded.isGlobalAppProxy())
            assertEquals("exclude", reloaded.getPerAppMode())
            assertEquals(setOf("bank"), reloaded.getPerAppPackages())
            reloaded.setAppRouting(AppRoutingMode.ALL, setOf("bank"))
            assertTrue(reloaded.isGlobalAppProxy())
            assertEquals(setOf("bank"), reloaded.getPerAppPackages())
        } finally { app.deleteSharedPreferences("app_routing_test_splate_prefs") }
    }
}
