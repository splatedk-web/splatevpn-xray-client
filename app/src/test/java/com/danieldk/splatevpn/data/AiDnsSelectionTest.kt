package com.danieldk.splatevpn.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AiDnsSelectionTest {
    @Test fun transientBrowserFailureGetsOneFreshConfirmation() = runBlocking {
        val initial = AiDnsCandidateResult(AiDnsChoice("comss_smart", false), listOf(
            AiDnsTargetResult("Gemini", AiDnsOutcome.ACCESSIBLE)))
        var calls = 0
        val winner = confirmBestAiDns(listOf(initial), "Gemini") {
            calls++
            if (calls == 1) initial.copy(targets = listOf(AiDnsTargetResult("Gemini", AiDnsOutcome.FAILED, detail = AiDnsDetail.JS_FAILED)))
            else initial
        }
        assertEquals(initial, winner)
        assertEquals(2, calls)
    }

    @Test fun repeatedBrowserFailureStopsAfterOneRetry() = runBlocking {
        val initial = AiDnsCandidateResult(AiDnsChoice("comss_smart", false), listOf(AiDnsTargetResult("Gemini", AiDnsOutcome.ACCESSIBLE)))
        var calls = 0
        assertNull(confirmBestAiDns(listOf(initial), "Gemini") {
            calls++
            initial.copy(targets = listOf(AiDnsTargetResult("Gemini", AiDnsOutcome.FAILED, detail = AiDnsDetail.JS_TIMEOUT)))
        })
        assertEquals(2, calls)
    }

    @Test fun explicitRestrictionsAreNotTransientFailures() {
        assertFalse(AiDnsTargetResult("Gemini", AiDnsOutcome.GEO_BLOCKED, detail = AiDnsDetail.JS_GEO).isTransientFailure())
        assertFalse(AiDnsTargetResult("Gemini", AiDnsOutcome.LIMITED, detail = AiDnsDetail.JS_LIMITED).isTransientFailure())
        assertFalse(AiDnsTargetResult("Gemini", AiDnsOutcome.FAILED, detail = AiDnsDetail.DNS_INVALID).isTransientFailure())
        assertTrue(AiDnsTargetResult("Gemini", AiDnsOutcome.FAILED, detail = AiDnsDetail.DNS_TIMEOUT).isTransientFailure())
    }

    @Test fun geminiRedirectIsVerifiedEvenWithoutOtherSuccessfulServices() = runBlocking {
        val initial = AiDnsCandidateResult(AiDnsChoice("malw", false), listOf(
            AiDnsTargetResult("Gemini", AiDnsOutcome.LIMITED, detail = AiDnsDetail.HTTP_REPLY, httpCode = 302)))
        val rendered = initial.copy(targets = listOf(AiDnsTargetResult("Gemini", AiDnsOutcome.ACCESSIBLE, detail = AiDnsDetail.JS_LOADED)))
        assertEquals(rendered, confirmBestAiDns(listOf(initial), "Gemini") { rendered })
    }

    @Test fun unresolvedRedirectIsNeverAutomaticallyApplied() = runBlocking {
        val initial = AiDnsCandidateResult(AiDnsChoice("malw", false), listOf(
            AiDnsTargetResult("Gemini", AiDnsOutcome.LIMITED, detail = AiDnsDetail.HTTP_REPLY, httpCode = 302)))
        assertNull(confirmBestAiDns(listOf(initial), "Gemini") { initial })
    }

    @Test fun workingFourthCandidateIsNotSkipped() = runBlocking {
        val candidates = (1..4).map { index -> AiDnsCandidateResult(AiDnsChoice("provider$index", false), listOf(
            AiDnsTargetResult("Gemini", AiDnsOutcome.ACCESSIBLE, elapsedMs = index.toLong()))) }
        val visited = mutableListOf<AiDnsChoice>()
        val winner = confirmBestAiDns(candidates, "Gemini") { choice ->
            visited += choice
            val candidate = candidates.first { it.choice == choice }
            if (choice == candidates.last().choice) candidate else candidate.copy(targets = listOf(
                AiDnsTargetResult("Gemini", AiDnsOutcome.GEO_BLOCKED, detail = AiDnsDetail.JS_GEO)))
        }
        assertEquals(candidates.last(), winner)
        assertEquals(4, visited.size)
    }

    @Test fun dnsFailureDoesNotBecomeEligibleForBrowserVerification() {
        assertFalse(AiDnsTargetResult("Gemini", AiDnsOutcome.FAILED, detail = AiDnsDetail.DNS_INVALID).canVerifyInBrowser())
        assertFalse(AiDnsTargetResult("Gemini", AiDnsOutcome.GEO_BLOCKED, detail = AiDnsDetail.HTTP_REPLY).canVerifyInBrowser())
        assertTrue(AiDnsTargetResult("Gemini", AiDnsOutcome.LIMITED, detail = AiDnsDetail.HTTP_REPLY, httpCode = 403).canVerifyInBrowser())
    }

    private fun result(key: String, fragment: Boolean = false, good: Int = 1, limited: Int = 2, ms: Long = 100) =
        AiDnsCandidateResult(AiDnsChoice(key, fragment), (0 until 3).map { index ->
            AiDnsTargetResult("service$index", when {
                index < good -> AiDnsOutcome.ACCESSIBLE
                index < good + limited -> AiDnsOutcome.LIMITED
                else -> AiDnsOutcome.FAILED
            }, ms)
        })

    @Test fun geoErrorsTakePrecedenceOverHttpSuccessOrChallenge() {
        assertEquals(AiDnsOutcome.GEO_BLOCKED, classifyAiDnsResponse(200, "Gemini isn’t available in your country"))
        assertEquals(AiDnsOutcome.GEO_BLOCKED, classifyAiDnsResponse(403, "unsupported_country_region_territory"))
        assertEquals(AiDnsOutcome.GEO_BLOCKED, classifyAiDnsResponse(302, "", "/?location=unsupported"))
    }
    @Test fun challengesAndAuthenticationAreNotSuccessfulChecks() {
        assertEquals(AiDnsOutcome.LIMITED, classifyAiDnsResponse(403, "challenge"))
        assertEquals(AiDnsOutcome.LIMITED, classifyAiDnsResponse(429, "rate limit"))
        assertEquals(AiDnsOutcome.LIMITED, classifyAiDnsResponse(200, "<title>Just a moment...</title>"))
        assertEquals(AiDnsOutcome.LIMITED, classifyAiDnsResponse(200, "challenge page", challenge = true))
        assertEquals(AiDnsOutcome.LIMITED, classifyAiDnsResponse(302, "", "https://accounts.google.com/"))
    }
    @Test fun ordinaryPageWithCaptchaLibraryIsNotAChallengePage() {
        assertEquals(AiDnsOutcome.ACCESSIBLE, classifyAiDnsResponse(200, "<html>Gemini<script src='recaptcha.js'></script></html>"))
        assertEquals(AiDnsOutcome.FAILED, classifyAiDnsResponse(200, ""))
        assertEquals(AiDnsOutcome.FAILED, classifyAiDnsResponse(503, "unavailable"))
    }
    @Test fun availabilityOutranksFastFailuresAndFragmentationBreaksTies() {
        val fastLimited = result("dns_ai", good = 0, limited = 3, ms = 1)
        val slowerGood = result("malw", good = 2, limited = 1, ms = 1000)
        val fragmented = result("geohide", fragment = true, good = 2, limited = 1, ms = 10)
        assertEquals(listOf(slowerGood, fragmented, fastLimited), listOf(fastLimited, fragmented, slowerGood).sortedWith(aiDnsRanking))
    }
    @Test fun equalAvailabilityPrefersFewerGeoBlocks() {
        val good = result("malw")
        val geo = result("geohide").let { it.copy(targets = it.targets.dropLast(1) + AiDnsTargetResult("last", AiDnsOutcome.GEO_BLOCKED)) }
        assertEquals(good, listOf(geo, good).sortedWith(aiDnsRanking).first())
    }
    @Test fun unconfirmedWinnerFallsBackToStableCandidate() = runBlocking {
        val initialBest = result("dns_ai", good = 2, limited = 1)
        val stable = result("malw")
        val checked = mutableListOf<String>()
        val winner = confirmBestAiDns(listOf(stable, initialBest)) { choice ->
            checked += choice.providerKey
            if (choice.providerKey == "dns_ai") result("dns_ai", good = 0) else stable
        }
        assertEquals(stable, winner)
        assertEquals(listOf("dns_ai", "malw"), checked)
    }
    @Test fun allLimitedOrFailedNeverAppliesSettings() = runBlocking {
        var verified = false
        val winner = confirmBestAiDns(listOf(result("malw", good = 0, limited = 3), result("dns_ai", good = 0, limited = 0))) {
            verified = true; result(it.providerKey)
        }
        assertNull(winner)
        assertFalse(verified)
    }
    @Test fun confirmationMustNotIntroduceANewGeoBlock() = runBlocking {
        val initial = result("malw")
        val winner = confirmBestAiDns(listOf(initial)) {
            initial.copy(targets = initial.targets.dropLast(1) + AiDnsTargetResult("last", AiDnsOutcome.GEO_BLOCKED))
        }
        assertNull(winner)
    }
    @Test fun cancellationPropagatesWithoutChoosingAWinner() = runBlocking {
        try {
            confirmBestAiDns(listOf(result("malw"))) { throw CancellationException("user stopped") }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }
    @Test fun geminiCurrentCountryMessageIsRecognizedAcrossEncodings() {
        listOf("Gemini isn’t currently supported in your country. Stay tuned!", "Gemini isn&#39;t currently supported in your country.",
            "Gemini isn&rsquo;t currently supported in your country.", "Gemini is not currently supported in your country.",
            "Gemini не поддерживается в вашем регионе").forEach {
            assertEquals(it, AiDnsOutcome.GEO_BLOCKED, classifyAiDnsResponse(200, "<p>$it</p>"))
        }
    }
    @Test fun unusedErrorTranslationsInScriptsAndStylesDoNotRejectHealthyPages() {
        assertEquals(AiDnsOutcome.ACCESSIBLE, classifyAiDnsResponse(200,
            "<html><script>const error='Gemini isn’t currently supported in your country';</script><style>/* something went wrong */</style><p>Welcome to Gemini</p></html>"))
    }
    @Test fun renderedPageErrorsAndSignInRemainUnconfirmed() {
        assertEquals(AiDnsOutcome.LIMITED, classifyAiDnsRendered("Something went wrong", "https://gemini.google.com/app"))
        assertEquals(AiDnsOutcome.LIMITED, classifyAiDnsRendered("Подтвердите, что вы человек", "https://gemini.google.com/app"))
        assertEquals(AiDnsOutcome.LIMITED, classifyAiDnsRendered("Sign in with your Google account", "https://accounts.google.com/"))
        assertEquals(AiDnsOutcome.ACCESSIBLE, classifyAiDnsRendered("Supercharge your creativity and productivity with Gemini", "https://gemini.google.com/app"))
        assertEquals(AiDnsOutcome.ACCESSIBLE, classifyAiDnsRendered("Welcome to Gemini. This site is protected by reCAPTCHA.", "https://gemini.google.com/app"))
    }
    @Test fun confirmationMustKeepTheSameServicesNotJustTheSameCount() = runBlocking {
        val initial = result("malw", good = 1)
        val swapped = initial.copy(targets = listOf(AiDnsTargetResult("service0", AiDnsOutcome.LIMITED), AiDnsTargetResult("service1", AiDnsOutcome.ACCESSIBLE)))
        assertNull(confirmBestAiDns(listOf(initial)) { swapped })
    }
    @Test fun geminiRequirementExcludesCandidatesThatOnlyOpenOtherServices() = runBlocking {
        val candidate = AiDnsCandidateResult(AiDnsChoice("malw", false), listOf(AiDnsTargetResult("Gemini", AiDnsOutcome.GEO_BLOCKED), AiDnsTargetResult("ChatGPT", AiDnsOutcome.ACCESSIBLE)))
        var called = false
        assertNull(confirmBestAiDns(listOf(candidate), requiredService = "Gemini") { called = true; candidate })
        assertFalse(called)
    }
    @Test fun renderedGeoBlockRejectsHttpWinner() = runBlocking {
        val candidate = AiDnsCandidateResult(AiDnsChoice("malw", false), listOf(AiDnsTargetResult("Gemini", AiDnsOutcome.ACCESSIBLE)))
        assertNull(confirmBestAiDns(listOf(candidate), requiredService = "Gemini") {
            candidate.copy(targets = listOf(AiDnsTargetResult("Gemini", AiDnsOutcome.GEO_BLOCKED, detail = AiDnsDetail.JS_GEO)))
        })
    }
}
