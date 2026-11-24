package com.danieldk.splatevpn.vpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.VpnService
import android.os.IBinder
import android.os.Parcel
import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.data.AdvancedRouteRule
import com.danieldk.splatevpn.data.AdvancedRoutingRules
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real Android ownership lookup + TUN + HEV + stock Xray, with two installed APK UIDs. */
class AppUidRoutingEndToEndTest {
    @Test fun onlySelectedApkCanReachMatchingSiteAndUdpTarget() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val helper = instrumentation.context
        assertNull("Grant test VPN with appops ACTIVATE_VPN on the dedicated QA emulator", VpnService.prepare(app))
        val selectedPackage = helper.packageName
        assertNotEquals(android.os.Process.myUid(), app.packageManager.getApplicationInfo(selectedPackage, 0).uid)
        AppFlowIdentityResolver.validatePackages(app, listOf(selectedPackage))
        val directory = File(app.cacheDir, "uid-e2e-${UUID.randomUUID()}").apply { mkdirs() }
        val binders = mutableListOf<ServiceConnection>()
        val worker = Executors.newFixedThreadPool(2)
        val loopback = InetAddress.getByName("127.0.0.2")
        val tcp = ServerSocket(0, 8, loopback).apply { soTimeout = 150 }
        val udp = DatagramSocket(0, loopback).apply { soTimeout = 150 }
        var core: Process? = null
        var vpn: AppIdentityTestVpnService? = null
        val log = File(directory, "core.log")
        try {
            val binary = File(app.applicationInfo.nativeLibraryDir, "libxray.so")
            AppIdentityCoreCompatibility.ensureCompatible(app, binary)
            val privatePort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
            val access = AppIdentityAccess(privatePort, setOf(selectedPackage), LocalProxyCredentials.generate())
            val rules = AdvancedRoutingRules.compile(listOf(
                AdvancedRouteRule("real-uid-tcp", "Real UID TCP", packages = listOf(selectedPackage),
                    suffixes = listOf("example.test"), keywords = listOf("api"), networks = setOf("tcp"), outbound = "direct"),
                AdvancedRouteRule("real-uid-udp", "Real UID UDP", packages = listOf(selectedPackage),
                    ips = listOf("198.18.0.8"), networks = setOf("udp"), outbound = "direct")
            )).map { it.apply {
                add("inboundTag", strings(AppIdentityAccess.INBOUND_TAG))
                if (get("network").asString == "udp") addProperty("outboundTag", "udp-origin")
            } }
            val config = JsonObject().apply {
                add("log", JsonObject().apply { addProperty("loglevel", "info"); addProperty("access", "") })
                add("inbounds", JsonArray().apply { add(JsonObject().apply {
                    addProperty("tag", AppIdentityAccess.INBOUND_TAG); addProperty("listen", "127.0.0.1")
                    addProperty("port", access.port); addProperty("protocol", "socks"); add("settings", access.settings())
                    add("sniffing", JsonObject().apply {
                        addProperty("enabled", true); addProperty("routeOnly", true); add("destOverride", strings("http"))
                    })
                }) })
                add("outbounds", JsonArray().apply {
                    add(JsonObject().apply { addProperty("tag", "block"); addProperty("protocol", "blackhole") })
                    for ((tag, port) in listOf("direct" to tcp.localPort, "udp-origin" to udp.localPort)) add(JsonObject().apply {
                        addProperty("tag", tag); addProperty("protocol", "freedom")
                        add("settings", JsonObject().apply { addProperty("redirect", "127.0.0.2:$port") })
                    })
                })
                add("routing", JsonObject().apply {
                    addProperty("domainStrategy", "AsIs")
                    add("rules", JsonArray().apply { rules.forEach(::add) })
                })
            }
            val source = File(directory, "client.json").apply { writeText(config.toString()) }
            core = ProcessBuilder(binary.absolutePath, "run", "-c", source.absolutePath)
                .redirectErrorStream(true).redirectOutput(log).start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
            while (true) {
                assertTrue(log.readText(), core.isAlive)
                try { Socket().use { it.connect(InetSocketAddress("127.0.0.1", privatePort), 100) }; break }
                catch (error: Exception) { if (System.nanoTime() >= deadline) throw error; Thread.sleep(20) }
            }
            worker.submit {
                while (!tcp.isClosed) {
                    val socket = try { tcp.accept() } catch (_: SocketTimeoutException) { continue }
                    catch (_: Exception) { break }
                    socket.use {
                        it.soTimeout = 2000
                        val reader = it.getInputStream().bufferedReader()
                        var line = reader.readLine()
                        while (!line.isNullOrEmpty()) line = reader.readLine()
                        it.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 15\r\nConnection: close\r\n\r\nuid-native-echo".toByteArray())
                    }
                }
            }
            worker.submit {
                while (!udp.isClosed) {
                    val packet = DatagramPacket(ByteArray(512), 512)
                    try { udp.receive(packet); udp.send(packet) }
                    catch (_: SocketTimeoutException) { continue }
                    catch (_: Exception) { break }
                }
            }
            val remote = bind(app, Intent().setComponent(ComponentName(selectedPackage,
                AppIdentityTestSocketService::class.java.name)), binders)
            val local = bind(app, Intent(app, AppIdentityTestVpnService::class.java), binders)
            vpn = (local as AppIdentityTestVpnService.LocalBinder).service
            val hev = File(directory, "hev.yaml").apply { writeText(buildString {
                appendLine("tunnel:"); appendLine("  mtu: 1500"); appendLine("  ipv4: 172.19.0.1")
                appendLine("socks5:"); appendLine("  address: 127.0.0.1"); appendLine("  port: $privatePort")
                appendLine("  udp: udp"); appendLine(access.fallback.hevYaml())
                appendLine("misc:"); appendLine("  connect-timeout: 1500")
                appendLine("  tcp-read-write-timeout: 5000"); appendLine("  udp-read-write-timeout: 5000")
                appendLine("  log-level: error")
            }) }
            vpn.startTunnel(hev.absolutePath, access)
            var first = ""
            val readyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
            while (first.isEmpty() && System.nanoTime() < readyDeadline) {
                first = remoteRequest(remote, "tcp", 18080, "api.example.test")
                if (first.isEmpty()) Thread.sleep(150)
            }
            assertEquals("uid-native-echo", first)
            assertEquals("", AppIdentityTestSocketService.request("tcp", 18080, "api.example.test"))
            assertEquals("", remoteRequest(remote, "tcp", 18080, "api.other.test"))
            assertEquals("", remoteRequest(remote, "tcp", 18080, "www.example.test"))
            assertEquals("uid-native-echo", remoteRequest(remote, "tcp", 18080, "api.example.test"))
            assertEquals("uid-native-echo", remoteRequest(remote, "udp", 18081, ""))
            assertEquals("", AppIdentityTestSocketService.request("udp", 18081, ""))
            assertEquals("uid-native-echo", remoteRequest(remote, "udp", 18081, ""))
        } catch (error: Throwable) {
            throw AssertionError("Real Android UID routing failed\nFlows: ${vpn?.flows}\n" +
                log.takeIf { it.exists() }?.readText().orEmpty(), error)
        } finally {
            vpn?.stopTunnel()
            binders.forEach { runCatching { app.unbindService(it) } }
            core?.let { it.destroy(); if (!it.waitFor(1, TimeUnit.SECONDS)) { it.destroyForcibly(); it.waitFor(1, TimeUnit.SECONDS) } }
            tcp.close(); udp.close(); worker.shutdownNow(); worker.awaitTermination(2, TimeUnit.SECONDS)
            directory.deleteRecursively()
        }
    }

    private fun bind(context: Context, intent: Intent, connections: MutableList<ServiceConnection>): IBinder {
        val result = AtomicReference<IBinder>()
        val ready = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) { result.set(service); ready.countDown() }
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        check(context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) { "Cannot bind ${intent.component}" }
        connections += connection
        check(ready.await(5, TimeUnit.SECONDS)) { "No binder for ${intent.component}" }
        return requireNotNull(result.get())
    }

    private fun remoteRequest(binder: IBinder, protocol: String, port: Int, host: String): String {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(AppIdentityTestSocketService.DESCRIPTOR)
            data.writeString(protocol); data.writeInt(port); data.writeString(host)
            check(binder.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0))
            reply.readException()
            assertNotEquals("Helper request unexpectedly used the target app UID", android.os.Process.myUid(), reply.readInt())
            return reply.readString().orEmpty()
        } finally { data.recycle(); reply.recycle() }
    }

    private fun strings(vararg values: String) = JsonArray().apply { values.forEach(::add) }
}
