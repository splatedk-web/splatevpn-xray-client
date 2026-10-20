package com.danieldk.splatevpn.data

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class BackupRestoreTest {
    private fun fixture(test: suspend (Context, AppDatabase, SettingsManager, BackupManager) -> Unit) = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("backup_qa_$name", mode)
        }
        context.getSharedPreferences("splate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val settings = SettingsManager(context)
        try { test(context, db, settings, BackupManager(context, db.serverDao(), db.subscriptionDao(), settings, db)) }
        finally {
            db.close()
            SubscriptionUpdateWorker.schedule(base, SettingsManager(base).getAutoUpdateInterval())
        }
    }
    private fun file(context: Context, data: BackupManager.BackupData): File = File.createTempFile("backup_qa_", ".json", context.cacheDir)
        .apply { writeText(Gson().toJson(data)) }

    @Test fun replaceRestoresEmptyListsAndRemovesOldDatabaseRows() = fixture { context, db, settings, manager ->
        db.serverDao().insertServer(ServerNode("old", "Old", "vless", "example.com", 443))
        settings.setPerAppPackages(setOf("old.app"))
        settings.setCustomRoute("example.com", "block")
        val input = file(context, BackupManager.BackupData())
        try {
            assertTrue(manager.importBackup(Uri.fromFile(input), BackupManager.ImportMode.REPLACE).isSuccess)
            assertTrue(db.serverDao().getAllServersSync().isEmpty())
            assertTrue(settings.getPerAppPackages().isEmpty())
            assertTrue(settings.getCustomRoutes().isEmpty())
        } finally { input.delete() }
    }
    @Test fun mergePreservesExistingRowsAppsAndRoutes() = fixture { context, db, settings, manager ->
        db.serverDao().insertServer(ServerNode("old", "Old", "vless", "example.com", 443, isSelected = true))
        settings.setPerAppPackages(setOf("old.app"))
        settings.setCustomRoute("example.com", "block")
        val input = file(context, BackupManager.BackupData(perAppPackages = listOf("new.app"),
            servers = listOf(ServerNode("old", "Renamed", "vless", "example.com", 443))))
        try {
            assertTrue(manager.importBackup(Uri.fromFile(input), BackupManager.ImportMode.MERGE).isSuccess)
            assertEquals(1, db.serverDao().getAllServersSync().size)
            assertEquals("old", db.serverDao().getSelectedServerSync()?.id)
            assertEquals(setOf("old.app", "new.app"), settings.getPerAppPackages())
            assertEquals(listOf("example.com" to "block"), settings.getCustomRoutes())
        } finally { input.delete() }
    }
    @Test fun exportAndRestorePreserveAppearanceScheduleAndTransportSettings() = fixture { context, _, settings, manager ->
        settings.setAppTheme("winx"); settings.setAppLanguage("ru"); settings.setAutoUpdateInterval("12h")
        settings.setXhttpMode("stream-one"); settings.setTlsFragmentPreset("custom"); settings.setTlsFragmentLength("11-22")
        val output = File.createTempFile("backup_qa_roundtrip_", ".json", context.cacheDir)
        try {
            assertTrue(manager.exportBackup(Uri.fromFile(output)).isSuccess)
            settings.setAppTheme("dark"); settings.setAppLanguage("en"); settings.setAutoUpdateInterval("off")
            settings.setXhttpMode("auto"); settings.setTlsFragmentLength("100-200")
            assertTrue(manager.importBackup(Uri.fromFile(output), BackupManager.ImportMode.REPLACE).isSuccess)
            assertEquals("winx", settings.getAppTheme())
            assertEquals("ru", settings.getAppLanguage())
            assertEquals("12h", settings.getAutoUpdateInterval())
            assertEquals("stream-one", settings.getXhttpMode())
            assertEquals("11-22", settings.getTlsFragmentLength())
        } finally { output.delete() }
    }
    @Test fun failedDatabaseWriteRollsBackDeletionsAndSubscriptionInsert() = fixture { context, db, settings, _ ->
        db.serverDao().insertServer(ServerNode("old", "Old", "vless", "example.com", 443))
        val failing = object : ServerDao by db.serverDao() {
            override suspend fun insertServers(servers: List<ServerNode>) { throw IOException("simulated write failure") }
        }
        val manager = BackupManager(context, failing, db.subscriptionDao(), settings, db)
        val input = file(context, BackupManager.BackupData(subscriptions = listOf(SubscriptionEntity("new", "New", "https://example.com"))))
        try {
            assertTrue(manager.importBackup(Uri.fromFile(input), BackupManager.ImportMode.REPLACE).isFailure)
            assertEquals("old", db.serverDao().getAllServersSync().single().id)
            assertTrue(db.subscriptionDao().getAllSubscriptionsSync().isEmpty())
        } finally { input.delete() }
    }
}
