package com.danieldk.splatevpn.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class SettingsRecoveryTest {
    @Test fun malformedXmlRestoresSnapshotBeforeAnyTypedGetterRuns() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "recovery_test_${UUID.randomUUID()}"
        val directory = File(app.noBackupFilesDir, "settings-recovery/$name")
        val primary = File(app.applicationInfo.dataDir, "shared_prefs/$name.xml")
        val recovery = SettingsRecovery(app, name)
        try {
            primary.parentFile!!.mkdirs()
            primary.writeText("<map><boolean name=\"block_ipv6\" value=\"true\">")
            directory.mkdirs()
            File(directory, "last-good.json").writeText(PreferenceValues.encode(mapOf("routing_mode" to "global", "block_ipv6" to true)))
            val restored = recovery.open(app.getSharedPreferences(name, Context.MODE_PRIVATE))
            assertEquals("notice=${recovery.notice.value}, values=${restored.all}", "global", restored.getString("routing_mode", null))
            assertTrue(restored.getBoolean("block_ipv6", false))
            assertEquals(true, recovery.notice.value?.recovered)
        } finally {
            recovery.close()
            app.deleteSharedPreferences(name)
            directory.deleteRecursively()
        }
    }

    @Test fun wrongTypeRestoresLastGoodSnapshotWithoutSilentlyResetting() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "recovery_test_${UUID.randomUUID()}"
        val prefs = app.getSharedPreferences(name, Context.MODE_PRIVATE)
        val directory = File(app.noBackupFilesDir, "settings-recovery/$name")
        var recovery: SettingsRecovery? = null
        try {
            prefs.edit().putString("routing_mode", "global").putBoolean("block_ipv6", true).commit()
            directory.mkdirs()
            File(directory, "last-good.json").writeText(PreferenceValues.encode(prefs.all))
            prefs.edit().putString("block_ipv6", "wrong-type").commit()
            recovery = SettingsRecovery(app, name)
            val restored = recovery.open(prefs)
            assertTrue(restored.getBoolean("block_ipv6", false))
            assertEquals("global", restored.getString("routing_mode", null))
            assertEquals(true, recovery.notice.value?.recovered)
            assertEquals(false, recovery.notice.value?.needsDecision)
            assertTrue(directory.listFiles()!!.any { it.name.startsWith("original-") })
        } finally {
            recovery?.close()
            app.deleteSharedPreferences(name)
            directory.deleteRecursively()
        }
    }

    @Test fun missingSnapshotQuarantinesAndRequiresExplicitDecision() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "recovery_test_${UUID.randomUUID()}"
        val prefs = app.getSharedPreferences(name, Context.MODE_PRIVATE)
        val recovery = SettingsRecovery(app, name)
        try {
            prefs.edit().putString("block_ipv6", "broken-original").commit()
            val preview = recovery.open(prefs)
            assertNotSame(prefs, preview)
            assertEquals(true, recovery.notice.value?.needsDecision)
            assertEquals("broken-original", prefs.all["block_ipv6"])
            preview.edit().putString("routing_mode", "smart_ru").commit()
            assertSame(prefs, recovery.acceptDefaults())
            assertEquals("smart_ru", prefs.getString("routing_mode", null))
            assertFalse(prefs.contains("block_ipv6"))
            assertNull(recovery.notice.value)
        } finally {
            recovery.close()
            app.deleteSharedPreferences(name)
            app.deleteSharedPreferences("${name}_recovery_preview")
            File(app.noBackupFilesDir, "settings-recovery/$name").deleteRecursively()
        }
    }

    @Test fun profileApplyAndUndoRestoreNetworkSettingsWithoutTouchingThemeOrSubscription() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "profile_test_${UUID.randomUUID()}_"
        val context = object : ContextWrapper(app) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(prefix + name, mode)
        }
        try {
            val settings = SettingsManager(context)
            settings.setAppTheme("dark")
            settings.setSubscriptionUrl("https://private.example/secret")
            settings.setRoutingMode("smart_ru")
            settings.setAppRouting(AppRoutingMode.EXCLUDE, setOf("bank"))
            val profiles = ConnectionProfiles(settings)
            profiles.save("My settings")
            assertTrue(profiles.apply("all"))
            assertEquals("global", settings.getRoutingMode())
            assertTrue(settings.isGlobalAppProxy())
            assertEquals("dark", settings.getAppTheme())
            assertEquals("https://private.example/secret", settings.getSubscriptionUrl())
            assertTrue(profiles.undo())
            assertEquals("smart", settings.getRoutingMode())
            assertEquals("ru", settings.getRoutingRegion())
            assertFalse(settings.isGlobalAppProxy())
            assertEquals(setOf("bank"), settings.getPerAppPackages())
            assertFalse(profiles.canUndo.value)
            assertEquals(1, ConnectionProfiles(SettingsManager(context)).profiles.value.size)
        } finally { app.deleteSharedPreferences(prefix + "splate_prefs") }
    }
}
