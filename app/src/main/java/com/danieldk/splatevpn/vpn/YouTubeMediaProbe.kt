package com.danieldk.splatevpn.vpn

import com.danieldk.splatevpn.data.CandidatePerformanceRepository
import com.danieldk.splatevpn.data.awaitConsumedResponse
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Call
import java.io.ByteArrayOutputStream

/** Bounded playback check; thumbnails, HTML error pages and HTTP 204 never count as media. */
internal enum class YouTubeProbeResult { PLAYABLE, PAGE_UNAVAILABLE, PLAYER_UNAVAILABLE, MEDIA_UNAVAILABLE }

internal object YouTubeMediaProbe {
    private const val WATCH_URL = "https://www.youtube.com/watch?v=aqz-KE-bpKQ&hl=en"
    internal const val SAMPLE_BYTES = 256 * 1024
    internal const val MIN_BYTES = 64 * 1024

    internal fun mediaUrl(html: String): HttpUrl? {
        val player = CandidatePerformanceRepository.extractPlayer(html) ?: return null
        return runCatching {
            if (player.getAsJsonObject("playabilityStatus")?.get("status")?.asString != "OK") return null
            val streams = player.getAsJsonObject("streamingData") ?: return null
            listOf("formats", "adaptiveFormats").asSequence()
                .flatMap { streams.getAsJsonArray(it)?.asSequence() ?: emptySequence() }
                .mapNotNull { it.asJsonObject.get("url")?.asString?.toHttpUrlOrNull() }
                .firstOrNull(::isMediaUrl)
        }.getOrNull()
    }

    private fun isMediaUrl(url: HttpUrl): Boolean = url.isHttps && url.port == 443 &&
        url.username.isEmpty() && url.password.isEmpty() && url.host.endsWith(".googlevideo.com") &&
        url.encodedPath == "/videoplayback"

    internal fun readMedia(response: Response): Boolean {
        if (response.code != 200 && response.code != 206) return false
        val type = response.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase()
        if (!type.startsWith("video/") && !type.startsWith("audio/") && type != "application/octet-stream") return false
        val input = response.body?.byteStream() ?: return false
        val buffer = ByteArray(8192)
        var received = 0
        while (received < SAMPLE_BYTES) {
            val count = input.read(buffer, 0, minOf(buffer.size, SAMPLE_BYTES - received))
            if (count < 0) break
            received += count
        }
        return received >= MIN_BYTES
    }

    suspend fun check(client: OkHttpClient, onCall: (Call) -> Unit = {}, checkAllowed: () -> Unit = {}): Boolean =
        probe(client, onCall, checkAllowed) == YouTubeProbeResult.PLAYABLE

    suspend fun probe(client: OkHttpClient, onCall: (Call) -> Unit = {}, checkAllowed: () -> Unit = {}): YouTubeProbeResult {
        fun request(url: HttpUrl) = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36")
            .header("Accept-Encoding", "identity").header("Cache-Control", "no-cache")
        checkAllowed()
        val html = client.newCall(request(WATCH_URL.toHttpUrlOrNull()!!).build()).also(onCall).awaitConsumedResponse { response ->
            if (response.code != 200) null else response.body?.byteStream()?.let {
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() < 1024 * 1024) {
                    checkAllowed()
                    val count = it.read(buffer, 0, minOf(buffer.size, 1024 * 1024 - output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }
        } ?: return YouTubeProbeResult.PAGE_UNAVAILABLE
        var media = mediaUrl(html)?.newBuilder()?.setQueryParameter("range", "0-${SAMPLE_BYTES - 1}")?.build()
            ?: return YouTubeProbeResult.PLAYER_UNAVAILABLE
        // CDN redirects are common; never follow them to an arbitrary host or downgrade TLS.
        repeat(4) {
            checkAllowed()
            val (downloaded, location) = client.newCall(request(media).header("Range", "bytes=0-${SAMPLE_BYTES - 1}").build())
                .also(onCall)
                .awaitConsumedResponse { response ->
                    readMedia(response) to if (response.code in listOf(301, 302, 303, 307, 308)) response.header("Location") else null
                }
            if (downloaded) return YouTubeProbeResult.PLAYABLE
            media = location?.let(media::resolve)?.takeIf(::isMediaUrl) ?: return YouTubeProbeResult.MEDIA_UNAVAILABLE
        }
        return YouTubeProbeResult.MEDIA_UNAVAILABLE
    }
}
