package com.danieldk.splatevpn.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class InternationalSettingsTest {
    private fun isolated(block: (Context) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "international_${UUID.randomUUID()}_"
        val directory = File(app.cacheDir, prefix).apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences(prefix + name, mode)
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(directory, "private").apply { mkdirs() }
        }
        try { block(context) } finally {
            listOf("splate_prefs", "temporary_routing", "network_profile_bindings").forEach { app.deleteSharedPreferences(prefix + it) }
            directory.deleteRecursively()
        }
    }
    @Test fun personalFolderDisablesFutureNodesButKeepsSubscriptions() = isolated { context ->
        val settings = SettingsManager(context)
        settings.storeDisabledServerIds(setOf("__personal_folder__"))
        val reopened = SettingsManager(context)
        assertFalse(reopened.isPersonalFolderEnabled())
        assertFalse(reopened.isServerEnabled(ServerNode("new", "New", "vless", "example.com", 443, subId = "custom")))
        assertTrue(reopened.isServerEnabled(ServerNode("subscription", "Sub", "vless", "example.com", 443, subId = "my-sub")))
        reopened.storeDisabledServerIds(emptySet())
        assertTrue(reopened.isPersonalFolderEnabled())
    }

    @Test fun disabledNodesPersistAndCanBeEnabledIndependently() = isolated { context ->
        val settings = SettingsManager(context)
        settings.setServersEnabled(setOf("personal-a", "personal-b"), false)
        assertEquals(setOf("personal-a", "personal-b"), SettingsManager(context).getDisabledServerIds())
        settings.setServersEnabled(setOf("personal-a"), true)
        assertEquals(setOf("personal-b"), settings.disabledServerIds.value)
        val snapshot = settings.preferenceSnapshot()
        settings.setServersEnabled(setOf("personal-b"), true)
        settings.restorePreferenceSnapshot(snapshot)
        assertEquals(setOf("personal-b"), settings.disabledServerIds.value)
    }

    @Test fun wifiOnlyPoliciesDefaultOffAndRestoreTheirReactiveValues() = isolated { context ->
        val settings = SettingsManager(context)
        assertFalse(settings.isUpdatesWifiOnly())
        assertFalse(settings.isChecksWifiOnly())
        settings.setUpdatesWifiOnly(true)
        settings.setChecksWifiOnly(true)
        assertTrue(settings.updatesWifiOnly.value)
        assertTrue(settings.checksWifiOnly.value)
        val snapshot = settings.preferenceSnapshot()
        settings.setUpdatesWifiOnly(false)
        settings.setChecksWifiOnly(false)
        settings.restorePreferenceSnapshot(snapshot)
        assertTrue(settings.updatesWifiOnly.value)
        assertTrue(settings.checksWifiOnly.value)
        val reopened = SettingsManager(context)
        assertTrue(reopened.isUpdatesWifiOnly())
        assertTrue(reopened.isChecksWifiOnly())
    }

    @Test fun freshInstallUsesLocalRoutingAndWaitsForOnboarding() = isolated { context ->
        val settings = SettingsManager(context)
        assertEquals("smart", settings.getRoutingMode())
        assertEquals("real_delay", settings.getPingMode())
        assertEquals("vpn", settings.getAiRoutingMode())
        assertTrue(settings.needsOnboarding.value)
        assertTrue(settings.regionalPresetsEnabled())
        settings.completeOnboarding("fa", "cn", "smart", false)
        val reopened = SettingsManager(context)
        assertEquals("fa", reopened.getAppLanguage())
        assertEquals("cn", reopened.getRoutingRegion())
        assertEquals("smart", reopened.getRoutingMode())
        assertFalse(reopened.needsOnboarding.value)
        assertFalse(reopened.regionalPresetsEnabled())
    }
    @Test fun existingInstallKeepsRussianRoutingWithoutForcingOnboarding() = isolated { context ->
        context.getSharedPreferences("splate_prefs", 0).edit().putString("routing_mode", "smart_ru").putString("app_language", "en").commit()
        val settings = SettingsManager(context)
        assertEquals("ru", settings.getRoutingRegion())
        assertEquals("smart", settings.getRoutingMode())
        assertFalse(settings.needsOnboarding.value)
        assertEquals("en", settings.getAppLanguage())
        assertTrue(settings.regionalPresetsEnabled())
    }
    @Test fun invalidOnboardingDoesNotPartiallyWritePreferences() = isolated { context ->
        val settings = SettingsManager(context)
        val before = settings.preferenceSnapshot()
        assertThrows(IllegalArgumentException::class.java) { settings.completeOnboarding("fa", "bad", "smart", false) }
        assertEquals(before, settings.preferenceSnapshot())
    }
    @Test fun temporaryRulesExpireAndRestorePermanentRoute() = isolated { context ->
        val routing = TemporaryRouting(context)
        routing.add("example.com", false, "proxy", 15)
        val base = listOf("domain:example.com" to "direct")
        assertEquals("full:example.com" to "proxy", routing.domainRoutes(base).first())
        val entry = routing.entries.value.single()
        assertTrue(entry.active(entry.expiresAt - 1))
        assertFalse(entry.active(entry.expiresAt))
        assertFalse(entry.active(entry.createdAt - 1))
        routing.remove(entry.id)
        assertEquals(base, routing.domainRoutes(base))
    }
    @Test fun removingTemporaryAppRoutingDoesNotLeaveHiddenBypasses() = isolated { context ->
        val now = System.currentTimeMillis()
        val old = listOf(TemporaryException("app", "com.example.old", true, "direct", now, now + 60_000),
            TemporaryException("site", "example.com", false, "proxy", now, now + 60_000))
        context.getSharedPreferences("temporary_routing", 0).edit()
            .putString("entries", com.google.gson.Gson().toJson(old)).commit()
        val routing = TemporaryRouting(context)
        assertTrue(routing.bypassApps().isEmpty())
        assertEquals(listOf("site"), routing.entries.value.map { it.id })
        assertEquals(listOf("site"), TemporaryRouting(context).entries.value.map { it.id })
        assertThrows(IllegalArgumentException::class.java) { routing.add("com.example.old", true, "direct", 15) }
    }
    @Test fun networkBindingRetainsReviewedSnapshotAndCanUndo() = isolated { context ->
        val settings = SettingsManager(context)
        val profiles = ConnectionProfiles(settings)
        profiles.save("China")
        val id = profiles.profiles.value.single().id
        val bindings = NetworkProfileBindings(context, settings, profiles)
        bindings.bind("wifi", id, "China")
        settings.setRoutingRegion("ir")
        assertTrue(bindings.apply("wifi"))
        assertNotEquals("ir", settings.getRoutingRegion())
        assertTrue(profiles.undo())
        assertEquals("ir", settings.getRoutingRegion())
        bindings.bind("wifi", null)
        assertFalse(bindings.apply("wifi"))
    }
}
