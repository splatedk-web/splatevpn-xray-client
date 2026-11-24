package com.danieldk.splatevpn.data

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal fun InputStream.readLimited(limit: Int): ByteArray {
    val result = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer, 0, minOf(buffer.size, limit + 1 - result.size()))
        if (count < 0) break
        require(result.size() + count <= limit) { "Response exceeds size limit" }
        result.write(buffer, 0, count)
    }
    return result.toByteArray()
}

class SubscriptionHttpException(val status: Int) : IOException("HTTP $status")
class SubscriptionDowngradeException : IOException("HTTPS subscription cannot redirect to HTTP")

/** Fully consumed response: no open socket can escape the cancellation boundary. */
data class SubscriptionResponse(val code: Int, val content: String, val headers: okhttp3.Headers) {
    val isSuccessful get() = code in 200..299
    val message get() = "HTTP $code"
    fun header(name: String): String? = headers[name]
}

@Singleton
class SubscriptionTransport @Inject constructor() {
    companion object {
        const val MAX_BYTES = 8 * 1024 * 1024
        const val MAX_NODES = LinkParser.MAX_SUBSCRIPTION_NODES
    }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .followRedirects(true).followSslRedirects(true)
        .addNetworkInterceptor { chain ->
            if (chain.call().request().url.isHttps && !chain.request().url.isHttps) throw SubscriptionDowngradeException()
            val request = chain.request()
            val identity = request.tag(SubscriptionIdentityRedirectState::class.java)
            chain.proceed(identity?.apply(request) ?: request)
        }.build()

    private val tunnelClient get() = com.danieldk.splatevpn.vpn.LocalProxyAccess.client(client)

    suspend fun fetch(url: String, userAgent: String, viaVpn: Boolean = false): SubscriptionResponse =
        fetch(url, userAgent, viaVpn, null)

    suspend fun fetch(url: String, userAgent: String, viaVpn: Boolean, identity: SubscriptionRequestIdentity?): SubscriptionResponse = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(url).header("User-Agent", userAgent)
            .tag(SubscriptionIdentityRedirectState::class.java, identity?.redirectStateFor(url)).build()
        val call = (if (viaVpn) tunnelClient else client).newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        val body = requireNotNull(it.body) { "Empty subscription response" }
                        require(body.contentLength() <= MAX_BYTES) { "Subscription exceeds 8 MB" }
                        val content = body.byteStream().use { stream -> stream.readLimited(MAX_BYTES) }
                        SubscriptionResponse(it.code, content.toString(Charsets.UTF_8), it.headers)
                    }
                    continuation.resume(result)
                } catch (error: Exception) { continuation.resumeWithException(error) }
            }
        })
    }
}
