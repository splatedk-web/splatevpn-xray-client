package com.danieldk.splatevpn.ui.components

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.data.*
import com.danieldk.splatevpn.ui.theme.SplateVPNTheme
import com.danieldk.splatevpn.vpn.LanShareManager
import com.danieldk.splatevpn.vpn.VpnStatusManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class DeviceTransferImportDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun confirmedPreviewImportsAndCancelLeavesTheDatabaseUntouched() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val prefix = "transfer-dialog-${UUID.randomUUID()}-"
        val directory = File(app.cacheDir, prefix).apply { mkdirs() }
        val preferences = mutableSetOf<String>()
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(directory, "private").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences(prefix + name, mode).also { preferences += prefix + name }
        }
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val settings = SettingsManager(context)
        val backup = BackupManager(context, database.serverDao(), database.subscriptionDao(), settings, database,
            AutoBackupManager(context, database.serverDao(), database.subscriptionDao(), AppLogManager(context), database))
        val transfer = DeviceTransferManager(context, backup, LanShareManager(context), VpnStatusManager(context))
        val password = "dialog-test-password".toCharArray()
        try {
            database.serverDao().insertServer(ServerNode("dialog-node", "Local dialog fixture", "socks", "192.0.2.1", 1080))
            val document = backup.exportDocument(password).getOrThrow()
            database.serverDao().clearAll()
            transfer.stageDocument(document, password, "LAN test fixture").getOrThrow()
            compose.setContent { SplateVPNTheme(languageSetting = "en") { DeviceTransferImportDialog(transfer, vpnActive = false) } }
            compose.onNodeWithText("Import settings").assertIsDisplayed()
            compose.onNodeWithText("Apply settings").assertIsDisplayed()
            compose.onNodeWithText("Cancel").assertIsDisplayed()
            instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                File(app.externalCacheDir, "transfer-import-dialog.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            compose.onNodeWithText("Cancel").performClick()
            compose.waitUntil(5_000) { transfer.pending.value == null }
            assertTrue(database.serverDao().getAllServersSync().isEmpty())

            transfer.stageDocument(document, password, "LAN test fixture").getOrThrow()
            compose.onNodeWithText("Apply settings").performClick()
            compose.waitUntil(10_000) { transfer.pending.value == null }
            assertEquals("dialog-node", database.serverDao().getAllServersSync().single().id)
        } finally {
            password.fill('\u0000')
            transfer.dismissPending()
            database.close()
            preferences.forEach(app::deleteSharedPreferences)
            SubscriptionUpdateWorker.schedule(app, SettingsManager(app).getAutoUpdateInterval())
            directory.deleteRecursively()
        }
    }
}
