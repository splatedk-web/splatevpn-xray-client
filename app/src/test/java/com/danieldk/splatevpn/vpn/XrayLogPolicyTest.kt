package com.danieldk.splatevpn.vpn

import org.junit.Assert.*
import org.junit.Test

class XrayLogPolicyTest {
    @Test fun negativeDnsAnswersAreQuietButResolverFailuresRemainVisible() {
        val policy = XrayLogPolicy()
        assertNull(policy.select("[Info] app/dns: failed to lookup ip for domain a.example > rcode: 3"))
        assertNull(policy.select("[Info] app/proxyman/outbound: failed to resolve ip for target a.example > features/dns: empty response"))
        assertNull(policy.select("[Info] proxy/dns: rejected type TypeHTTPS query for domain a.example."))
        assertNotNull(policy.select("[Error] app/dns: failed to retrieve response > closed pipe"))
        assertNotNull(policy.select("[Info] app/dns: failed to lookup ip for domain a.example > rcode: 2"))
        assertNotNull(policy.select("[Info] transport/internet/udp: failed to handle UDP input > io: read/write on closed pipe"))
    }
    @Test fun routinePacketAndDnsTracingDoesNotFloodTheUi() {
        val policy = XrayLogPolicy()
        listOf("proxy/socks: client UDP connection", "app/dispatcher: taking detour [dns-out]",
            "proxy/dns: handling DNS traffic", "transport/internet/splithttp: XHTTP is dialing",
            "proxy/dns: question > unpacking Question.Name: segment prefix is reserved").forEach {
            assertNull(policy.select("2026/10/23 09:24:08.123 [Info] [1234] $it"))
        }
    }
    @Test fun infoLevelTransportFailuresAndWarningsRemainVisible() {
        val policy = XrayLogPolicy()
        listOf("[Info] failed to GET https://max.ru/ > connection refused",
            "[Info] REALITY handshake failed", "[Error] DNS error", "[Warning] core started").forEach {
            assertEquals(it, policy.select(it))
        }
    }
    @Test fun repeatedFailuresAreSampledWithoutLosingTheirCountOrNewErrors() {
        var now = 0L
        val policy = XrayLogPolicy { now }
        val first = "2026/10/23 09:24:08.123 [Info] [123] failed to GET > timeout"
        val second = "2026/10/23 09:24:09.456 [Info] [456] failed to GET > timeout"
        assertEquals(first, policy.select(first))
        repeat(100) { assertNull(policy.select(second)) }
        assertNotNull(policy.select("[Info] REALITY handshake failed"))
        now = 5000
        assertTrue(requireNotNull(policy.select(second)).contains("suppressed 100"))
    }
    @Test fun dnsNegativeAnswersAreQuietAndRepeatedUpstreamErrorsAreGrouped() {
        val policy = XrayLogPolicy()
        assertNull(policy.select("[Info] app/dns: failed to lookup ip for domain a.example > record not found"))
        assertNotNull(policy.select("[Error] app/dns: failed to retrieve response for a.example. > connection reset"))
        assertNull(policy.select("[Error] app/dns: failed to retrieve response for b.example. > connection reset"))
        assertNotNull(policy.select("[Error] app/dns: failed to retrieve response for b.example. > connection refused"))
    }
    @Test fun xhttpSessionIdsAndEphemeralPortsDoNotDefeatErrorThrottling() {
        val policy = XrayLogPolicy()
        val template = "[Info] failed to GET https://max.ru/ > Get https://max.ru/%s: read tcp 10.0.0.1:%d->192.0.2.1:443: connection reset by peer"
        assertNotNull(policy.select(template.format("11111111-2222-3333-4444-555555555555", 12345)))
        assertNull(policy.select(template.format("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", 54321)))
    }
}
