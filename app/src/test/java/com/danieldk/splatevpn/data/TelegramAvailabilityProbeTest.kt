package com.danieldk.splatevpn.data

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TelegramAvailabilityProbeTest {
    private val nonce = ByteArray(16) { (it + 1).toByte() }
    private fun response(): ByteArray {
        val body = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x05162463).put(nonce).put(ByteArray(16) { 0x21 })
            .put(8.toByte()).put(byteArrayOf(0x17, 0x55, 0x31, 0x29, 0x45, 0x61, 0x13, 0x37))
            .put(ByteArray(3)).putInt(0x1cb5c415).putInt(1).putLong(0x123456789abcdefL).array()
        return ByteBuffer.allocate(20 + body.size).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(0).putLong((1_700_000_000L shl 32) or 1L).putInt(body.size).put(body).array()
    }
    private fun overwriteInt(packet: ByteArray, offset: Int, value: Int) = packet.also {
        ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value)
    }

    @Test fun requestUsesIntermediateFramingAndAnUnencryptedNonceChallenge() {
        val packet = TelegramAvailabilityProbe.request(nonce, 1_700_000_000_123L)
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0xeeeeeeee.toInt(), buffer.int)
        assertEquals(packet.size - 8, buffer.int)
        assertEquals(0L, buffer.long)
        val id = buffer.long
        assertEquals(0L, id and 3)
        assertEquals(1_700_000_000L, id ushr 32)
        assertEquals(20, buffer.int)
        assertEquals(0xbe7e8ef1.toInt(), buffer.int)
        assertArrayEquals(nonce, ByteArray(16).also { buffer.get(it) })
        assertFalse(buffer.hasRemaining())
    }

    @Test fun acceptsCompleteResPqOnlyForTheOriginalChallenge() {
        assertTrue(TelegramAvailabilityProbe.validResponse(response(), nonce))
        assertFalse(TelegramAvailabilityProbe.validResponse(response(), nonce.copyOf().also { it[0]++ }))
        assertFalse(TelegramAvailabilityProbe.validResponse(response().also { it[0] = 1 }, nonce))
        assertFalse(TelegramAvailabilityProbe.validResponse(overwriteInt(response(), 16, 68), nonce))
        assertFalse(TelegramAvailabilityProbe.validResponse(overwriteInt(response(), 20, 0), nonce))
    }

    @Test fun malformedPqAndFingerprintVectorsCannotQualifyAServer() {
        val withoutFingerprints = response().copyOf(76)
        overwriteInt(withoutFingerprints, 16, withoutFingerprints.size - 20)
        assertFalse(TelegramAvailabilityProbe.validResponse(withoutFingerprints, nonce))
        assertFalse(TelegramAvailabilityProbe.validResponse(overwriteInt(response(), 68, 0), nonce))
        assertFalse(TelegramAvailabilityProbe.validResponse(overwriteInt(response(), 72, -1), nonce))
        assertFalse(TelegramAvailabilityProbe.validResponse(overwriteInt(response(), 72, Int.MAX_VALUE), nonce))
        assertFalse(TelegramAvailabilityProbe.validResponse(response().also { it[56] = 0 }, nonce))
        assertFalse(TelegramAvailabilityProbe.validResponse(response().also { it[56] = 255.toByte() }, nonce))
    }

    @Test fun shortOversizedAndUnalignedFramesAreRejectedWithoutReadingPastTheInput() {
        for (length in listOf(0, 19, 40, 75, 85, 4100)) {
            assertFalse(TelegramAvailabilityProbe.validResponse(ByteArray(length), nonce))
        }
        assertFalse(TelegramAvailabilityProbe.validResponse(response(), ByteArray(15)))
    }
}
