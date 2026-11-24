package com.danieldk.splatevpn.data

import android.content.Context
import android.os.Build
import com.danieldk.splatevpn.SplateConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/** A source-specific identifier, unrelated to Android's hardware or advertising IDs. */
object SubscriptionHwidPolicy {
    internal fun canonicalUrl(raw: String): HttpUrl? {
        val normalized = LinkParser.normalizeSubscriptionUrl(raw) ?: return null
        val url = normalized.toHttpUrlOrNull() ?: return null
        if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        return url.newBuilder().fragment(null).build()
    }

    fun key(url: String): String? = canonicalUrl(url)?.toString()?.let {
        MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8)).hex()
    }

    fun canSend(url: String): Boolean {
        val parsed = canonicalUrl(url) ?: return false
        val host = parsed.host
        if (host in PUBLIC_HOSTS || host.endsWith(".githubusercontent.com") ||
            host.endsWith(".githack.com") || host.endsWith(".torproject.org")) return false
        return true
    }

    fun enabled(url: String, overrides: Map<String, Boolean>): Boolean {
        if (!canSend(url)) return false
        val sourceKey = key(url) ?: return false
        return overrides[sourceKey] ?: (sourceKey == key(SplateConstants.PRESET_SUB_SPONSOR_URL))
    }

    private val PUBLIC_HOSTS = setOf(
        "github.com", "githubusercontent.com", "githack.com", "torproject.org",
        "translate.yandex.ru", "translate.yandex.com", "white.igareck.net"
    )
}

@Singleton
class SubscriptionHwidStore @Inject constructor(@ApplicationContext private val context: Context) {
    fun idForSource(url: String): String {
        val canonical = SubscriptionHwidPolicy.canonicalUrl(url)
            ?: throw IllegalArgumentException("HWID requires an HTTPS subscription")
        require(SubscriptionHwidPolicy.canSend(url)) { "HWID is unavailable for public feeds" }
        return deriveSubscriptionHwid(installationSecret(), canonical.toString())
    }

    private fun installationSecret(): ByteArray = synchronized(FILE_LOCK) {
        val directory = context.noBackupFilesDir
        if (!directory.exists() && !directory.mkdirs()) throw IOException("Cannot store subscription identity")
        RandomAccessFile(File(directory, "subscription-hwid-v1.key"), "rw").use { file ->
            file.channel.lock().use {
                if (file.length() == 0L) {
                    val secret = ByteArray(32).also(SecureRandom()::nextBytes)
                    file.write(secret)
                    file.fd.sync()
                    secret
                } else {
                    // Never silently register a different device after a damaged local file.
                    if (file.length() != 32L) throw IOException("Stored subscription identity is damaged")
                    ByteArray(32).also(file::readFully)
                }
            }
        }
    }

    private companion object { val FILE_LOCK = Any() }
}

internal fun deriveSubscriptionHwid(secret: ByteArray, canonicalSource: String): String {
    require(secret.size == 32)
    return Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(secret, "HmacSHA256"))
        doFinal(("splate-subscription-hwid-v1\u0000" + canonicalSource).toByteArray(Charsets.UTF_8)).hex()
    }
}

private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }

/** Deliberately not a data class: neither the source token nor the ID belongs in logs. */
class SubscriptionRequestIdentity(sourceUrl: String, private val hwid: String) {
    private val source = SubscriptionHwidPolicy.canonicalUrl(sourceUrl)
    init { require(hwid.matches(Regex("[a-zA-Z0-9=-]{10,64}"))) { "Invalid subscription identity" } }

    internal fun redirectStateFor(url: String): SubscriptionIdentityRedirectState? {
        val requested = SubscriptionHwidPolicy.canonicalUrl(url) ?: return null
        if (source != requested || !SubscriptionHwidPolicy.canSend(url)) return null
        return SubscriptionIdentityRedirectState(requested, hwid)
    }

    override fun toString() = "SubscriptionRequestIdentity([redacted])"
}

internal class SubscriptionIdentityRedirectState(private val source: HttpUrl, private val hwid: String) {
    private var allowed = true

    fun apply(request: Request): Request {
        val target = request.url
        if (!target.isHttps || target.scheme != source.scheme || target.host != source.host || target.port != source.port) {
            allowed = false
        }
        return request.newBuilder()
            .removeHeader("X-HWID")
            .removeHeader("X-Device-OS")
            .removeHeader("X-Ver-OS")
            .removeHeader("X-Device-Model")
            .apply {
                if (allowed) {
                    header("X-HWID", hwid)
                    header("X-Device-OS", "Android")
                    Build.VERSION.RELEASE?.takeIf { it.isNotEmpty() }?.let { header("X-Ver-OS", it) }
                    Build.MODEL?.takeIf { it.isNotEmpty() }?.let { header("X-Device-Model", it) }
                }
            }.build()
    }

    override fun toString() = "SubscriptionIdentityRedirectState([redacted])"
}

enum class SubscriptionHwidFailure { REQUIRED, DEVICE_LIMIT }

class SubscriptionHwidException(val reason: SubscriptionHwidFailure, val status: Int) : IOException(
    when (reason) {
        SubscriptionHwidFailure.REQUIRED -> "Subscription requires a device ID (HWID)"
        SubscriptionHwidFailure.DEVICE_LIMIT -> "Subscription device limit reached"
    }
)

internal fun SubscriptionResponse.checkHwidAccess() {
    fun enabled(name: String) = header(name)?.trim()?.let { it.equals("true", true) || it == "1" } == true
    when {
        enabled("x-hwid-max-devices-reached") -> throw SubscriptionHwidException(SubscriptionHwidFailure.DEVICE_LIMIT, code)
        enabled("x-hwid-not-supported") -> throw SubscriptionHwidException(SubscriptionHwidFailure.REQUIRED, code)
        // 3x-ui also sets x-hwid-limit on successful access to the last available slot.
        !isSuccessful && enabled("x-hwid-limit") -> throw SubscriptionHwidException(SubscriptionHwidFailure.DEVICE_LIMIT, code)
    }
}
