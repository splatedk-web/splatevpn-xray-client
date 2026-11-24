package com.danieldk.splatevpn.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class AiDnsFailoverSettingsTest {
    @Test fun switchPersistsAndProfileRestoreUpdatesItsFlow() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val context = object : ContextWrapper(app) {
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("ai_failover_test_$name", mode)
        }
        try {
            val settings = SettingsManager(context)
            assertTrue(settings.isAiDnsAutoSwitchEnabled())
            settings.setAiDnsAutoSwitchEnabled(false)
            assertFalse(settings.aiDnsAutoSwitchFlow.value)
            assertFalse(SettingsManager(context).isAiDnsAutoSwitchEnabled())
            val snapshot = settings.preferenceSnapshot().toMutableMap()
            snapshot["ai_dns_auto_switch"] = true
            settings.restorePreferenceSnapshot(snapshot)
            assertTrue(settings.aiDnsAutoSwitchFlow.value)
            assertEquals(true, settings.networkPreferences.value["ai_dns_auto_switch"])
        } finally { app.deleteSharedPreferences("ai_failover_test_splate_prefs") }
    }
}
