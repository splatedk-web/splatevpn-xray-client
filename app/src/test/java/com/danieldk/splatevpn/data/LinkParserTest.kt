package com.danieldk.splatevpn.data

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class LinkParserTest {
    @Test fun vlessServerDescriptionIsSeparateFromTheName() {
        val link = "vless://00000000-0000-0000-0000-000000000001@example.com:443?type=ws#Gcore?serverDescription=Q0RO"
        val node = requireNotNull(LinkParser.parse(link))
        assertEquals("Gcore", node.name)
        assertEquals("CDN", LinkParser.vlessServerDescription(node.rawLink))
        assertEquals("Gcore" to "CDN", LinkParser.splitVlessLabel("Gcore?serverDescription=Q0RO"))
        assertEquals("Gcore" to "CDN", LinkParser.splitVlessLabel("Gcore?serverDescription=UTBSTw=="))
        assertEquals("CDN", LinkParser.jsonServerDescription("""{"meta":{"serverDescription":"**CDN**"}}"""))
        assertEquals("Q0RO", LinkParser.jsonServerDescription("""{"meta":{"serverDescription":"Q0RO"}}"""))
        assertEquals("Gcore" to "CDN", LinkParser.splitVlessLabel("Gcore?serverDescription=KipDRE4qKg=="))
        assertEquals("Gcore?serverDescription=??" to null, LinkParser.splitVlessLabel("Gcore?serverDescription=??"))
    }

    @Test fun socksSharingKeepsUserPasswordEncodingAndIpv6Endpoint() {
        val source = ServerNode("socks", "Proxy name", "socks", "2001:db8::1", 1080,
            uuid = "pass:@/#?+ %25", encryption = "user:@/+ %25")
        val link = LinkParser.toShareLink(source)
        val restored = requireNotNull(LinkParser.parse(link))
        assertEquals(source.encryption, restored.encryption)
        assertEquals(source.uuid, restored.uuid)
        assertEquals(source.address, restored.address)
        assertEquals(source.port, restored.port)
        assertEquals(source.name, restored.name)
        assertTrue(link.contains("@[2001:db8::1]:1080"))
    }

    @Test fun socksSharingUsesCurrentProfileInsteadOfStaleRawCredentials() {
        val source = requireNotNull(LinkParser.parse("socks5://old:secret@old.example:1080#old"))
            .copy(name = "New", address = "new.example", port = 2080, encryption = "new", uuid = "password")
        val restored = requireNotNull(LinkParser.parse(LinkParser.toShareLink(source)))
        assertEquals(source.encryption, restored.encryption)
        assertEquals(source.uuid, restored.uuid)
        assertEquals(source.address, restored.address)
        assertEquals(source.port, restored.port)
        assertEquals(source.name, restored.name)
        val anonymous = source.copy(encryption = "", uuid = "", rawLink = "")
        val withoutAuth = requireNotNull(LinkParser.parse(LinkParser.toShareLink(anonymous)))
        assertEquals("", withoutAuth.encryption)
        assertEquals("", withoutAuth.uuid)
    }

    @Test fun exportedRealityKeySurvivesMessengerUnderscoreFormatting() {
        val key = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(1952) { -1 })
        assertTrue(key.contains("__"))
        val node = requireNotNull(LinkParser.parse("vless://00000000-0000-0000-0000-000000000000@vpn.example:443?security=reality&type=xhttp&pqv=$key"))
        val exported = LinkParser.toShareLink(node)
        assertFalse(exported.contains("__"))
        assertEquals(key, LinkParser.extractRealityVerificationKey(exported.replace("__", "")))
    }
    @Test fun realityKeySurvivesLiteralPlusPercentEncodingPaddingAndSharing() {
        val bytes = ByteArray(1952) { (it % 251).toByte() }
        val canonical = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val standard = Base64.getEncoder().encodeToString(bytes)
        assertTrue(standard.contains('+'))
        for (parameter in listOf("pqv", "mldsa65Verify")) {
            for (value in listOf(standard, java.net.URLEncoder.encode(standard, "UTF-8"), "$canonical=")) {
                val link = "vless://00000000-0000-0000-0000-000000000000@vpn.example:443?security=reality&type=xhttp&$parameter=$value"
                val node = requireNotNull(LinkParser.parse(link))
                assertEquals(canonical, LinkParser.extractRealityVerificationKey(node.rawLink))
                assertEquals(canonical, LinkParser.extractRealityVerificationKey(LinkParser.toShareLink(node)))
            }
        }
    }

    @Test fun invalidRealityKeyIsRetainedForExplicitValidation() {
        assertEquals("truncated-key", LinkParser.extractRealityVerificationKey("vless://test@vpn.example:443?pqv=truncated-key"))
        assertEquals(" ", LinkParser.extractRealityVerificationKey("vless://test@vpn.example:443?pqv=%20"))
    }

    @Test fun longRealityVerificationKeySurvivesCanonicalSharing() {
        val key = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(1952) { (it % 251).toByte() })
        val link = "vless://00000000-0000-0000-0000-000000000000@vpn.example:443?security=reality&pbk=test&pqv=$key&type=xhttp#test"
        val node = requireNotNull(LinkParser.parse(link))
        assertEquals(key, LinkParser.extractRealityVerificationKey(node.rawLink))
        assertEquals(key, LinkParser.extractRealityVerificationKey(LinkParser.toShareLink(node)))
        assertEquals(key, LinkParser.extractRealityVerificationKey(link.replace("pqv=", "mldsa65Verify=")))
        assertNull(LinkParser.extractRealityVerificationKey(""))
        assertNull(LinkParser.extractRealityVerificationKey(link.substringBefore("&pqv=")))
    }

    @Test
    fun testParseBatch_emptyOrBlank() {
        val emptyResult = LinkParser.parseBatch("")
        assertTrue(emptyResult.isEmpty())

        val whitespaceResult = LinkParser.parseBatch("   \n\n  ")
        assertTrue(whitespaceResult.isEmpty())
    }

    @Test
    fun testParseVless_reality() {
        val link = "vless://00000000-0000-0000-0000-000000000000@1.2.3.4:443?security=reality&type=tcp&sni=example.com&pbk=abcdef0123456789&sid=1234&fp=chrome#TestServer"
        val node = LinkParser.parse(link)
        assertNotNull(node)
        assertEquals("vless", node?.protocol)
        assertEquals("1.2.3.4", node?.address)
        assertEquals(443, node?.port)
        assertEquals("reality", node?.security)
        assertEquals("example.com", node?.sni)
        assertEquals("abcdef0123456789", node?.publicKey)
        assertEquals("1234", node?.shortId)
        assertEquals("chrome", node?.fingerprint)
        assertEquals("TestServer", node?.name)
    }

    @Test
    fun testParseTrojan() {
        val link = "trojan://secretpassword@trojan.example.com:8443?security=tls&sni=sni.example.com#TrojanNode"
        val node = LinkParser.parse(link)
        assertNotNull(node)
        assertEquals("trojan", node?.protocol)
        assertEquals("secretpassword", node?.uuid)
        assertEquals("trojan.example.com", node?.address)
        assertEquals(8443, node?.port)
        assertEquals("tls", node?.security)
        assertEquals("sni.example.com", node?.sni)
        assertEquals("TrojanNode", node?.name)
    }

    @Test
    fun testParseVmess_withHashFragment() {
        // Construct valid VMess JSON payload
        val vmessJson = """
            {
              "v": "2",
              "ps": "OriginalName",
              "add": "vmess.example.com",
              "port": 443,
              "id": "11111111-2222-3333-4444-555555555555",
              "aid": 0,
              "net": "ws",
              "type": "none",
              "host": "ws.example.com",
              "path": "/ws-path",
              "tls": "tls",
              "sni": "vmess.example.com"
            }
        """.trimIndent()

        val base64 = Base64.getEncoder().encodeToString(vmessJson.toByteArray(Charsets.UTF_8))
        // Link with trailing hash fragment: previously caused JSON parse error!
        val link = "vmess://$base64#OverrideServerName"

        val node = LinkParser.parse(link)
        assertNotNull(node)
        assertEquals("vmess", node?.protocol)
        assertEquals("vmess.example.com", node?.address)
        assertEquals(443, node?.port)
        assertEquals("11111111-2222-3333-4444-555555555555", node?.uuid)
        assertEquals("ws", node?.network)
        assertEquals("tls", node?.security)
        assertEquals("/ws-path", node?.path)
        assertEquals("OriginalName", node?.name)
    }

    @Test
    fun testParseShadowsocks() {
        // ss://BASE64(method:password@host:port)#tag
        val userInfo = "chacha20-ietf-poly1305:mypassword@ss.example.com:8388"
        val base64 = Base64.getEncoder().encodeToString(userInfo.toByteArray(Charsets.UTF_8))
        val link = "ss://$base64#SS-Node"

        val node = LinkParser.parse(link)
        assertNotNull(node)
        assertEquals("shadowsocks", node?.protocol)
        assertEquals("ss.example.com", node?.address)
        assertEquals(8388, node?.port)
        assertEquals("mypassword", node?.uuid)
        assertEquals("chacha20-ietf-poly1305", node?.encryption)
        assertEquals("SS-Node", node?.name)
    }

    @Test
    fun testParseBatch_multipleLinks() {
        val link1 = "vless://00000000-0000-0000-0000-000000000000@node1.com:443?security=reality&pbk=key1#Node1"
        val link2 = "trojan://pass2@node2.com:443?security=tls#Node2"
        val rawBatch = """
            $link1

            invalid://some-bad-data
            $link2
        """.trimIndent()

        val nodes = LinkParser.parseBatch(rawBatch, subId = "sub123", subName = "MySub")
        assertEquals(2, nodes.size)
        assertEquals("Node1", nodes[0].name)
        assertEquals("sub123", nodes[0].subId)
        assertEquals("MySub", nodes[0].subName)
        assertEquals("Node2", nodes[1].name)
        assertEquals("sub123", nodes[1].subId)
    }

    @Test
    fun testParseUserNodes() {
        val node0 = "vless://5c395a96-1cea-41c2-9fdd-8fa8e5fa9392@89.125.28.77:2053?alpn=h2%2Chttp%2F1.1&encryption=none&fp=chrome&host=&path=%2Fws&security=tls&sni=dl.google.com&type=ws#VLESS-WS-Cloudflare-oneplus"
        val parsed0 = LinkParser.parse(node0)
        println("Parsed0: $parsed0")
        assertNotNull(parsed0)

        val node1 = "vless://5c395a96-1cea-41c2-9fdd-8fa8e5fa9392@89.125.28.77:443?encryption=none&extra=%7B%22mode%22%3A%22packet-up%22%2C%22xPaddingBytes%22%3A%22100-1000%22%7D&fp=chrome&host=&mode=packet-up&path=%2F&pbk=DVHAAhW_6ZLKfg8uqNnTARfon5WLU__89hPod6ydOwo&security=reality&sid=5dc7&sni=stg-am-images.shop.samsung.com&spx=%2F5ca362d157bcb27&type=xhttp&x_padding_bytes=100-1000#VLESS-XHTTP-Reality"
        val parsed1 = LinkParser.parse(node1)
        println("Parsed1: $parsed1")
        assertNotNull(parsed1)

        val combinedRaw = "$node0\n$node1\n# anti-tspu-salt=test"
        val b64 = Base64.getEncoder().encodeToString(combinedRaw.toByteArray(Charsets.UTF_8))
        val subNodes = LinkParser.parseSubscription(b64, "sub-test", "TestSub")
        assertEquals(2, subNodes.size)
        assertEquals("VLESS-WS-Cloudflare-oneplus", subNodes[0].name)
        assertEquals("VLESS-XHTTP-Reality", subNodes[1].name)
    }
    @org.junit.Test
    fun rejectsOversizedNodeListsBeforeBuildingTheEntireSubscription() {
        val link = "vless://11111111-2222-3333-4444-555555555555@example.com:443?security=tls\n"
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            LinkParser.parseSubscription(link.repeat(LinkParser.MAX_SUBSCRIPTION_NODES + 1))
        }
    }
}
