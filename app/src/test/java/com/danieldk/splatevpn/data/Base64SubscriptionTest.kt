package com.danieldk.splatevpn.data

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class Base64SubscriptionTest {

    @Test
    fun parseBase64ConfigSubscription() {
        val rawLinks = """
            hy2://96ab5cd282733fd4c312a61f972f330a@vp3.yysyy.online:443?sni=vp3.yysyy.online#hysteria2-node1
            vless://a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d@1.2.3.4:443?encryption=none&security=reality&sni=test.com&fp=chrome&pbk=1234567890123456789012345678901234567890123=&sid=abcd#TestVless
            trojan://password123@5.6.7.8:443?security=tls&sni=trojan.com#TestTrojan
        """.trimIndent()
        val encoded = Base64.getEncoder().encodeToString(rawLinks.toByteArray(Charsets.UTF_8))

        assertTrue("isBase64Content should detect valid base64 links", LinkParser.isBase64Content(encoded))
        assertFalse("isBase64Content should not trigger on regular url", LinkParser.isBase64Content("https://example.com/test"))

        val nodes = LinkParser.parseSubscription(encoded, "test_sub", "Test Sub")
        assertEquals(3, nodes.size)
        assertEquals("hysteria2-node1", nodes[0].name)
        assertEquals("TestVless", nodes[1].name)
        assertEquals("TestTrojan", nodes[2].name)
    }

    @Test
    fun unpaddedAndUrlSafeBase64IsSupported() {
        val rawLinks = "vless://a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d@1.2.3.4:443?encryption=none&security=none#UnpaddedNode"
        val standard = Base64.getEncoder().encodeToString(rawLinks.toByteArray(Charsets.UTF_8))
        val unpadded = standard.trimEnd('=')

        val decoded = LinkParser.decodeSubscriptionContent(unpadded)
        assertTrue(decoded.contains("vless://a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d@1.2.3.4:443"))

        val nodes = LinkParser.parseSubscription(unpadded, "sub", "Sub")
        assertEquals(1, nodes.size)
        assertEquals("UnpaddedNode", nodes[0].name)
    }

    @Test
    fun dataUriPrefixIsStrippedAndDecoded() {
        val raw = "vless://a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d@1.2.3.4:443?encryption=none#DataUriNode"
        val b64 = Base64.getEncoder().encodeToString(raw.toByteArray(Charsets.UTF_8))
        val dataUri = "data:text/plain;base64,$b64"

        val nodes = LinkParser.parseSubscription(dataUri, "sub", "Sub")
        assertEquals(1, nodes.size)
        assertEquals("DataUriNode", nodes[0].name)
    }
}
