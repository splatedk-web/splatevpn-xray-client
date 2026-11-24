package com.danieldk.splatevpn.data

import org.junit.Assert.*
import org.junit.Test
import java.net.URI

class SmartDnsProvidersTest {
    @Test fun presetsHaveUniqueKeysAndRealBootstrapAddresses() {
        assertEquals(SmartDnsProviders.all.size, SmartDnsProviders.all.map { it.key }.toSet().size)
        SmartDnsProviders.all.forEach { provider ->
            val uri = URI(provider.dohUrl)
            assertEquals("https", uri.scheme)
            assertEquals(provider.host, uri.host)
            assertEquals("/dns-query", uri.path)
            assertTrue(provider.bootstrapIpv4.isNotEmpty())
            provider.bootstrapIpv4.forEach { ip ->
                val parts = ip.split('.').map { it.toInt() }
                assertEquals(4, parts.size)
                assertTrue(parts.all { it in 0..255 })
                assertFalse("Do not pin FakeDNS replies", parts[0] == 198 && parts[1] in 18..19)
            }
        }
    }
    @Test fun unknownAndLegacyValuesPreserveDnsAiDefault() {
        assertEquals("dns_ai", SmartDnsProviders.forAi("unknown").key)
        assertEquals("dns_ai", SmartDnsProviders.find("comss")!!.key)
        assertEquals("dns_ai", SmartDnsProviders.find("comss_smart")!!.key)
        assertEquals("dns_ai", SmartDnsProviders.find("xbox")!!.key)
        assertFalse(SmartDnsProviders.all.any { it.key.contains("comss") })
        assertNull(SmartDnsProviders.find("cloudflare"))
    }
    @Test fun malwGatewayUsesTheOperatorsComEndpoint() {
        assertEquals("https://5u35p8m9i7.cloudflare-gateway.com/dns-query", SmartDnsProviders.forAi("malw_gateway").dohUrl)
    }
    @Test fun dnsAiBootstrapMatchesTheCurrentPublishedEncryptedEndpoints() {
        // https://dns-ai.ru/ — operator settings reviewed 2026-11-02.
        assertEquals(listOf("194.87.196.13", "45.151.102.219"), SmartDnsProviders.forAi("dns_ai").bootstrapIpv4)
    }
}
