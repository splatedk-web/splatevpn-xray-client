package com.danieldk.splatevpn.data

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class AutoBackupSafetyTest {
    private fun fixture(test: suspend (AppDatabase, AutoBackupManager, Context) -> Unit) = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "vault-${UUID.randomUUID()}"
        val directory = File(app.cacheDir, name).apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir(): File = directory
            override fun getSharedPreferences(key: String, mode: Int) = app.getSharedPreferences("$name-$key", mode)
        }
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        try { test(db, AutoBackupManager(context, db.serverDao(), db.subscriptionDao(), AppLogManager(context), db), context) }
        finally { db.close(); directory.deleteRecursively(); app.deleteSharedPreferences("$name-vault_metadata") }
    }

    @Test fun intentionalEmptyDatabaseNeverResurrectsDeletedCredentials() = fixture { db, manager, _ ->
        db.serverDao().insertServer(ServerNode("node", "Private", "vless", "example.com", 443))
        manager.backupToVault()
        manager.prepareForDeletion()
        db.serverDao().clearAll()
        // Simulate process death before the empty snapshot could replace the old one.
        assertEquals(0, manager.tryAutoRestoreIfEmpty())
        assertTrue(db.serverDao().getAllServersSync().isEmpty())
        manager.backupToVault()
        assertEquals(0, manager.tryAutoRestoreIfEmpty())
    }

    @Test fun concurrentSnapshotsRemainReadableAndPreserveSelection() = fixture { db, manager, _ ->
        db.serverDao().insertServer(ServerNode("node", "Private", "vless", "example.com", 443, isSelected = true))
        coroutineScope { List(12) { async(Dispatchers.IO) { manager.backupToVault() } }.awaitAll() }
        db.serverDao().clearAll() // A genuine loss: there was no intentional deletion marker.
        assertEquals(1, manager.tryAutoRestoreIfEmpty())
        assertEquals("node", db.serverDao().getSelectedServerSync()!!.id)
    }
}
