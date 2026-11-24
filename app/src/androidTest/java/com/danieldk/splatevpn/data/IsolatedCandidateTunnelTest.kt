package com.danieldk.splatevpn.data

import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.vpn.*
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.Socket
import java.util.UUID

/** Starts the shipped Xray but never issues CONNECT or sends traffic outside loopback. */
class IsolatedCandidateTunnelTest {
    @Test fun isolatedCandidateRequiresAuthenticationAndCancellationClosesItsWorker() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(app.cacheDir, "candidate-worker-${UUID.randomUUID()}").apply { mkdirs() }
        val preferences = mutableSetOf<String>()
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) =
                (directory.name + "-" + name).let { preferences += it; app.getSharedPreferences(it, mode) }
        }
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val status = VpnStatusManager(context)
        val core = XrayCoreManager(context, AppLogManager(context), status, LiveConnectionTracker(), testHealthAdvisor(context, status))
        val settings = SettingsManager(context)
        val probes = ServerProbeRepository(context, database.serverDao(), settings,
            javax.inject.Provider { status }, XrayConfigGenerator(), javax.inject.Provider { core }, PhysicalFailureEvidence(context, settings))
        val node = ServerNode("fixture", "Local worker", "vless", "192.0.2.1", 9,
            uuid = "11111111-2222-3333-4444-555555555555", ping = 123, security = "tls", sni = "example.invalid")
        var running: Job? = null
        try {
            database.serverDao().insertServer(node)
            settings.setTlsFragmentEnabled(false)
            probes.markRequiresTlsFragment(node.id)
            val opened = CompletableDeferred<Int>()
            running = launch(Dispatchers.IO) {
                probes.withServerTunnel(node, 20_000) { port, credentials ->
                    val source = context.cacheDir.listFiles().orEmpty().single { it.name.startsWith("xray_test_") }
                    val config = JsonParser.parseString(source.readText()).asJsonObject
                    val outbounds = config.getAsJsonArray("outbounds")
                    assertTrue("Learned fragmentation must reach the real qualification worker",
                        outbounds.any { it.asJsonObject.get("tag")?.asString == "fragment" })
                    val proxy = outbounds.first { it.asJsonObject.get("tag")?.asString == "proxy" }.asJsonObject
                    assertEquals("fragment", proxy.getAsJsonObject("streamSettings").getAsJsonObject("sockopt").get("dialerProxy").asString)
                    val access = requireNotNull(credentials)
                    Socket("127.0.0.1", port).use { socket ->
                        socket.soTimeout = 3000
                        val input = socket.getInputStream()
                        val output = socket.getOutputStream()
                        output.write(byteArrayOf(5, 1, 0)); output.flush()
                        assertEquals(5, input.read()); assertEquals(255, input.read())
                    }
                    Socket("127.0.0.1", port).use { socket ->
                        socket.soTimeout = 3000
                        val input = socket.getInputStream()
                        val output = socket.getOutputStream()
                        output.write(byteArrayOf(5, 1, 2)); output.flush()
                        assertEquals(5, input.read()); assertEquals(2, input.read())
                        val user = access.username.toByteArray()
                        val password = access.password.toByteArray()
                        output.write(byteArrayOf(1, user.size.toByte())); output.write(user)
                        output.write(password.size); output.write(password); output.flush()
                        assertEquals(1, input.read()); assertEquals(0, input.read())
                    }
                    opened.complete(port)
                    awaitCancellation()
                }
            }
            val port = withTimeout(20_000) { opened.await() }
            running.cancelAndJoin()
            withTimeout(5000) {
                while (runCatching { Socket("127.0.0.1", port).use { true } }.getOrDefault(false)) delay(40)
            }
            assertEquals(123L, database.serverDao().getServerById(node.id)?.ping)
            assertEquals(0L, database.serverDao().getServerById(node.id)?.lastVerificationFailedAt)
            assertTrue(probes.activePingIds.value.isEmpty())
            assertTrue(probes.verifiedDelays.value.isEmpty())
            assertFalse(context.cacheDir.listFiles().orEmpty().any { it.name.startsWith("xray_test_") })
        } finally {
            running?.cancelAndJoin()
            core.stopCore()
            database.close()
            preferences.forEach(app::deleteSharedPreferences)
            directory.deleteRecursively()
        }
    }
}
