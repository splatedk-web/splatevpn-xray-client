package com.danieldk.splatevpn.data

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.vpn.LanShareManager
import com.danieldk.splatevpn.vpn.VpnStatusManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class DeviceTransferSnapshotTest {
    @Test fun plaintextCloudBackupRequiresExplicitOptInAndConfirmedPreviewWithoutWeakeningLan() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "transfer-plaintext-${UUID.randomUUID()}-"
        val directory = File(app.cacheDir, prefix).apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(directory, "private").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences(prefix + name, mode)
        }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val settings = SettingsManager(context)
        val backup = BackupManager(context, db.serverDao(), db.subscriptionDao(), settings, db,
            AutoBackupManager(context, db.serverDao(), db.subscriptionDao(), AppLogManager(context), db))
        val transfer = DeviceTransferManager(context, backup, LanShareManager(context), VpnStatusManager(context))
        val password = charArrayOf()
        try {
            db.serverDao().insertServer(ServerNode("cloud", "Cloud copy", "socks", "192.0.2.10", 1080,
                uuid = "cloud-proxy-password", isSelected = true))
            settings.setMtu(1280)
            val file = File(directory, "plain-backup.json")
            backup.exportBackup(android.net.Uri.fromFile(file)).getOrThrow()
            val document = file.readText(Charsets.UTF_8)
            assertTrue(document.contains("cloud-proxy-password"))
            db.serverDao().clearAll()
            db.serverDao().insertServer(ServerNode("local", "Existing local node", "socks", "192.0.2.20", 1080))
            settings.setMtu(1400)

            assertTrue(transfer.stageDocument(document, password, "LAN").isFailure)
            assertTrue(backup.importDocument(document, password, BackupManager.ImportMode.REPLACE).isFailure)
            assertNull(transfer.pending.value)
            assertEquals("local", db.serverDao().getAllServersSync().single().id)
            assertEquals(1400, settings.getMtu())

            val preview = transfer.stageDocument(document, password, "Google Drive", allowPlaintext = true).getOrThrow()
            assertEquals(1, preview.servers)
            assertEquals("Google Drive", preview.source)
            assertFalse(preview.encrypted)
            assertEquals(preview, transfer.pending.value)
            assertEquals("local", db.serverDao().getAllServersSync().single().id)
            assertEquals(1400, settings.getMtu())
            transfer.dismissPending()
            assertNull(transfer.pending.value)
            assertTrue(transfer.applyPending(preview, BackupManager.ImportMode.REPLACE).isFailure)
            assertEquals("local", db.serverDao().getAllServersSync().single().id)

            val replacement = transfer.stageDocument(document, password, "Google Drive", allowPlaintext = true).getOrThrow()
            assertNotEquals(preview.operationId, replacement.operationId)
            assertTrue(transfer.applyPending(preview, BackupManager.ImportMode.REPLACE).isFailure)
            transfer.dismissPending(preview)
            assertEquals(replacement, transfer.pending.value)
            assertEquals("local", db.serverDao().getAllServersSync().single().id)
            transfer.applyPending(replacement, BackupManager.ImportMode.REPLACE).getOrThrow()
            assertEquals("cloud", db.serverDao().getAllServersSync().single().id)
            assertEquals("cloud-proxy-password", db.serverDao().getAllServersSync().single().uuid)
            assertEquals(1280, settings.getMtu())
            assertEquals("3h", settings.autoUpdateInterval.value)
            assertNull(transfer.pending.value)
            // The cloud opt-in belongs to one pending import, not to later LAN transfers.
            assertTrue(transfer.stageDocument(document, password, "LAN").isFailure)
            assertNull(transfer.pending.value)
        } finally {
            transfer.stopSending(); transfer.dismissPending(); db.close(); directory.deleteRecursively()
            app.deleteSharedPreferences(prefix + "splate_prefs")
            app.deleteSharedPreferences(prefix + "vault_metadata")
        }
    }

    @Test fun encryptedSnapshotCarriesProxyPasswordTorAndServersOnlyAfterConfirmedImport() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "transfer-${UUID.randomUUID()}-"
        val directory = File(app.cacheDir, prefix).apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(directory, "private").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences(prefix + name, mode)
        }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val settings = SettingsManager(context)
        val tor = TorRepository(context, SubscriptionTransport())
        val backup = BackupManager(context, db.serverDao(), db.subscriptionDao(), settings, db,
            AutoBackupManager(context, db.serverDao(), db.subscriptionDao(), AppLogManager(context), db), tor)
        val transfer = DeviceTransferManager(context, backup, LanShareManager(context), VpnStatusManager(context))
        val key = "pairing-key-for-the-device".toCharArray()
        try {
            db.serverDao().insertServer(ServerNode("source", "Source", "socks", "192.168.1.50", 1080, uuid = "proxy-secret", isSelected = true))
            settings.setMtu(1280)
            settings.setAutoUpdateInterval("3h")
            settings.setLightUiMode()
            val lanPassword = settings.getLanPassword()
            tor.save(TorSettings(exitNodes = "A".repeat(40)))
            val document = backup.exportDocument(key).getOrThrow()
            assertFalse(document.contains(lanPassword))
            assertFalse(document.contains("proxy-secret"))
            settings.rotateLanPassword(); settings.setMtu(1400)
            settings.setAutoUpdateInterval("off")
            settings.restoreFullUiEffects()
            db.serverDao().clearAll()
            tor.save(TorSettings())

            val preview = transfer.stageDocument(document, key, "LAN").getOrThrow()
            assertEquals(1, preview.servers)
            assertTrue(preview.includesTor)
            assertTrue(preview.includesProxyPassword)
            assertEquals("3h", preview.subscriptionUpdateInterval)
            assertEquals("off", settings.autoUpdateInterval.value)
            assertTrue(db.serverDao().getAllServersSync().isEmpty())
            assertNotEquals(lanPassword, settings.getLanPassword())
            assertTrue(tor.current().exitNodes.isEmpty())
            assertEquals(UiPerformanceSettings(), settings.performanceSettings.value)

            assertTrue(transfer.applyPending(preview, BackupManager.ImportMode.REPLACE).isSuccess)
            assertEquals("source", db.serverDao().getAllServersSync().single().id)
            assertEquals(lanPassword, settings.getLanPassword())
            assertEquals(1280, settings.getMtu())
            assertEquals("3h", settings.autoUpdateInterval.value)
            assertEquals(UiPerformanceSettings(false, false, false), settings.performanceSettings.value)
            assertFalse(settings.getGlassBlurEnabled())
            assertEquals("A".repeat(40), tor.current().exitNodes)
            assertNull(transfer.pending.value)
        } finally {
            key.fill('\u0000'); transfer.stopSending(); transfer.dismissPending()
            db.close(); directory.deleteRecursively()
            app.deleteSharedPreferences(prefix + "splate_prefs")
            app.deleteSharedPreferences(prefix + "vault_metadata")
        }
    }

    @Test fun scanningUploadInvitationDoesNotSendSecretsBeforeConfirmation() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(app.cacheDir, "transfer-consent-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = directory
            override fun getNoBackupFilesDir() = directory
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences(directory.name + name, mode)
        }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val settings = SettingsManager(context)
        val backup = BackupManager(context, db.serverDao(), db.subscriptionDao(), settings, db,
            AutoBackupManager(context, db.serverDao(), db.subscriptionDao(), AppLogManager(context), db))
        val transfer = DeviceTransferManager(context, backup, LanShareManager(context), VpnStatusManager(context))
        try {
            // Unreachable private endpoint is deliberate: staging must not perform a network request.
            val invitation = DeviceTransferInvitation("http://192.168.255.254:65534/transfer/${"b".repeat(43)}",
                "a".repeat(43), DeviceTransferDirection.UPLOAD)
            val preview = transfer.receiveInvitation(invitation.encode()).getOrThrow()
            assertFalse(preview.received)
            assertEquals("192.168.255.254", preview.source)
            assertEquals(preview, transfer.pending.value)
            transfer.dismissPending()
            assertNull(transfer.pending.value)
        } finally {
            transfer.stopSending(); transfer.dismissPending(); db.close(); directory.deleteRecursively()
            app.deleteSharedPreferences(directory.name + "splate_prefs")
            app.deleteSharedPreferences(directory.name + "vault_metadata")
        }
    }
}
