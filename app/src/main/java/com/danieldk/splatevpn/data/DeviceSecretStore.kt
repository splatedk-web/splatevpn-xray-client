package com.danieldk.splatevpn.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device-bound settings; neither the Android backup nor portable app backups contain this file. */
internal class DeviceSecretStore(context: Context, private val alias: String) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "$alias.json"))
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    fun read(): String? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        val data = file.openRead().use { it.readLimited(64 * 1024).toString(Charsets.UTF_8) }
        val obj = JsonParser.parseString(data).asJsonObject
        require(obj["version"].asInt == 1)
        val nonce = Base64.getDecoder().decode(obj["iv"].asString)
        require(nonce.size == 12)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, nonce))
            updateAAD(alias.toByteArray())
            doFinal(Base64.getDecoder().decode(obj["ciphertext"].asString)).toString(Charsets.UTF_8)
        }
    }
    fun write(value: String) {
        require(value.toByteArray(Charsets.UTF_8).size <= 40 * 1024) { "Device secret settings exceed size limit" }
        val data = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, key()); updateAAD(alias.toByteArray())
            JsonObject().apply {
                addProperty("version", 1); addProperty("iv", Base64.getEncoder().encodeToString(iv))
                addProperty("ciphertext", Base64.getEncoder().encodeToString(doFinal(value.toByteArray(Charsets.UTF_8))))
            }.toString().toByteArray(Charsets.UTF_8)
        }
        val output = file.startWrite()
        try { output.write(data); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }
    fun delete() = file.delete()
}
