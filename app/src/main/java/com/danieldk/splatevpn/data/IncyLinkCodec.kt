package com.danieldk.splatevpn.data

import com.google.gson.JsonParser
import java.net.URI
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Official crypt1 wire format: https://docs.incy.cc/en/deep-links/#encrypted-crypt1-links */
object IncyLinkCodec {
    private const val PREFIX = "incy://crypt1/"
    const val MAX_LINK_LENGTH = 65_536

    // Public K1 from the MIT-licensed @incy/link-encoder, version 1.3.0.
    // This format hides URLs from casual scanning; its shared key does not provide secrecy.
    private val key = SecretKeySpec(Base64.getDecoder().decode("9tQOoMioiZ18aC0Jug1BZd/is91F5rs+JcsjPPAMJGI="), "AES")

    data class Decoded(val url: String, val name: String?) {
        override fun toString() = "IncyLinkCodec.Decoded([hidden])"
    }

    fun isSupportedLink(raw: String): Boolean = raw.trim().startsWith(PREFIX, ignoreCase = true)

    fun decode(raw: String): Decoded {
        val link = raw.trim()
        require(isSupportedLink(link) && link.length <= MAX_LINK_LENGTH) { "Invalid INCY crypt1 link" }
        try {
            val encoded = link.substring(PREFIX.length).trimEnd('/')
            require(encoded.isNotEmpty() && encoded.matches(Regex("[A-Za-z0-9_-]+={0,2}")))
            val wire = Base64.getUrlDecoder().decode(encoded)
            require(wire.size >= 29)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, wire.copyOfRange(0, 12)))
            val plaintext = cipher.doFinal(wire, 12, wire.size - 12).toString(Charsets.UTF_8)
            val payload = JsonParser.parseString(plaintext).asJsonObject
            require(payload["v"]?.isJsonPrimitive == true && payload["v"].asJsonPrimitive.isNumber && payload["v"].asString == "1")
            val urlField = payload["url"]
            require(urlField?.isJsonPrimitive == true && urlField.asJsonPrimitive.isString)
            val url = urlField.asString
            val uri = URI(url)
            require(uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank())
            val name = payload["n"]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString?.trim()?.take(128)?.takeIf { it.isNotEmpty() }
            return Decoded(url, name)
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid INCY crypt1 link")
        }
    }
}
