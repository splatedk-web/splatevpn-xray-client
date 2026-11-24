package com.danieldk.splatevpn.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DnsFailureDiagnosisTest {
    private val selected = DnsProviderEndpoint("malw_gateway", "MALW · CF", "https://resolver.test/dns-query")
    private val google = DnsProviderCatalog.standard.first { it.key == "google" }
    private fun result(available: Boolean) = DnsProviderCheckResult(
        if (available) DnsProviderCheckStatus.AVAILABLE else DnsProviderCheckStatus.FAILED, if (available) 50 else null)

    @Test fun dnsFailureIsConfirmedWithoutRequiringAnHttpRequestOrDisablingFakeDns() = runBlocking {
        val calls = mutableListOf<String>()
        val found = confirmDnsFailure(selected, listOf(google), { true }) {
            calls += it.key
            result(it.key == google.key)
        }
        assertEquals(google, found?.endpoint)
        assertEquals(listOf(selected.key, google.key, selected.key, google.key), calls)
    }

    @Test fun anOutageOfTheWholeTunnelDoesNotBlameDnsOrSuggestSwitchingProvider() = runBlocking {
        assertNull(confirmDnsFailure(selected, DnsProviderCatalog.standard, { true }) { result(false) })
    }

    @Test fun transientFailuresAndCandidatesThatStopRespondingAreNotConfirmed() = runBlocking {
        for (samples in listOf(listOf(true), listOf(false, true, true), listOf(false, true, false, false))) {
            val queue = ArrayDeque(samples)
            assertNull(confirmDnsFailure(selected, listOf(google), { true }) { result(queue.removeFirst()) })
        }
    }

    @Test fun changedConnectionOrDnsDiscardsTheComparison() = runBlocking {
        var current = true
        var calls = 0
        assertNull(confirmDnsFailure(selected, listOf(google), { current }) {
            calls++
            if (calls == 2) current = false
            result(it.key == google.key)
        })
        assertEquals(2, calls)
    }

    @Test fun networkPermissionWithdrawalAndCancellationDoNotCountAsFailures() = runBlocking {
        assertNull(confirmDnsFailure(selected, listOf(google), { true }) { null })
        try {
            confirmDnsFailure(selected, listOf(google), { true }) { throw CancellationException() }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }

    @Test fun duplicateCandidatesAndTheSelectedProviderAreNotRetestedAsAlternatives() = runBlocking {
        val calls = mutableListOf<String>()
        assertNull(confirmDnsFailure(selected, listOf(selected, google, google), { true }) {
            calls += it.key
            result(false)
        })
        assertEquals(listOf(selected.key, google.key), calls)
    }
}
