package com.danieldk.splatevpn.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.SecureRandom

/** Bounded DNS-wire validation: an HTTP 200 is not proof of a usable resolver. */
internal object AiDnsWire {
    fun query(host: String): ByteArray = ByteArrayOutputStream().apply {
        val id = SecureRandom().nextInt(65536)
        write(byteArrayOf((id shr 8).toByte(), id.toByte(), 1, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        host.split('.').forEach { label -> require(label.length in 1..63); write(label.length); write(label.toByteArray(Charsets.US_ASCII)) }
        write(byteArrayOf(0, 0, 1, 0, 1))
    }.toByteArray()

    fun addresses(response: ByteArray, query: ByteArray): List<String> {
        fun u8(i: Int): Int = response.getOrNull(i)?.toInt()?.and(255) ?: throw IOException("dns_truncated")
        fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
        if (query.size < 17 || response.size !in query.size..65535 || response[0] != query[0] || response[1] != query[1] ||
            u8(2) and 0xfa != 0x80 || u8(3) and 15 != 0 || u16(4) != 1 ||
            !response.copyOfRange(12, query.size).contentEquals(query.copyOfRange(12, query.size))) throw IOException("dns_invalid")
        var offset = query.size
        val ips = mutableSetOf<String>()
        repeat(u16(6)) {
            while (true) {
                val length = u8(offset++)
                if (length == 0) break
                if (length and 0xc0 == 0xc0) {
                    val pointer = ((length and 63) shl 8) or u8(offset++)
                    if (pointer >= response.size) throw IOException("dns_name")
                    break
                }
                if (length > 63 || offset + length >= response.size) throw IOException("dns_name")
                offset += length
            }
            val type = u16(offset); val klass = u16(offset + 2); val size = u16(offset + 8); offset += 10
            if (offset + size > response.size) throw IOException("dns_truncated")
            if (type == 1 && klass == 1 && size == 4) {
                val a = u8(offset); val b = u8(offset + 1)
                if (a in setOf(0, 10, 127) || a >= 224 || a == 169 && b == 254 || a == 172 && b in 16..31 ||
                    a == 192 && b == 168 || a == 100 && b in 64..127 || a == 198 && b in 18..19) throw IOException("dns_local")
                ips += (0..3).joinToString(".") { u8(offset + it).toString() }
            }
            offset += size
        }
        if (ips.isEmpty()) throw IOException("dns_no_ipv4")
        return ips.toList()
    }
}
