package com.danieldk.splatevpn.data

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.URLEncoder
import java.util.Base64
import java.util.UUID

class SubscriptionRepositorySafetyTest {
    private fun fixture(test: suspend (AppDatabase, SettingsManager, AutoBackupManager, Context) -> Unit) = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "subscription-${UUID.randomUUID()}"
        val directory = File(app.cacheDir, name).apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = directory
            override fun getNoBackupFilesDir() = File(directory, "no-backup").apply { mkdirs() }
            override fun getSharedPreferences(key: String, mode: Int) = app.getSharedPreferences("$name-$key", mode)
        }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try { test(db, SettingsManager(context), AutoBackupManager(context, db.serverDao(), db.subscriptionDao(), AppLogManager(context), db), context) }
        finally { db.close(); directory.deleteRecursively() }
    }

    private class HttpFixture : java.io.Closeable {
        private val server = ServerSocket(0)
        @Volatile var status = 200
        @Volatile var headers = ""
        @Volatile var content = "vless://11111111-2222-3333-4444-555555555555@example.com:443?security=tls#Updated"
        val url get() = "http://127.0.0.1:${server.localPort}/private-token"
        init {
            Thread {
                while (!server.isClosed) {
                    try {
                        server.accept().use { socket ->
                            socket.soTimeout = 5000
                            val reader = socket.getInputStream().bufferedReader()
                            while (!reader.readLine().isNullOrEmpty()) { }
                            val body = content.toByteArray(Charsets.UTF_8)
                            socket.getOutputStream().apply {
                                write("HTTP/1.1 $status Test\r\nContent-Length: ${body.size}\r\n${headers}Connection: close\r\n\r\n".toByteArray())
                                write(body); flush()
                            }
                        }
                    } catch (error: java.io.IOException) { if (!server.isClosed) throw error }
                }
            }.apply { isDaemon = true; start() }
        }
        override fun close() = server.close()
    }

    @Test fun failedRefreshKeepsWorkingNodesAndHistorySurvivesNextSuccess() = fixture { db, settings, vault, _ ->
        HttpFixture().use { http ->
            db.subscriptionDao().insertSubscription(SubscriptionEntity("sub", "Private", http.url, lastUpdated = 123, nodeCount = 1))
            db.serverDao().insertServer(ServerNode("old", "Old", "vless", "example.com", 443, subId = "sub", isSelected = true))
            val repository = SubscriptionRepository(db.serverDao(), db.subscriptionDao(), settings, vault, db, SubscriptionTransport())
            http.status = 503
            val failed = repository.refreshSubscriptions(manual = false)
            assertEquals(0, failed.updated)
            assertEquals(1, failed.retryableFailures)
            assertEquals("old", db.serverDao().getSelectedServerSync()!!.id)
            assertEquals(123L, db.subscriptionDao().getSubscriptionById("sub")!!.lastUpdated)
            assertEquals("HTTP 503", db.subscriptionDao().getSubscriptionById("sub")!!.lastError)
            db.subscriptionDao().insertSubscription(SubscriptionEntity("permanent", "Denied", "https://example.com", lastError = "HTTP 403"))
            http.status = 200
            val retried = repository.refreshSubscriptions(manual = false, retryOnly = true)
            assertEquals(1, retried.updated)
            assertEquals(1, retried.failures.size) // A permanent error must not vanish after another source recovers.
            val history = repository.updateHistory("sub").first()
            assertEquals(2, history.size)
            assertTrue(history.first().successful)
            assertEquals("HTTP 503", history.last().error)
            assertFalse(history.toString().contains("private-token"))
        }
    }

    @Test fun importRollsBackNodesWhenSubscriptionInsertFails() = fixture { db, settings, vault, _ ->
        HttpFixture().use { http ->
            val failing = object : SubscriptionDao by db.subscriptionDao() {
                override suspend fun insertSubscription(sub: SubscriptionEntity) { error("Simulated disk failure") }
            }
            val repository = SubscriptionRepository(db.serverDao(), failing, settings, vault, db, SubscriptionTransport())
            assertTrue(repository.importSubscription(http.url).isFailure)
            assertTrue(db.serverDao().getAllServersSync().isEmpty())
            assertTrue(db.subscriptionDao().getAllSubscriptionsSync().isEmpty())
        }
    }

    @Test fun hwidDenialWithHttp200CannotReplaceSavedNodesOrMetadata() = fixture { db, settings, vault, _ ->
        HttpFixture().use { http ->
            db.subscriptionDao().insertSubscription(SubscriptionEntity("sub", "Saved", http.url, lastUpdated = 123, nodeCount = 1))
            db.serverDao().insertServer(ServerNode("old", "Working", "vless", "example.com", 443, subId = "sub", isSelected = true))
            val repository = SubscriptionRepository(db.serverDao(), db.subscriptionDao(), settings, vault, db, SubscriptionTransport())
            http.headers = "x-hwid-max-devices-reached: true\r\nprofile-title: Device limit\r\n"
            val result = repository.updateSubscription("sub")
            assertTrue(result.isFailure)
            assertEquals(SubscriptionHwidFailure.DEVICE_LIMIT, (result.exceptionOrNull() as SubscriptionHwidException).reason)
            assertEquals(listOf("old"), db.serverDao().getAllServersSync().map { it.id })
            assertEquals("old", db.serverDao().getSelectedServerSync()!!.id)
            val saved = db.subscriptionDao().getSubscriptionById("sub")!!
            assertEquals("Saved", saved.name)
            assertEquals(123L, saved.lastUpdated)
            assertEquals("HWID device limit reached", saved.lastError)
            assertFalse(saved.lastFailureRetryable)
            assertTrue(repository.importSubscription(http.url).isFailure)
            assertEquals(1, db.subscriptionDao().getAllSubscriptionsSync().size)
            http.headers = "x-hwid-limit: true\r\n"
            assertTrue(repository.updateSubscription("sub").isSuccess)
            assertEquals("", db.subscriptionDao().getSubscriptionById("sub")!!.lastError)
        }
    }

    @Test fun encodedServicePlaceholdersCannotBeImportedOrReplaceWorkingNodes() = fixture { db, settings, vault, _ ->
        HttpFixture().use { http ->
            val notices = listOf("⚠️Подписка не работает!", "Новые подписки в @RKP_channel").joinToString("\n") { name ->
                "vless://11111111-2222-3333-4444-555555555555@198.51.100.1:443?security=tls#" +
                    URLEncoder.encode(name, "UTF-8")
            }
            http.content = Base64.getEncoder().encodeToString(notices.toByteArray(Charsets.UTF_8))
            val repository = SubscriptionRepository(db.serverDao(), db.subscriptionDao(), settings, vault, db, SubscriptionTransport())

            // HTTP 200 and syntactically valid, encoded VLESS links do not make a provider notice usable.
            val imported = repository.importSubscription(http.url, "New source")
            assertTrue(imported.isFailure)
            assertEquals("Subscription contains only service placeholders", subscriptionFailureLabel(requireNotNull(imported.exceptionOrNull())))
            assertTrue(db.serverDao().getAllServersSync().isEmpty())
            assertTrue(db.subscriptionDao().getAllSubscriptionsSync().isEmpty())

            db.subscriptionDao().insertSubscription(SubscriptionEntity("sub", "Saved source", http.url, lastUpdated = 123, nodeCount = 1))
            db.serverDao().insertServer(ServerNode("working", "Working node", "vless", "example.com", 443,
                subId = "sub", subName = "Saved source", isSelected = true))
            val updated = repository.updateSubscription("sub")
            assertTrue(updated.isFailure)
            assertEquals(listOf("working"), db.serverDao().getAllServersSync().map { it.id })
            assertEquals("working", db.serverDao().getSelectedServerSync()!!.id)
            val saved = requireNotNull(db.subscriptionDao().getSubscriptionById("sub"))
            assertEquals(123L, saved.lastUpdated)
            assertEquals(1, saved.nodeCount)
            assertEquals("Saved source", saved.name)
            assertEquals(http.url, saved.url)
            assertTrue(saved.lastAttemptAt > 0)
            assertEquals("Subscription contains only service placeholders", saved.lastError)
            assertFalse(saved.lastFailureRetryable)
            val history = repository.updateHistory("sub").first()
            assertEquals(1, history.size)
            assertFalse(history.single().successful)
            assertEquals(saved.lastError, history.single().error)
        }
    }

    @Test fun historyRetentionIsBoundedWithoutDiscardingNewestResult() = fixture { db, _, _, _ ->
        repeat(25) { index ->
            db.subscriptionDao().insertUpdateEvent(SubscriptionUpdateEvent(subscriptionId = "sub", attemptedAt = index.toLong(),
                successful = true, previousNodeCount = index, nodeCount = index + 1, error = "", retryable = false))
            db.subscriptionDao().trimUpdateHistory("sub")
        }
        val history = db.subscriptionDao().updateHistory("sub").first()
        assertEquals(20, history.size)
        assertEquals(24L, history.first().attemptedAt)
        assertEquals(5L, history.last().attemptedAt)
    }

    @Test fun routingConsentCannotEnableRulesThatChangedAfterPreviewOpened() = fixture { db, settings, vault, _ ->
        val before = """[{"type":"field","outboundTag":"proxy","domain":["full:example.com"]}]"""
        val after = before.replace("\"proxy\"", "\"direct\"")
        db.subscriptionDao().insertSubscription(SubscriptionEntity("sub", "Private", "https://example.com", importedRulesJson = after))
        val repository = SubscriptionRepository(db.serverDao(), db.subscriptionDao(), settings, vault, db, SubscriptionTransport())
        val stale = repository.setSubscriptionRulesEnabled("sub", true, expectedRulesJson = before)
        assertTrue(stale.isFailure)
        assertFalse(db.subscriptionDao().getSubscriptionById("sub")!!.useSubscriptionRules)
        assertEquals(after, db.subscriptionDao().getSubscriptionById("sub")!!.importedRulesJson)

        // Property order in a preview is not a policy change; canonical equality is sufficient.
        val reviewed = """[{"domain":["full:example.com"],"outboundTag":"direct","type":"field"}]"""
        assertTrue(repository.setSubscriptionRulesEnabled("sub", true, reviewed).isSuccess)
        assertTrue(db.subscriptionDao().getSubscriptionById("sub")!!.useSubscriptionRules)
        assertTrue(repository.setSubscriptionRulesEnabled("sub", false, before).isSuccess)
        assertFalse(db.subscriptionDao().getSubscriptionById("sub")!!.useSubscriptionRules)
    }

    @Test fun refreshedRoutingPolicyNeedsNewConsentAndUrlChangeClearsIt() = fixture { db, settings, vault, _ ->
        HttpFixture().use { http ->
            http.content = """{
              "outbounds":[
                {"tag":"vpn","protocol":"vless","settings":{"vnext":[{"address":"vpn.example","port":443,"users":[{"id":"11111111-2222-3333-4444-555555555555","encryption":"none"}]}]},"streamSettings":{"network":"tcp","security":"tls"}},
                {"tag":"local","protocol":"freedom"}
              ],
              "routing":{"rules":[{"type":"field","domain":["domain:example.com"],"outboundTag":"vpn"}]}
            }"""
            val repository = SubscriptionRepository(db.serverDao(), db.subscriptionDao(), settings, vault, db, SubscriptionTransport())
            assertTrue(repository.importSubscription(http.url, "Rules source").isSuccess)
            var sub = db.subscriptionDao().getAllSubscriptionsSync().single()
            assertFalse(sub.useSubscriptionRules)
            assertTrue(sub.importedRulesJson.isNotBlank())
            assertTrue(repository.setSubscriptionRulesEnabled(sub.id, true, sub.importedRulesJson).isSuccess)
            assertTrue(repository.updateSubscription(sub.id).isSuccess)
            sub = db.subscriptionDao().getSubscriptionById(sub.id)!!
            assertTrue(sub.useSubscriptionRules) // An unchanged set keeps the user's consent.
            val reviewed = sub.importedRulesJson
            http.content = http.content.replace("\"outboundTag\":\"vpn\"", "\"outboundTag\":\"local\"")
            assertTrue(repository.updateSubscription(sub.id).isSuccess)
            sub = db.subscriptionDao().getSubscriptionById(sub.id)!!
            assertFalse(sub.useSubscriptionRules)
            assertNotEquals(reviewed, sub.importedRulesJson)
            assertTrue(repository.setSubscriptionRulesEnabled(sub.id, true, reviewed).isFailure)
            assertTrue(repository.setSubscriptionRulesEnabled(sub.id, true, sub.importedRulesJson).isSuccess)
            assertTrue(repository.editSubscription(sub.id, sub.name, "https://other.example/sub").isSuccess)
            sub = db.subscriptionDao().getSubscriptionById(sub.id)!!
            assertFalse(sub.useSubscriptionRules)
            assertEquals("", sub.importedRulesJson)
        }
    }

    @Test fun successfulRefreshRetainsVerifiedRemovedNodeAndReappearanceRestoresItsIdentity() = fixture { db, settings, vault, _ ->
        HttpFixture().use { http ->
            val oldLink = "vless://11111111-2222-3333-4444-555555555555@previous.example:443?security=tls#Previous"
            val old = requireNotNull(LinkParser.parse(oldLink)).copy(id = "old", subId = "sub", isSelected = true,
                lastVerifiedAt = System.currentTimeMillis(), ping = 25)
            db.subscriptionDao().insertSubscription(SubscriptionEntity("sub", "Private", http.url, nodeCount = 1))
            db.serverDao().insertServer(old)
            val repository = SubscriptionRepository(db.serverDao(), db.subscriptionDao(), settings, vault, db, SubscriptionTransport())
            assertTrue(repository.updateSubscription("sub").isSuccess)
            val updated = db.serverDao().getServersBySubIdSync("sub")
            assertEquals(2, updated.size)
            assertTrue(updated.single { it.id == "old" }.isRetained)
            assertEquals("old", db.serverDao().getSelectedServerSync()!!.id)
            assertEquals(1, db.subscriptionDao().getSubscriptionById("sub")!!.retainedNodeCount)
            assertEquals(2, db.subscriptionDao().getSubscriptionById("sub")!!.nodeCount)

            http.content = oldLink
            assertTrue(repository.updateSubscription("sub").isSuccess)
            val returned = db.serverDao().getServersBySubIdSync("sub").single()
            assertEquals("old", returned.id)
            assertFalse(returned.isRetained)
            assertEquals(old.lastVerifiedAt, returned.lastVerifiedAt)
            assertEquals(0, db.subscriptionDao().getSubscriptionById("sub")!!.retainedNodeCount)
        }
    }

    @Test fun disablingReserveImmediatelyPurgesOnlyRetainedNodesAndRepairsSelection() = fixture { db, settings, vault, _ ->
        val now = System.currentTimeMillis()
        db.subscriptionDao().insertSubscription(SubscriptionEntity("sub", "Private", "https://example.com", nodeCount = 2, retainedNodeCount = 1))
        db.serverDao().insertServers(listOf(
            ServerNode("old", "Old", "vless", "old.example", 443, subId = "sub", isSelected = true,
                isRetained = true, retainedAt = now, lastVerifiedAt = now),
            ServerNode("new", "New", "vless", "new.example", 443, subId = "sub")
        ))
        val repository = SubscriptionRepository(db.serverDao(), db.subscriptionDao(), settings, vault, db, SubscriptionTransport())
        repository.setSubscriptionRetention("sub", false)
        assertEquals(listOf("new"), db.serverDao().getAllServersSync().map { it.id })
        assertEquals("new", db.serverDao().getSelectedServerSync()!!.id)
        val sub = db.subscriptionDao().getSubscriptionById("sub")!!
        assertFalse(sub.retainWorkingNodes)
        assertEquals(1, sub.nodeCount)
        assertEquals(0, sub.retainedNodeCount)
    }

    @Test fun inFlightOldProbeCannotCertifyOrOverwriteEditedCredentials() = fixture { db, _, _, _ ->
        val old = ServerNode("same", "Old", "vless", "example.com", 443, uuid = "old-key")
        val updated = old.copy(uuid = "new-key", ping = 0)
        db.serverDao().insertServer(updated)
        assertFalse(db.serverDao().recordVerificationIfUnchanged(old, 1000, successful = true))
        assertFalse(db.serverDao().recordVerificationIfUnchanged(old, 1000, successful = false))
        assertFalse(db.serverDao().updatePingIfUnchanged(old, 25))
        var current = db.serverDao().getServerById("same")!!
        assertEquals(0L, current.lastVerifiedAt)
        assertEquals(0L, current.lastVerificationFailedAt)
        assertEquals(0L, current.ping)
        assertTrue(db.serverDao().recordVerificationIfUnchanged(updated, 2000, successful = true))
        assertTrue(db.serverDao().recordVerificationIfUnchanged(updated, 3000, successful = false))
        current = db.serverDao().getServerById("same")!!
        assertEquals(2000L, current.lastVerifiedAt)
        assertEquals(3000L, current.lastVerificationFailedAt)
        assertFalse(SubscriptionNodeMerger.canRetain(current, 4000))
    }

    @Test fun expiredReserveIsPrunedAtStartupWithoutChangingCurrentProviderNodes() = fixture { db, settings, vault, _ ->
        val now = System.currentTimeMillis()
        val expired = now - SubscriptionNodeMerger.RETENTION_MILLIS - 1
        db.subscriptionDao().insertSubscription(SubscriptionEntity("sub", "Private", "https://example.com", nodeCount = 2, retainedNodeCount = 1))
        db.serverDao().insertServers(listOf(
            ServerNode("expired", "Old", "vless", "old.example", 443, subId = "sub", isRetained = true,
                retainedAt = expired, lastVerifiedAt = expired),
            ServerNode("current", "Current", "vless", "current.example", 443, subId = "sub")
        ))
        val repository = SubscriptionRepository(db.serverDao(), db.subscriptionDao(), settings, vault, db, SubscriptionTransport())
        repository.pruneExpiredRetainedNodes()
        assertEquals(listOf("current"), db.serverDao().getAllServersSync().map { it.id })
        assertEquals(1, db.subscriptionDao().getSubscriptionById("sub")!!.nodeCount)
        assertEquals(0, db.subscriptionDao().getSubscriptionById("sub")!!.retainedNodeCount)
    }
}
