package com.danieldk.splatevpn.data

import com.danieldk.splatevpn.vpn.LocalProxyAccess
import com.danieldk.splatevpn.vpn.LocalProxyCredentials
import java.io.DataInputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

/** Unauthenticated MTProto req_pq_multi/resPQ exchange, not a telegram.org homepage check. */
internal object TelegramAvailabilityProbe {
    fun request(nonce: ByteArray, now: Long): ByteArray {
        require(nonce.size == 16)
        val id = ((now / 1000L) shl 32) or (((now % 1000L) shl 32) / 1000L and -4L)
        return ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0xeeeeeeee.toInt()).putInt(40).putLong(0).putLong(id).putInt(20)
            .putInt(0xbe7e8ef1.toInt()).put(nonce).array()
    }
    fun validResponse(packet: ByteArray, nonce: ByteArray): Boolean {
        if (nonce.size != 16 || packet.size !in 76..4096 || packet.size % 4 != 0) return false
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.long != 0L) return false
        val messageId = buffer.long
        if ((messageId and 1L) == 0L || buffer.int != packet.size - 20 || buffer.int != 0x05162463) return false
        if (!ByteArray(16).also { buffer.get(it) }.contentEquals(nonce)) return false
        buffer.position(buffer.position() + 16) // server_nonce

        // A nonce echo is not enough: require the complete TL resPQ body, including
        // the factorization challenge and at least one RSA key fingerprint.
        val prefix = buffer.get().toInt() and 0xff
        val length = when {
            prefix < 254 -> prefix
            prefix == 254 && buffer.remaining() >= 3 ->
                (buffer.get().toInt() and 0xff) or ((buffer.get().toInt() and 0xff) shl 8) or
                    ((buffer.get().toInt() and 0xff) shl 16)
            else -> return false
        }
        if (length !in 1..8) return false
        val prefixBytes = if (prefix == 254) 4 else 1
        val padding = (4 - (prefixBytes + length) % 4) % 4
        if (buffer.remaining() < length + padding + 16) return false
        val pq = ByteArray(length).also { buffer.get(it) }
        if (pq.all { it == 0.toByte() } || (pq.last().toInt() and 1) == 0) return false
        buffer.position(buffer.position() + padding)
        if (buffer.int != 0x1cb5c415) return false
        val count = buffer.int
        return count > 0 && count == buffer.remaining() / 8 && buffer.remaining() % 8 == 0
    }
    fun measure(port: Int, access: LocalProxyCredentials?, socket: Socket): Boolean {
        socket.soTimeout = 4000
        // Production DC bootstrap address from the official Telegram Android client.
        LocalProxyAccess.connectSocks(socket, port, access, "149.154.167.51", 443, 4000)
        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
        socket.getOutputStream().apply { write(request(nonce, System.currentTimeMillis())); flush() }
        val input = DataInputStream(socket.getInputStream())
        val length = Integer.reverseBytes(input.readInt())
        if (length !in 76..4096 || length % 4 != 0) return false
        return validResponse(ByteArray(length).also { input.readFully(it) }, nonce)
    }
}
