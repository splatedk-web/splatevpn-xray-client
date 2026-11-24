package com.danieldk.splatevpn.vpn

import com.danieldk.splatevpn.SplateConstants
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.security.SecureRandom
import java.util.Base64
import javax.net.SocketFactory

/** Credentials belong to one local listener; never install a process-wide Java Authenticator. */
data class LocalProxyCredentials(val username: String, val password: String) {
    init {
        require(username.isNotBlank() && ':' !in username && username.toByteArray().size in 1..255)
        require(password.isNotEmpty() && password.toByteArray().size in 1..255)
        require((username + password).none { it.isISOControl() })
    }
    // Keep credentials out of accidental logs, error messages and data-class interpolation.
    override fun toString() = "LocalProxyCredentials([hidden])"

    internal fun socksSettings(udp: Boolean) = JsonObject().apply {
        addProperty("auth", "password"); addProperty("udp", udp)
        add("accounts", JsonArray().apply { add(JsonObject().apply {
            addProperty("user", username); addProperty("pass", password)
        }) })
    }
    internal fun hevYaml(): String = "  username: '${username.replace("'", "''")}'\n  password: '${password.replace("'", "''")}'"

    companion object {
        fun generate(): LocalProxyCredentials {
            fun token(bytes: Int) = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also { SecureRandom().nextBytes(it) })
            return LocalProxyCredentials("splate_" + token(9), token(24))
        }
    }
}

/** Snapshot of the running core, kept stable while the user edits the next connection's settings. */
object LocalProxyAccess {
    private data class Runtime(
        val token: Long,
        val credentials: LocalProxyCredentials?,
        val socksPort: Int,
        val dnsProbePort: Int?,
        val alive: () -> Boolean
    )
    private val generations = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var runtime: Runtime? = null
    val active get() = runtime?.credentials
    val activeSocksPort: Int get() = runtime?.socksPort ?: SplateConstants.XRAY_SOCKS_PORT
    val activeDnsProbePort: Int get() = runtime?.dnsProbePort ?: SplateConstants.DNS_CHECK_SOCKS_PORT
    val isAvailable get() = runtime?.alive?.invoke() == true
    @Synchronized internal fun publish(credentials: LocalProxyCredentials?, alive: () -> Boolean): Long =
        publish(credentials, SplateConstants.XRAY_SOCKS_PORT, null, alive)

    @Synchronized internal fun publish(
        credentials: LocalProxyCredentials?,
        socksPort: Int = SplateConstants.XRAY_SOCKS_PORT,
        dnsProbePort: Int? = null,
        alive: () -> Boolean = { true }
    ): Long {
        val token = generations.incrementAndGet()
        runtime = Runtime(token, credentials, socksPort, dnsProbePort, alive)
        return token
    }
    @Synchronized internal fun revoke(token: Long?) { if (token != null && runtime?.token == token) runtime = null }
    internal fun isOwnedPort(port: Int): Boolean {
        val current = runtime
        return port == SplateConstants.XRAY_SOCKS_PORT ||
            port == SplateConstants.DNS_CHECK_SOCKS_PORT ||
            (current != null && (port == current.socksPort || port == current.dnsProbePort))
    }
    private fun ownedPort(port: Int) = isOwnedPort(port)

    private class SocksTunnelSocket(
        private val proxyPort: Int,
        private val credentials: LocalProxyCredentials?,
        private val requireRunningVpn: Boolean
    ) : Socket() {
        override fun connect(endpoint: SocketAddress?, timeout: Int) {
            val inet = endpoint as? InetSocketAddress ?: throw IOException("Unsupported address: $endpoint")
            val host = inet.hostString
            val port = inet.port
            val timeoutMs = timeout.coerceAtLeast(1500)
            super.connect(InetSocketAddress(SplateConstants.XRAY_SOCKS_ADDRESS, proxyPort), timeoutMs)
            negotiateSocks(this, credentials, host, port, timeoutMs, requireRunningVpn)
        }
    }

    private class SocksTunnelSocketFactory(
        private val proxyPort: Int,
        private val credentials: LocalProxyCredentials?,
        private val requireRunningVpn: Boolean
    ) : SocketFactory() {
        override fun createSocket(): Socket = SocksTunnelSocket(proxyPort, credentials, requireRunningVpn)
        override fun createSocket(host: String, port: Int): Socket {
            val s = SocksTunnelSocket(proxyPort, credentials, requireRunningVpn)
            s.connect(InetSocketAddress.createUnresolved(host, port), 10_000)
            return s
        }
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket {
            return createSocket(host, port)
        }
        override fun createSocket(host: InetAddress, port: Int): Socket {
            return createSocket(host.hostAddress ?: host.hostName, port)
        }
        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket {
            return createSocket(address.hostAddress ?: address.hostName, port)
        }
    }

