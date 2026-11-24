package com.danieldk.splatevpn.data

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class BackupCipherTest {
    @Test fun encryptedBackupRoundTripsUnicodeAndUsesFreshRandomness() {
        val json = """{"app":"SplateVPN","secret":"ключ 密钥 گذرواژه"}"""
        val password = "correct horse battery".toCharArray()
        val first = BackupCipher.encrypt(json, password)
        assertNotEquals(first, BackupCipher.encrypt(json, password))
        assertFalse(first.contains("secret"))
        assertEquals(json, BackupCipher.decryptIfNeeded(first, password))
    }
    @Test fun rejectsWrongPasswordAndTampering() {
        val encoded = BackupCipher.encrypt("""{"app":"SplateVPN"}""", "password123".toCharArray())
        assertThrows(IllegalArgumentException::class.java) { BackupCipher.decryptIfNeeded(encoded, "wrong".toCharArray()) }
        val damaged = JsonParser.parseString(encoded).asJsonObject
        val cipher = damaged["ciphertext"].asString
        damaged.addProperty("ciphertext", (if (cipher[0] == 'A') "B" else "A") + cipher.drop(1))
        assertThrows(IllegalArgumentException::class.java) { BackupCipher.decryptIfNeeded(damaged.toString(), "password123".toCharArray()) }
    }
    @Test fun rejectsUnsupportedKdfBeforeExpensiveWorkAndAcceptsLegacyJson() {
        val legacy = """{"app":"SplateVPN"}"""
        assertEquals(legacy, BackupCipher.decryptIfNeeded(legacy, null))
        val encrypted = JsonParser.parseString(BackupCipher.encrypt(legacy, "password123".toCharArray())).asJsonObject
        encrypted.addProperty("iterations", Int.MAX_VALUE)
        assertThrows(IllegalArgumentException::class.java) { BackupCipher.decryptIfNeeded(encrypted.toString(), "password123".toCharArray()) }
    }
}
