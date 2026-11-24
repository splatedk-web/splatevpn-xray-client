package com.danieldk.splatevpn.data

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.vpn.LanShareManager
import com.danieldk.splatevpn.vpn.VpnStatusManager
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class CloudBackupMigrationTest {
    private fun fixture(test: suspend (Context, WebDavSyncManager, DeviceSecretStore) -> Unit) = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "cloud-migration-${UUID.randomUUID()}"
        val directory = File(app.cacheDir, prefix).apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(directory, "private").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences("$prefix-$name", mode)
        }
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val settings = SettingsManager(context)
        val vault = AutoBackupManager(context, database.serverDao(), database.subscriptionDao(), AppLogManager(context), database)
        val backup = BackupManager(context, database.serverDao(), database.subscriptionDao(), settings, database, vault)
        val transfer = DeviceTransferManager(context, backup, LanShareManager(context), VpnStatusManager(context))
        val webDav = WebDavSyncManager(context, backup, transfer, settings)
        val store = DeviceSecretStore(context, "splate-cloud-backup-v1")
        try { test(context, webDav, store) }
        finally {
            transfer.stopSending(); transfer.dismissPending(); store.delete(); database.close()
            directory.deleteRecursively()
            for (name in listOf("splate_prefs", "vault_metadata")) app.deleteSharedPreferences("$prefix-$name")
        }
    }

    private fun legacy(target: String) = JsonObject().apply {
        add("settings", JsonObject().apply {
            addProperty("enabled", true); addProperty("target", target)
            addProperty("treeUri", if (target == "folder") "content://old.provider/tree/selected-folder" else "")
            addProperty("unmeteredOnly", false); addProperty("intervalHours", 6)
            // Derive this hint from the retained secret even if old metadata was stale.
            addProperty("hasPassword", false); addProperty("lastSuccessAt", 1234)
            addProperty("lastError", "")
        })
        addProperty("password", "kept-encryption-secret")
        addProperty("device", "123456abcdef")
    }.toString()

    @Test fun oldFolderJobStopsAndKeepsSecretFilesAndDismissedNoticeAcrossRestart() = fixture { context, webDav, store ->
        val oldFile = File(context.filesDir, "previous-folder-snapshot.json").apply { writeText("keep this snapshot") }
        store.write(legacy("folder"))
        val manager = CloudBackupManager(context, webDav)
        val loaded = manager.load()
        assertFalse(loaded.enabled)
        assertTrue(loaded.folderMigrationNotice)
        assertTrue(loaded.hasPassword)
        assertFalse(loaded.unmeteredOnly)
        assertEquals(6, loaded.intervalHours)
        assertEquals(1234L, loaded.lastSuccessAt)
        // No WebDAV destination exists. A stale scheduled folder job must simply stop.
        assertTrue(manager.backupNow(scheduled = true).isSuccess)
        assertTrue(manager.backupNow().isFailure)
        assertEquals("keep this snapshot", oldFile.readText())
        val persisted = JsonParser.parseString(requireNotNull(store.read())).asJsonObject
        assertEquals("kept-encryption-secret", persisted["password"].asString)
        assertEquals("123456abcdef", persisted["device"].asString)
        assertFalse(persisted.getAsJsonObject("settings").has("treeUri"))
        assertFalse(persisted.getAsJsonObject("settings").has("target"))
        assertTrue(manager.dismissFolderMigrationNotice().isSuccess)
        val restarted = CloudBackupManager(context, webDav).load()
        assertFalse(restarted.enabled)
        assertFalse(restarted.folderMigrationNotice)
        assertTrue(restarted.hasPassword)
        assertEquals("keep this snapshot", oldFile.readText())
    }

    @Test fun existingWebDavRetainsEnabledScheduleAndSecretWithoutFolderNotice() = fixture { context, webDav, store ->
        store.write(legacy("webdav"))
        val loaded = CloudBackupManager(context, webDav).load()
        assertTrue(loaded.enabled)
        assertFalse(loaded.folderMigrationNotice)
        assertEquals(6, loaded.intervalHours)
        assertTrue(loaded.hasPassword)
        val persisted = JsonParser.parseString(requireNotNull(store.read())).asJsonObject
        assertEquals("kept-encryption-secret", persisted["password"].asString)
        assertFalse(persisted.getAsJsonObject("settings").has("target"))
    }
}
