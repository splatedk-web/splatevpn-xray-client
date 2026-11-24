package com.danieldk.splatevpn.vpn

import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.data.AdvancedRouteRule
import com.danieldk.splatevpn.data.AdvancedRoutingRules
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** An executable contract with the shipped, unmodified Xray, not a JSON shape assertion.
 * Only loopback origins and DNS hosts exist. A missed identity condition cannot reach an origin.
 */
class AppIdentityNativeTest {
    @Test fun productionCompatibilityGuardAcceptsTheShippedCoreAndCachedRecheck() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val binary = File(app.applicationInfo.nativeLibraryDir, "libxray.so")
        AppIdentityCoreCompatibility.ensureCompatible(app, binary)
        AppIdentityCoreCompatibility.ensureCompatible(app, binary)
    }

    @Test fun tcpMatchesAuthenticatedUsernameAndDomainIncludingSniffedHost() {
        val origin = ServerSocket(0, 8, InetAddress.getByName(ORIGIN)).apply { soTimeout = 200 }
        val executor = Executors.newSingleThreadExecutor()
        val received = ConcurrentLinkedQueue<String>()
        val responses = executor.submit<Unit> {
            while (!origin.isClosed) {
                val client = try { origin.accept() } catch (_: SocketTimeoutException) { continue }
                    catch (error: IOException) { if (origin.isClosed) break else throw error }
                client.use { socket ->
                    socket.soTimeout = 3000
                    val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                    val request = reader.readLine() ?: throw EOFException("No origin request")
                    var headers = 0
                    while (true) {
                        val header = reader.readLine() ?: throw EOFException("Incomplete origin headers")
                        if (header.isEmpty()) break
                        check(++headers <= 32 && header.length < 1024)
                    }
                    received += request
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: ${BODY.length}\r\n" +
                        "Connection: close\r\n\r\n$BODY").toByteArray(Charsets.US_ASCII))
                }
            }
        }
        try {
            withCore(origin.localPort, "tcp") { port ->
                val expectedRequests = mutableListOf<String>()
                for (numeric in listOf(false, true)) for ((index, case) in CASES.withIndex()) {
                    val path = "/identity-$numeric-$index"
                    val result = runCatching {
                        Socket().use { socket ->
                            socket.soTimeout = 1500
                            LocalProxyAccess.connectSocks(socket, port, credentials(case.user),
                                if (numeric) ORIGIN else case.host, origin.localPort, 1500, requireRunningVpn = false)
                            socket.getOutputStream().write(("GET $path HTTP/1.1\r\nHost: ${case.host}\r\n" +
                                "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                            val response = socket.getInputStream().bufferedReader(Charsets.US_ASCII).readText()
                            if (!response.startsWith("HTTP/1.1 200 OK\r\n")) throw IOException("No allowed response")
                            response.substringAfter("\r\n\r\n")
                        }
                    }
                    if (case.allowed) {
                        assertEquals("$case, numeric=$numeric: ${result.exceptionOrNull()}", BODY, result.getOrNull())
                        expectedRequests += "GET $path HTTP/1.1"
                    } else {
                        assertTrue("Wrong identity/domain reached origin: $case, numeric=$numeric, $result",
                            result.exceptionOrNull() is IOException)
                    }
                    assertEquals(expectedRequests, received.toList())
                    if (responses.isDone) responses.get(1, TimeUnit.SECONDS)
                }
                Socket("127.0.0.1", port).use { socket ->
                    socket.soTimeout = 1500
                    socket.getOutputStream().write(byteArrayOf(5, 1, 0))
                    assertEquals(5, socket.getInputStream().read())
                    assertEquals("Private identity listener must refuse anonymous auth", 255, socket.getInputStream().read())
                }
                assertTrue(runCatching { Socket().use { socket ->
                    socket.soTimeout = 1500
                    LocalProxyAccess.connectSocks(socket, port, LocalProxyCredentials(EXPECTED_USER, "wrong"),
                        GOOD_HOST, origin.localPort, 1500, requireRunningVpn = false)
                } }.exceptionOrNull() is IOException)
                assertEquals(2, received.size)
            }
            origin.close()
            responses.get(3, TimeUnit.SECONDS)
        } finally {
            origin.close(); executor.shutdownNow(); executor.awaitTermination(3, TimeUnit.SECONDS)
        }
    }

    @Test fun udpAssociationsRetainTheirOwnAuthenticatedUserWithoutCrossUserReuse() {
        val origin = DatagramSocket(InetSocketAddress(ORIGIN, 0)).apply { soTimeout = 200 }
        val executor = Executors.newSingleThreadExecutor()
        val received = ConcurrentLinkedQueue<String>()
        val responses = executor.submit<Unit> {
            while (!origin.isClosed) {
                val packet = DatagramPacket(ByteArray(2048), 2048)
                try { origin.receive(packet) } catch (_: SocketTimeoutException) { continue }
                    catch (error: IOException) { if (origin.isClosed) break else throw error }
                received += String(packet.data, packet.offset, packet.length, Charsets.US_ASCII)
                origin.send(packet)
            }
        }
        try {
            withCore(origin.localPort, "udp") { port ->
                // Keep all control connections alive together: these associations must not share identity.
                val controls = mutableListOf<Socket>()
                val datagrams = mutableListOf<DatagramSocket>()
                val expected = mutableListOf<String>()
                try {
                    val associations = CASES.map { case ->
                        val control = Socket("127.0.0.1", port).apply { soTimeout = 2000 }.also { controls += it }
                        val endpoint = associate(control, credentials(case.user))
                        val socket = DatagramSocket(InetSocketAddress("127.0.0.1", 0)).also { datagrams += it }
                        Triple(case, endpoint, socket)
                    }
                    for ((index, triple) in (associations + associations.first()).withIndex()) {
                        val (case, endpoint, socket) = triple
                        val body = "identity-udp-$index"
                        val payload = udpPacket(case.host, origin.localPort, body)
                        socket.send(DatagramPacket(payload, payload.size, endpoint))
                        socket.soTimeout = if (case.allowed) 2000 else 700
                        val result = runCatching {
                            val answer = DatagramPacket(ByteArray(2048), 2048)
                            socket.receive(answer)
                            decodeUdp(answer)
                        }
                        if (case.allowed) {
                            assertEquals("$case: ${result.exceptionOrNull()}", body, result.getOrNull())
                            expected += body
                        } else assertTrue("UDP identity leaked across associations: $case, $result",
                            result.exceptionOrNull() is SocketTimeoutException)
                        assertEquals(expected, received.toList())
                        if (responses.isDone) responses.get(1, TimeUnit.SECONDS)
                    }
                    assertEquals(2, received.size)
                } finally {
                    datagrams.forEach { it.close() }; controls.forEach { it.close() }
                }
            }
            origin.close()
            responses.get(3, TimeUnit.SECONDS)
        } finally {
            origin.close(); executor.shutdownNow(); executor.awaitTermination(3, TimeUnit.SECONDS)
        }
    }

    private fun withCore(originPort: Int, network: String, action: (Int) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(app.cacheDir, "app-identity-${UUID.randomUUID()}").apply { mkdirs() }
        val log = File(directory, "client.log")
        val validationLog = File(directory, "validation.log")
        val binary = File(app.applicationInfo.nativeLibraryDir, "libxray.so")
        val processes = mutableListOf<Process>()
        try {
            val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
            val route = AdvancedRouteRule("app-native", "Native identity", outbound = "direct",
                suffixes = listOf("example.test"), keywords = listOf("api"), packages = listOf(PACKAGE))
            val config = JsonObject().apply {
                add("log", JsonObject().apply { addProperty("loglevel", "debug") })
                add("dns", JsonObject().apply {
                    addProperty("queryStrategy", "UseIPv4")
                    add("hosts", JsonObject().apply { CASES.forEach { addProperty(it.host, ORIGIN) } })
                    add("servers", JsonArray())
                })
                add("inbounds", JsonArray().apply { add(JsonObject().apply {
                    addProperty("tag", "app-private"); addProperty("listen", "127.0.0.1")
                    addProperty("port", port); addProperty("protocol", "socks")
                    add("settings", JsonObject().apply {
                        addProperty("auth", "password"); addProperty("udp", true)
                        add("accounts", JsonArray().apply { listOf(EXPECTED_USER, OTHER_USER, EMAIL_ONLY_USER).forEach { username ->
                            add(JsonObject().apply {
                                addProperty("user", username); addProperty("pass", PASSWORD)
                                // SOCKS account.email is not an identity alias. This must not impersonate PACKAGE.
                                if (username == EMAIL_ONLY_USER) addProperty("email", EXPECTED_USER)
                            })
                        } })
                    })
                    add("sniffing", JsonObject().apply {
                        addProperty("enabled", true); addProperty("routeOnly", true)
                        add("destOverride", JsonArray().apply { add("http"); add("tls") })
                    })
                }) })
                add("outbounds", JsonArray().apply {
                    add(JsonObject().apply { addProperty("tag", "block"); addProperty("protocol", "blackhole") })
                    add(JsonObject().apply {
                        addProperty("tag", "direct"); addProperty("protocol", "freedom")
                        add("settings", JsonObject().apply {
                            addProperty("domainStrategy", "ForceIPv4")
                            add("finalRules", JsonArray().apply {
                                add(JsonObject().apply {
                                    addProperty("action", "allow"); addProperty("network", network)
                                    addProperty("port", originPort); add("ip", JsonArray().apply { add("$ORIGIN/32") })
                                })
                                add(JsonObject().apply { addProperty("action", "block"); addProperty("blockDelay", "0") })
                            })
                        })
                    })
                })
                add("routing", JsonObject().apply {
                    addProperty("domainStrategy", "AsIs")
                    add("rules", JsonArray().apply { AdvancedRoutingRules.compile(listOf(route)).forEach { add(it) } })
                })
            }
            val source = File(directory, "client.json").apply { writeText(config.toString()) }
            val validation = ProcessBuilder(binary.absolutePath, "run", "-test", "-c", source.absolutePath)
                .redirectErrorStream(true).redirectOutput(validationLog).start().also { processes += it }
            assertTrue("Xray validation timed out", validation.waitFor(3, TimeUnit.SECONDS))
            assertEquals(validationLog.readText(), 0, validation.exitValue())
            assertTrue(validationLog.readText(), validationLog.readText().contains("Configuration OK"))
            val process = ProcessBuilder(binary.absolutePath, "run", "-c", source.absolutePath)
                .redirectErrorStream(true).redirectOutput(log).start().also { processes += it }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (true) {
                assertTrue(log.readText(), process.isAlive)
                try { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 100) }; break }
                catch (error: IOException) { if (System.nanoTime() > deadline) throw error; Thread.sleep(25) }
            }
            action(port)
            assertTrue(log.readText(), process.isAlive)
        } catch (error: Throwable) {
            throw AssertionError("Native app identity $network: ${validationLog.takeIf { it.exists() }?.readText().orEmpty()}\n" +
                log.takeIf { it.exists() }?.readText().orEmpty(), error)
        } finally {
            processes.forEach { process ->
                process.destroy()
                if (!process.waitFor(2, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(2, TimeUnit.SECONDS) }
            }
            directory.deleteRecursively()
        }
    }

    private fun associate(socket: Socket, credentials: LocalProxyCredentials): InetSocketAddress {
        val out = socket.getOutputStream()
        val input = DataInputStream(socket.getInputStream())
        out.write(byteArrayOf(5, 1, 2)); out.flush()
        assertEquals(5, input.readUnsignedByte()); assertEquals(2, input.readUnsignedByte())
        val username = credentials.username.toByteArray(Charsets.US_ASCII)
        val password = credentials.password.toByteArray(Charsets.US_ASCII)
        out.write(byteArrayOf(1, username.size.toByte())); out.write(username)
        out.write(password.size); out.write(password); out.flush()
        assertEquals(1, input.readUnsignedByte()); assertEquals(0, input.readUnsignedByte())
        out.write(byteArrayOf(5, 3, 0, 1, 0, 0, 0, 0, 0, 0)); out.flush()
        assertEquals(5, input.readUnsignedByte()); assertEquals(0, input.readUnsignedByte())
        assertEquals(0, input.readUnsignedByte())
        val address = when (input.readUnsignedByte()) {
            1 -> ByteArray(4).also(input::readFully)
            4 -> ByteArray(16).also(input::readFully)
            else -> throw IOException("Unexpected UDP relay address")
        }
        return InetSocketAddress(InetAddress.getByAddress(address), input.readUnsignedShort())
    }

    private fun udpPacket(host: String, port: Int, body: String): ByteArray = ByteArrayOutputStream().apply {
        val encoded = host.toByteArray(Charsets.US_ASCII)
        write(byteArrayOf(0, 0, 0, 3, encoded.size.toByte())); write(encoded)
        write(port ushr 8); write(port and 255); write(body.toByteArray(Charsets.US_ASCII))
    }.toByteArray()

    private fun decodeUdp(packet: DatagramPacket): String {
        val input = DataInputStream(ByteArrayInputStream(packet.data, packet.offset, packet.length))
        assertEquals(0, input.readUnsignedShort()); assertEquals(0, input.readUnsignedByte())
        val size = when (input.readUnsignedByte()) {
            1 -> 4
            4 -> 16
            3 -> input.readUnsignedByte()
            else -> throw IOException("Invalid SOCKS UDP address")
        }
        input.readFully(ByteArray(size + 2))
        return input.readBytes().toString(Charsets.US_ASCII)
    }

    private fun credentials(username: String) = LocalProxyCredentials(username, PASSWORD)
    private data class Case(val user: String, val host: String, val allowed: Boolean)

    companion object {
        private const val PACKAGE = "com.brave.browser"
        private const val GOOD_HOST = "api.example.test"
        private const val ORIGIN = "127.0.0.2"
        private const val PASSWORD = "native-private-identity-secret"
        private const val EMAIL_ONLY_USER = "email-field-is-not-identity"
        private const val BODY = "native-app-identity-ok"
        private val EXPECTED_USER = AdvancedRoutingRules.appIdentity(PACKAGE)
        private val OTHER_USER = AdvancedRoutingRules.appIdentity("org.mozilla.firefox")
        private val CASES = listOf(
            Case(EXPECTED_USER, GOOD_HOST, true),
            Case(OTHER_USER, GOOD_HOST, false),
            Case(EMAIL_ONLY_USER, GOOD_HOST, false),
            Case(EXPECTED_USER, "api.other.test", false),
            Case(EXPECTED_USER, "www.example.test", false)
        )
    }
}
