package com.danieldk.splatevpn.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.room.TypeConverter
import com.google.gson.Gson
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Room encrypts the profile on every write, including imports, edits and subscription updates. */
class AmneziaWgProfileConverter {
    private val alias = "splate_awg_profiles_v1"
    private fun key(): SecretKey = synchronized(lock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    @TypeConverter fun encode(profile: AmneziaWgProfile?): String? = profile?.let {
        val json = Gson().toJson(it.validated())
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, key()); updateAAD(alias.toByteArray())
            "v1:" + Base64.getEncoder().encodeToString(iv + doFinal(json.toByteArray()))
        }
    }
    @TypeConverter fun decode(value: String?): AmneziaWgProfile? = value?.let {
        require(it.startsWith("v1:") && it.length <= 128 * 1024) { "Invalid encrypted AWG profile" }
        val data = Base64.getDecoder().decode(it.substring(3)); require(data.size > 28)
        val json = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12))); updateAAD(alias.toByteArray())
            doFinal(data.copyOfRange(12, data.size)).toString(Charsets.UTF_8)
        }
        Gson().fromJson(json, AmneziaWgProfile::class.java).validated()
    }
    companion object { private val lock = Any() }
}
