package com.danieldk.splatevpn.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withTimeoutOrNull

internal const val AI_DNS_CHECK_INTERVAL_MS = 5 * 60_000L
internal const val AI_DNS_CONFIRM_DELAY_MS = 15_000L

/** No browser/JavaScript in background: an HTTP challenge is inconclusive, not a dead DNS. */
internal fun AiDnsTargetResult.needsFailover() = outcome == AiDnsOutcome.FAILED || outcome == AiDnsOutcome.GEO_BLOCKED

private fun improvesAiDns(current: AiDnsCandidateResult, candidate: AiDnsCandidateResult, failed: Set<String>): Boolean {
    val replacement = candidate.targets.associateBy { it.service }
    return current.targets.isNotEmpty() && current.targets.all { target ->
        val next = replacement[target.service] ?: return@all false
        when (target.outcome) {
            AiDnsOutcome.ACCESSIBLE -> next.outcome == AiDnsOutcome.ACCESSIBLE
            AiDnsOutcome.LIMITED -> next.outcome == AiDnsOutcome.ACCESSIBLE || next.outcome == AiDnsOutcome.LIMITED
            else -> true
        }
    } && failed.any { replacement[it]?.outcome == AiDnsOutcome.ACCESSIBLE }
}

/** Two failures, two successful replacement probes, then one last check of the old route. */
internal suspend fun findAiDnsReplacement(
    choice: AiDnsChoice,
    candidates: List<AiDnsChoice> = SmartDnsProviders.all.flatMap { listOf(AiDnsChoice(it.key, false), AiDnsChoice(it.key, true)) },
    confirmDelay: suspend () -> Unit = { delay(AI_DNS_CONFIRM_DELAY_MS) },
    probe: suspend (AiDnsChoice) -> AiDnsCandidateResult
): AiDnsCandidateResult? {
    val first = probe(choice)
    if (first.targets.none { it.needsFailover() }) return null
    confirmDelay()
    val second = probe(choice)
    val failed = first.targets.filter { it.needsFailover() }.map { it.service }.toSet()
        .intersect(second.targets.filter { it.needsFailover() }.map { it.service }.toSet())
    if (failed.isEmpty()) return null
    // Both observations protect working services, including a service that just recovered.
    val baseline = AiDnsCandidateResult(choice, (first.targets + second.targets).groupBy { it.service }.map { (_, samples) ->
        samples.firstOrNull { it.outcome == AiDnsOutcome.ACCESSIBLE }
            ?: samples.firstOrNull { it.outcome == AiDnsOutcome.LIMITED } ?: samples.last()
    })
    for (candidate in candidates.distinct().filter { it != choice }.sortedBy { it.tlsFragment != choice.tlsFragment }) {
        currentCoroutineContext().ensureActive()
        val tested = probe(candidate)
        if (tested.choice != candidate || !improvesAiDns(baseline, tested, failed)) continue
        confirmDelay()
        val confirmed = probe(candidate)
        if (confirmed.choice != candidate || !improvesAiDns(baseline, confirmed, failed)) continue
        val recovered = failed.filter { service ->
            tested.targets.any { it.service == service && it.outcome == AiDnsOutcome.ACCESSIBLE } &&
                confirmed.targets.any { it.service == service && it.outcome == AiDnsOutcome.ACCESSIBLE }
        }.toSet()
        if (recovered.isEmpty()) continue
        val latest = probe(choice)
        if (latest.targets.none { it.service in recovered && it.needsFailover() }) return null
        if (improvesAiDns(latest, confirmed, recovered)) return confirmed
    }
    return null
}

internal data class AiDnsMonitorConfig(
    val connected: Boolean,
    val enabled: Boolean,
    val directSmartDns: Boolean,
    val choice: AiDnsChoice,
    val network: String?,
    val serverId: String? = null,
    val viaVpn: Boolean = false,
    val vpnRoute: AiDnsVpnRoute? = null
) {
    val shouldRun get() = connected && enabled && (directSmartDns || viaVpn) && network != null &&
        (!viaVpn || !serverId.isNullOrBlank() && vpnRoute?.node?.id == serverId)
}

/** collectLatest cancels probes and the timer on disconnect, mode/provider edits or network changes. */
internal suspend fun monitorAiDns(
    states: Flow<AiDnsMonitorConfig>,
    check: suspend (AiDnsMonitorConfig) -> AiDnsCandidateResult?,
    apply: suspend (AiDnsMonitorConfig, AiDnsCandidateResult) -> Unit,
    wait: suspend (Long) -> Unit = { delay(it) }
) {
    states.collectLatest { config ->
        if (!config.shouldRun) return@collectLatest
        var initial = true
        while (true) {
            wait(if (initial) AI_DNS_CONFIRM_DELAY_MS else AI_DNS_CHECK_INTERVAL_MS)
            initial = false
            currentCoroutineContext().ensureActive()
            val replacement = withTimeoutOrNull(180_000) { check(config) }
            currentCoroutineContext().ensureActive()
            if (replacement != null) apply(config, replacement)
        }
    }
}
