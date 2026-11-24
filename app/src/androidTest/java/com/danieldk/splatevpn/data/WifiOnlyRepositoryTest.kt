package com.danieldk.splatevpn.data

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.vpn.VpnStatusManager
import com.danieldk.splatevpn.vpn.XrayConfigGenerator
import com.danieldk.splatevpn.vpn.XrayCoreManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import javax.inject.Provider

/** A missing permitted route is deterministic and must never become a server/provider failure. */
class WifiOnlyRepositoryTest {
    @Test fun deniedUpdatesAndProbesPreserveSavedNodesHealthAndUpdateHistory() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "wifi-policy-${UUID.randomUUID()}"
        val directory = File(app.cacheDir, prefix).apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = directory
            override fun getNoBackupFilesDir() = File(directory, "private").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences("$prefix-$name", mode)
            override fun getSystemService(name: String): Any? =
                if (name == Context.CONNECTIVITY_SERVICE) null else super.getSystemService(name)
        }
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val settings = SettingsManager(context)
            settings.setUpdatesWifiOnly(true)
            settings.setChecksWifiOnly(true)
            val source = SubscriptionEntity("sub", "Saved", "https://example.com/private", lastUpdated = 123, nodeCount = 1)
            val node = ServerNode("working", "Saved", "vless", "example.com", 443, subId = source.id,
                isSelected = true, ping = 42, lastVerifiedAt = 1000)
            database.subscriptionDao().insertSubscription(source)
            database.serverDao().insertServer(node)
            val vault = AutoBackupManager(context, database.serverDao(), database.subscriptionDao(), AppLogManager(context), database)
            val subscriptions = SubscriptionRepository(database.serverDao(), database.subscriptionDao(), settings, vault, database, SubscriptionTransport())
            assertTrue(subscriptions.updateSubscription(source.id).exceptionOrNull() is WifiRequiredException)
            assertEquals(source, database.subscriptionDao().getSubscriptionById(source.id))
            assertTrue(subscriptions.updateHistory(source.id).first().isEmpty())
            val deferred = subscriptions.refreshSubscriptions(manual = false)
            assertEquals(0, deferred.updated)
            assertTrue(deferred.failures.isEmpty())
            val probes = ServerProbeRepository(context, database.serverDao(), settings,
                Provider<VpnStatusManager> { error("A denied check must not touch the VPN") }, XrayConfigGenerator(),
                Provider<XrayCoreManager> { error("A denied check must not launch the core") }, PhysicalFailureEvidence(context, settings))
            suspend fun denied(block: suspend () -> Unit) {
                try { block(); fail("Wi-Fi is unavailable") } catch (_: WifiRequiredException) { }
            }
            denied { probes.testPing(node) }
            denied { probes.testRealDelay(node) }
            denied { probes.testServersPing(listOf(node)) }
            assertEquals(node, database.serverDao().getServerById(node.id))
            assertTrue(probes.sessionPingResults.value.isEmpty())
            assertFalse(probes.qualityTracker.isQuarantined(node.id))

            // Begin a real fetch, then lose the permitted route while the response is pending.
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                val requested = CompletableDeferred<Unit>()
                val peer = launch(Dispatchers.IO) {
                    server.accept().use {
                        val reader = it.getInputStream().bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) { }
                        requested.complete(Unit)
                        awaitCancellation()
                    }
                }
                try {
                    val previous = source.copy(url = "http://127.0.0.1:${server.localPort}/test",
                        lastAttemptAt = 456, lastError = "Earlier result")
                    database.subscriptionDao().insertSubscription(previous)
                    settings.setUpdatesWifiOnly(false)
                    withTimeout(10_000) {
                        val update = async { subscriptions.updateSubscription(source.id) }
                        requested.await()
                        assertNotEquals(previous.lastAttemptAt, database.subscriptionDao().getSubscriptionById(source.id)?.lastAttemptAt)
                        settings.setUpdatesWifiOnly(true)
                        assertTrue(update.await().exceptionOrNull() is WifiRequiredException)
                    }
                    assertEquals(previous, database.subscriptionDao().getSubscriptionById(source.id))
                    assertTrue(subscriptions.updateHistory(source.id).first().isEmpty())
                    assertEquals(node, database.serverDao().getServerById(node.id))
                } finally {
                    server.close()
                    peer.cancelAndJoin()
                }
            }
        } finally {
            database.close()
            for (name in listOf("splate_prefs", "vault_metadata")) app.deleteSharedPreferences("$prefix-$name")
            directory.deleteRecursively()
        }
    }
}
