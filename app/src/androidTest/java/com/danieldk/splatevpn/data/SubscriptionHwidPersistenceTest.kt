package com.danieldk.splatevpn.data

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.SplateConstants
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class SubscriptionHwidPersistenceTest {
    private val source = "https://private.example/subscription/account-token"

    private fun fixture(test: suspend (Context, AppDatabase, SettingsManager, BackupManager) -> Unit) = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "hwid-${UUID.randomUUID()}"
        val directory = File(app.cacheDir, name).apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(directory, "no-backup").apply { mkdirs() }
            override fun getSharedPreferences(key: String, mode: Int) = app.getSharedPreferences("$name-$key", mode)
        }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val settings = SettingsManager(context)
        val vault = AutoBackupManager(context, db.serverDao(), db.subscriptionDao(), AppLogManager(context), db)
        try { test(context, db, settings, BackupManager(context, db.serverDao(), db.subscriptionDao(), settings, db, vault)) }
        finally {
            db.close()
            directory.deleteRecursively()
            app.deleteSharedPreferences("$name-splate_prefs")
            app.deleteSharedPreferences("$name-vault_metadata")
            SubscriptionUpdateWorker.schedule(app, SettingsManager(app).getAutoUpdateInterval())
        }
    }

    @Test fun sourceIdentitySurvivesRecreationAndConcurrentRefreshes() = fixture { context, _, settings, _ ->
        val executor = Executors.newFixedThreadPool(4)
        val first = try {
            val results = executor.invokeAll((1..16).map { Callable { SubscriptionHwidStore(context).idForSource(source) } })
            val ids = results.map { it.get() }.toSet()
            assertEquals(1, ids.size)
            ids.single()
        } finally { executor.shutdownNow() }
        assertTrue(first.matches(Regex("[a-f0-9]{64}")))
        assertEquals(first, SubscriptionHwidStore(context).idForSource(source))
        assertNotEquals(first, SubscriptionHwidStore(context).idForSource("$source-other"))
        assertFalse(settings.isSubscriptionHwidEnabled(source))
        settings.setSubscriptionHwidEnabled(source, true)
        assertTrue(SettingsManager(context).isSubscriptionHwidEnabled(source))
        assertEquals(first, SettingsManager(context).subscriptionHwidStore.idForSource(source))
    }

    @Test fun damagedSecretDoesNotSilentlyRegisterAnotherDevice() = fixture { context, _, _, _ ->
        SubscriptionHwidStore(context).idForSource(source)
        val secretFile = File(context.noBackupFilesDir, "subscription-hwid-v1.key")
        secretFile.writeBytes(byteArrayOf(1, 2, 3))
        assertThrows(java.io.IOException::class.java) { SubscriptionHwidStore(context).idForSource(source) }
        assertArrayEquals(byteArrayOf(1, 2, 3), secretFile.readBytes())
    }

    @Test fun sponsorDefaultAndExplicitChoiceSurviveSettingsRecreation() = fixture { context, _, settings, _ ->
        val sponsor = SplateConstants.PRESET_SUB_SPONSOR_URL
        val source = sponsor.ifBlank { "https://subscriptions.example/manual" }
        assertEquals(sponsor.isNotBlank(), settings.isSubscriptionHwidEnabled(source))
        settings.setSubscriptionHwidEnabled(source, true)
        assertTrue(SettingsManager(context).isSubscriptionHwidEnabled(source))
        settings.setSubscriptionHwidEnabled(source, false)
        assertFalse(SettingsManager(context).isSubscriptionHwidEnabled(source))
        settings.setSubscriptionHwidEnabled("https://raw.githubusercontent.com/owner/repo/main/list.txt", true)
        assertFalse(settings.isSubscriptionHwidEnabled("https://raw.githubusercontent.com/owner/repo/main/list.txt"))
        assertEquals(1, settings.getSubscriptionHwidOverrides().size)
    }

    @Test fun backupTransfersConsentButNeverClonesInstallationIdentity() = fixture { context, _, settings, manager ->
        val first = settings.subscriptionHwidStore.idForSource(source)
        settings.setSubscriptionHwidEnabled(source, true)
        val file = File(context.cacheDir, "hwid-backup-${UUID.randomUUID()}.json")
        try {
            assertTrue(manager.exportBackup(Uri.fromFile(file)).isSuccess)
            val document = file.readText()
            assertFalse(document.contains(first))
            assertFalse(document.contains("account-token"))
            fixture { otherContext, _, otherSettings, otherManager ->
                val other = otherSettings.subscriptionHwidStore.idForSource(source)
                assertNotEquals(first, other)
                assertTrue(otherManager.importBackup(Uri.fromFile(file), BackupManager.ImportMode.REPLACE).isSuccess)
                assertTrue(otherSettings.isSubscriptionHwidEnabled(source))
                assertEquals(other, SubscriptionHwidStore(otherContext).idForSource(source))
            }
            settings.setSubscriptionHwidEnabled(source, false)
            assertTrue(manager.importBackup(Uri.fromFile(file), BackupManager.ImportMode.REPLACE).isSuccess)
            assertTrue(settings.isSubscriptionHwidEnabled(source))
            assertEquals(first, SubscriptionHwidStore(context).idForSource(source))
        } finally { file.delete() }
    }
}
