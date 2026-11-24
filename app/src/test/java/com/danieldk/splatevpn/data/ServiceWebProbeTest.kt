package com.danieldk.splatevpn.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class ServiceWebProbeTest {
    private val base = "https://web.whatsapp.com/".toHttpUrl()
    private val domains = setOf("whatsapp.com", "whatsapp.net")

    @Test fun errorPagesAndUntrustedOrInsecureScriptsCannotQualify() {
        assertTrue(ServiceWebProbe.scriptUrls("<h1>Service unavailable</h1>", base, domains).isEmpty())
        val html = """<script src="https://whatsapp.net.evil.test/app.js"></script>
            <script src="http://static.whatsapp.net/app.js"></script>
            <script src="https://user:password@static.whatsapp.net/app.js"></script>
            <script src="https://static.whatsapp.net:8443/app.js"></script>"""
        assertTrue(ServiceWebProbe.scriptUrls(html, base, domains).isEmpty())
        assertTrue(ServiceWebProbe.scriptUrls("""<form id="captcha"><script src="/app.js"></script>""", base, domains).isEmpty())
    }

    @Test fun applicationRequiresAnActualJavascriptResponse() {
        val html = """<script src="https://static.whatsapp.net/app.js?a=1&amp;b=2"></script>"""
        assertEquals("https://static.whatsapp.net/app.js?a=1&b=2", ServiceWebProbe.scriptUrls(html, base, domains).single().toString())
        assertTrue(ServiceWebProbe.validScript("application/javascript; charset=utf-8", ByteArray(8192) { 'x'.code.toByte() }))
        assertFalse(ServiceWebProbe.validScript("text/html", ByteArray(8192)))
        assertFalse(ServiceWebProbe.validScript("application/javascript", "<!doctype html>".padEnd(8192).toByteArray()))
        assertFalse(ServiceWebProbe.validScript("text/javascript", ByteArray(1024)))
    }
}