    /** OkHttp client configured with HTTP proxy for listeners that expose an HTTP proxy port. */
    fun client(base: OkHttpClient, port: Int = activeSocksPort,
        credentials: LocalProxyCredentials? = active, requireRunningVpn: Boolean = ownedPort(port)): OkHttpClient {
        require(port in 1..65535)
        val owner = if (requireRunningVpn) runtime ?: throw IOException("VPN must be connected for this request") else null
        if (owner != null && !owner.alive()) throw IOException("VPN must be connected for this request")
        val effectiveCredentials = if (owner != null) owner.credentials else credentials
        return base.newBuilder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
            .proxyAuthenticator { _, response ->
                val credential = effectiveCredentials?.let { okhttp3.Credentials.basic(it.username, it.password, Charsets.UTF_8) } ?: return@proxyAuthenticator null
                if (response.request.header("Proxy-Authorization") != null) null
                else response.request.newBuilder().header("Proxy-Authorization", credential).build()
            }
            .addInterceptor { chain ->
                if (requireRunningVpn && (runtime !== owner || owner?.alive?.invoke() != true)) throw IOException("VPN connection changed")
                chain.proceed(chain.request())
            }
            .build()
    }

    /** OkHttp client configured with SOCKS5 tunnel for Xray's native SOCKS listeners. */
    fun socksClient(base: OkHttpClient, port: Int = activeSocksPort,
        credentials: LocalProxyCredentials? = active, requireRunningVpn: Boolean = ownedPort(port)): OkHttpClient {
        require(port in 1..65535)
        val owner = if (requireRunningVpn) runtime ?: throw IOException("VPN must be connected for this request") else null
        if (owner != null && !owner.alive()) throw IOException("VPN must be connected for this request")
        val effectiveCredentials = if (owner != null) owner.credentials else credentials
        return base.newBuilder()
            .proxy(Proxy.NO_PROXY)
            .dns(object : okhttp3.Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    return listOf(InetAddress.getByAddress(hostname, byteArrayOf(127, 0, 0, 1)))
                }
            })
            .socketFactory(SocksTunnelSocketFactory(port, effectiveCredentials, requireRunningVpn))
            .addInterceptor { chain ->
                if (requireRunningVpn && (runtime !== owner || owner?.alive?.invoke() != true)) throw IOException("VPN connection changed")
                chain.proceed(chain.request())
            }
            .build()
    }

    /** RFC 1928 & RFC 1929 authentication and SOCKS5 CONNECT, preserving remote DNS for tunnel checks. */
    internal fun connectSocks(socket: Socket, proxyPort: Int, credentials: LocalProxyCredentials?,
        host: String, port: Int, timeoutMs: Int, requireRunningVpn: Boolean = ownedPort(proxyPort)) {
        require(proxyPort in 1..65535 && port in 1..65535)
        val owner = if (requireRunningVpn) runtime ?: throw IOException("VPN must be connected for this request") else null
        if (owner != null && !owner.alive()) throw IOException("VPN must be connected for this request")
        socket.soTimeout = timeoutMs
        socket.connect(InetSocketAddress(SplateConstants.XRAY_SOCKS_ADDRESS, proxyPort), timeoutMs)
        negotiateSocks(socket, credentials, host, port, timeoutMs, requireRunningVpn)
    }

    internal fun negotiateSocks(socket: Socket, credentials: LocalProxyCredentials?,
        host: String, port: Int, timeoutMs: Int, requireRunningVpn: Boolean) {
        val owner = if (requireRunningVpn) runtime ?: throw IOException("VPN must be connected for this request") else null
        if (requireRunningVpn && (runtime !== owner || owner?.alive?.invoke() != true)) throw IOException("VPN connection changed")
        val access = if (owner != null) owner.credentials else credentials
        val output = socket.getOutputStream()
        val input = DataInputStream(socket.getInputStream())
        val methods = if (access != null) byteArrayOf(5, 1, 2) else byteArrayOf(5, 1, 0)
        output.write(methods); output.flush()
        val socksVersion = input.readUnsignedByte()
        if (socksVersion != 5) throw IOException("Invalid SOCKS version: $socksVersion")
        val selectedMethod = input.readUnsignedByte()
        if (selectedMethod == 2) {
            val user = requireNotNull(access).username.toByteArray(Charsets.UTF_8)
            val pass = access.password.toByteArray(Charsets.UTF_8)
            val authBuffer = ByteArray(3 + user.size + pass.size).apply {
                this[0] = 1
                this[1] = user.size.toByte()
                System.arraycopy(user, 0, this, 2, user.size)
                this[2 + user.size] = pass.size.toByte()
                System.arraycopy(pass, 0, this, 3 + user.size, pass.size)
            }
            output.write(authBuffer)
            output.flush()
            val authVersion = input.readUnsignedByte()
            val authStatus = input.readUnsignedByte()
            if (authStatus != 0) throw IOException("Local proxy authentication rejected (status $authStatus)")
        } else if (selectedMethod != 0) {
            throw IOException("Local proxy rejected authentication method (selected: $selectedMethod)")
        }
        val isIpv4 = host.matches(Regex("""^(\d{1,3}\.){3}\d{1,3}$"""))
        val requestBytes = if (isIpv4) {
            val parts = host.split('.').map { it.toInt() }
            if (parts.all { it in 0..255 }) {
                ByteArray(10).apply {
                    this[0] = 5; this[1] = 1; this[2] = 0; this[3] = 1
                    parts.forEachIndexed { i, p -> this[4 + i] = p.toByte() }
                    this[8] = (port ushr 8).toByte()
                    this[9] = (port and 255).toByte()
                }
            } else {
                val hostname = host.toByteArray(Charsets.UTF_8)
                ByteArray(7 + hostname.size).apply {
                    this[0] = 5; this[1] = 1; this[2] = 0; this[3] = 3
                    this[4] = hostname.size.toByte()
                    System.arraycopy(hostname, 0, this, 5, hostname.size)
                    this[5 + hostname.size] = (port ushr 8).toByte()
                    this[6 + hostname.size] = (port and 255).toByte()
                }
            }
        } else {
            val hostname = host.removeSurrounding("[", "]").toByteArray(Charsets.UTF_8)
            require(hostname.size in 1..255)
            ByteArray(7 + hostname.size).apply {
                this[0] = 5; this[1] = 1; this[2] = 0; this[3] = 3
                this[4] = hostname.size.toByte()
                System.arraycopy(hostname, 0, this, 5, hostname.size)
                this[5 + hostname.size] = (port ushr 8).toByte()
                this[6 + hostname.size] = (port and 255).toByte()
            }
        }
        output.write(requestBytes)
        output.flush()
        if (input.readUnsignedByte() != 5 || input.readUnsignedByte() != 0)
            throw IOException("Local proxy CONNECT rejected")
        input.readUnsignedByte()
        val size = when (input.readUnsignedByte()) {
            1 -> 4
            3 -> input.readUnsignedByte()
            4 -> 16
            else -> throw IOException("Invalid local proxy address")
        }
        input.readFully(ByteArray(size + 2))
    }

    /**
     * Probes SOCKS5 readiness on a connected socket by offering No-Auth (0x00) and Password (0x02).
     * If the server chooses Password (0x02), completes RFC 1929 subnegotiation cleanly.
     * Prevents Xray from logging "failed to read username and password for authentication > EOF".
     */
    internal fun probeReadiness(socket: Socket, credentials: Pair<String, String>?): Boolean {
        if (socket.soTimeout == 0) socket.soTimeout = 3000
        val out = socket.getOutputStream()
        val input = DataInputStream(socket.getInputStream())

        // SOCKS5 greeting offering both No-Auth (0x00) and Password (0x02)
        out.write(byteArrayOf(5, 2, 0, 2))
        out.flush()

        val ver = input.readUnsignedByte()
        if (ver != 5) return false
        val selectedMethod = input.readUnsignedByte()

        return when (selectedMethod) {
            0 -> true
            2 -> {
                val (user, pass) = credentials ?: return false
                val userBytes = user.toByteArray(Charsets.UTF_8)
                val passBytes = pass.toByteArray(Charsets.UTF_8)
                if (userBytes.isEmpty() || userBytes.size > 255 || passBytes.isEmpty() || passBytes.size > 255) return false
                out.write(1) // RFC 1929 subnegotiation version 1
                out.write(userBytes.size)
                out.write(userBytes)
                out.write(passBytes.size)
                out.write(passBytes)
                out.flush()

                val subVer = input.readUnsignedByte()
                val status = input.readUnsignedByte()
                subVer == 1 && status == 0
            }
            else -> false
        }
    }
}
