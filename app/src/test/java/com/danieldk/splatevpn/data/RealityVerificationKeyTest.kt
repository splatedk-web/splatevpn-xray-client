package com.danieldk.splatevpn.data

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class RealityVerificationKeyTest {
    private val bytes = ByteArray(1952) { (it % 251).toByte() }
    private val canonical = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    @Test fun acceptsEquivalentBase64RepresentationsWithoutChangingKeyBytes() {
        listOf(canonical, "$canonical=", Base64.getEncoder().encodeToString(bytes),
            Base64.getEncoder().withoutPadding().encodeToString(bytes),
            canonical.chunked(64).joinToString("\r\n"),
            "\uFEFF\u200E" + canonical.take(500) + "\u200B" + canonical.drop(500) + "\u2069").forEach { encoded ->
            val normalized = RealityVerificationKey.normalize(encoded)
            assertEquals(canonical, normalized)
            assertArrayEquals(bytes, Base64.getUrlDecoder().decode(normalized))
        }
    }

    @Test fun rejectsTruncatedWrongSizeAndDamagedKeysInsteadOfDroppingVerification() {
        listOf(canonical.dropLast(1), canonical + "==", canonical.dropLast(1) + "!",
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32)),
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(1953)),
            canonical.replaceRange(200, 201, " ")).forEach { malformed ->
            val error = assertThrows(IllegalArgumentException::class.java) { RealityVerificationKey.normalize(malformed) }
            assertTrue(error.message.orEmpty().contains("pqv/mldsa65Verify"))
            assertTrue(error.message.orEmpty().contains("1952"))
            assertFalse(error.message.orEmpty().contains(malformed))
        }
    }
}
