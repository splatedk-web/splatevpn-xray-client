package com.danieldk.splatevpn.data

import org.junit.Assert.*
import org.junit.Test

class ServerEndpointValidationTest {
    @Test fun validEndpointsAllowHostnamesAndIpAddresses() {
        assertTrue(validateServerEndpoint(" example.com ", " 443 ").isValid)
        assertTrue(validateServerEndpoint("192.0.2.1", "1").isValid)
        assertTrue(validateServerEndpoint("2001:db8::1", "65535").isValid)
    }

    @Test fun invalidPortsNeverFallBackToAnOldValue() {
        listOf("", "abc", "0", "-1", "65536", "99999999999999", "80.5").forEach {
            assertNull("Unexpected valid port: $it", validateServerEndpoint("example.com", it).port)
        }
    }

    @Test fun missingOrWhitespaceContainingAddressPreventsSaving() {
        listOf("", "   ", "exa mple.com", "host\nname", "https://example.com", "example.com/path").forEach {
            assertFalse(validateServerEndpoint(it, "443").isValid)
        }
    }
}
