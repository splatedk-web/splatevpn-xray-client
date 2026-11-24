package com.danieldk.splatevpn.vpn

import android.content.Context
import com.danieldk.splatevpn.data.AdvancedRoutingRules
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Behavioral gate for future core replacements: config validation alone cannot detect a core
 * silently ignoring a routing field or losing authenticated identities on UDP associations.
 * No packets leave loopback, no DNS/assets are used, and success lives only in this process.
 */
object AppIdentityCoreCompatibility {
    private const val LOOPBACK = "127.0.0.1"
    private const val MATCHED_HOST = "allow.app-identity.invalid"
    private const val PRIVATE_TAG = "compat-private"
    private const val IO_TIMEOUT_MS = 2_000
    private const val NEGATIVE_TIMEOUT_MS = 700
    private val gate = Mutex()
    private var passedSha256: String? = null

    /** Throws rather than allowing an app-scoped rule to broaden after an incompatible update. */
    suspend fun ensureCompatible(context: Context, binary: File) = withContext(Dispatchers.IO) {
        gate.withLock {
            val digest = MessageDigest.getInstance("SHA-256")
            binary.inputStream().buffered().use { source ->
                val block = ByteArray(128 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = source.read(block)
                    if (count < 0) break
                    digest.update(block, 0, count)
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            if (passedSha256 == hash) return@withLock
            probe(context, binary)
            currentCoroutineContext().ensureActive()
            passedSha256 = hash
        }
    }

    private suspend fun probe(context: Context, binary: File) {
        val directory = File(context.cacheDir, "app-identity-check-${UUID.randomUUID()}")
        check(directory.mkdirs()) { "Не удалось подготовить проверку правил приложений" }
        val source = File(directory, "config.json")
        var process: Process? = null
        var tcpOrigin: ServerSocket? = null
        var udpOrigin: DatagramSocket? = null
        val udpClients = mutableListOf<DatagramSocket>()
        val workers = Executors.newFixedThreadPool(2)
        var stage = "startup"
        try {
            val loopback = InetAddress.getByName(LOOPBACK)
            val tcp = ServerSocket(0, 8, loopback).apply { soTimeout = 200 }.also { tcpOrigin = it }
            val udp = DatagramSocket(InetSocketAddress(loopback, 0)).apply { soTimeout = 200 }.also { udpOrigin = it }
            val socksPort = ServerSocket(0, 1, loopback).use { it.localPort }
            var otherPort = ServerSocket(0, 1, loopback).use { it.localPort }
            while (otherPort == socksPort) otherPort = ServerSocket(0, 1, loopback).use { it.localPort }
            val password = LocalProxyCredentials.generate().password
            val alice = LocalProxyCredentials(AdvancedRoutingRules.appIdentity("com.splate.compatibility.alpha"), password)
            val bob = LocalProxyCredentials(AdvancedRoutingRules.appIdentity("com.splate.compatibility.beta"), password)
            val otherAlice = LocalProxyCredentials(alice.username, LocalProxyCredentials.generate().password)
            val nonce = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val tcpHits = AtomicInteger()
            val udpHits = AtomicInteger()
            workers.submit { serveTcp(tcp, nonce, tcpHits) }
            workers.submit { serveUdp(udp, nonce, udpHits) }
            source.writeText(configuration(socksPort, otherPort, tcp.localPort, udp.localPort, alice, bob, otherAlice).toString())
            process = ProcessBuilder(binary.absolutePath, "run", "-c", source.absolutePath)
                .directory(directory)
                .redirectErrorStream(true)
                .redirectOutput(File("/dev/null"))
                .apply { environment()["XRAY_LOCATION_ASSET"] = directory.absolutePath }
                .start()
            waitForListener(process, socksPort)

            stage = "TCP user AND domain"
            currentCoroutineContext().ensureActive()
            check(tcpExchange(socksPort, tcp.localPort, alice, MATCHED_HOST, nonce, IO_TIMEOUT_MS))
            check(tcpHits.get() == 1)
            stage = "TCP other user"
            currentCoroutineContext().ensureActive()
            check(!tcpExchange(socksPort, tcp.localPort, bob, MATCHED_HOST, nonce, NEGATIVE_TIMEOUT_MS))
            check(tcpHits.get() == 1)
            stage = "TCP other domain"
            currentCoroutineContext().ensureActive()
            check(!tcpExchange(socksPort, tcp.localPort, alice, "deny.app-identity.invalid", nonce, NEGATIVE_TIMEOUT_MS))
            check(tcpHits.get() == 1)
            stage = "TCP other inbound"
            currentCoroutineContext().ensureActive()
            check(!tcpExchange(otherPort, tcp.localPort, otherAlice, MATCHED_HOST, nonce, NEGATIVE_TIMEOUT_MS))
            check(tcpHits.get() == 1)
            stage = "TCP wrong password"
            currentCoroutineContext().ensureActive()
            check(!tcpExchange(socksPort, tcp.localPort, otherAlice, MATCHED_HOST, nonce, NEGATIVE_TIMEOUT_MS))
            check(tcpHits.get() == 1)

            stage = "UDP user identity"
            currentCoroutineContext().ensureActive()
            // Keep endpoints reserved until all probes finish: immediate source-port reuse can
            // accidentally hit an old UDP dispatch entry instead of a new authenticated flow.
            repeat(3) { udpClients += DatagramSocket(InetSocketAddress(loopback, 0)) }
            check(udpExchange(udpClients[0], socksPort, udp.localPort, alice, nonce, IO_TIMEOUT_MS))
            check(udpHits.get() == 1)
            stage = "UDP other user"
            currentCoroutineContext().ensureActive()
            check(!udpExchange(udpClients[1], socksPort, udp.localPort, bob, nonce, NEGATIVE_TIMEOUT_MS))
            check(udpHits.get() == 1)
            stage = "UDP other inbound"
            currentCoroutineContext().ensureActive()
            check(!udpExchange(udpClients[2], otherPort, udp.localPort, otherAlice, nonce, NEGATIVE_TIMEOUT_MS))
            check(udpHits.get() == 1)
            check(process.isAlive)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Do not expose temporary auth/config/log contents in diagnostics.
            throw IllegalStateException("Ядро Xray не прошло проверку правил приложений ($stage). " +
                "Подключение остановлено, чтобы сохранить ограничение по приложению.", failure)
        } finally {
            runCatching { tcpOrigin?.close() }
            runCatching { udpOrigin?.close() }
            udpClients.forEach { runCatching { it.close() } }
            workers.shutdownNow()
            process?.let { child ->
                runCatching {
                    child.destroy()
                    if (!child.waitFor(500, TimeUnit.MILLISECONDS)) {
                        child.destroyForcibly()
                        child.waitFor(1, TimeUnit.SECONDS)
                    }
                }
            }
            runCatching { workers.awaitTermination(500, TimeUnit.MILLISECONDS) }
            source.delete()
            directory.delete()
        }
    }

    private fun configuration(socksPort: Int, otherPort: Int, tcpPort: Int, udpPort: Int,
        alice: LocalProxyCredentials, bob: LocalProxyCredentials, otherAlice: LocalProxyCredentials) = JsonObject().apply {
        add("log", JsonObject().apply { addProperty("loglevel", "none") })
        add("dns", JsonObject().apply { add("servers", JsonArray()) })
        fun inbound(port: Int, tag: String, accounts: List<LocalProxyCredentials>) = JsonObject().apply {
            addProperty("tag", tag); addProperty("listen", LOOPBACK); addProperty("port", port); addProperty("protocol", "socks")
            add("settings", JsonObject().apply {
                addProperty("auth", "password"); addProperty("udp", true); addProperty("ip", LOOPBACK)
                add("accounts", JsonArray().apply { for (auth in accounts) add(JsonObject().apply {
                    addProperty("user", auth.username); addProperty("pass", auth.password)
                }) })
            })
            add("sniffing", JsonObject().apply {
                addProperty("enabled", true); addProperty("routeOnly", true)
                add("destOverride", strings("http"))
            })
        }
        add("inbounds", JsonArray().apply {
            add(inbound(socksPort, PRIVATE_TAG, listOf(alice, bob)))
            add(inbound(otherPort, "compat-other", listOf(otherAlice)))
        })
        // The first outbound is the fallback; no match must never reach an origin.
        add("outbounds", JsonArray().apply {
            add(JsonObject().apply { addProperty("tag", "deny"); addProperty("protocol", "blackhole") })
            add(JsonObject().apply { addProperty("tag", "allow"); addProperty("protocol", "freedom") })
        })
        add("routing", JsonObject().apply {
            addProperty("domainStrategy", "AsIs")
            add("rules", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", "field"); addProperty("network", "tcp"); addProperty("port", tcpPort)
                    add("inboundTag", strings(PRIVATE_TAG))
                    add("domain", strings("full:$MATCHED_HOST")); add("user", strings(alice.username)); addProperty("outboundTag", "allow")
                })
                add(JsonObject().apply {
                    addProperty("type", "field"); addProperty("network", "udp"); addProperty("port", udpPort)
                    add("inboundTag", strings(PRIVATE_TAG))
                    add("ip", strings("$LOOPBACK/32")); add("user", strings(alice.username)); addProperty("outboundTag", "allow")
                })
            })
        })
    }

    private fun strings(vararg values: String) = JsonArray().apply { values.forEach { add(it) } }

    private suspend fun waitForListener(process: Process, port: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (true) {
            currentCoroutineContext().ensureActive()
            check(process.isAlive) { "Compatibility core exited" }
            if (runCatching { Socket().use { it.connect(InetSocketAddress(LOOPBACK, port), 100) } }.isSuccess) return
            check(System.nanoTime() < deadline) { "Compatibility core startup timed out" }
            delay(25)
        }
    }

    private fun authenticate(socket: Socket, port: Int, auth: LocalProxyCredentials, timeout: Int): DataInputStream {
        socket.soTimeout = timeout
        socket.connect(InetSocketAddress(LOOPBACK, port), timeout)
        val input = DataInputStream(socket.getInputStream())
        val output = socket.getOutputStream()
        output.write(byteArrayOf(5, 1, 2)); output.flush()
        if (input.readUnsignedByte() != 5 || input.readUnsignedByte() != 2) throw IOException("SOCKS authentication unavailable")
        val user = auth.username.toByteArray(Charsets.UTF_8)
        val pass = auth.password.toByteArray(Charsets.UTF_8)
        output.write(byteArrayOf(1, user.size.toByte())); output.write(user)
        output.write(pass.size); output.write(pass); output.flush()
        if (input.readUnsignedByte() != 1 || input.readUnsignedByte() != 0) throw IOException("SOCKS authentication rejected")
        return input
    }

    private fun readSocksReply(input: DataInputStream): InetSocketAddress {
        if (input.readUnsignedByte() != 5 || input.readUnsignedByte() != 0 || input.readUnsignedByte() != 0)
            throw IOException("SOCKS command rejected")
        val address = when (input.readUnsignedByte()) {
            1 -> ByteArray(4).also { input.readFully(it) }
            4 -> ByteArray(16).also { input.readFully(it) }
            else -> throw IOException("Non-numeric SOCKS reply")
        }
        val port = input.readUnsignedShort()
        val host = InetAddress.getByAddress(address)
        if (!host.isLoopbackAddress && !host.isAnyLocalAddress) throw IOException("Non-loopback SOCKS reply")
        return InetSocketAddress(if (host.isAnyLocalAddress) InetAddress.getByName(LOOPBACK) else host, port)
    }

    private fun command(command: Int, port: Int) = byteArrayOf(5, command.toByte(), 0, 1, 127, 0, 0, 1,
        (port ushr 8).toByte(), port.toByte())

    private fun tcpExchange(socksPort: Int, targetPort: Int, auth: LocalProxyCredentials, host: String,
        nonce: ByteArray, timeout: Int): Boolean = try {
        Socket().use { socket ->
            val input = authenticate(socket, socksPort, auth, timeout)
            socket.getOutputStream().apply { write(command(1, targetPort)); flush() }
            readSocksReply(input)
            socket.getOutputStream().apply {
                write("GET /identity HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII)); flush()
            }
            val received = ByteArray(nonce.size).also { input.readFully(it) }
            received.contentEquals(nonce)
        }
    } catch (_: IOException) { false }

    private fun udpExchange(datagram: DatagramSocket, socksPort: Int, targetPort: Int, auth: LocalProxyCredentials, nonce: ByteArray,
        timeout: Int): Boolean = try {
        datagram.soTimeout = timeout
        Socket().use { control ->
            val input = authenticate(control, socksPort, auth, timeout)
            control.getOutputStream().apply { write(command(3, datagram.localPort)); flush() }
            val relay = readSocksReply(input)
            if (relay.port == 0) throw IOException("Missing UDP relay")
            datagram.connect(relay)
            val packet = byteArrayOf(0, 0, 0, 1, 127, 0, 0, 1, (targetPort ushr 8).toByte(), targetPort.toByte()) + nonce
            datagram.send(DatagramPacket(packet, packet.size))
            val received = DatagramPacket(ByteArray(1024), 1024)
            datagram.receive(received)
            received.length == packet.size && received.data.copyOfRange(received.offset, received.offset + received.length).contentEquals(packet)
        }
    } catch (_: IOException) { false }

    private fun serveTcp(origin: ServerSocket, nonce: ByteArray, hits: AtomicInteger) {
        while (!origin.isClosed) {
            try {
                origin.accept().use { socket ->
                    socket.soTimeout = IO_TIMEOUT_MS
                    val input = socket.getInputStream()
                    var tail = 0
                    var read = 0
                    while (tail != 0x0d0a0d0a) {
                        val next = input.read()
                        if (next < 0 || ++read > 4096) throw EOFException("Incomplete local probe")
                        tail = (tail shl 8) or next
                    }
                    hits.incrementAndGet()
                    socket.getOutputStream().apply { write(nonce); flush() }
                }
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: IOException) {
                if (origin.isClosed) return
            }
        }
    }

    private fun serveUdp(origin: DatagramSocket, nonce: ByteArray, hits: AtomicInteger) {
        while (!origin.isClosed) {
            try {
                val packet = DatagramPacket(ByteArray(1024), 1024)
                origin.receive(packet)
                if (packet.length == nonce.size && packet.data.copyOfRange(packet.offset, packet.offset + packet.length).contentEquals(nonce)) {
                    hits.incrementAndGet()
                    origin.send(DatagramPacket(nonce, nonce.size, packet.socketAddress))
                }
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: IOException) {
                if (origin.isClosed) return
            }
        }
    }
}
