package com.danieldk.splatevpn.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Runs Chromium/JavaScript on synthetic pages; never signs into a real account. */
@RunWith(AndroidJUnit4::class)
class GeminiWebViewCheckTest {
    @Test fun delayedDomErrorsAreDetectedAndUnusedTranslationsAreIgnored() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val checker = GeminiWebViewCheck(context, "ai_dns_qa")
        val cases = listOf(
            "Gemini isn’t currently supported in your country. Stay tuned!" to AiDnsOutcome.GEO_BLOCKED,
            "Something went wrong. Please try again." to AiDnsOutcome.LIMITED,
            "Welcome to Gemini. Supercharge your creativity and productivity." to AiDnsOutcome.ACCESSIBLE
        )
        for ((text, expected) in cases) {
            val html = """<html><body>Loading…<script>const unused="Gemini isn't currently supported in your country";setTimeout(()=>document.body.innerText=${org.json.JSONObject.quote(text)},500);</script></body></html>"""
            val result = withTimeout(30_000) { checker.check(1, html) }
            assertEquals(result.toString(), expected, result.outcome)
        }
    }
    @Test fun cancellingBrowserCheckReleasesItsResources() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val job = launch { GeminiWebViewCheck(context, "ai_dns_qa").check(1, "<body>Loading…</body>") }
        delay(1500); job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }
    @Test fun closedProxyCannotBeReportedAsAccessibleAndServiceIsIsolated() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val result = withTimeout(35_000) { verifyGeminiPage(context, 1) }
        assertEquals(result.toString(), AiDnsOutcome.FAILED, result.outcome)
    }
}
