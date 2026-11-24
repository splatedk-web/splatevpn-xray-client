package com.danieldk.splatevpn.vpn

import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.data.Hysteria2Profile
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.*
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real HTTP through two bundled Xray processes and local Hysteria QUIC; no public endpoints. */
class Hysteria2NativeTest {
    @Test fun tcpRelayOverHysteria2() = relay(false)
    @Test fun tcpRelayOverSalamander() = relay(true)
    @Test fun liveTcpStreamSurvivesPortHopping() = relay(false, hopping = true)
    @Test fun liveTcpStreamSurvivesPortHoppingWithSalamander() = relay(true, hopping = true)

    private fun relay(obfuscated: Boolean, hopping: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val directory = File(app.cacheDir, "hy2-test-${UUID.randomUUID()}").apply { mkdirs() }
        val binary = File(app.applicationInfo.nativeLibraryDir, "libxray.so")
        val processes = mutableListOf<Process>()
        val worker = Executors.newSingleThreadExecutor()
        // The server endpoint is 127.0.0.1; a different loopback IP avoids its direct-route exception.
        val target = ServerSocket(0, 1, InetAddress.getByName("127.0.0.2")).apply { soTimeout = 20000 }
        var forwarders: UdpForwarders? = null
        val stage = AtomicReference("waiting for target connection")
        try {
            val cert = File(directory, "cert.pem")
            val key = File(directory, "key.pem")
            for ((name, file) in listOf("cert.pem" to cert, "key.pem" to key)) {
                instrumentation.context.assets.open("xhttp-test/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
            }
            val encoded = cert.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it).encoded }
            val pin = MessageDigest.getInstance("SHA-256").digest(encoded).joinToString("") { "%02x".format(it) }
            val remotePort = DatagramSocket(0).use { reservation ->
                forwarders = UdpForwarders(reservation.localPort, if (hopping) 8 else 1)
                reservation.localPort
            }
            val socksPort = ServerSocket(0).use { it.localPort }
            val ports = requireNotNull(forwarders).ports.joinToString(",")
            val extra = if (obfuscated) "&obfs=salamander&obfs-password=local-obfs-key" else ""
            val hop = if (hopping) "&hop-interval=5" else ""
            val node = requireNotNull(Hysteria2Profile.parse("hy2://local-auth@127.0.0.1:$ports/?pinSHA256=$pin$extra$hop"))
            val client = JsonParser.parseString(XrayConfigGenerator().generateTestConfig(node, socksPort)).asJsonObject
            client.getAsJsonObject("log").addProperty("loglevel", "debug")
            val stream = Hysteria2Profile.outbound(node).getAsJsonObject("streamSettings").deepCopy()
            // UDP hopping is client-only; the server listens behind the simulated port-forwarding NAT.
            if (hopping) {
                val masks = stream.getAsJsonObject("finalmask").getAsJsonArray("udp")
                masks.remove(masks.first { it.asJsonObject["type"].asString == "udphop" })
                if (masks.size() == 0) stream.remove("finalmask")
            }
            stream.add("tlsSettings", JsonObject().apply {
                add("alpn", JsonArray().apply { add("h3") })
                add("certificates", JsonArray().apply { add(JsonObject().apply {
                    addProperty("certificateFile", cert.absolutePath); addProperty("keyFile", key.absolutePath)
                }) })
            })
            val server = JsonObject().apply {
                add("log", JsonObject().apply { addProperty("loglevel", "debug") })
                add("inbounds", JsonArray().apply { add(JsonObject().apply {
                    addProperty("listen", "127.0.0.1"); addProperty("port", remotePort); addProperty("protocol", "hysteria")
                    add("settings", JsonObject().apply {
                        addProperty("version", 2)
                        add("users", JsonArray().apply { add(JsonObject().apply { addProperty("auth", "local-auth") }) })
                    })
                    add("streamSettings", stream)
                }) })
                add("outbounds", JsonArray().apply { add(JsonObject().apply {
                    addProperty("protocol", "freedom")
                    // Server-side Hysteria blocks private targets by default in Xray 26.10.21.
                    // Allow only this test's single loopback HTTP socket.
                    add("settings", JsonObject().apply {
                        add("finalRules", JsonArray().apply { add(JsonObject().apply {
                            addProperty("action", "allow"); addProperty("network", "tcp")
                            addProperty("port", target.localPort)
                            add("ip", JsonArray().apply { add("127.0.0.2/32") })
                        }) })
                    })
                }) })
            }
            fun start(name: String, config: JsonObject): Process {
                val file = File(directory, "$name.json").apply { writeText(config.toString()) }
                return ProcessBuilder(binary.absolutePath, "run", "-c", file.absolutePath)
                    .redirectErrorStream(true).redirectOutput(File(directory, "$name.log")).start().also { processes += it }
            }
            val serverProcess = start("server", server)
            val clientProcess = start("client", client)
            val response = worker.submit<Unit> {
                target.accept().use { socket ->
                    stage.set("reading HTTP request at target")
                    socket.soTimeout = 15000
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    stage.set("sending HTTP response")
                    if (hopping) requireNotNull(forwarders).resetObservations()
                    val output = socket.getOutputStream()
                    output.write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray())
                    fun chunk(value: String) {
                        val bytes = value.toByteArray()
                        output.write((bytes.size.toString(16) + "\r\n").toByteArray())
                        output.write(bytes); output.write("\r\n".toByteArray()); output.flush()
                    }
                    if (hopping) {
                        val started = System.nanoTime()
                        // Keep ONE accepted TCP stream alive through at least two hopping intervals.
                        // Random port selection may repeat; allow extra intervals before failing.
                        while (true) {
                            chunk("hy2-data\n")
                            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                            if (elapsed >= 11000 && requireNotNull(forwarders).hasHopped()) break
                            if (elapsed >= 35000) break
                            Thread.sleep(250)
                        }
                    }
                    chunk("hy2-ok")
                    output.write("0\r\n\r\n".toByteArray()); output.flush()
                    stage.set("HTTP response sent")
                }
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (true) {
                assertTrue(File(directory, "client.log").readText(), clientProcess.isAlive)
                assertTrue(File(directory, "server.log").readText(), serverProcess.isAlive)
                try { Socket().use { it.connect(InetSocketAddress("127.0.0.1", socksPort), 100) }; break }
                catch (error: java.io.IOException) { if (System.nanoTime() > deadline) throw error; Thread.sleep(50) }
            }
            Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))).use { socket ->
                socket.soTimeout = 15000
                socket.connect(InetSocketAddress(target.inetAddress, target.localPort), 15000)
                socket.getOutputStream().write("GET / HTTP/1.0\r\nHost: localhost\r\n\r\n".toByteArray())
                val reader = socket.getInputStream().bufferedReader()
                assertEquals("HTTP/1.1 200 OK", reader.readLine())
                while (!reader.readLine().isNullOrEmpty()) { }
                val body = StringBuilder()
                while (true) {
                    val size = requireNotNull(reader.readLine()).toInt(16)
                    if (size == 0) { assertEquals("", reader.readLine()); break }
                    repeat(size) { body.append(reader.read().also { assertTrue(it >= 0) }.toChar()) }
                    assertEquals("", reader.readLine())
                }
                assertTrue(body.toString().endsWith("hy2-ok"))
            }
            response.get(5, TimeUnit.SECONDS)
            assertTrue("HTTP bypassed the Hysteria transport", requireNotNull(forwarders).hasTraffic())
            if (hopping) assertTrue("No remote/local port change observed: $forwarders", requireNotNull(forwarders).hasHopped())
            assertTrue(File(directory, "client.log").readText(), clientProcess.isAlive)
            assertTrue(File(directory, "server.log").readText(), serverProcess.isAlive)
        } catch (error: Throwable) {
            throw AssertionError(buildString {
                appendLine(error.toString())
                appendLine("Target: ${stage.get()}; UDP: $forwarders")
                for (name in listOf("client", "server")) {
                    appendLine("$name: ${File(directory, "$name.log").takeIf { it.exists() }?.readText().orEmpty()}")
                }
            }, error)
        } finally {
            target.close(); worker.shutdownNow()
            processes.forEach { it.destroy(); if (!it.waitFor(2, TimeUnit.SECONDS)) { it.destroyForcibly(); it.waitFor(2, TimeUnit.SECONDS) } }
            forwarders?.close()
            directory.deleteRecursively()
        }
    }

    /** Simulate server-side UDP DNAT, retaining a distinct peer for every client source socket. */
    private class UdpForwarders(private val serverPort: Int, portCount: Int) : java.io.Closeable {
        private val loopback = InetAddress.getByName("127.0.0.1")
        private val sockets = List(portCount) { DatagramSocket(0, loopback) }
        private val workers = Executors.newCachedThreadPool()
        private val backends = ConcurrentHashMap<Pair<Int, SocketAddress>, DatagramSocket>()
        private val destinationPorts = ConcurrentHashMap.newKeySet<Int>()
        private val sourcePorts = ConcurrentHashMap.newKeySet<Int>()
        val ports = sockets.map { it.localPort }

        init {
            sockets.forEach { front -> workers.submit {
                val buffer = ByteArray(65535)
                while (!front.isClosed) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        front.receive(packet)
                        destinationPorts += front.localPort; sourcePorts += packet.port
                        val client = packet.socketAddress
                        val backend = backends.computeIfAbsent(front.localPort to client) {
                            DatagramSocket(0, loopback).also { connection -> workers.submit {
                                val replyBuffer = ByteArray(65535)
                                while (!connection.isClosed) {
                                    try {
                                        val reply = DatagramPacket(replyBuffer, replyBuffer.size)
                                        connection.receive(reply)
                                        front.send(DatagramPacket(reply.data, reply.length, client))
                                    } catch (error: java.io.IOException) { if (!connection.isClosed) throw error }
                                }
                            } }
                        }
                        backend.send(DatagramPacket(packet.data, packet.length, loopback, serverPort))
                    } catch (error: java.io.IOException) { if (!front.isClosed) throw error }
                }
            } }
        }

        fun hasHopped() = destinationPorts.size >= 2 && sourcePorts.size >= 2
        fun hasTraffic() = destinationPorts.isNotEmpty()
        fun resetObservations() { destinationPorts.clear(); sourcePorts.clear() }
        override fun toString() = "destination ports=$destinationPorts, client ports=$sourcePorts"
        override fun close() {
            sockets.forEach { it.close() }
            workers.shutdownNow()
            // Front readers may finish one in-flight packet before noticing the socket close.
            backends.values.forEach { it.close() }
            workers.awaitTermination(3, TimeUnit.SECONDS)
            backends.values.forEach { it.close() }
        }
    }
}
