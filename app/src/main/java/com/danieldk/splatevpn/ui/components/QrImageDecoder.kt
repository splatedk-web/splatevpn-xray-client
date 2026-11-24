package com.danieldk.splatevpn.ui.components

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.qrcode.QRCodeMultiReader
import com.google.zxing.qrcode.QRCodeReader

internal const val MAX_QR_PAYLOAD_LENGTH = 65_536

/** Keep payloads verbatim: URI credentials and base64 are case and whitespace sensitive. */
internal fun validQrPayload(value: String?): String? = value?.takeIf {
    it.isNotBlank() && it.length <= MAX_QR_PAYLOAD_LENGTH
}

/** Android independent so dense, inverted and multiple codes can be regression tested on the JVM. */
internal fun decodeQrPayloads(source: LuminanceSource): List<String> {
    val hints = mapOf<DecodeHintType, Any>(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.CHARACTER_SET to "UTF-8"
    )
    for (candidate in listOf(source, source.invert())) {
        for (bitmap in listOf(
            BinaryBitmap(HybridBinarizer(candidate)),
            BinaryBitmap(GlobalHistogramBinarizer(candidate))
        )) {
            val results = try {
                QRCodeMultiReader().decodeMultiple(bitmap, hints)
                    .mapNotNull { validQrPayload(it.text) }.distinct()
            } catch (_: ReaderException) {
                emptyList()
            }
            if (results.isNotEmpty()) return results
            try {
                validQrPayload(QRCodeReader().decode(bitmap, hints).text)?.let { return listOf(it) }
            } catch (_: ReaderException) {
                // Retry another threshold/polarity. Do not swallow cancellation or memory errors.
            }
        }
    }
    return emptyList()
}

internal class QrLuminanceSource(width: Int, height: Int, private val pixels: ByteArray) :
    LuminanceSource(width, height) {
    init { require(width > 0 && height > 0 && pixels.size.toLong() == width.toLong() * height) }

    override fun getMatrix(): ByteArray = pixels

    override fun getRow(y: Int, row: ByteArray?): ByteArray {
        require(y in 0 until height)
        val result = if (row != null && row.size >= width) row else ByteArray(width)
        pixels.copyInto(result, 0, y * width, (y + 1) * width)
        return result
    }
}

