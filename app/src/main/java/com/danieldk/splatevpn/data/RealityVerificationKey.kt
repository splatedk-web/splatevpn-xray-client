package com.danieldk.splatevpn.data

import java.util.Base64

/** Xray expects a 1952-byte ML-DSA-65 public key in unpadded Base64URL. */
internal object RealityVerificationKey {
    private const val ERROR = "Invalid REALITY pqv/mldsa65Verify: expected a complete 1952-byte ML-DSA-65 public key. Re-import the original profile."
    private val alphabet = Regex("[A-Za-z0-9_-]{2603}")

    fun normalize(value: String): String {
        require(value.length <= 8192) { ERROR }
        // Clipboard wrapping and bidi/zero-width formatting do not encode key bytes.
        val encoded = value.filterNot {
            it.isWhitespace() || it in "\u200B\u200C\u200D\u200E\u200F\u202A\u202B\u202C\u202D\u202E\u2060\u2066\u2067\u2068\u2069\uFEFF"
        }
        require(encoded.length in 2603..2604) { ERROR }
        val raw = encoded.removeSuffix("=").replace('+', '-').replace('/', '_')
        require(alphabet.matches(raw)) { ERROR }
        val decoded = try {
            Base64.getUrlDecoder().decode(raw)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException(ERROR)
        }
        require(decoded.size == 1952) { ERROR }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(decoded)
    }
}
