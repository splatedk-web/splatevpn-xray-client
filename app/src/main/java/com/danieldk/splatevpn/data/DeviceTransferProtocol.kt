package com.danieldk.splatevpn.data

import com.google.gson.JsonParser
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

data class TransferPreview(
    val servers: Int,
    val subscriptions: Int,
    val includesTor: Boolean,
    val includesProxyPassword: Boolean,
    val source: String = "LAN",
    val received: Boolean = true,
    val encrypted: Boolean = true,
    val customRoutes: Int = 0,
    val advancedRules: Int = 0,
    val operationId: java.util.UUID? = null,
    val subscriptionUpdateInterval: String? = null
)

enum class DeviceTransferDirection { DOWNLOAD, UPLOAD }

data class DeviceTransferInvitation(val url: String, val secret: String, val direction: DeviceTransferDirection) {
    fun encode(): String = "splate-transfer://receive?url=${URLEncoder.encode(url, "UTF-8")}&mode=${direction.name.lowercase()}#$secret"
}

/** QR shares a random encryption key in the fragment; HTTP never receives that fragment. */
object DeviceTransferProtocol {
    const val MAX_DOCUMENT_BYTES = 32 * 1024 * 1024
    const val LIFETIME_MS = 180_000L
    private val secretPattern = Regex("[A-Za-z0-9_-]{43}")

    fun isInvitation(raw: String): Boolean = raw.trim().startsWith("splate-transfer://", true)

    fun newSecret(): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))

    fun isPrivateIpv4(host: String): Boolean {
        val pieces = host.split('.')
        if (pieces.size != 4 || pieces.any { !Regex("0|[1-9][0-9]{0,2}").matches(it) }) return false
        val values = pieces.map { it.toInt() }
        if (values.any { it !in 0..255 }) return false
        return values[0] == 10 || (values[0] == 172 && values[1] in 16..31) || (values[0] == 192 && values[1] == 168)
    }

    fun parseInvitation(raw: String): DeviceTransferInvitation {
        require(raw.length <= 2048) { "Invalid pairing code" }
        val uri = URI(raw.trim())
        require(uri.scheme.equals("splate-transfer", true) && uri.host == "receive" && uri.rawUserInfo == null) { "Not a SplateVPN pairing code" }
        val query = uri.rawQuery.orEmpty().split('&').map { part ->
            val pair = part.split('=', limit = 2)
            require(pair.size == 2) { "Invalid pairing code" }
            pair[0] to URLDecoder.decode(pair[1], "UTF-8")
        }
        require(query.map { it.first }.toSet() == setOf("url", "mode") && query.size == 2) { "Invalid pairing options" }
        val params = query.toMap()
        val endpoint = URI(requireNotNull(params["url"]))
        require(endpoint.scheme == "http" && endpoint.rawUserInfo == null && endpoint.rawQuery == null && endpoint.rawFragment == null &&
            endpoint.port in 1024..65535 && isPrivateIpv4(endpoint.host.orEmpty())) { "Pairing is only available on a private LAN address" }
        require(Regex("/transfer/[A-Za-z0-9_-]{43}").matches(endpoint.rawPath.orEmpty())) { "Invalid pairing token" }
        val secret = uri.rawFragment.orEmpty()
        require(secretPattern.matches(secret)) { "Invalid pairing key" }
        val direction = when (params["mode"]) {
            "download" -> DeviceTransferDirection.DOWNLOAD
            "upload" -> DeviceTransferDirection.UPLOAD
            else -> error("Unsupported pairing operation")
        }
        return DeviceTransferInvitation(endpoint.toASCIIString(), secret, direction)
    }

    fun requireEncrypted(document: String) {
        require(document.toByteArray(Charsets.UTF_8).size <= MAX_DOCUMENT_BYTES) { "Transfer exceeds 32 MB" }
        val root = JsonParser.parseString(document)
        require(root.isJsonObject && root.asJsonObject["app"]?.asString == "SplateVPN-Encrypted") { "Only encrypted settings transfers are accepted" }
    }
}

/** One authenticated request per QR, bounded lifetime and a small failed-attempt allowance. */
internal class DeviceTransferGate(private val token: String, private val expiresAt: Long) {
    private var consumed = false
    private var failures = 0
    @Synchronized fun claim(candidate: String, now: Long): Boolean {
        if (consumed || now >= expiresAt || failures >= 12) return false
        val matches = MessageDigest.isEqual(token.toByteArray(Charsets.US_ASCII), candidate.toByteArray(Charsets.US_ASCII))
        if (!matches) { failures++; return false }
        consumed = true
        return true
    }
    @Synchronized fun finished(now: Long): Boolean = consumed || now >= expiresAt || failures >= 12
}
