package com.danieldk.splatevpn.ui.components

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object QrCodeGenerator {
    private const val TAG = "QrCodeGenerator"

    /** Максимальный лимит байт для QR-кода версии 40 при ErrorCorrectionLevel.L */
    const val MAX_QR_BYTE_CAPACITY = 2953

    fun isPayloadTooLargeForQr(content: String): Boolean {
        return content.toByteArray(Charsets.UTF_8).size > MAX_QR_BYTE_CAPACITY
    }

    fun generateQrBitmap(content: String, sizePx: Int = 600): Bitmap? {
        if (content.isBlank()) return null
        return try {
            val hints = mapOf(
                EncodeHintType.MARGIN to 1,
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L
            )
            val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val pixels = IntArray(width * height)
            for (y in 0 until height) {
                val offset = y * width
                for (x in 0 until width) {
                    pixels[offset + x] = if (bitMatrix[x, y]) Color.BLACK else Color.WHITE
                }
            }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            bitmap
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка генерации QR-кода (${content.length} симв.): ${e.message}")
            null
        }
    }
}
