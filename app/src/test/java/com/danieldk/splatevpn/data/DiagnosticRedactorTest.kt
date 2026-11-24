package com.danieldk.splatevpn.data

import org.junit.Assert.*
import org.junit.Test

class DiagnosticRedactorTest {
    @Test fun encodedAmneziaPrivateProfilesAreHidden() {
        assertFalse(DiagnosticRedactor.redact("Import vpn://cHJpdmF0ZS1jb25maWc=").contains("cHJpdmF0ZS1jb25maWc="))
    }
    @Test fun hidesMixedProxyAccountsAndCompleteBasicAuthorization() {
        val token = java.util.Base64.getEncoder().encodeToString("local-user:local-password".toByteArray())
        val report = DiagnosticRedactor.redact("{\"user\":\"local-user\",\"pass\":\"local-password\"}\nProxy-Authorization: Basic $token\nHTTP 407")
        assertFalse(report.contains("local-user"))
        assertFalse(report.contains("local-password"))
        assertFalse(report.contains(token))
        assertTrue(report.contains("HTTP 407"))
    }
    @Test fun retainsRealityFailureFieldWhileHidingLongKeys() {
        val key = "a".repeat(2603)
        val result = DiagnosticRedactor.redact("invalid \"mldsa65Verify\": $key; pqv=$key; \"mldsa65Seed\":\"seed-secret\"")
        assertTrue(result.contains("mldsa65Verify"))
        assertFalse(result.contains(key))
        assertFalse(result.contains("seed-secret"))
        assertTrue(result.contains("[key hidden]"))
    }
    @Test fun hidesCredentialsAndAddressesWithoutRemovingUsefulStatus() {
        val report = DiagnosticRedactor.redact("12:34:56.789 HTTP 403 vless://private@vpn.example.com:443 \"password\":\"super-secret\" 203.0.113.8 2001:db8::4 127.0.0.1", listOf("My private server"))
        assertFalse(report.contains("super-secret"))
        assertFalse(report.contains("vpn.example.com"))
        assertFalse(report.contains("203.0.113.8"))
        assertFalse(report.contains("2001:db8::4"))
        assertTrue(report.contains("12:34:56.789"))
        assertTrue(report.contains("HTTP 403"))
        assertTrue(report.contains("127.0.0.1"))
    }
    @Test fun hidesValuesWithoutCredentialLabelsAndLocalPaths() {
        val report = DiagnosticRedactor.redact("Connected to My private server; raw-value /storage/emulated/0/private/file.txt", listOf("My private server", "raw-value"))
        assertFalse(report.contains("My private server"))
        assertFalse(report.contains("raw-value"))
        assertFalse(report.contains("private/file.txt"))
    }
}
