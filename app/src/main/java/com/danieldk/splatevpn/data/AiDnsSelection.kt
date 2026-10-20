package com.danieldk.splatevpn.data

import java.util.Locale

data class AiDnsChoice(val providerKey: String, val tlsFragment: Boolean)
enum class AiDnsOutcome { ACCESSIBLE, LIMITED, GEO_BLOCKED, FAILED }
enum class AiDnsDetail { DNS_HTTP, DNS_CONNECTION, DNS_TIMEOUT, DNS_INVALID, DNS_CONTENT, CONNECTION, CONNECTION_TIMEOUT, TLS_FAILURE, HTTP_REPLY, JS_LOADED, JS_LIMITED, JS_GEO, JS_TIMEOUT, JS_UNAVAILABLE, JS_FAILED }
data class AiDnsTargetResult(val service: String, val outcome: AiDnsOutcome, val elapsedMs: Long = 0,
    val detail: AiDnsDetail? = null, val httpCode: Int? = null)
data class AiDnsCandidateResult(val choice: AiDnsChoice, val targets: List<AiDnsTargetResult>) {
    val accessible get() = targets.count { it.outcome == AiDnsOutcome.ACCESSIBLE }
    val limited get() = targets.count { it.outcome == AiDnsOutcome.LIMITED }
    val geoBlocked get() = targets.count { it.outcome == AiDnsOutcome.GEO_BLOCKED }
    val medianMs: Long get() = targets.filter { it.outcome != AiDnsOutcome.FAILED }.map { it.elapsedMs }.sorted()
        .let { if (it.isEmpty()) Long.MAX_VALUE else it[it.size / 2] }
}
enum class AiDnsSelectionPhase { IDLE, TESTING, VERIFYING, APPLIED, NO_MATCH, CANCELLED, ERROR }
data class AiDnsSelectionState(
    val phase: AiDnsSelectionPhase = AiDnsSelectionPhase.IDLE,
    val completed: Int = 0,
    val total: Int = SmartDnsProviders.all.size * 2,
    val current: AiDnsChoice? = null,
    val results: List<AiDnsCandidateResult> = emptyList(),
    val winner: AiDnsCandidateResult? = null,
    val error: String? = null
) {
    val running get() = phase == AiDnsSelectionPhase.TESTING || phase == AiDnsSelectionPhase.VERIFYING
}

