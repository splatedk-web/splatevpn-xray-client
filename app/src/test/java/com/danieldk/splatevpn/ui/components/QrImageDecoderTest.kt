package com.danieldk.splatevpn.ui.components

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.*
import org.junit.Test

class QrImageDecoderTest {
    @Test fun importsCredentialsAndUtf8WithoutRewritingThem() {
        val payload = "vless://A+B=@example.com:443?security=reality&pbk=Ab+C_d-123#Москва"
        assertEquals(listOf(payload), decodeQrPayloads(source(qr(payload))))
    }

    @Test fun readsDenseQrContainingALongRealityConfiguration() {
        val payload = "vless://test@example.com:443?security=reality&pqv=" + "Ab9+_z".repeat(350)
        assertEquals(listOf(payload), decodeQrPayloads(source(qr(payload, 760))))
    }

    @Test fun readsWhiteQrOnBlackBackground() {
        val payload = "https://example.com/subscription?token=A+B%2F"
        val matrix = qr(payload)
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) matrix.flip(x, y)
        assertEquals(listOf(payload), decodeQrPayloads(source(matrix)))
    }

    @Test fun readsRotatedCode() {
        val payload = "trojan://secret@example.com:443#Rotated"
        val matrix = qr(payload)
        val rotated = BitMatrix(matrix.height, matrix.width)
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix[x, y]) rotated.set(matrix.height - y - 1, x)
        }
        assertEquals(listOf(payload), decodeQrPayloads(source(rotated)))
    }

    @Test fun findsMultipleCodesAndRemovesDuplicatePayloads() {
        val first = "https://example.com/first"
        val second = "socks5://example.com:1080"
        val codeSize = 300
        val canvas = BitMatrix(codeSize * 3, codeSize + 60)
        for ((index, text) in listOf(first, second, first).withIndex()) {
            val code = qr(text, codeSize)
            for (y in 0 until code.height) for (x in 0 until code.width) {
                if (code[x, y]) canvas.set(index * codeSize + x, y + 30)
            }
        }
        assertEquals(setOf(first, second), decodeQrPayloads(source(canvas)).toSet())
        assertEquals(2, decodeQrPayloads(source(canvas)).size)
    }

    @Test fun returnsNoPayloadForBlankImage() {
        assertTrue(decodeQrPayloads(QrLuminanceSource(128, 128, ByteArray(128 * 128) { -1 })).isEmpty())
    }

    @Test fun rejectsBlankAndOversizedResults() {
        assertNull(validQrPayload(null))
        assertNull(validQrPayload("  "))
        assertNull(validQrPayload("x".repeat(MAX_QR_PAYLOAD_LENGTH + 1)))
        assertEquals(" x ", validQrPayload(" x "))
    }

    private fun qr(payload: String, size: Int = 360): BitMatrix = QRCodeWriter().encode(
        payload, BarcodeFormat.QR_CODE, size, size,
        mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L)
    )

    private fun source(matrix: BitMatrix): QrLuminanceSource {
        val data = ByteArray(matrix.width * matrix.height)
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            data[y * matrix.width + x] = if (matrix[x, y]) 0 else -1
        }
        return QrLuminanceSource(matrix.width, matrix.height, data)
    }
}

