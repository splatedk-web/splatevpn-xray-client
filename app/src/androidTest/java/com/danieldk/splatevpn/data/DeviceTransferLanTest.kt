package com.danieldk.splatevpn.data

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.vpn.LanShareManager
import com.danieldk.splatevpn.vpn.VpnStatusManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID

/** Two isolated device stores communicating through real HTTP on the emulator's private interface. */
class DeviceTransferLanTest {
    @Test fun downloadPairingAuthenticatesEncryptionAndCannotBeReplayed() = runBlocking {
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val sender = Fixture()
        val receiver = Fixture()
        try {
            sender.db.serverDao().insertServer(ServerNode("phone", "Phone", "socks", "192.168.1.20", 1080, uuid = "private-proxy-password"))
            val first = sender.manager.startSending().getOrThrow()
            val incorrectKey = first.invitation.substringBefore('#') + "#" + "z".repeat(43)
            assertTrue(receiver.manager.receiveInvitation(incorrectKey).isFailure)
            assertNull(receiver.manager.pending.value)
            assertTrue(receiver.db.serverDao().getAllServersSync().isEmpty())

            val second = sender.manager.startSending().getOrThrow()
            val preview = receiver.manager.receiveInvitation(second.invitation).getOrThrow()
            assertEquals(1, preview.servers)
            assertTrue(receiver.db.serverDao().getAllServersSync().isEmpty())
            assertTrue("A download invitation must be single use", receiver.manager.receiveInvitation(second.invitation).isFailure)
            assertTrue(receiver.manager.applyPending(preview, BackupManager.ImportMode.REPLACE).isSuccess)
            assertEquals("private-proxy-password", receiver.db.serverDao().getAllServersSync().single().uuid)
            assertNull(receiver.manager.pending.value)
        } finally { sender.close(); receiver.close() }
    }

    @Test fun televisionUploadRequiresConfirmationOnBothDevices() = runBlocking {
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val phone = Fixture()
        val television = Fixture()
        try {
            phone.db.serverDao().insertServer(ServerNode("phone", "Phone", "socks", "192.168.1.20", 1080))
            val invitation = television.manager.startReceiving().getOrThrow().invitation
            val sendingPreview = phone.manager.receiveInvitation(invitation).getOrThrow()
            assertFalse(sendingPreview.received)
            assertNull("Scanning the TV QR must not send credentials", television.manager.pending.value)
            assertTrue(television.db.serverDao().getAllServersSync().isEmpty())
            assertTrue(phone.manager.applyPending(sendingPreview, BackupManager.ImportMode.MERGE).isSuccess)
            val received = requireNotNull(television.manager.pending.value)
            assertEquals(1, received.servers)
            assertTrue("Receiving data must not apply it silently", television.db.serverDao().getAllServersSync().isEmpty())
            assertTrue(television.manager.applyPending(received, BackupManager.ImportMode.REPLACE).isSuccess)
            assertEquals("phone", television.db.serverDao().getAllServersSync().single().id)
        } finally { phone.close(); television.close() }
    }

    @Test fun staleSendingConfirmationCannotAuthorizeAnotherReceiver() = runBlocking {
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val phone = Fixture()
        val first = Fixture()
        val second = Fixture()
        try {
            phone.db.serverDao().insertServer(ServerNode("phone", "Phone", "socks", "192.168.1.20", 1080))
            val old = phone.manager.receiveInvitation(first.manager.startReceiving().getOrThrow().invitation).getOrThrow()
            val current = phone.manager.receiveInvitation(second.manager.startReceiving().getOrThrow().invitation).getOrThrow()
            assertNotEquals(old.operationId, current.operationId)
            assertTrue(phone.manager.applyPending(old, BackupManager.ImportMode.MERGE).isFailure)
            assertNull(first.manager.pending.value)
            assertNull(second.manager.pending.value)
            phone.manager.applyPending(current, BackupManager.ImportMode.MERGE).getOrThrow()
            assertNull(first.manager.pending.value)
            assertNotNull(second.manager.pending.value)
        } finally { phone.close(); first.close(); second.close() }
    }

    @Test fun oversizedHeadersAreRejectedAndCancellationClosesTheClientSocket() = runBlocking {
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val address = LanShareManager(app).getActiveLanEndpoints().first().ipAddress
        val receiver = Fixture()
        try {
            for (oversized in listOf(true, false)) {
                ServerSocket(0, 1, InetAddress.getByName(address)).use { listener ->
                    val started = CompletableDeferred<Unit>()
                    val closed = CompletableDeferred<Boolean>()
                    val peer = Thread {
                        try {
                            listener.accept().use { socket ->
                                socket.soTimeout = 5_000
                                val input = socket.getInputStream().bufferedReader()
                                while (!input.readLine().isNullOrEmpty()) { }
                                val output = socket.getOutputStream()
                                output.write("HTTP/1.1 200 OK\r\n".toByteArray())
                                if (oversized) {
                                    output.write(("X-Test: " + "x".repeat(1000) + "\r\n").repeat(20).toByteArray())
                                } else {
                                    output.write("Content-Length: 100\r\n\r\n".toByteArray())
                                }
                                output.flush()
                                started.complete(Unit)
                                closed.complete(input.read() == -1)
                            }
                        } catch (_: java.io.IOException) {
                            started.complete(Unit)
                            closed.complete(true)
                        }
                    }.apply { isDaemon = true; start() }
                    try {
                        val invitation = DeviceTransferInvitation(
                            "http://$address:${listener.localPort}/transfer/${DeviceTransferProtocol.newSecret()}",
                            DeviceTransferProtocol.newSecret(), DeviceTransferDirection.DOWNLOAD)
                        val receive = async(Dispatchers.Default) { receiver.manager.receiveInvitation(invitation.encode()) }
                        withTimeout(10_000) { started.await() }
                        if (oversized) {
                            val result = checkNotNull(withTimeoutOrNull(3_000) { receive.await() }) {
                                "Oversized HTTP headers did not get rejected promptly"
                            }
                            assertTrue(result.isFailure)
                            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("headers exceed"))
                        } else {
                            assertEquals("Cancellation did not close the blocked LAN read", true,
                                withTimeoutOrNull(3_000) { receive.cancelAndJoin(); true })
                        }
                        assertTrue(withTimeout(5_000) { closed.await() })
                        assertNull(receiver.manager.pending.value)
                    } finally {
                        listener.close()
                        peer.join(6_000)
                    }
                }
            }
        } finally { receiver.close() }
    }

    private class Fixture {
        private val app = InstrumentationRegistry.getInstrumentation().targetContext
        private val prefix = "lan-transfer-${UUID.randomUUID()}-"
        private val directory = File(app.cacheDir, prefix).apply { mkdirs() }
        private val context = object : ContextWrapper(app) {
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(directory, "private").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences(prefix + name, mode)
        }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        private val settings = SettingsManager(context)
        private val backup = BackupManager(context, db.serverDao(), db.subscriptionDao(), settings, db,
            AutoBackupManager(context, db.serverDao(), db.subscriptionDao(), AppLogManager(context), db))
        val manager = DeviceTransferManager(context, backup, LanShareManager(context), VpnStatusManager(context))
        suspend fun close() {
            manager.stopSending()
            manager.dismissPending()
            db.close(); directory.deleteRecursively()
            app.deleteSharedPreferences(prefix + "splate_prefs")
            app.deleteSharedPreferences(prefix + "vault_metadata")
            SubscriptionUpdateWorker.schedule(app, SettingsManager(app).getAutoUpdateInterval())
        }
    }
}
