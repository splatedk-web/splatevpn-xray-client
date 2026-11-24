package com.danieldk.splatevpn.data

import org.junit.Assert.*
import org.junit.Test

class AiDnsVpnPolicyTest {
    @Test fun vpnMonitoringRequiresAConnectedServerAndCancelsOnModeChanges() {
        val config = AiDnsMonitorConfig(true, true, false, AiDnsChoice("dns_ai", false), "wifi",
            serverId = "connected-node", viaVpn = true,
            vpnRoute = AiDnsVpnRoute(ServerNode("connected-node", "test", "vless", "vpn.example", 443)))
        assertTrue(config.shouldRun)
        assertFalse(config.copy(serverId = null).shouldRun)
        assertFalse(config.copy(vpnRoute = null).shouldRun)
        assertFalse(config.copy(serverId = "another-node").shouldRun)
        assertFalse(config.copy(connected = false).shouldRun)
        assertFalse(config.copy(viaVpn = false).shouldRun)
    }

    @Test fun vpnCandidatesDoNotRepeatAnInapplicableDirectFragmentationChoice() {
        val candidates = aiDnsCandidates(viaVpn = true)
        assertEquals(SmartDnsProviders.all.size, candidates.size)
        assertTrue(candidates.none { it.tlsFragment })
        assertEquals(SmartDnsProviders.all.size * 2, aiDnsCandidates(viaVpn = false).size)
    }

    @Test fun genericSuccessAndScriptOnlyBrandingDoNotVerifyAnAiService() {
        assertEquals(AiDnsOutcome.LIMITED, classifyAiDnsServiceResponse("ChatGPT", 200, "<h1>Proxy is running</h1>"))
        assertEquals(AiDnsOutcome.LIMITED, classifyAiDnsServiceResponse("Gemini", 200,
            "<script>const name='Gemini';</script><h1>Upstream maintenance</h1>"))
        assertEquals(AiDnsOutcome.ACCESSIBLE, classifyAiDnsServiceResponse("ChatGPT", 200, "<title>ChatGPT</title><h1>Welcome</h1>"))
        assertEquals(AiDnsOutcome.GEO_BLOCKED, classifyAiDnsServiceResponse("Gemini", 200, "Gemini isn't available in your country"))
        assertEquals(AiDnsOutcome.LIMITED, classifyAiDnsRendered("Proxy is running and accepting connections", "https://gemini.google.com/app"))
    }
}
