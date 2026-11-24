package com.danieldk.splatevpn.data

import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class DnsHealthIssue(
    val failedDnsKey: String,
    val failedDnsLabel: String,
    val isChecking: Boolean = false,
    val candidate: DnsProviderEndpoint? = null,
    val candidateLatencyMs: Long? = null,
    val allFailed: Boolean = false,
    val isFakeDnsOff: Boolean = true
)

@Singleton
class DnsHealthAdvisor @Inject constructor(
    private val tunnelHealthAdvisor: TunnelHealthAdvisor
) {
    val issue: StateFlow<DnsHealthIssue?> = tunnelHealthAdvisor.dnsIssue

    fun reportDnsError(rawLine: String) {
        tunnelHealthAdvisor.inspectXrayLog(rawLine)
    }

    fun applyCandidate(): Boolean = tunnelHealthAdvisor.applyCandidate()

    fun applyFakeDns(): Boolean = tunnelHealthAdvisor.applyFakeDns()

    fun dismiss() {
        tunnelHealthAdvisor.dismiss()
    }
}
