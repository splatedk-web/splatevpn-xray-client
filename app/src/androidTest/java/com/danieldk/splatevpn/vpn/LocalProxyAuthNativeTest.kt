package com.danieldk.splatevpn.vpn

import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.data.ServerNode
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Both protocols share a single authenticated Xray listener; all traffic stays on loopback. */
class LocalProxyAuthNativeTest {
    @Test fun mixedHttpSocksAndConnectRequireTheSameCredentials() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(app.cacheDir, "mixed-auth-${UUID.randomUUID()}").apply { mkdirs() }
        val auth = LocalProxyCredentials("native-user", "native-password")
        val target = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 10000 }
        val worker = Executors.newSingleThreadExecutor()
        var process: Process? = null
        val originRequests = AtomicInteger()
        try {
            val reservations = List(2) { ServerSocket(0) }
            val port = reservations[0].localPort
            val dnsPort = reservations[1].localPort
            reservations.forEach { it.close() }
            val node = ServerNode("fixture", "fixture", "vless", "192.0.2.1", 443, uuid = UUID.randomUUID().toString())
            val config = JsonParser.parseString(XrayConfigGenerator().generateConfig(node, mode = "global",
                enableSniffing = false, bypassAi = false, adBlocker = false,
                localProxyAccess = auth, dnsProbePort = dnsPort)).asJsonObject
            config.getAsJsonArray("inbounds")[0].asJsonObject.addProperty("port", port)
            config.add("dns", JsonObject().apply { add("servers", JsonArray().apply { add("localhost") }) })
            config.add("outbounds", JsonArray().apply { add(JsonObject().apply {
                addProperty("tag", "local"); addProperty("protocol", "freedom")
                add("settings", JsonObject().apply { add("finalRules", JsonArray().apply { add(JsonObject().apply {
                    addProperty("action", "allow"); addProperty("network", "tcp"); addProperty("port", target.localPort)
                    add("ip", JsonArray().apply { add("127.0.0.1/32") })
                }) }) })
            }) })
            config.add("routing", JsonObject().apply { add("rules", JsonArray().apply { add(JsonObject().apply {
                addProperty("type", "field"); addProperty("network", "tcp"); addProperty("outboundTag", "local")
            }) }) })
            val source = File(directory, "client.json").apply { writeText(config.toString()) }
            val log = File(directory, "client.log")
            process = ProcessBuilder(File(app.applicationInfo.nativeLibraryDir, "libxray.so").absolutePath,
                "run", "-c", source.absolutePath).redirectErrorStream(true).redirectOutput(log).start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (true) {
                assertTrue(log.readText(), process.isAlive)
                try { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 100) }; break }
                catch (error: java.io.IOException) { if (System.nanoTime() > deadline) throw error; Thread.sleep(30) }
            }
            for (listener in listOf(port, dnsPort)) Socket("127.0.0.1", listener).use { socket ->
                socket.soTimeout = 3000
                socket.getOutputStream().write(byteArrayOf(5, 1, 0))
                assertEquals(5, socket.getInputStream().read()); assertEquals(255, socket.getInputStream().read())
            }
            val base = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
            val url = "http://127.0.0.1:${target.localPort}/"
            for (credentials in listOf(null, LocalProxyCredentials("native-user", "wrong-password"))) {
                LocalProxyAccess.client(base, port, credentials).newCall(Request.Builder().url(url).build()).execute().use {
                    assertEquals(407, it.code)
                }
            }
            assertEquals(0, originRequests.get())
            val responses = worker.submit<Unit> { repeat(3) { target.accept().use { socket ->
                socket.soTimeout = 5000
                val reader = socket.getInputStream().bufferedReader()
                assertTrue(reader.readLine().startsWith("GET /"))
                while (true) {
                    val header = reader.readLine() ?: break
                    if (header.isEmpty()) break
                    assertFalse("Proxy credentials reached the origin", header.startsWith("Proxy-Authorization:", true))
                }
                originRequests.incrementAndGet()
                socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 7\r\nConnection: close\r\n\r\nauth-ok".toByteArray())
            } } }
            LocalProxyAccess.client(base, port, auth).newCall(Request.Builder().url(url).build()).execute().use {
                assertEquals(200, it.code); assertEquals("auth-ok", it.body!!.string())
            }
            fun readResponse(socket: Socket) {
                socket.soTimeout = 5000
                val reader = socket.getInputStream().bufferedReader()
                assertEquals("HTTP/1.1 200 OK", reader.readLine())
                while (!reader.readLine().isNullOrEmpty()) { }
                assertEquals("auth-ok", CharArray(7).also { chars -> repeat(7) { chars[it] = reader.read().toChar() } }.concatToString())
            }
            Socket().use { socket ->
                socket.soTimeout = 5000
                LocalProxyAccess.connectSocks(socket, port, auth, "127.0.0.1", target.localPort, 3000)
                socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
                readResponse(socket)
            }
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 5000
                socket.getOutputStream().write(("CONNECT 127.0.0.1:${target.localPort} HTTP/1.1\r\n" +
                    "Host: 127.0.0.1:${target.localPort}\r\nProxy-Authorization: ${okhttp3.Credentials.basic(auth.username, auth.password)}\r\n\r\n").toByteArray())
                val reader = socket.getInputStream().bufferedReader()
                assertTrue(reader.readLine().startsWith("HTTP/1.1 200")); assertEquals("", reader.readLine())
                socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
                readResponse(socket)
            }
            responses.get(5, TimeUnit.SECONDS)
            assertEquals(3, originRequests.get())
            assertTrue(log.readText(), process.isAlive)
        } finally {
            target.close(); worker.shutdownNow()
            process?.let { it.destroy(); if (!it.waitFor(2, TimeUnit.SECONDS)) { it.destroyForcibly(); it.waitFor(2, TimeUnit.SECONDS) } }
            directory.deleteRecursively()
        }
    }
}
