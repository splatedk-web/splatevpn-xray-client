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
        assertEquals("dns_ai", SmartDnsProviders.find("xbox")!!.key)
        assertEquals("comss_smart", SmartDnsProviders.forAi("comss_smart").key)
        assertNull(SmartDnsProviders.find("cloudflare"))
    }
    @Test fun malwGatewayUsesTheOperatorsComEndpoint() {
        assertEquals("https://5u35p8m9i7.cloudflare-gateway.com/dns-query", SmartDnsProviders.forAi("malw_gateway").dohUrl)
    }
}
