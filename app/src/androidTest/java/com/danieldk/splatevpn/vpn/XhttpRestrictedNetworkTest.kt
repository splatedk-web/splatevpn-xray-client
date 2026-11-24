package com.danieldk.splatevpn.vpn

import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.data.ServerNode
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.*
import java.util.UUID

/** Local transport fixture: no public Internet or working client DNS is available. */
class XhttpRestrictedNetworkTest {
    @Test fun xhttpTlsWithMaxSniTransfersDataWithUnreachableClientDns() = fixture(reality = false)
    @Test fun xhttpRealityWithMaxSniTransfersDataWithUnreachableClientDns() = fixture(reality = true)
    @Test fun tcpRealityJsonSubscriptionTransfersDataWithUnreachableClientDns() =
        fixture(reality = true, network = "tcp", importJson = true)
    @Test fun tcpRealityShareLinkWithDefaultPathTransfersDataWithUnreachableClientDns() =
        fixture(reality = true, network = "tcp")

    private fun fixture(reality: Boolean, network: String = "xhttp", importJson: Boolean = false) = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val test = InstrumentationRegistry.getInstrumentation().context
        val dir = File(app.cacheDir, "xhttp-restricted-${UUID.randomUUID()}").apply { mkdirs() }
        val binary = File(app.applicationInfo.nativeLibraryDir, "libxray.so")
        val endpoint = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        fun freePort() = ServerSocket(0).use { it.localPort }
        val transportPort = freePort()
        val socksPort = freePort()
        val id = "11111111-2222-3333-4444-555555555555"
        val processes = mutableListOf<Process>()
        suspend fun start(name: String, json: String, port: Int? = null) {
            val file = File(dir, "$name.json").apply { writeText(json) }
            processes += ProcessBuilder(binary.absolutePath, "run", "-c", file.absolutePath)
                .redirectErrorStream(true).redirectOutput(File(dir, "$name.log")).start()
            if (port != null) withTimeout(5000) {
                while (true) {
                    check(processes.last().isAlive) { "$name exited before listening" }
                    if (runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 100) }; true }.getOrDefault(false)) break
                    delay(50)
                }
            }
        }
        val response = async(Dispatchers.IO) {
            runCatching {
            endpoint.soTimeout = 15000
            endpoint.accept().use { socket ->
                socket.soTimeout = 5000
                val reader = socket.getInputStream().bufferedReader()
                while (!reader.readLine().isNullOrEmpty()) { /* consume HTTP headers */ }
                socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 14\r\nConnection: close\r\n\r\nxhttp-works-ok".toByteArray())
            }
            }
        }
        try {
            listOf("cert.pem", "key.pem").forEach { name ->
                test.assets.open("xhttp-test/$name").use { input -> File(dir, name).outputStream().use { input.copyTo(it) } }
            }
            val server = JsonParser.parseString("""{"log":{"loglevel":"debug"},"dns":{"hosts":{"target.invalid":"127.0.0.1"},"servers":["tcp://127.0.0.1:1"]},"inbounds":[{"listen":"127.0.0.1","port":$transportPort,"protocol":"vless","settings":{"clients":[{"id":"$id"}],"decryption":"none"},"streamSettings":{"network":"xhttp","security":"tls","tlsSettings":{"certificates":[{"certificateFile":"${File(dir, "cert.pem").absolutePath}","keyFile":"${File(dir, "key.pem").absolutePath}"}]},"xhttpSettings":{"path":"/mobile","mode":"auto"}}}],"outbounds":[{"protocol":"freedom","settings":{"finalRules":[{"action":"allow","network":"tcp","ip":["127.0.0.1"],"port":"${endpoint.localPort}"}]},"streamSettings":{"sockopt":{"domainStrategy":"UseIP"}}}]}""").asJsonObject
            server.getAsJsonArray("inbounds")[0].asJsonObject.getAsJsonObject("streamSettings").apply {
                addProperty("network", network)
                if (network == "tcp") remove("xhttpSettings")
            }
            var publicKey = ""
            if (reality) {
                val targetPort = freePort()
                val camouflage = server.deepCopy()
                camouflage.getAsJsonArray("inbounds")[0].asJsonObject.addProperty("port", targetPort)
                start("camouflage", camouflage.toString(), targetPort)
                val keyProcess = ProcessBuilder(binary.absolutePath, "x25519").redirectErrorStream(true).start()
                assertTrue(keyProcess.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
                val keys = keyProcess.inputStream.bufferedReader().readLines().associate {
                    it.substringBefore(':').replace(" ", "").lowercase() to it.substringAfter(':').trim()
                }
                val privateKey = requireNotNull(keys["privatekey"])
                publicKey = requireNotNull(keys.entries.firstOrNull { it.key.startsWith("password") || it.key == "publickey" }?.value)
                val stream = server.getAsJsonArray("inbounds")[0].asJsonObject.getAsJsonObject("streamSettings")
                stream.addProperty("security", "reality")
                stream.remove("tlsSettings")
                stream.add("realitySettings", JsonParser.parseString("""{"target":"127.0.0.1:$targetPort","serverNames":["max.ru"],"privateKey":"$privateKey","shortIds":["abcd"]}"""))
            }
            start("server", server.toString(), transportPort)
            val node = ServerNode(id = "test", name = "local fixture", protocol = "vless", address = "127.0.0.1",
                port = transportPort, uuid = id, network = network, security = if (reality) "reality" else "tls", sni = "max.ru",
                publicKey = publicKey, shortId = if (reality) "abcd" else "", path = "/mobile", mode = "packet-up")
            val generator = XrayConfigGenerator()
            val effectiveNode = if (importJson) {
                val exported = JsonParser.parseString(generator.generateTestConfig(node, socksPort)).asJsonObject
                com.danieldk.splatevpn.data.LinkParser.parseSubscription("[" + exported.toString() + "]").single()
            } else if (network == "tcp") {
                requireNotNull(com.danieldk.splatevpn.data.LinkParser.parse(
                    "vless://$id@127.0.0.1:$transportPort?type=tcp&security=reality&sni=max.ru&pbk=$publicKey&sid=abcd"
                ))
            } else node
            val config = JsonParser.parseString(generator.generateTestConfig(effectiveNode, socksPort)).asJsonObject
            // Trust only the fixture's exact self-signed certificate, keeping SNI max.ru.
            if (!reality) {
            val tls = config.getAsJsonArray("outbounds")[0].asJsonObject.getAsJsonObject("streamSettings").getAsJsonObject("tlsSettings")
            val certificate = File(dir, "cert.pem").inputStream().use {
                java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(it)
            }
            val fingerprint = java.security.MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            tls.addProperty("pinnedPeerCertSha256", fingerprint)
            tls.remove("verifyPeerCertByName")
            }
            config.add("dns", JsonParser.parseString("""{"servers":["tcp://127.0.0.1:1"],"queryStrategy":"UseIPv4"}"""))
            config.getAsJsonObject("log").addProperty("loglevel", "debug")
            start("client", config.toString())
            var ready = false
            repeat(50) {
                if (!ready) {
                    ready = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", socksPort), 100) }; true }.getOrDefault(false)
                    if (!ready) delay(100)
                }
            }
            assertTrue("Local Xray failed to start", ready)
            Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))).use { socket ->
                socket.soTimeout = 10000
                socket.connect(InetSocketAddress.createUnresolved("target.invalid", endpoint.localPort), 10000)
                socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: target.invalid\r\nConnection: close\r\n\r\n".toByteArray())
                val reader = socket.getInputStream().bufferedReader()
                assertEquals("HTTP/1.1 200 OK", reader.readLine())
                while (!reader.readLine().isNullOrEmpty()) { /* consume response headers */ }
                val body = CharArray(14)
                var count = 0
                while (count < body.size) {
                    val received = reader.read(body, count, body.size - count)
                    check(received > 0) { "Truncated response through XHTTP" }
                    count += received
                }
                assertEquals("xhttp-works-ok", String(body))
            }
            response.await().getOrThrow()
        } catch (error: Throwable) {
            val logs = dir.listFiles().orEmpty().filter { it.extension == "log" }.joinToString("\n") { it.name + ": " + it.readText().takeLast(6000) }
            throw AssertionError(logs, error)
        } finally {
            endpoint.close()
            response.cancel()
            processes.forEach { it.destroy(); if (!it.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) it.destroyForcibly() }
            dir.deleteRecursively()
        }
    }
}
