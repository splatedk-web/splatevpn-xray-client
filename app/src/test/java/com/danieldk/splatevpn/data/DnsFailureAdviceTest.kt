package com.danieldk.splatevpn.data

import org.junit.Assert.*
import org.junit.Test

class DnsFailureAdviceTest {
    @Test fun enabledFakeDnsIsNeverOfferedAgain() {
        for (ru in listOf(false, true)) {
            val issue = dnsFailureAdvice(DnsHealthIssue("google", "Google", allFailed = true, isFakeDnsOff = false), ru)
            assertNull(issue.primaryActionId)
            assertNull(issue.secondaryActionId)
        }
    }

    @Test fun disabledFakeDnsAndWorkingCandidateHaveAppropriateActions() {
        val failed = DnsHealthIssue("google", "Google", allFailed = true)
        assertEquals("ENABLE_FAKEDNS", dnsFailureAdvice(failed, true).primaryActionId)
        val candidate = DnsProviderEndpoint("quad9", "Quad9", "https://9.9.9.9/dns-query")
        val found = dnsFailureAdvice(failed.copy(candidate = candidate, allFailed = false, isFakeDnsOff = false), false)
        assertEquals("APPLY_DNS", found.primaryActionId)
        assertEquals("quad9", found.primaryActionExtra)
        assertNull(found.secondaryActionId)
    }
}
