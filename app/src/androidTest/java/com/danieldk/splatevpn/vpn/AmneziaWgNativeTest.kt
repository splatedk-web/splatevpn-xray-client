package com.danieldk.splatevpn.vpn

import android.net.ConnectivityManager
import android.net.VpnService
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.data.*
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.*
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

class AmneziaWgNativeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private fun fixture(): Pair<ServerNode, OkHttpClient> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Start the isolated AWG fixture with scripts/run_awg_android_qa.py", args.containsKey("awg_profile"))
        val node = AmneziaWg.node(Base64.getDecoder().decode(args.getString("awg_profile")).toString(Charsets.UTF_8))
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(Base64.getDecoder().decode(args.getString("awg_certificate")).inputStream()) as X509Certificate
        val tls = HandshakeCertificates.Builder().addTrustedCertificate(cert).build()
        return node to OkHttpClient.Builder().sslSocketFactory(tls.sslSocketFactory(), tls.trustManager).build()
    }
    @Test fun shippedAwgAndXrayCarryVerifiedHttpsDnsAndUdpAndStopCleanly() = runBlocking {
        val (node, client) = fixture()
        assertNull("Authorize the test app as VPN before testing descriptor protection", VpnService.prepare(app))
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val physical = cm.activeNetwork!!
        var protectedSockets = 0
        AwgSocketProtection(VpnService()) { fd -> physical.bindSocket(fd); protectedSockets++ }.use {
            val port = ServerSocket(0).use { it.localPort }
            val auth = LocalProxyCredentials.generate()
            val raw = XrayConfigGenerator().generateTestConfig(node, port, localProxyAccess = auth)
            val prepared = PreparedTransports.prepare(app, raw)
            assertFalse(prepared.json.contains(node.amneziaWg!!.privateKey))
            val dir = File(app.cacheDir, "awg-native-${UUID.randomUUID()}").apply { mkdirs() }
            val config = File(dir, "config.json").apply { writeText(prepared.json) }
            val output = File(dir, "core.log")
            val process = ProcessBuilder(File(app.applicationInfo.nativeLibraryDir, "libxray.so").absolutePath, "run", "-c", config.path).redirectErrorStream(true).redirectOutput(output).start()
            try {
                val deadline = System.currentTimeMillis() + 5000
                while (System.currentTimeMillis() < deadline && process.isAlive) {
                    if (runCatching { Socket("127.0.0.1", port).use { LocalProxyAccess.probeReadiness(it, auth.username to auth.password) } }.getOrDefault(false)) break
                    kotlinx.coroutines.delay(40)
                }
                assertTrue(process.isAlive)
                val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
                assertTrue("HTTPS through encrypted AWG failed", TunnelProbe.measure(proxy, "https://awg-test.invalid/generate_204", 12000, credentials = auth, client = client) > 0)
                assertEquals("Untrusted certificate must fail", -1L, TunnelProbe.measure(proxy, "https://awg-test.invalid/generate_204", 3000, credentials = auth))
                AmneziaWgRuntime.rebindAll()
                assertTrue(TunnelProbe.measure(proxy, "https://awg-test.invalid/generate_204", 12000, credentials = auth, client = client) > 0)
                // Native UDP also uses the same peer. The Xray outbound's listener is authenticated.
                val outbound = com.google.gson.JsonParser.parseString(prepared.json).asJsonObject.getAsJsonArray("outbounds").first { it.asJsonObject["tag"].asString == "proxy" }.asJsonObject
                val settings = outbound.getAsJsonObject("settings").getAsJsonArray("servers")[0].asJsonObject
                val user = settings.getAsJsonArray("users")[0].asJsonObject
                val control = Socket("127.0.0.1", settings["port"].asInt)
                control.use { c ->
                    c.soTimeout = 5000; val input = java.io.DataInputStream(c.getInputStream()); val out = c.getOutputStream()
                    out.write(byteArrayOf(5,1,2)); assertEquals(5, input.readUnsignedByte()); assertEquals(2,input.readUnsignedByte())
                    val u = user["user"].asString.toByteArray(); val p = user["pass"].asString.toByteArray()
                    out.write(byteArrayOf(1,u.size.toByte()) + u + byteArrayOf(p.size.toByte()) + p); assertEquals(1,input.readUnsignedByte()); assertEquals(0,input.readUnsignedByte())
                    out.write(byteArrayOf(5,3,0,1,0,0,0,0,0,0)); val reply = ByteArray(10); input.readFully(reply); assertEquals(0,reply[1].toInt())
                    val relayPort = (reply[8].toInt() and 255)*256 + (reply[9].toInt() and 255)
                    DatagramSocket().use { udp ->
                        udp.soTimeout = 8000
                        val host = "awg-test.invalid".toByteArray(); val payload = "AWG encrypted datagram".toByteArray()
                        val packet = byteArrayOf(0,0,0,3,host.size.toByte()) + host + byteArrayOf(31,145.toByte()) + payload
                        udp.send(DatagramPacket(packet,packet.size,InetAddress.getByName("127.0.0.1"),relayPort))
                        val received = DatagramPacket(ByteArray(2048),2048); udp.receive(received)
                        assertTrue(received.data.copyOf(received.length).toString(Charsets.UTF_8).endsWith(payload.toString(Charsets.UTF_8)))
                    }
                }
                assertTrue("SCM_RIGHTS/protect was not exercised", protectedSockets >= 2)
            } finally {
                process.destroyForcibly(); assertTrue(process.waitFor(3, TimeUnit.SECONDS)); prepared.close(); assertFalse(prepared.alive)
                dir.deleteRecursively()
            }
        }
    }
    @Test fun mainVpnServiceEstablishesAwgAndStopsWithoutOrphanedCore() = runBlocking {
        assumeTrue("Opt in only on the isolated emulator", InstrumentationRegistry.getArguments().getString("awg_full_service") == "true")
        val (node, client) = fixture()
        assertNull("Authorize the isolated VPN test app", VpnService.prepare(app))
        val widget = dagger.hilt.android.EntryPointAccessors.fromApplication(app, com.danieldk.splatevpn.ui.widget.SplateAppWidgetProvider.WidgetEntryPoint::class.java)
        val worker = dagger.hilt.android.EntryPointAccessors.fromApplication(app, SubscriptionUpdateWorker.WorkerDependencies::class.java)
        val repository = worker.serverRepository(); val settings = widget.settingsManager(); val status = widget.vpnStatusManager()
        assertEquals(VpnState.DISCONNECTED, status.state.value)
        val before = settings.preferenceSnapshot(); val previous = repository.selectedServer.first()
        var added: ServerNode? = null
        try {
            settings.setAutoSelectServer(false); settings.setRoutingMode("global"); settings.setGlobalAppProxy(true); settings.setAdBlocker(false)
            settings.setNotificationPreferences(settings.getNotificationPreferences().copy(showSpeed = false, showServer = false, showDuration = false))
            repository.addServersFromLinks(AmneziaWg.conf(node.amneziaWg!!))
            added = repository.allServers.first().first { it.isAmneziaWg && it.amneziaWg == node.amneziaWg }
            repository.setActiveServer(added.id)
            app.startForegroundService(android.content.Intent(app, SplateVpnService::class.java).setAction("START"))
            withTimeout(30000) { status.state.first { it in setOf(VpnState.CONNECTED, VpnState.FAILED) } }
            assertEquals("Main VPN failed: ${status.errorMessage.value}", VpnState.CONNECTED, status.state.value)
            assertTrue(LocalProxyAccess.isAvailable)
            kotlinx.coroutines.delay(3500) // include a real stats notification refresh
            val nm = app.getSystemService(android.app.NotificationManager::class.java)
            val notice = nm.activeNotifications.first { it.id == 1 }.notification
            assertEquals("SplateVPN", notice.extras.getString(android.app.Notification.EXTRA_TITLE))
            assertFalse(notice.extras.getBoolean(android.app.Notification.EXTRA_SHOW_CHRONOMETER))
            assertFalse(notice.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString().contains("↓"))
            val auth = LocalProxyAccess.active
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1",10808))
            assertTrue("Main core did not carry AWG HTTPS", TunnelProbe.measure(proxy,"https://awg-test.invalid/generate_204",12000,credentials=auth,client=client)>0)
        } finally {
            try {
                app.startService(android.content.Intent(app,SplateVpnService::class.java).setAction("STOP"))
                withTimeout(15000) { status.state.first { it in setOf(VpnState.DISCONNECTED, VpnState.FAILED) } }
                withTimeout(15000) { while (LocalProxyAccess.isAvailable || AwgSocketProtection.path != null) kotlinx.coroutines.delay(50) }
                assertFalse(LocalProxyAccess.isAvailable)
            } finally {
                previous?.let { repository.setActiveServer(it.id) }
                added?.let { repository.deleteServer(it.id) }
                settings.restorePreferenceSnapshot(before)
            }
        }
    }

    @Test fun cancellationDuringNativeStartupLeavesNoAwgProcess() = runBlocking {
        val node = fixture().first
        val before = AmneziaWgRuntime.runningCount
        repeat(8) {
            val job = launch(kotlinx.coroutines.Dispatchers.IO) {
                PreparedTransports.prepare(app, XrayConfigGenerator().generateTestConfig(node, 26000 + it)).use { kotlinx.coroutines.delay(2000) }
            }
            kotlinx.coroutines.delay(10L + it * 5)
            job.cancelAndJoin()
        }
        assertEquals(before, AmneziaWgRuntime.runningCount)
    }

    @Test fun roomAndAutomaticVaultKeepPrivateKeysEncrypted() = runBlocking {
        val node = fixture().first
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        try {
            db.serverDao().insertServer(node)
            val stored = db.openHelper.readableDatabase.query("SELECT amneziaWg, rawLink, uuid, fullConfigJson FROM servers").use { c -> assertTrue(c.moveToFirst()); (0..3).joinToString("|") { c.getString(it).orEmpty() } }
            assertFalse(stored.contains(node.amneziaWg!!.privateKey)); assertTrue(stored.startsWith("v1:"))
            assertEquals(node, db.serverDao().getServerById(node.id))
            val document = Gson().toJsonTree(mapOf("servers" to listOf(node))).asJsonObject
            val sealed = AwgLocalVault.seal(document); assertFalse(sealed.toString().contains(node.amneziaWg.privateKey))
            assertEquals(document, AwgLocalVault.open(sealed))
            val broken = stored.substringBefore('|').dropLast(3) + "abc"
            assertTrue(runCatching { AmneziaWgProfileConverter().decode(broken) }.isFailure)
        } finally { db.close() }
    }
}
