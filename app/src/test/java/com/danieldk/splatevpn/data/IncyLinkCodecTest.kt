package com.danieldk.splatevpn.data

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

class IncyLinkCodecTest {
    // Published cross-platform vector from INCY-DEV/incy-link-encoder 1.3.0.
    private val vector = "incy://crypt1/AAECAwQFBgcICQoLNyIQL3rDwRZqnyoD8pGKSLXP6o8NdSXQVSSALNbbUyIr__tWGFUexdIfKvvmDnuDGbmBvuppfNef6aKNZUwOm4c-Sg"

    @Test fun decodesOfficialCrossPlatformVectorAndNormalizesImport() {
        val decoded = IncyLinkCodec.decode(vector)
        assertEquals("https://sub.example.com/test-vector", decoded.url)
        assertNull(decoded.name)
        assertEquals(decoded.url, LinkParser.normalizeSubscriptionUrl(vector))
        assertFalse(LinkParser.isUnsupportedEncryptedSubscriptionLink(vector))
    }

    @Test fun invalidAuthenticationTagIsRejectedWithoutLeakingPayload() {
        val wire = Base64.getUrlDecoder().decode(vector.substringAfter("crypt1/"))
        wire[wire.lastIndex] = (wire.last().toInt() xor 1).toByte()
        val error = assertThrows(IllegalArgumentException::class.java) {
            IncyLinkCodec.decode("incy://crypt1/" + Base64.getUrlEncoder().withoutPadding().encodeToString(wire))
        }
        assertEquals("Invalid INCY crypt1 link", error.message)
    }

    @Test fun rejectsOversizedAndTruncatedInput() {
        listOf("incy://crypt1/" + "A".repeat(IncyLinkCodec.MAX_LINK_LENGTH), "incy://crypt1/AA", "incy://crypt1/", "incy://crypt1/!").forEach {
            assertThrows(IllegalArgumentException::class.java) { IncyLinkCodec.decode(it) }
            assertNull(LinkParser.normalizeSubscriptionUrl(it))
        }
    }

    @Test fun validatesAuthenticatedSchemaVersionAndUrlBeforeImport() {
        listOf("""{"url":"https://example.com","v":2}""", """{"url":"https://example.com","v":"1"}""",
            """{"url":"file:///private/config","v":1}""", """{"url":1,"v":1}""").forEach { payload ->
            assertThrows(IllegalArgumentException::class.java) { IncyLinkCodec.decode(encryptFixture(payload)) }
        }
    }

    @Test fun boundsOptionalNameAndKeepsUrlQueryBytes() {
        val decoded = IncyLinkCodec.decode(encryptFixture("""{"n":"${"N".repeat(200)}","url":"https://example.com/sub?key=a+b%2Fc","v":1}"""))
        assertEquals(128, decoded.name?.length)
        assertEquals("https://example.com/sub?key=a+b%2Fc", decoded.url)
    }

    private fun encryptFixture(plaintext: String): String {
        val iv = ByteArray(12) { it.toByte() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(Base64.getDecoder()
            .decode("9tQOoMioiZ18aC0Jug1BZd/is91F5rs+JcsjPPAMJGI="), "AES"), GCMParameterSpec(128, iv))
        return "incy://crypt1/" + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(iv + cipher.doFinal(plaintext.toByteArray()))
    }
}
