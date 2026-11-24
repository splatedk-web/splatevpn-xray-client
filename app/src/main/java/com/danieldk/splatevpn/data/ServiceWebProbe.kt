package com.danieldk.splatevpn.data

import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream

/** Verify the web application and a boot script, rather than accepting any HTTP 200 page. */
internal object ServiceWebProbe {
    private val challenge = Regex("""<(?:form|iframe)\b[^>]*(?:id|class|src)\s*=\s*["'][^"']*(?:captcha|challenge)""", RegexOption.IGNORE_CASE)
    private val scripts = Regex("""<script\b[^>]*\bsrc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    internal fun scriptUrls(html: String, base: HttpUrl, domains: Set<String>): List<HttpUrl> {
        if (challenge.containsMatchIn(html)) return emptyList()
        return scripts.findAll(html).mapNotNull { match ->
            base.resolve(match.groupValues[1].replace("&amp;", "&"))?.takeIf { allowed(it, domains) }
        }.distinct().take(3).toList()
    }

    private fun allowed(url: HttpUrl, domains: Set<String>) = url.isHttps && url.port == 443 &&
        url.username.isEmpty() && url.password.isEmpty() && domains.any { url.host == it || url.host.endsWith(".$it") }

    internal fun validScript(contentType: String, bytes: ByteArray): Boolean {
        val type = contentType.substringBefore(';').trim().lowercase()
        if (type !in setOf("application/javascript", "text/javascript", "application/x-javascript")) return false
        val prefix = bytes.take(256).toByteArray().toString(Charsets.UTF_8).trimStart().lowercase()
        return bytes.size >= 8192 && !prefix.startsWith("<!doctype html") && !prefix.startsWith("<html")
    }

    fun check(client: OkHttpClient, goal: BalancerGoal, onCall: (Call) -> Unit, checkAllowed: () -> Unit): Boolean {
        val (url, domains) = when (goal) {
            BalancerGoal.INSTAGRAM -> "https://www.instagram.com/" to setOf("instagram.com", "cdninstagram.com", "fbcdn.net")
            BalancerGoal.WHATSAPP -> "https://web.whatsapp.com/" to setOf("whatsapp.com", "whatsapp.net")
            BalancerGoal.TWITCH -> "https://www.twitch.tv/" to setOf("twitch.tv")
            else -> error("Unsupported web application goal: $goal")
        }
        fun <T> read(target: HttpUrl, limit: Int, consume: (HttpUrl, String, ByteArray) -> T): T? {
            checkAllowed()
            val call = client.newCall(Request.Builder().url(target).header("Accept-Encoding", "identity")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36").build())
            onCall(call)
            return call.execute().use { response ->
                if (!response.isSuccessful || !allowed(response.request.url, domains)) return@use null
                val body = response.body ?: return@use null
                val output = ByteArrayOutputStream()
                val input = body.byteStream()
                val buffer = ByteArray(8192)
                while (output.size() < limit) {
                    checkAllowed()
                    val count = input.read(buffer, 0, minOf(buffer.size, limit - output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                checkAllowed()
                consume(response.request.url, response.header("Content-Type").orEmpty(), output.toByteArray())
            }
        }
        val bootScripts = read(url.toHttpUrl(), 512 * 1024) { base, type, bytes ->
            if (type.substringBefore(';').trim().lowercase() != "text/html") emptyList()
            else scriptUrls(bytes.toString(Charsets.UTF_8), base, domains)
        } ?: return false
        return bootScripts.any { script -> read(script, 8192) { _, type, bytes -> validScript(type, bytes) } == true }
    }
}
