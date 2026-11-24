package com.danieldk.splatevpn.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Portable authenticated envelope. Never stores a password or an encryption key. */
internal object BackupCipher {
    private const val ITERATIONS = 310_000
    private const val MAX_BYTES = 16 * 1024 * 1024
    private const val KIND = "SplateVPN-Encrypted"
    private val aad = "$KIND/1/PBKDF2-HMAC-SHA256/$ITERATIONS/AES-256-GCM".toByteArray()
    fun encrypt(json: String, password: CharArray): String {
        require(password.size in 8..1024) { "Use a password of at least 8 characters" }
        require(json.toByteArray().size <= MAX_BYTES)
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        val encrypted = crypt(Cipher.ENCRYPT_MODE, json.toByteArray(Charsets.UTF_8), password, salt, nonce)
        return JsonObject().apply {
            addProperty("app", KIND); addProperty("version", 1)
            addProperty("iterations", ITERATIONS)
            addProperty("salt", Base64.getEncoder().encodeToString(salt))
            addProperty("nonce", Base64.getEncoder().encodeToString(nonce))
            addProperty("ciphertext", Base64.getEncoder().encodeToString(encrypted))
        }.toString()
    }
    fun decryptIfNeeded(document: String, password: CharArray?): String {
        require(document.length <= MAX_BYTES * 2) { "Backup is too large" }
        val root = JsonParser.parseString(document).asJsonObject
        if (root["app"]?.asString != KIND) return document
        require(password != null && password.isNotEmpty()) { "This backup requires a password" }
        require(password.size <= 1024)
        require(root["version"]?.asInt == 1 && root["iterations"]?.asInt == ITERATIONS) { "Unsupported encrypted backup version" }
        val decoder = Base64.getDecoder()
        val salt = decoder.decode(root["salt"].asString)
        val nonce = decoder.decode(root["nonce"].asString)
        val ciphertext = decoder.decode(root["ciphertext"].asString)
        require(salt.size == 16 && nonce.size == 12 && ciphertext.size in 16..MAX_BYTES + 16) { "Invalid encrypted backup" }
        return try { crypt(Cipher.DECRYPT_MODE, ciphertext, password, salt, nonce).toString(Charsets.UTF_8) }
        catch (_: javax.crypto.AEADBadTagException) { throw IllegalArgumentException("Incorrect password or damaged backup") }
    }
    private fun crypt(mode: Int, bytes: ByteArray, password: CharArray, salt: ByteArray, nonce: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        val key = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(aad)
                doFinal(bytes)
            }
        } finally { key.fill(0) }
    }
}
