package com.danieldk.splatevpn.vpn

import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.data.ServerNode
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Shipped Xray and real TLS/SOCKS sockets; no external servers or user VPN settings. */
class TunnelSecurityNativeTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val generator = XrayConfigGenerator()
    private val publicNode = ServerNode("fixture", "fixture", "vless", "192.0.2.1", 443,
        uuid = "11111111-2222-3333-4444-555555555555")

    @Test fun xraySendsMixedSniInTwoClientHelloRecords() {
        val directory = File(app.cacheDir, "tls-wire-${UUID.randomUUID()}").apply { mkdirs() }
        val loopback = InetAddress.getByName("127.0.0.1")
        try {
            ServerSocket(0, 8, loopback).use { peer ->
                peer.soTimeout = 8_000
                val socks = ServerSocket(0, 8, loopback).use { it.localPort }
                val node = publicNode.copy(address = loopback.hostAddress!!, port = peer.localPort,
                    security = "tls", sni = "example.org")
                val config = File(directory, "config.json").apply {
                    writeText(generator.generateTestConfig(node, socks, enableTlsFragment = true,
                        tlsFragmentLength = "100", tlsFragmentInterval = "10",
                        tlsFragmentPackets = com.danieldk.splatevpn.data.TLS_HELLO_TWO_PARTS, tlsMixedSni = true))
                }
                val log = File(directory, "core.log")
                val core = ProcessBuilder(File(app.applicationInfo.nativeLibraryDir, "libxray.so").absolutePath,
                    "run", "-c", config.absolutePath).redirectErrorStream(true).redirectOutput(log).start()
                try {
                    val upstream = InetSocketAddress(loopback, socks)
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
                    while (true) {
                        assertTrue("Xray exited: ${log.readText()}", core.isAlive)
                        try { Socket().use { it.connect(upstream, 100) }; break }
                        catch (error: java.io.IOException) {
                            if (System.nanoTime() > deadline) throw error
                            Thread.sleep(25)
                        }
                    }
                    Socket().use { client ->
                        client.connect(upstream, 1_000)
                        client.soTimeout = 8_000
                        val input = java.io.DataInputStream(client.getInputStream())
                        val output = client.getOutputStream()
                        output.write(byteArrayOf(5, 1, 0))
                        assertEquals(0x0500, input.readUnsignedShort())
                        output.write(byteArrayOf(5, 1, 0, 1, 1, 1, 1, 1, 1, -69))
                        input.readFully(ByteArray(10))
                        output.write(0)
                        peer.accept().use { connection ->
                            connection.soTimeout = 8_000
                            val wire = java.io.DataInputStream(connection.getInputStream())
                            fun record(): ByteArray {
                                assertEquals(22, wire.readUnsignedByte())
                                wire.readUnsignedShort()
                                return ByteArray(wire.readUnsignedShort()).also(wire::readFully)
                            }
                            val first = record()
                            val hello = first + record()
                            assertEquals(100, first.size)
                            assertEquals(1, hello[0].toInt())
                            val declaredSize = (1..3).fold(0) { size, i -> size * 256 + (hello[i].toInt() and 255) }
                            assertEquals(declaredSize + 4, hello.size)
                            assertTrue(String(hello, Charsets.ISO_8859_1).contains("ExAmPlE.oRg"))
                        }
                    }
                } finally {
                    core.destroy()
                    if (!core.waitFor(2, TimeUnit.SECONDS)) core.destroyForcibly()
                }
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun coreRejectsPlaintextPublicVlessWithoutRejectingTlsOrLocalPeers() {
        val directory = File(app.cacheDir, "transport-security-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            fun validate(node: ServerNode, raw: String? = null): Int {
                val file = File(directory, "config.json").apply {
                    writeText(generator.generateTestConfig(node.copy(fullConfigJson = raw), 25001))
                }
                val log = File(directory, "validation.log")
                val process = ProcessBuilder(File(app.applicationInfo.nativeLibraryDir, "libxray.so").absolutePath,
                    "run", "-test", "-c", file.absolutePath).redirectErrorStream(true).redirectOutput(log).start()
                try {
                    assertTrue("Core validation exceeded deadline", process.waitFor(10, TimeUnit.SECONDS))
                    return process.exitValue()
                } finally { if (process.isAlive) process.destroyForcibly() }
            }
            assertNotEquals("Public plaintext VLESS must be rejected", 0, validate(publicNode))
            assertEquals(0, validate(publicNode.copy(security = "tls", sni = "example.com")))
            assertEquals(0, validate(publicNode.copy(address = "127.0.0.1")))
            val imported = """{"outbounds":[{"tag":"imported","protocol":"vless","settings":{"vnext":[{"address":"192.0.2.1","port":443,"users":[{"id":"11111111-2222-3333-4444-555555555555","encryption":"none"}]}]},"streamSettings":{"network":"tcp","security":"none"}}]}"""
            assertNotEquals("Imported profiles must not evade core validation", 0,
                validate(publicNode.copy(security = "tls"), imported))
        } finally { directory.deleteRecursively() }
    }

    @Test fun authenticatedTunnelRequiresValidHttpsAndDoesNotAcceptPlaintext() = runBlocking {
        val directory = File(app.cacheDir, "https-probe-${UUID.randomUUID()}").apply { mkdirs() }
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val origin = serverTls.sslContext().serverSocketFactory.createServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val executor = Executors.newCachedThreadPool()
        var process: Process? = null
        try {
            executor.submit {
                while (!origin.isClosed) {
                    val socket = runCatching { origin.accept() }.getOrNull() ?: break
                    executor.submit { runCatching { socket.use {
                        it.soTimeout = 5000
                        val input = it.getInputStream().bufferedReader()
                        val firstLine = input.readLine().orEmpty()
                        while (!input.readLine().isNullOrEmpty()) { }
                        if (firstLine.startsWith("GET /blackhole ")) {
                            it.getOutputStream().write("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        } else if (firstLine.startsWith("GET /speed ")) {
                            it.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: 524288\r\nConnection: close\r\n\r\n".toByteArray())
                            it.getOutputStream().write(ByteArray(512 * 1024))
                        } else {
                            it.getOutputStream().write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        }
                    } } }
                }
            }
            val port = ServerSocket(0).use { it.localPort }
            val auth = LocalProxyCredentials.generate()
            val config = JsonParser.parseString(generator.generateTestConfig(publicNode.copy(security = "tls"), port,
                localProxyAccess = auth)).asJsonObject
            config.add("outbounds", JsonArray().apply { add(JsonObject().apply {
                addProperty("tag", "local"); addProperty("protocol", "freedom")
                add("settings", JsonObject().apply { add("finalRules", JsonArray().apply { add(JsonObject().apply {
                    addProperty("action", "allow"); addProperty("network", "tcp"); addProperty("port", origin.localPort)
                    add("ip", JsonArray().apply { add("127.0.0.1/32") })
                }) }) })
            }) })
            config.add("routing", JsonObject().apply { add("rules", JsonArray().apply { add(JsonObject().apply {
                addProperty("type", "field"); addProperty("network", "tcp"); addProperty("outboundTag", "local")
            }) }) })
            val source = File(directory, "core.json").apply { writeText(config.toString()) }
            val log = File(directory, "core.log")
            val running = ProcessBuilder(File(app.applicationInfo.nativeLibraryDir, "libxray.so").absolutePath,
                "run", "-c", source.absolutePath).redirectErrorStream(true).redirectOutput(log).start()
            process = running
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (true) {
                assertTrue("Isolated core exited", running.isAlive)
                try { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 100) }; break }
                catch (error: java.io.IOException) { if (System.nanoTime() > deadline) throw error; Thread.sleep(30) }
            }
            val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
            val trustedClient = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
            val url = "https://127.0.0.1:${origin.localPort}/check"
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
            assertTrue("Valid HTTPS must pass through the authenticated native proxy",
                TunnelProbe.measure(proxy, url, 5000, url, credentials = auth, client = trustedClient) > 0)
            assertEquals("An untrusted certificate must fail", -1L,
                TunnelProbe.measure(proxy, url, 3000, url, credentials = auth))
            val plainUrl = url.replace("https://", "http://")
            assertEquals(-1L, TunnelProbe.measure(proxy, plainUrl, 1000, plainUrl, credentials = auth))
            // Every endpoint accepts TCP/TLS, but the first thirteen cannot carry a successful HTTP check.
            val candidates = (1..14).toList()
            val chosen = com.danieldk.splatevpn.data.selectResponsiveCandidate(
                com.danieldk.splatevpn.data.completeDiscoveryQueue(candidates.take(6), candidates),
                parallelism = 6, settleMs = 0, score = { _, ping -> ping }
            ) { id ->
                val target = if (id == 14) url else url.replace("/check", "/blackhole")
                TunnelProbe.measure(proxy, target, 4000, target, credentials = auth, client = trustedClient)
            }
            assertEquals("Discovery must reach a working HTTPS candidate beyond the TCP shortlist", 14, chosen?.first)
            val failedTarget = url.replace("/check", "/blackhole")
            assertEquals("A live TCP/TLS endpoint without working HTTP is unhealthy", -1L,
                TunnelProbe.measure(proxy, failedTarget, 3000, failedTarget, credentials = auth, client = trustedClient))
            val downloadClient = LocalProxyAccess.socksClient(trustedClient, port, auth, requireRunningVpn = false)
            assertTrue("Download sampling must consume real TLS data through native SOCKS",
                requireNotNull(measureTorDownload(downloadClient, url.replace("/check", "/speed"))) > 0)
        } finally {
            origin.close(); executor.shutdownNow()
            process?.let { it.destroy(); if (!it.waitFor(2, TimeUnit.SECONDS)) it.destroyForcibly() }
            directory.deleteRecursively()
        }
    }
}
