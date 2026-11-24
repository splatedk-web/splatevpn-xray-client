package com.danieldk.splatevpn.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class UiPerformancePreferencesTest {
    @Test fun lightModePersistsAndLeavesThemeTransparencyAndNetworkPreferencesUntouched() = isolated { context ->
        val preferences = context.getSharedPreferences("splate_prefs", Context.MODE_PRIVATE)
        check(preferences.edit().putString("app_theme", "ocean").putFloat("glass_transparency", .43f)
            .putString("custom_doh_url", "https://example.org/dns-query").commit())
        val settings = SettingsManager(context)
        val network = NetworkPreferences.capture(settings.preferenceSnapshot())
        assertEquals(UiPerformanceSettings(), settings.performanceSettings.value)
        assertTrue(settings.glassBlurEnabled.value)

        settings.setLightUiMode()
        assertEquals(UiPerformanceSettings(false, false, false), settings.performanceSettings.value)
        assertFalse(settings.glassBlurEnabled.value)
        val restarted = SettingsManager(context)
        assertEquals(settings.performanceSettings.value, restarted.performanceSettings.value)
        assertFalse(restarted.glassBlurEnabled.value)
        assertEquals("ocean", restarted.getAppTheme())
        assertEquals(.43f, restarted.getGlassTransparency(), .001f)
        assertEquals(network, NetworkPreferences.capture(restarted.preferenceSnapshot()))
    }

    @Test fun individualChoicesRemainIndependentAndFullEffectsCanBeRestored() = isolated { context ->
        val settings = SettingsManager(context)
        settings.setUiAnimationsEnabled(false)
        assertEquals(UiPerformanceSettings(false, true, true), settings.performanceSettings.value)
        settings.setGlassHighlightsEnabled(false)
        assertEquals(UiPerformanceSettings(false, true, false), settings.performanceSettings.value)
        settings.setDecorativeBackgroundEnabled(false)
        settings.setGlassBlurEnabled(false)
        assertEquals(UiPerformanceSettings(false, false, false), SettingsManager(context).performanceSettings.value)

        settings.setUiAnimationsEnabled(true)
        assertEquals(UiPerformanceSettings(true, false, false), settings.performanceSettings.value)
        assertFalse("An individual animation choice must not silently re-enable blur", settings.glassBlurEnabled.value)
        settings.restoreFullUiEffects()
        val restarted = SettingsManager(context)
        assertEquals(UiPerformanceSettings(), restarted.performanceSettings.value)
        assertTrue(restarted.glassBlurEnabled.value)
    }

    private fun isolated(block: (Context) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "ui-performance-${UUID.randomUUID()}"
        val context = object : ContextWrapper(app) {
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences("$prefix-$name", mode)
        }
        try { block(context) } finally { app.deleteSharedPreferences("$prefix-splate_prefs") }
    }
}