internal fun classifyAiDnsResponse(code: Int, body: String, location: String? = null, challenge: Boolean = false): AiDnsOutcome {
    val visible = body.replace(Regex("<(script|style)\\b[^>]*>.*?</\\1\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
    val content = normalizeAiPageText(visible + "\n" + location.orEmpty())
    if (listOf("location=unsupported", "unsupported_country", "country_not_supported", "region_not_supported",
            "not available in your country", "isn't available in your country", "isn’t available in your country",
            "isn't currently supported in your country", "is not currently supported in your country",
            "not supported in your country", "not available in your region", "недоступен в вашей стране", "недоступно в вашей стране",
            "не поддерживается в вашей стране", "не поддерживается в вашем регионе").any { it in content }) {
        return AiDnsOutcome.GEO_BLOCKED
    }
    if (challenge || code in listOf(401, 403, 429) || listOf("just a moment", "attention required!", "our systems have detected unusual traffic", "/sorry/index").any { it in content }) {
        return AiDnsOutcome.LIMITED
    }
    // A sign-in/consent redirect proves reachability, not availability of the service.
    if (code in 300..399) return AiDnsOutcome.LIMITED
    return if (code in 200..299 && body.isNotBlank()) AiDnsOutcome.ACCESSIBLE else AiDnsOutcome.FAILED
}

internal fun normalizeAiPageText(value: String): String = value.replace(Regex("<[^>]+>"), " ")
    .replace(Regex("&#(x[0-9a-f]+|[0-9]+);?", RegexOption.IGNORE_CASE)) { match ->
        val token = match.groupValues[1]
        val code = if (token.startsWith("x", true)) token.drop(1).toIntOrNull(16) else token.toIntOrNull()
        code?.takeIf { it in 1..0x10ffff && it !in 0xd800..0xdfff }?.let { String(Character.toChars(it)) } ?: match.value
    }.replace("&rsquo;", "'").replace("&lsquo;", "'").replace("&apos;", "'").replace("&quot;", "\"")
    .replace("&nbsp;", " ").replace("&amp;", "&").replace('’', '\'').replace('‘', '\'').replace('ʼ', '\'')
    .replace("\\u2019", "'").replace("\\u0027", "'").replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

/** Only rendered DOM text belongs here; script translation bundles contain unused errors. */
internal fun classifyAiDnsRendered(text: String, location: String): AiDnsOutcome {
    val base = classifyAiDnsResponse(200, text, location)
    if (base != AiDnsOutcome.ACCESSIBLE) return base
    val content = normalizeAiPageText(text)
    val host = runCatching { java.net.URI(location).host }.getOrNull()
    if (host in setOf("accounts.google.com", "consent.google.com") || listOf("something went wrong", "что-то пошло не так",
            "что то пошло не так", "can't access this service", "cannot access this service", "не удалось получить доступ",
            "verify you are human", "подтвердите, что вы человек", "sign in to continue").any { it in content } ||
        Regex("\\bcaptcha\\b").containsMatchIn(content)) return AiDnsOutcome.LIMITED
    return base
}

internal val aiDnsRanking = compareByDescending<AiDnsCandidateResult> { it.accessible }
    .thenBy { it.geoBlocked }.thenByDescending { it.limited }
    .thenBy { it.choice.tlsFragment }.thenBy { it.medianMs }

/** A redirect/challenge needs a browser check; it is not evidence that DNS failed. */
internal fun AiDnsTargetResult.canVerifyInBrowser() = outcome == AiDnsOutcome.ACCESSIBLE ||
    (outcome == AiDnsOutcome.LIMITED && detail == AiDnsDetail.HTTP_REPLY)

internal fun AiDnsTargetResult.isTransientFailure() = outcome == AiDnsOutcome.FAILED && detail in setOf(
    AiDnsDetail.DNS_CONNECTION, AiDnsDetail.DNS_TIMEOUT, AiDnsDetail.CONNECTION,
    AiDnsDetail.CONNECTION_TIMEOUT, AiDnsDetail.TLS_FAILURE, AiDnsDetail.JS_TIMEOUT, AiDnsDetail.JS_FAILED)

/** Never apply a fast challenge page or an unconfirmed one-off success. */
internal suspend fun confirmBestAiDns(
    results: List<AiDnsCandidateResult>,
    requiredService: String? = null,
    verify: suspend (AiDnsChoice) -> AiDnsCandidateResult
): AiDnsCandidateResult? {
    val eligible = results.filter { candidate ->
        if (requiredService == null) candidate.accessible > 0
        else candidate.targets.any { it.service == requiredService && it.canVerifyInBrowser() }
    }
    for (candidate in eligible.sortedWith(aiDnsRanking)) {
        var confirmed = verify(candidate.choice)
        // A one-off transport/browser failure must not discard a working finalist.
        // Never retry an explicit geo block, challenge, invalid DNS or unsupported WebView.
        if (confirmed.choice == candidate.choice && confirmed.targets.any { target ->
                (target.service == requiredService || candidate.targets.any { it.service == target.service && it.outcome == AiDnsOutcome.ACCESSIBLE }) &&
                    target.isTransientFailure()
            }) confirmed = verify(candidate.choice)
        val requiredConfirmed = requiredService == null || confirmed.targets.any {
            it.service == requiredService && it.outcome == AiDnsOutcome.ACCESSIBLE
        }
        if (requiredConfirmed && confirmed.choice == candidate.choice && candidate.targets.filter { it.outcome == AiDnsOutcome.ACCESSIBLE }.all { initial ->
                confirmed.targets.any { it.service == initial.service && it.outcome == AiDnsOutcome.ACCESSIBLE }
            } && confirmed.geoBlocked <= candidate.geoBlocked) return confirmed
    }
    return null
}
