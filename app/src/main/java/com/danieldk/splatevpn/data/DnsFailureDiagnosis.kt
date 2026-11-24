package com.danieldk.splatevpn.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Compare resolvers through the same VPN; ordinary HTTP may depend on the failed resolver. */
internal suspend fun confirmDnsFailure(
    selected: DnsProviderEndpoint,
    candidates: List<DnsProviderEndpoint>,
    current: () -> Boolean,
    check: suspend (DnsProviderEndpoint) -> DnsProviderCheckResult?
): DnsProviderCheckResultWithEndpoint? {
    suspend fun probe(endpoint: DnsProviderEndpoint): DnsProviderCheckResult? {
        currentCoroutineContext().ensureActive()
        if (!current()) return null
        val result = check(endpoint)
        return result.takeIf { current() }
    }
    if (probe(selected)?.status != DnsProviderCheckStatus.FAILED) return null
    for (candidate in candidates.distinctBy { it.key }.filter { it.key != selected.key }) {
        val first = probe(candidate) ?: return null
        if (first.status != DnsProviderCheckStatus.AVAILABLE) continue
        if (probe(selected)?.status != DnsProviderCheckStatus.FAILED) return null
        val confirmed = probe(candidate) ?: return null
        if (confirmed.status == DnsProviderCheckStatus.AVAILABLE) {
            return DnsProviderCheckResultWithEndpoint(candidate, confirmed.latencyMs)
        }
    }
    return null
}

internal data class DnsProviderCheckResultWithEndpoint(val endpoint: DnsProviderEndpoint, val latencyMs: Long?)
