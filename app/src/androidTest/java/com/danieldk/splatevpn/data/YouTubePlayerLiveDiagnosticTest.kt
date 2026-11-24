package com.danieldk.splatevpn.data

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.vpn.YouTubeMediaProbe
import com.danieldk.splatevpn.vpn.LocalProxyAccess
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Opt-in check against the mobile page; never treats HTML success as playable media. */
class YouTubePlayerLiveDiagnosticTest {
    @Test fun capturedMobilePageExposesTheActualPlayer() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("run_captured_youtube") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val html = java.io.File(context.cacheDir, "forkprobe-mobile-watch.html").readText()
        assertTrue(Regex("ytInitialPlayerResponse\\s*=\\s*null").containsMatchIn(html))
        assertNotNull(CandidatePerformanceRepository.extractPlayer(html))
        assertNotNull(YouTubeMediaProbe.mediaUrl(html))
    }

    @Test fun mobileWatchPageExposesTheActualPlayer() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("run_live_youtube") == "true")
        val base = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build()
        val port = InstrumentationRegistry.getArguments().getString("youtube_proxy_port")?.toInt()
        val client = if (port == null) base else LocalProxyAccess.socksClient(
            base, port, credentials = null, requireRunningVpn = false
        )
        try {
            val request = Request.Builder().url("https://www.youtube.com/watch?v=aqz-KE-bpKQ&hl=en")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36")
                .build()
            val html = client.newCall(request).execute().use { response ->
                assertEquals(200, response.code)
                requireNotNull(response.body).byteStream().use { input ->
                    input.readBytesAtMost(1024 * 1024).toString(Charsets.UTF_8)
                }
            }
            val player = CandidatePerformanceRepository.extractPlayer(html)
            assertNotNull("Live mobile page must expose a player object", player)
            assertEquals("OK", player!!.getAsJsonObject("playabilityStatus").get("status").asString)
            assertNotNull("Player must contain a supported media URL", YouTubeMediaProbe.mediaUrl(html))
            Log.i("YouTubeLiveProbe", "PASS: actual mobile player and media URL parsed; placeholder=" +
                Regex("ytInitialPlayerResponse\\s*=\\s*null").containsMatchIn(html))
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private fun java.io.InputStream.readBytesAtMost(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() < limit) {
            val count = read(buffer, 0, minOf(buffer.size, limit - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
