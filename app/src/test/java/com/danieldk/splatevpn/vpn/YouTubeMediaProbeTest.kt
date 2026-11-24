package com.danieldk.splatevpn.vpn

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient

class YouTubeMediaProbeTest {
    @Test fun unavailablePlayerIsDistinctFromBlockedMediaAndStopsBeforeMediaRequest() = runBlocking {
        var calls = 0
        val blockedPlayer = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Type", "text/html").body("<html>Sign in to continue</html>".toResponseBody()).build()
        }.build()
        assertEquals(YouTubeProbeResult.PLAYER_UNAVAILABLE, YouTubeMediaProbe.probe(blockedPlayer))
        assertEquals(1, calls)
        val blockedMedia = OkHttpClient.Builder().addInterceptor { chain ->
            val body = if (chain.request().url.host == "www.youtube.com") player("https://r1.googlevideo.com/videoplayback?a=1")
                else "<html>Media denied</html>".repeat(5000)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Type", "text/html").body(body.toResponseBody()).build()
        }.build()
        assertEquals(YouTubeProbeResult.MEDIA_UNAVAILABLE, YouTubeMediaProbe.probe(blockedMedia))
    }
    private fun player(url: String, status: String = "OK") =
        "var ytInitialPlayerResponse = {\"playabilityStatus\":{\"status\":\"$status\"},\"streamingData\":{\"formats\":[{\"url\":\"$url\"}]}};"

    @Test fun onlyPlayableGoogleVideoStreamsAreAccepted() {
        assertNotNull(YouTubeMediaProbe.mediaUrl(player("https://r1.googlevideo.com/videoplayback?a=1")))
        listOf("http://r1.googlevideo.com/videoplayback", "https://googlevideo.com.evil.test/videoplayback",
            "https://user:pass@r1.googlevideo.com/videoplayback", "https://r1.googlevideo.com/generate_204").forEach {
            assertNull(YouTubeMediaProbe.mediaUrl(player(it)))
        }
        assertNull(YouTubeMediaProbe.mediaUrl(player("https://r1.googlevideo.com/videoplayback", "LOGIN_REQUIRED")))
        assertNull(YouTubeMediaProbe.mediaUrl("<html>consent or captcha</html>"))
    }

    private fun response(code: Int, type: String, bytes: Int) = Response.Builder()
        .request(Request.Builder().url("https://r1.googlevideo.com/videoplayback").build())
        .protocol(Protocol.HTTP_1_1).code(code).message("test")
        .header("Content-Type", type)
        .body(ByteArray(bytes).toResponseBody(type.toMediaType())).build()

    @Test fun thumbnailSuccessAndSmallOrErrorBodiesCannotPassPlaybackCheck() {
        response(204, "video/mp4", 0).use { assertFalse(YouTubeMediaProbe.readMedia(it)) }
        response(200, "text/html", 100_000).use { assertFalse(YouTubeMediaProbe.readMedia(it)) }
        response(200, "image/jpeg", 100_000).use { assertFalse(YouTubeMediaProbe.readMedia(it)) }
        response(403, "video/mp4", 100_000).use { assertFalse(YouTubeMediaProbe.readMedia(it)) }
        response(206, "video/mp4", 1024).use { assertFalse(YouTubeMediaProbe.readMedia(it)) }
        response(206, "video/mp4", YouTubeMediaProbe.MIN_BYTES).use { assertTrue(YouTubeMediaProbe.readMedia(it)) }
    }

}
