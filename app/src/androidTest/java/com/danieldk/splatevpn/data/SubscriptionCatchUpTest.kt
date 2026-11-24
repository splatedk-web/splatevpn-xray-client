package com.danieldk.splatevpn.data

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.io.File
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Actual Room transactions and local HTTP; no public subscriptions are requested. */
class SubscriptionCatchUpTest {
    @Test fun concurrentCatchUpsFetchOnlyOverdueAutomaticSourcesOnce() = fixture { db, repository ->
        HttpFixture().use { http ->
            val now = System.currentTimeMillis()
            db.subscriptionDao().insertSubscriptions(listOf(
                SubscriptionEntity("old", "Old", http.url, lastUpdated = now - TimeUnit.DAYS.toMillis(4)),
                SubscriptionEntity("seed", "Bundled seed", http.url, lastUpdated = 0),
                SubscriptionEntity("fresh", "Fresh", http.url, lastUpdated = now),
                SubscriptionEntity("manual", "Manual", http.url, lastUpdated = 0, autoUpdate = false),
                SubscriptionEntity("disabled", "Disabled", http.url, lastUpdated = 0, enabled = false)))
            val reports = coroutineScope {
                val first = async { repository.refreshSubscriptions(manual = false, dueOnly = true) }
                val second = async { repository.refreshSubscriptions(manual = false, dueOnly = true) }
                listOf(first.await(), second.await())
            }
            assertEquals(2, reports.sumOf { it.updated })
            assertEquals("Catch-up and periodic work must not download the same source twice", 2, http.requests.get())
            assertTrue(reports.all { it.failures.isEmpty() })
            assertEquals(now, db.subscriptionDao().getSubscriptionById("fresh")!!.lastUpdated)
            assertEquals(0L, db.subscriptionDao().getSubscriptionById("manual")!!.lastUpdated)
            assertEquals(0L, db.subscriptionDao().getSubscriptionById("disabled")!!.lastUpdated)
            val repeated = repository.refreshSubscriptions(manual = false, dueOnly = true)
            assertEquals(0, repeated.updated)
            assertEquals(2, http.requests.get())
        }
    }

    @Test fun failedCatchUpPreservesSuccessDateAndDoesNotRetryOnEveryResume() = fixture { db, repository ->
        HttpFixture(status = 503).use { http ->
            val oldSuccess = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(4)
            db.subscriptionDao().insertSubscription(SubscriptionEntity("old", "Old", http.url, lastUpdated = oldSuccess, nodeCount = 1))
            db.serverDao().insertServer(ServerNode("saved", "Saved", "vless", "example.org", 443, subId = "old", isSelected = true))
            val failed = repository.refreshSubscriptions(manual = false, dueOnly = true)
            assertEquals(1, failed.retryableFailures)
            val requests = http.requests.get()
            assertTrue(requests > 0)
            val after = db.subscriptionDao().getSubscriptionById("old")!!
            assertEquals(oldSuccess, after.lastUpdated)
            assertTrue(after.lastAttemptAt > oldSuccess)
            assertTrue(after.lastError.isNotEmpty())
            assertEquals("saved", db.serverDao().getSelectedServerSync()!!.id)
            val resumed = repository.refreshSubscriptions(manual = false, dueOnly = true)
            assertEquals(0, resumed.updated)
            assertEquals("Opening the app again must respect the retry cooldown", requests, http.requests.get())
        }
    }

    private fun fixture(block: suspend (AppDatabase, SubscriptionRepository) -> Unit) = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "catch-up-${UUID.randomUUID()}"
        val directory = File(app.cacheDir, prefix).apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = directory
            override fun getNoBackupFilesDir() = File(directory, "no-backup").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences("$prefix-$name", mode)
        }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val settings = SettingsManager(context)
        settings.completeOnboarding("en", "ru", "global", false)
        val vault = AutoBackupManager(context, db.serverDao(), db.subscriptionDao(), AppLogManager(context), db)
        try {
            block(db, SubscriptionRepository(db.serverDao(), db.subscriptionDao(), settings, vault, db, SubscriptionTransport()))
        } finally {
            db.close()
            directory.deleteRecursively()
            app.applicationInfo.dataDir.let { dataDir ->
                File(dataDir, "shared_prefs").listFiles().orEmpty().filter { it.name.startsWith(prefix) && it.extension == "xml" }
                    .forEach { app.deleteSharedPreferences(it.nameWithoutExtension) }
            }
        }
    }

    private class HttpFixture(private val status: Int = 200) : Closeable {
        private val listener = ServerSocket(0)
        val requests = AtomicInteger()
        val url get() = "http://127.0.0.1:${listener.localPort}/subscription"
        private val thread = Thread {
            while (!listener.isClosed) try {
                listener.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    requests.incrementAndGet()
                    val body = "vless://11111111-2222-3333-4444-555555555555@example.org:443?security=tls#Local".toByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 $status Test\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body)
                        flush()
                    }
                }
            } catch (error: java.io.IOException) { if (!listener.isClosed) throw error }
        }.apply { isDaemon = true; name = "subscription-catch-up-test"; start() }
        override fun close() {
            listener.close()
            thread.join(2000)
        }
    }
}
