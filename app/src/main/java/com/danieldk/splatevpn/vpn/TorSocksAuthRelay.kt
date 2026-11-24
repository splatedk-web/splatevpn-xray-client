package com.danieldk.splatevpn.vpn

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Authenticated loopback SOCKS5 bridge in front of Tor's app-private Unix domain socket.
 * Rejects unauthenticated local port-scanners (such as RKNHardening outside split tunneling)
 * before any connection reaches Tor.
 */
internal class TorSocksAuthRelay(
    val credentials: LocalProxyCredentials = LocalProxyCredentials.generate(),
    private val upstreamConnector: () -> UpstreamConnection
) : Closeable {

    interface UpstreamConnection : Closeable {
        val inputStream: InputStream
        val outputStream: OutputStream
    }

    private val serverSocket = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
    val port: Int = serverSocket.localPort
    private val closed = AtomicBoolean(false)
    private val activeConnections = ConcurrentHashMap.newKeySet<Closeable>()
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "splate-tor-relay").apply { isDaemon = true }
    }

    init {
        executor.execute(::acceptLoop)
    }

    private fun acceptLoop() {
        while (!closed.get() && !serverSocket.isClosed) {
            val client = try {
                serverSocket.accept()
            } catch (_: IOException) {
                break
            }
            activeConnections.add(client)
            executor.execute {
                try {
                    handleClient(client)
                } finally {
                    activeConnections.remove(client)
                    runCatching { client.close() }
                }
            }
        }
    }

    private fun handleClient(client: Socket) {
        client.soTimeout = 5_000
        val clientIn = DataInputStream(client.getInputStream())
        val clientOut = client.getOutputStream()

        // 1. SOCKS5 greeting — require RFC 1929 username/password (0x02), reject No-Auth (0x00).
        val version = try { clientIn.readUnsignedByte() } catch (_: IOException) { return }
        if (version != 5) return
        val methodCount = try { clientIn.readUnsignedByte() } catch (_: IOException) { return }
        if (methodCount <= 0) return
        val methods = ByteArray(methodCount)
        try { clientIn.readFully(methods) } catch (_: IOException) { return }
        if (methods.none { it == 2.toByte() }) {
            runCatching {
                clientOut.write(byteArrayOf(5, 0xFF.toByte()))
                clientOut.flush()
            }
            return
        }
        clientOut.write(byteArrayOf(5, 2))
        clientOut.flush()

        // 2. RFC 1929 subnegotiation with constant-time credential verification.
        val authVer = try { clientIn.readUnsignedByte() } catch (_: IOException) { return }
        if (authVer != 1) return
        val uLen = try { clientIn.readUnsignedByte() } catch (_: IOException) { return }
        val userBytes = ByteArray(uLen)
        try { clientIn.readFully(userBytes) } catch (_: IOException) { return }
        val pLen = try { clientIn.readUnsignedByte() } catch (_: IOException) { return }
        val passBytes = ByteArray(pLen)
        try { clientIn.readFully(passBytes) } catch (_: IOException) { return }

        val userOk = MessageDigest.isEqual(userBytes, credentials.username.toByteArray(Charsets.UTF_8))
        val passOk = MessageDigest.isEqual(passBytes, credentials.password.toByteArray(Charsets.UTF_8))
        if (!userOk || !passOk) {
            runCatching {
                clientOut.write(byteArrayOf(1, 1))
                clientOut.flush()
            }
            return
        }
        clientOut.write(byteArrayOf(1, 0))
        clientOut.flush()
        client.soTimeout = 0

        // 3. Connect to Tor's private Unix domain socket and perform upstream no-auth SOCKS5 greeting.
        val upstream = try {
            upstreamConnector()
        } catch (_: IOException) {
            return
        }
        activeConnections.add(upstream)
        try {
            val upIn = DataInputStream(upstream.inputStream)
            val upOut = upstream.outputStream
            upOut.write(byteArrayOf(5, 1, 0))
            upOut.flush()
            if (upIn.readUnsignedByte() != 5 || upIn.readUnsignedByte() != 0) return

            val reverse = executor.submit {
                runCatching { copyStream(upIn, clientOut) }
                runCatching { client.shutdownOutput() }
            }
            try {
                copyStream(clientIn, upOut)
            } catch (_: IOException) {
            } finally {
                runCatching { upstream.close() }
                runCatching { reverse.get() }
            }
        } finally {
            activeConnections.remove(upstream)
            runCatching { upstream.close() }
        }
    }

    private fun copyStream(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(16 * 1024)
        while (!closed.get()) {
            val read = input.read(buffer)
            if (read <= 0) break
            output.write(buffer, 0, read)
            output.flush()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { serverSocket.close() }
        activeConnections.forEach { runCatching { it.close() } }
        activeConnections.clear()
        executor.shutdownNow()
    }

    companion object {
        fun forUnixSocket(
            unixSocketPath: String,
            credentials: LocalProxyCredentials = LocalProxyCredentials.generate()
        ): TorSocksAuthRelay = TorSocksAuthRelay(credentials) {
            val socket = LocalSocket()
            socket.connect(LocalSocketAddress(unixSocketPath, LocalSocketAddress.Namespace.FILESYSTEM))
            object : UpstreamConnection {
                override val inputStream: InputStream = socket.inputStream
                override val outputStream: OutputStream = socket.outputStream
                override fun close() = socket.close()
            }
        }
    }
}
