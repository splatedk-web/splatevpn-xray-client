package com.danieldk.splatevpn.data

import com.danieldk.splatevpn.SplateConstants
import kotlinx.coroutines.CancellationException
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URI
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class DnsProviderEndpoint(val key: String, val label: String, val address: String)
object DnsProviderCatalog {
    val standard = listOf(
        DnsProviderEndpoint("cloudflare", "Cloudflare", "https://1.1.1.1/dns-query"),
        DnsProviderEndpoint("google", "Google", "https://8.8.8.8/dns-query"),
        DnsProviderEndpoint("quad9", "Quad9", "https://9.9.9.9/dns-query"),
        DnsProviderEndpoint("adguard", "AdGuard", "https://94.140.14.14/dns-query"),
        DnsProviderEndpoint("yandex", "Yandex", "tcp://77.88.8.8:53")
    )
    fun endpoints(custom: String) = standard + SmartDnsProviders.all.map {
        DnsProviderEndpoint(it.key, it.label, it.dohUrl)
    } + DnsProviderEndpoint("custom", "", custom.trim())
}

enum class DnsProviderCheckStatus { WAITING, CHECKING, AVAILABLE, FAILED, INVALID }
data class DnsProviderCheckResult(val status: DnsProviderCheckStatus, val latencyMs: Long? = null)
data class DnsProviderCheckState(val running: Boolean = false,
    val results: Map<String, DnsProviderCheckResult> = emptyMap(), val viaVpn: Boolean = false, val completedAt: Long = 0)

/** A real A-query, not a ping or a TCP-connect-only check. No settings are modified. */
@Singleton
class DnsProviderChecker @Inject constructor() {
    private val random = SecureRandom()
    private val client = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS).callTimeout(4, TimeUnit.SECONDS)
        .followRedirects(false).retryOnConnectionFailure(false)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> {
                val preset = SmartDnsProviders.all.firstOrNull { it.host.equals(hostname, true) }
                return preset?.bootstrapIpv4?.map { java.net.InetAddress.getByName(it) } ?: Dns.SYSTEM.lookup(hostname)
            }
        }).build()

    suspend fun check(endpoint: DnsProviderEndpoint, viaVpn: Boolean): DnsProviderCheckResult {
        val address = endpoint.address
        val uri = runCatching { URI(address) }.getOrNull()
        val doh = uri?.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null
        val tcp = runCatching { tcpAddress(address) }.getOrNull()
        if (!doh && tcp == null) return DnsProviderCheckResult(DnsProviderCheckStatus.INVALID)
        val id = random.nextInt(65536)
        val query = DnsProbeWire.query(id)
        val start = System.nanoTime()
        val valid = try {
            if (doh) {
                val routed = if (viaVpn) com.danieldk.splatevpn.vpn.LocalProxyAccess.socksClient(client, com.danieldk.splatevpn.vpn.LocalProxyAccess.activeDnsProbePort)
                    else client.newBuilder().proxy(Proxy.NO_PROXY).build()
                val call = routed.newCall(Request.Builder().url(address)
                    .header("Accept", "application/dns-message")
                    .post(query.toRequestBody("application/dns-message".toMediaType())).build())
                BoundedNetworkTask.run(4500, onCancel = call::cancel) {
                    call.execute().use { response ->
                        val body = response.body
                        response.code == 200 && body != null && body.contentLength() <= 65535 &&
                            DnsProbeWire.valid(body.byteStream().readLimited(65535), id)
                    }
                } == true
            } else {
                val target = requireNotNull(tcp)
                val socket = Socket()
                try {
                    BoundedNetworkTask.run(4500, onCancel = { socket.close() }) {
                        socket.soTimeout = 4000
                        if (viaVpn) com.danieldk.splatevpn.vpn.LocalProxyAccess.connectSocks(socket,
                            com.danieldk.splatevpn.vpn.LocalProxyAccess.activeDnsProbePort, com.danieldk.splatevpn.vpn.LocalProxyAccess.active,
                            target.first, target.second, 3000)
                        else socket.connect(InetSocketAddress(target.first, target.second), 3000)
                        DataOutputStream(socket.getOutputStream()).apply { writeShort(query.size); write(query); flush() }
                        val input = DataInputStream(socket.getInputStream())
                        val size = input.readUnsignedShort()
                        require(size in 12..65535)
                        val response = ByteArray(size).also(input::readFully)
                        DnsProbeWire.valid(response, id)
                    } == true
                } finally { socket.close() }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { false }
        return DnsProviderCheckResult(if (valid) DnsProviderCheckStatus.AVAILABLE else DnsProviderCheckStatus.FAILED,
            if (valid) ((System.nanoTime() - start) / 1_000_000).coerceAtLeast(1) else null)
    }

    internal fun tcpAddress(value: String): Pair<String, Int>? {
        val raw = value.trim()
        if (raw.startsWith("tcp://")) {
            val uri = URI(raw)
            if (uri.host.isNullOrBlank() || uri.userInfo != null || uri.query != null || uri.fragment != null ||
                !uri.path.isNullOrEmpty()) return null
            val port = if (uri.port == -1) 53 else uri.port
            return if (port in 1..65535) uri.host to port else null
        }
        if (raw.matches(Regex("(?:\\d{1,3}\\.){3}\\d{1,3}")) && raw.split('.').all { it.toInt() in 0..255 }) return raw to 53
        if (raw.matches(Regex("[0-9a-fA-F:]+")) && raw.count { it == ':' } >= 2) return raw to 53
        return null
    }
}

internal object DnsProbeWire {
    fun query(id: Int): ByteArray {
        val bytes = java.io.ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeShort(id); out.writeShort(0x100); out.writeShort(1)
            repeat(3) { out.writeShort(0) }
            listOf("example", "com").forEach { out.writeByte(it.length); out.writeBytes(it) }
            out.writeByte(0); out.writeShort(1); out.writeShort(1)
        }
        return bytes.toByteArray()
    }
    fun valid(message: ByteArray, id: Int): Boolean = runCatching {
        require(message.size in 12..65535)
        var pos = 0
        fun u16(): Int { require(pos + 2 <= message.size); return ((message[pos++].toInt() and 255) shl 8) or (message[pos++].toInt() and 255) }
        require(u16() == id)
        val flags = u16()
        require(flags and 0x8000 != 0 && flags and 0x780f == 0 && flags and 0x200 == 0)
        require(u16() == 1)
        val answers = u16(); require(answers in 1..512)
        u16(); u16()
        fun name(): String {
            var cursor = pos; var end = -1; val labels = mutableListOf<String>(); val seen = hashSetOf<Int>()
            repeat(128) {
                require(cursor in message.indices && seen.add(cursor))
                val size = message[cursor++].toInt() and 255
                if (size == 0) { pos = if (end == -1) cursor else end; return labels.joinToString(".") }
                if (size and 0xc0 == 0xc0) {
                    require(cursor < message.size)
                    if (end == -1) end = cursor + 1
                    cursor = ((size and 63) shl 8) or (message[cursor].toInt() and 255)
                } else {
                    require(size in 1..63 && cursor + size <= message.size)
                    labels += String(message, cursor, size, Charsets.US_ASCII); cursor += size
                }
            }
            error("DNS compression loop")
        }
        require(name().equals("example.com", true) && u16() == 1 && u16() == 1)
        var hasAddress = false
        repeat(answers) {
            name(); val type = u16(); val cls = u16(); u16(); u16(); val length = u16()
            require(pos + length <= message.size)
            if (type == 1 && cls == 1 && length == 4) hasAddress = true
            pos += length
        }
        hasAddress
    }.getOrDefault(false)
}
