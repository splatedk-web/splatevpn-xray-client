package com.danieldk.splatevpn.data

import org.junit.Assert.*
import org.junit.Test

class AnonymousErrorReportTest {
    @Test fun neverIncludesMessagesDevicePathsThreadNamesOrForeignFrames() {
        val error = IllegalStateException("vless://secret@198.51.100.1 password=private user@example.com")
        error.stackTrace = arrayOf(
            StackTraceElement("com.danieldk.splatevpn.ui.components.QrScannerActivity", "onCreate", "/storage/emulated/0/private-user.kt", 91),
            StackTraceElement("com.personal.secret", "password", "device-name.txt", 12))
        val json = AnonymousErrorReports.json(AnonymousErrorReports.create("1.2.5", "QR", error))
        listOf("198.51", "vless://", "private-user", "personal", "user@example", "device-name", "password", "/storage").forEach {
            assertFalse(it, json.contains(it))
        }
        assertTrue(json.contains("QrScannerActivity"))
        assertTrue(json.contains("IllegalStateException"))
        assertTrue(json.contains("91"))
    }

    @Test fun boundsCauseChainsAndFramesAndRejectsFreeformCategory() {
        var cause: Throwable = IllegalArgumentException("secret")
        repeat(8) { cause = IllegalStateException("private", cause).apply {
            stackTrace = Array(100) { StackTraceElement("com.danieldk.splatevpn.Test", "run", "Test.kt", it) }
        } }
        val report = AnonymousErrorReports.create("version with private text", "my IP 192.0.2.1", cause)
        assertEquals("unknown", report.appVersion)
        assertEquals("OTHER", report.category)
        assertEquals(4, report.exceptions.size)
        assertTrue(report.exceptions.all { it.frames.size <= 24 })
    }
}
