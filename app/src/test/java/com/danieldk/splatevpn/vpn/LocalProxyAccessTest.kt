package com.danieldk.splatevpn.vpn

import com.danieldk.splatevpn.SplateConstants
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.net.Authenticator
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LocalProxyAccessTest {
    @Test fun ownedRuntimeIsRequiredEvenWhenAuthenticationIsDisabled() {
        assertThrows(java.io.IOException::class.java) { LocalProxyAccess.client(OkHttpClient()) }
        val first = LocalProxyAccess.publish(null)
        var replacement: Long? = null
        try {
            assertTrue(LocalProxyAccess.isAvailable)
            assertNull(LocalProxyAccess.active)
            val stale = LocalProxyAccess.client(OkHttpClient())
            replacement = LocalProxyAccess.publish(LocalProxyCredentials.generate())
            LocalProxyAccess.revoke(first)
            assertTrue("Old cleanup must not revoke the replacement", LocalProxyAccess.isAvailable)
            assertThrows(java.io.IOException::class.java) {
                stale.newCall(Request.Builder().url("http://never-resolve.invalid/").build()).execute()
            }
        } finally { LocalProxyAccess.revoke(replacement); LocalProxyAccess.revoke(first) }
        assertFalse(LocalProxyAccess.isAvailable)
        assertThrows(java.io.IOException::class.java) { LocalProxyAccess.client(OkHttpClient()) }
    }

    @Test fun crashedOwnedCoreCannotSendARequestToAnotherLocalListener() {
        val alive = java.util.concurrent.atomic.AtomicBoolean(true)
        val token = LocalProxyAccess.publish(LocalProxyCredentials.generate(), alive::get)
        try {
            val client = LocalProxyAccess.client(OkHttpClient())
            alive.set(false)
            assertFalse(LocalProxyAccess.isAvailable)
            assertThrows(java.io.IOException::class.java) { LocalProxyAccess.client(OkHttpClient()) }
            assertThrows(java.io.IOException::class.java) {
                client.newCall(Request.Builder().url("http://never-resolve.invalid/").build()).execute()
            }
        } finally { LocalProxyAccess.revoke(token) }
    }

    @Test fun credentialsAreGeneratedAndValidatedAndNotPrinted() {
        val values = List(20) { LocalProxyCredentials.generate() }
        assertEquals(20, values.map { it.username }.distinct().size)
        assertEquals(20, values.map { it.password }.distinct().size)
        values.forEach { assertFalse(it.toString().contains(it.password)); assertTrue(it.password.length >= 24) }
        for ((user, pass) in listOf("" to "pass", "a:b" to "pass", "valid" to "", "valid" to "bad\npassword", "я".repeat(200) to "pass")) {
            assertThrows(IllegalArgumentException::class.java) { LocalProxyCredentials(user, pass) }
        }
        assertTrue(LocalProxyCredentials("u'", "p'word").hevYaml().contains("password: 'p''word'"))
    }

    @Test fun generatedMainDnsAndProbeListenersUseOneAccountAndLanRemainsIndependent() {
        val auth = LocalProxyCredentials("test-user", "test-password")
        val generator = XrayConfigGenerator()
        val main = JsonParser.parseString(generator.generateConfig(null, localProxyAccess = auth,
            dnsProbePort = SplateConstants.DNS_CHECK_SOCKS_PORT, shareLan = true,
            lanAccess = LanAccess("192.0.2.2", "lan-user", "lan-password"))).asJsonObject
        val privateInbounds = main.getAsJsonArray("inbounds").map { it.asJsonObject }
            .filter { it["tag"].asString in setOf("socks-in", "dns-check-in") }
        assertEquals(2, privateInbounds.size)
        privateInbounds.forEach { inbound ->
            assertEquals("127.0.0.1", inbound["listen"].asString)
            assertEquals("password", inbound.getAsJsonObject("settings")["auth"].asString)
            assertEquals("test-user", inbound.getAsJsonObject("settings").getAsJsonArray("accounts")[0].asJsonObject["user"].asString)
        }
        val lan = main.getAsJsonArray("inbounds").first { it.asJsonObject["tag"].asString == "socks-lan" }.asJsonObject
        assertEquals("lan-user", lan.getAsJsonObject("settings").getAsJsonArray("accounts")[0].asJsonObject["user"].asString)
        val ai = JsonParser.parseString(generator.generateAiDnsProbeConfig("dns_ai", false, 24001, auth)).asJsonObject
        assertEquals("password", ai.getAsJsonArray("inbounds")[0].asJsonObject.getAsJsonObject("settings")["auth"].asString)
    }

    @Test fun httpProxyCredentialsAreScopedAndDoNotReplaceGlobalAuthenticator() {
        val before = Authenticator::class.java.getMethod("getDefault").invoke(null)
        val auth = LocalProxyCredentials("local-user", "local-pass")
        ServerSocket(0).use { listener ->
            val worker = Executors.newSingleThreadExecutor()
            try {
                val response = worker.submit<String> {
                    var authorization = ""
                    repeat(2) { attempt -> listener.accept().use { socket ->
                        socket.soTimeout = 3000
                        val reader = socket.getInputStream().bufferedReader()
                        assertTrue(reader.readLine().startsWith("GET http://never-resolve.invalid/"))
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Proxy-Authorization:", true)) authorization = line.substringAfter(':').trim()
                        }
                        socket.getOutputStream().write((if (attempt == 0)
                            "HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"proxy\"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            else "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok").toByteArray())
                    } }
                    authorization
                }
                LocalProxyAccess.client(OkHttpClient(), listener.localPort, auth).newCall(Request.Builder()
                    .url("http://never-resolve.invalid/").build()).execute().use { assertEquals("ok", it.body!!.string()) }
                assertEquals(okhttp3.Credentials.basic(auth.username, auth.password, Charsets.UTF_8), response.get(5, TimeUnit.SECONDS))
                assertSame(before, Authenticator::class.java.getMethod("getDefault").invoke(null))
            } finally { worker.shutdownNow() }
        }
    }

    @Test fun socksAuthenticationPreservesRemoteDnsAndDoesNotUseGlobalAuthenticator() {
        val before = Authenticator::class.java.getMethod("getDefault").invoke(null)
        val auth = LocalProxyCredentials("local-user", "local-pass")
        ServerSocket(0).use { listener ->
            val worker = Executors.newSingleThreadExecutor()
            try {
                val accepted = worker.submit<String> { listener.accept().use { socket ->
                    val input = DataInputStream(socket.getInputStream())
                    assertEquals(listOf(5, 1, 2), List(3) { input.readUnsignedByte() })
                    socket.getOutputStream().write(byteArrayOf(5, 2))
                    assertEquals(1, input.readUnsignedByte())
                    fun field() = ByteArray(input.readUnsignedByte()).also(input::readFully).toString(Charsets.UTF_8)
                    assertEquals(auth.username, field()); assertEquals(auth.password, field())
                    socket.getOutputStream().write(byteArrayOf(1, 0))
                    assertEquals(listOf(5, 1, 0, 3), List(4) { input.readUnsignedByte() })
                    val hostname = field(); assertEquals(53, input.readUnsignedShort())
                    socket.getOutputStream().write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 1))
                    hostname
                } }
                Socket().use { socket ->
                    socket.soTimeout = 3000
                    LocalProxyAccess.connectSocks(socket, listener.localPort, auth, "never-resolve.invalid", 53, 3000)
                }
                assertEquals("never-resolve.invalid", accepted.get(5, TimeUnit.SECONDS))
                assertSame(before, Authenticator::class.java.getMethod("getDefault").invoke(null))
            } finally { worker.shutdownNow() }
        }
    }

    @Test fun probeReadinessCompletesRfc1929SubnegotiationWhenPasswordAuthIsSelected() {
        val user = "test_socks_user"
        val pass = "test_socks_pass"
        ServerSocket(0).use { listener ->
            val worker = Executors.newSingleThreadExecutor()
            try {
                val serverCompletedWithoutEof = worker.submit<Boolean> {
                    listener.accept().use { socket ->
                        socket.soTimeout = 3000
                        val input = DataInputStream(socket.getInputStream())
                        val out = socket.getOutputStream()

                        // Expect client offering SOCKS5 with methods 0x00 and 0x02
                        val ver = input.readUnsignedByte()
                        val nmethods = input.readUnsignedByte()
                        val methods = List(nmethods) { input.readUnsignedByte() }
                        assertEquals(5, ver)
                        assertTrue(methods.containsAll(listOf(0, 2)))

                        // Server selects Password Auth (0x02)
                        out.write(byteArrayOf(5, 2))
                        out.flush()

                        // RFC 1929 subnegotiation: version (1), ulen, uname, plen, passwd
                        val authVer = input.readUnsignedByte()
                        assertEquals(1, authVer)
                        val ulen = input.readUnsignedByte()
                        val recvUser = ByteArray(ulen).also(input::readFully).toString(Charsets.UTF_8)
                        val plen = input.readUnsignedByte()
                        val recvPass = ByteArray(plen).also(input::readFully).toString(Charsets.UTF_8)

                        assertEquals(user, recvUser)
                        assertEquals(pass, recvPass)

                        // Auth success status
                        out.write(byteArrayOf(1, 0))
                        out.flush()
                        true
                    }
                }
                Socket().use { client ->
                    client.soTimeout = 3000
                    client.connect(java.net.InetSocketAddress("127.0.0.1", listener.localPort), 1000)
                    val ready = LocalProxyAccess.probeReadiness(client, user to pass)
                    assertTrue("Readiness probe must succeed on valid RFC 1929 auth", ready)
                }
                assertTrue(serverCompletedWithoutEof.get(5, TimeUnit.SECONDS))
            } finally { worker.shutdownNow() }
        }
    }

    @Test fun probeReadinessSucceedsWhenNoAuthIsSelected() {
        ServerSocket(0).use { listener ->
            val worker = Executors.newSingleThreadExecutor()
            try {
                val serverCompleted = worker.submit<Boolean> {
                    listener.accept().use { socket ->
                        socket.soTimeout = 3000
                        val input = DataInputStream(socket.getInputStream())
                        val out = socket.getOutputStream()

                        assertEquals(5, input.readUnsignedByte())
                        val nmethods = input.readUnsignedByte()
                        val methods = List(nmethods) { input.readUnsignedByte() }
                        assertTrue(methods.contains(0))

                        // Select No-Auth
                        out.write(byteArrayOf(5, 0))
                        out.flush()
                        true
                    }
                }
                Socket().use { client ->
                    client.soTimeout = 3000
                    client.connect(java.net.InetSocketAddress("127.0.0.1", listener.localPort), 1000)
                    val ready = LocalProxyAccess.probeReadiness(client, null)
                    assertTrue("Readiness probe must succeed on No-Auth", ready)
                }
                assertTrue(serverCompleted.get(5, TimeUnit.SECONDS))
            } finally { worker.shutdownNow() }
        }
    }

    @Test fun probeReadinessFailsWhenServerRejectsAuthentication() {
        ServerSocket(0).use { listener ->
            val worker = Executors.newSingleThreadExecutor()
            try {
                val serverCompleted = worker.submit<Boolean> {
                    listener.accept().use { socket ->
                        socket.soTimeout = 3000
                        val input = DataInputStream(socket.getInputStream())
                        val out = socket.getOutputStream()

                        assertEquals(5, input.readUnsignedByte())
                        val nmethods = input.readUnsignedByte()
                        List(nmethods) { input.readUnsignedByte() }

                        // Select password auth
                        out.write(byteArrayOf(5, 2))
                        out.flush()

                        assertEquals(1, input.readUnsignedByte())
                        val ulen = input.readUnsignedByte()
                        input.readFully(ByteArray(ulen))
                        val plen = input.readUnsignedByte()
                        input.readFully(ByteArray(plen))

                        // Reject auth (status 0x01)
                        out.write(byteArrayOf(1, 1))
                        out.flush()
                        true
                    }
                }
                Socket().use { client ->
                    client.soTimeout = 3000
                    client.connect(java.net.InetSocketAddress("127.0.0.1", listener.localPort), 1000)
                    val ready = LocalProxyAccess.probeReadiness(client, "bad" to "creds")
                    assertFalse("Readiness probe must report false on auth failure", ready)
                }
                assertTrue(serverCompleted.get(5, TimeUnit.SECONDS))
            } finally { worker.shutdownNow() }
        }
    }
}
