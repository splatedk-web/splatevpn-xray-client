package com.danieldk.splatevpn.data

import org.junit.Assert.*
import org.junit.Test

class CandidateServiceParsingTest {
    @Test fun youtubeMobilePlaceholderDoesNotHideTheLaterPlayer() {
        val player = CandidatePerformanceRepository.extractPlayer("""
            <script>var ytInitialPlayerResponse = null;</script>
            <script>var ytInitialPlayerResponse = {"playabilityStatus":{"status":"OK"},
            "streamingData":{"formats":[{"url":"https://media.googlevideo.com/videoplayback?a=1"}]}};</script>
        """.trimIndent())
        assertNotNull(player)
        assertEquals("OK", player!!.getAsJsonObject("playabilityStatus").get("status").asString)
    }

    private fun tiktok(data: String, before: String = "") = (before +
        "<script id=\"__UNIVERSAL_DATA_FOR_REHYDRATION__\" type=\"application/json\">" +
        "{\"__DEFAULT_SCOPE__\":{\"webapp.user-detail\":$data}}</script>").toByteArray()

    @Test fun youtubePlayerExtractionPreservesQuotedBracesAndNestedPlaybackData() {
        val player = CandidatePerformanceRepository.extractPlayer("""
            <script>var ytInitialPlayerResponse = {"playabilityStatus":{"status":"OK"},
            "videoDetails":{"title":"A } bracket and an escaped \" quote"},
            "streamingData":{"formats":[{"url":"https://media.googlevideo.com/videoplayback?a=1"}]}};</script>
        """.trimIndent())
        assertNotNull(player)
        assertEquals("OK", player!!.getAsJsonObject("playabilityStatus").get("status").asString)
        assertEquals("A } bracket and an escaped \" quote", player.getAsJsonObject("videoDetails").get("title").asString)
        assertEquals(1, player.getAsJsonObject("streamingData").getAsJsonArray("formats").size())
    }

    @Test fun youtubeConsentPagesTruncatedJsonAndNonObjectAssignmentsAreNotPlayers() {
        assertNull(CandidatePerformanceRepository.extractPlayer("<h1>Consent required</h1>"))
        assertNull(CandidatePerformanceRepository.extractPlayer("var ytInitialPlayerResponse = {\"playabilityStatus\":"))
        assertNull(CandidatePerformanceRepository.extractPlayer("var ytInitialPlayerResponse = null;"))
        assertNull(CandidatePerformanceRepository.extractPlayer("var ytInitialPlayerResponse = \"unavailable\";"))
        val excessivelyNested = "var ytInitialPlayerResponse = " + "{\"nested\":".repeat(80) + "0" + "}".repeat(80)
        assertNull(CandidatePerformanceRepository.extractPlayer(excessivelyNested))
    }

    @Test fun tiktokRequiresSuccessfulStructuredUserDataRatherThanScaffoldMarkers() {
        assertTrue(CandidatePerformanceRepository.tiktokAccessible(tiktok("""
            {"statusCode":0,"userInfo":{"user":{"id":"12345","uniqueId":"tiktok"}}}
        """.trimIndent())))
        assertFalse(CandidatePerformanceRepository.tiktokAccessible(tiktok("{\"statusCode\":10202,\"userInfo\":null}")))
        assertFalse(CandidatePerformanceRepository.tiktokAccessible(tiktok("{\"statusCode\":0,\"userInfo\":{}}")))
        assertFalse(CandidatePerformanceRepository.tiktokAccessible("__UNIVERSAL_DATA_FOR_REHYDRATION__ webapp.user-detail userInfo".toByteArray()))
    }

    @Test fun tiktokChallengesAndInvalidJsonFailClosedButAProfileCanMentionCaptcha() {
        val user = "{\"statusCode\":0,\"userInfo\":{\"user\":{\"id\":\"12345\",\"signature\":\"I study captcha systems\"}}}"
        assertTrue(CandidatePerformanceRepository.tiktokAccessible(tiktok(user)))
        assertFalse(CandidatePerformanceRepository.tiktokAccessible(tiktok(user, "<div class=\"verify-center\">Verify</div>")))
        assertFalse(CandidatePerformanceRepository.tiktokAccessible(tiktok("{broken JSON")))
        assertFalse(CandidatePerformanceRepository.tiktokAccessible(null))
    }
}
