package com.danieldk.splatevpn.data

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class BackupRestoreTest {
    private fun fixture(test: suspend (Context, AppDatabase, SettingsManager, BackupManager) -> Unit) = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "backup_qa_${java.util.UUID.randomUUID()}_"
        val directory = File(base.cacheDir, prefix).apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(directory, "no-backup").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("$prefix$name", mode)
        }
        context.getSharedPreferences("splate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val settings = SettingsManager(context)
        try { test(context, db, settings, BackupManager(context, db.serverDao(), db.subscriptionDao(), settings, db, AutoBackupManager(context, db.serverDao(), db.subscriptionDao(), AppLogManager(context), db))) }
        finally {
            db.close()
            directory.deleteRecursively()
            base.deleteSharedPreferences("${prefix}splate_prefs")
            base.deleteSharedPreferences("${prefix}vault_metadata")
            SubscriptionUpdateWorker.schedule(base, SettingsManager(base).getAutoUpdateInterval())
        }
    }
    private fun file(context: Context, data: BackupManager.BackupData): File = File.createTempFile("backup_qa_", ".json", context.cacheDir)
        .apply { writeText(Gson().toJson(data)) }

    @Test fun interruptedImportRecoversOnlyIfDatabaseDidNotCommit() = fixture { context, db, settings, manager ->
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            settings.setPerAppPackages(setOf("old.app"))
            val journal = BackupImportJournal(context, db, settings, null)
            journal.begin(includeTor = false)
            settings.setPerAppPackages(setOf("new.app"))
            manager.recoverInterruptedImport()
            assertEquals(setOf("old.app"), settings.getPerAppPackages())

            val token = journal.begin(includeTor = false)
            settings.setPerAppPackages(setOf("new.app"))
            journal.markCommitted(token)
            manager.recoverInterruptedImport()
            assertEquals(setOf("new.app"), settings.getPerAppPackages())
            assertFalse(BackupImportJournal.exists(context))
        }
    }

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
    @Test fun advancedRulesAndTheirReactiveValueRoundTripAndLegacyReplaceClearsThem() = fixture { context, _, settings, manager ->
        val rule = AdvancedRouteRule("backup-rule", "Sites", suffixes = listOf("ozon.ru"), keywords = listOf("ozon"),
            packages = listOf("com.brave.browser"), outbound = "direct")
        val encoded = AdvancedRoutingRules.encode(listOf(rule))
        settings.storeAdvancedRouting(encoded)
        val output = File.createTempFile("backup_advanced_", ".json", context.cacheDir)
        val legacy = file(context, BackupManager.BackupData())
        try {
            assertTrue(manager.exportBackup(Uri.fromFile(output)).isSuccess)
            settings.storeAdvancedRouting("[]")
            assertTrue(manager.importBackup(Uri.fromFile(output), BackupManager.ImportMode.REPLACE).isSuccess)
            assertEquals(encoded, settings.advancedRouting.value)
            assertEquals(encoded, settings.advancedRoutingJson())
            assertTrue(manager.importBackup(Uri.fromFile(legacy), BackupManager.ImportMode.MERGE).isSuccess)
            assertEquals(encoded, settings.advancedRoutingJson())
            assertTrue(manager.importBackup(Uri.fromFile(legacy), BackupManager.ImportMode.REPLACE).isSuccess)
            assertEquals("[]", settings.advancedRouting.value)
        } finally { output.delete(); legacy.delete() }
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
    @Test fun exportAndRestorePreserveAppearanceScheduleAndTransportSettings() = fixture { context, db, settings, manager ->
        val lastUpdate = System.currentTimeMillis()
        val source = SubscriptionEntity("schedule-source", "Schedule source", "https://example.com/subscription",
            autoUpdate = false, lastUpdated = lastUpdate)
        db.subscriptionDao().insertSubscription(source)
        settings.setAppTheme("winx"); settings.setAppLanguage("fa"); settings.setAutoUpdateInterval("12h")
        settings.setUiMode(AppUiMode.PRO)
        settings.setRegionalPresetsEnabled(true)
        settings.setSortServersByPing(true)
        settings.setLastAutoUpdateTime(lastUpdate)
        settings.setSubscriptionUserAgent(source.url, SubscriptionUserAgent.HAPP)
        settings.setXhttpMode("stream-one"); settings.setTlsFragmentPreset("custom"); settings.setTlsFragmentLength("11-22")
        settings.setAiDnsAutoSwitchEnabled(false)
        val output = File.createTempFile("backup_qa_roundtrip_", ".json", context.cacheDir)
        try {
            assertTrue(manager.exportBackup(Uri.fromFile(output)).isSuccess)
            settings.setAppTheme("dark"); settings.setAppLanguage("en"); settings.setAutoUpdateInterval("off")
            settings.setUiMode(AppUiMode.SIMPLE)
            settings.setRegionalPresetsEnabled(false)
            settings.setSortServersByPing(false)
            settings.setLastAutoUpdateTime(0)
            settings.setSubscriptionUserAgent(source.url, SubscriptionUserAgent.AUTO)
            db.subscriptionDao().clearAll()
            settings.setXhttpMode("auto"); settings.setTlsFragmentLength("100-200")
            settings.setAiDnsAutoSwitchEnabled(true)
            assertTrue(manager.importBackup(Uri.fromFile(output), BackupManager.ImportMode.REPLACE).isSuccess)
            assertEquals("winx", settings.getAppTheme())
            assertEquals("fa", settings.getAppLanguage())
            assertEquals(AppUiMode.PRO, settings.uiModeFlow.value)
            assertTrue(settings.regionalPresetsEnabled())
            assertTrue(settings.isSortServersByPing())
            assertEquals("12h", settings.getAutoUpdateInterval())
            assertEquals("12h", settings.autoUpdateInterval.value)
            assertEquals(lastUpdate, settings.getLastAutoUpdateTime())
            assertEquals(source, db.subscriptionDao().getSubscriptionById(source.id))
            assertEquals(SubscriptionUserAgent.HAPP, settings.getSubscriptionUserAgent(source.url))
            assertEquals("stream-one", settings.getXhttpMode())
            assertEquals("11-22", settings.getTlsFragmentLength())
            assertFalse(settings.isAiDnsAutoSwitchEnabled())
            assertFalse(settings.aiDnsAutoSwitchFlow.value)
        } finally { output.delete() }
    }
    @Test fun failedDatabaseWriteRollsBackDeletionsAndSubscriptionInsert() = fixture { context, db, settings, _ ->
        db.serverDao().insertServer(ServerNode("old", "Old", "vless", "example.com", 443))
        val failing = object : ServerDao by db.serverDao() {
            override suspend fun insertServers(servers: List<ServerNode>) { throw IOException("simulated write failure") }
        }
        val manager = BackupManager(context, failing, db.subscriptionDao(), settings, db, AutoBackupManager(context, db.serverDao(), db.subscriptionDao(), AppLogManager(context), db))
        val input = file(context, BackupManager.BackupData(subscriptions = listOf(SubscriptionEntity("new", "New", "https://example.com"))))
        try {
            assertTrue(manager.importBackup(Uri.fromFile(input), BackupManager.ImportMode.REPLACE).isFailure)
            assertEquals("old", db.serverDao().getAllServersSync().single().id)
            assertTrue(db.subscriptionDao().getAllSubscriptionsSync().isEmpty())
        } finally { input.delete() }
    }
    @Test fun cancellationAfterDatabaseCommitKeepsMatchingPreferences() = fixture { context, db, settings, _ ->
        settings.setRoutingMode("smart_ru")
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val delayed = object : ServerDao by db.serverDao() {
            override suspend fun insertServers(servers: List<ServerNode>) {
                started.complete(Unit)
                kotlinx.coroutines.delay(200)
                db.serverDao().insertServers(servers)
            }
        }
        val manager = BackupManager(context, delayed, db.subscriptionDao(), settings, db,
            AutoBackupManager(context, db.serverDao(), db.subscriptionDao(), AppLogManager(context), db))
        val source = file(context, BackupManager.BackupData(servers = listOf(ServerNode("committed", "New", "vless", "example.com", 443)),
            settings = mapOf("routing_mode" to "global")))
        try {
            kotlinx.coroutines.coroutineScope {
                val job = launch { manager.importBackup(android.net.Uri.fromFile(source), BackupManager.ImportMode.REPLACE) }
                started.await()
                job.cancel()
                job.join()
            }
            assertEquals("committed", db.serverDao().getAllServersSync().single().id)
            assertEquals("global", settings.getRoutingMode())
        } finally { source.delete() }
    }
    @Test fun deferredConstraintFailureRollsBackDatabaseAndPreferences() = fixture { context, db, settings, _ ->
        settings.setRoutingMode("smart_ru")
        db.serverDao().insertServer(ServerNode("old", "Old", "vless", "example.com", 443))
        val sqlite = db.openHelper.writableDatabase
        sqlite.execSQL("PRAGMA foreign_keys = ON")
        sqlite.execSQL("CREATE TABLE commit_test_parent (id INTEGER PRIMARY KEY)")
        sqlite.execSQL("CREATE TABLE commit_test_child (id INTEGER PRIMARY KEY, parentId INTEGER REFERENCES commit_test_parent(id) DEFERRABLE INITIALLY DEFERRED)")
        val deferredFailure = object : ServerDao by db.serverDao() {
            override suspend fun insertServers(servers: List<ServerNode>) {
                db.serverDao().insertServers(servers)
                sqlite.execSQL("INSERT INTO commit_test_child (id,parentId) VALUES (1,999)")
            }
        }
        val manager = BackupManager(context, deferredFailure, db.subscriptionDao(), settings, db,
            AutoBackupManager(context, db.serverDao(), db.subscriptionDao(), AppLogManager(context), db))
        val source = file(context, BackupManager.BackupData(servers = listOf(ServerNode("new", "New", "vless", "example.com", 443)),
            settings = mapOf("routing_mode" to "global")))
        try {
            assertTrue(manager.importBackup(android.net.Uri.fromFile(source), BackupManager.ImportMode.REPLACE).isFailure)
            assertEquals("old", db.serverDao().getAllServersSync().single().id)
            assertEquals("smart", settings.getRoutingMode())
        } finally { source.delete() }
    }
}
