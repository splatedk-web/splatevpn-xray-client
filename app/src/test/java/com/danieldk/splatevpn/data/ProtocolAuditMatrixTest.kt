package com.danieldk.splatevpn.data

import com.danieldk.splatevpn.vpn.XrayConfigGenerator
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class ProtocolAuditMatrixTest {

    private val generator = XrayConfigGenerator()

    private fun primaryOutbound(node: ServerNode, enableTlsFragment: Boolean = false) =
        JsonParser.parseString(generator.generateConfig(node, enableTlsFragment = enableTlsFragment))
            .asJsonObject.getAsJsonArray("outbounds")
            .first { it.asJsonObject["tag"].asString == "proxy" }.asJsonObject

    @Test
    fun shadowsocksHandlesIpv6TrailingSlashUppercaseAndSs2022() {
        val sip002Ipv6 = "SS://${Base64.getEncoder().encodeToString("aes-256-gcm:my+pass".toByteArray())}@[2001:db8::1]:9443/?plugin=v2ray-plugin#SS-IPv6"
        val node1 = requireNotNull(LinkParser.parse(sip002Ipv6))
        assertEquals("shadowsocks", node1.protocol)
        assertEquals("2001:db8::1", node1.address)
        assertEquals(9443, node1.port)
        assertEquals("aes-256-gcm", node1.encryption)
        assertEquals("my+pass", node1.uuid)
        assertEquals("SS-IPv6", node1.name)
        val shared = LinkParser.toShareLink(node1.copy(rawLink = ""))
        assertTrue(shared, shared.contains("@[2001:db8::1]:9443"))
        assertEquals("2001:db8::1", requireNotNull(LinkParser.parse(shared)).address)

        val ss2022 = "ss://2022-blake3-aes-128-gcm:abc%2Fdef%2B123%3D@ss2022.example:8443#SS2022"
        val node2 = requireNotNull(LinkParser.parse(ss2022))
        assertEquals("2022-blake3-aes-128-gcm", node2.encryption)
        assertEquals("abc/def+123=", node2.uuid)
        assertEquals(8443, node2.port)

        val ss2022EncodedColon = "ss://2022-blake3-aes-256-gcm%3Akey1%3Akey2@ss2022.example:8444#SS2022Colon"
        val node3 = requireNotNull(LinkParser.parse(ss2022EncodedColon))
        assertEquals("2022-blake3-aes-256-gcm", node3.encryption)
        assertEquals("key1:key2", node3.uuid)

        val legacyIpv6 = "ss://${Base64.getEncoder().encodeToString("chacha20-ietf-poly1305:sec@[2001:db8::2]:8389".toByteArray())}#LegacyIPv6"
        val node4 = requireNotNull(LinkParser.parse(legacyIpv6))
        assertEquals("2001:db8::2", node4.address)
        assertEquals(8389, node4.port)
        assertEquals("sec", node4.uuid)

        assertNull(LinkParser.parse("ss://YWVzLTI1Ni1nY206cGFzcw==@example.com:99999"))
        assertNull(LinkParser.parse("ss://YWVzLTI1Ni1nY206cGFzcw==@:8388"))
    }

    @Test
    fun vmessHandlesJsonNullsNumericTlsUppercaseAndGrpc() {
        val rawJson = """{"v":"2","ps":"VMess-Nulls","add":"vm.example","port":"8443","id":"11111111-2222-3333-4444-555555555555","net":"grpc","type":"multi","host":"a.example,b.example","path":"myGrpcService","tls":"1","sni":null,"fp":null,"scy":null}"""
        val link = "VMESS://${Base64.getEncoder().encodeToString(rawJson.toByteArray())}"
        val node = requireNotNull(LinkParser.parse(link))
        assertEquals("vmess", node.protocol)
        assertEquals("vm.example", node.address)
        assertEquals(8443, node.port)
        assertEquals("grpc", node.network)
        assertEquals("myGrpcService", node.path)
        assertEquals("tls", node.security)
        assertEquals("a.example", node.sni)
        assertEquals("chrome", node.fingerprint)
        assertEquals("auto", node.encryption)

        val stream = primaryOutbound(node).getAsJsonObject("streamSettings")
        assertEquals("grpc", stream["network"].asString)
        assertEquals("myGrpcService", stream.getAsJsonObject("grpcSettings")["serviceName"].asString)

        val badPortJson = """{"v":"2","add":"vm.example","port":70000,"id":"uuid"}"""
        assertNull(LinkParser.parse("vmess://${Base64.getEncoder().encodeToString(badPortJson.toByteArray())}"))
    }

    @Test
    fun vlessAndTrojanSupportHttpUpgradeKcpGrpcH2MigrationAlpnAndEncodedCredentials() {
        val httpUpgrade = requireNotNull(LinkParser.parse(
            "vless://11111111-2222-3333-4444-555555555555@hu.example:443?type=httpupgrade&security=tls&sni=hu.example&host=cdn.example&path=%2Fupgrade&alpn=http%2F1.1#HU"
        ))
        val huStream = primaryOutbound(httpUpgrade).getAsJsonObject("streamSettings")
        assertEquals("httpupgrade", huStream["network"].asString)
        assertEquals("/upgrade", huStream.getAsJsonObject("httpupgradeSettings")["path"].asString)
        assertEquals("cdn.example", huStream.getAsJsonObject("httpupgradeSettings")["host"].asString)
        assertEquals(listOf("http/1.1"), huStream.getAsJsonObject("tlsSettings").getAsJsonArray("alpn").map { it.asString })

        val kcp = requireNotNull(LinkParser.parse(
            "vless://11111111-2222-3333-4444-555555555555@kcp.example:443?type=kcp&headerType=utp&seed=my+seed&security=none#KCP"
        ))
        val kcpStream = primaryOutbound(kcp, enableTlsFragment = true).getAsJsonObject("streamSettings")
        assertEquals("kcp", kcpStream["network"].asString)
        assertEquals("utp", kcpStream.getAsJsonObject("kcpSettings").getAsJsonObject("header")["type"].asString)
        assertEquals("my+seed", kcpStream.getAsJsonObject("kcpSettings")["seed"].asString)
        assertFalse(kcpStream.getAsJsonObject("sockopt").has("dialerProxy"))

        val legacyH2 = requireNotNull(LinkParser.parse(
            "vless://11111111-2222-3333-4444-555555555555@h2.example:443?type=h2&security=tls&path=%2Fh2path&host=h2.example#H2"
        ))
        val h2Stream = primaryOutbound(legacyH2).getAsJsonObject("streamSettings")
        assertEquals("xhttp", h2Stream["network"].asString)
        assertEquals("stream-one", h2Stream.getAsJsonObject("xhttpSettings")["mode"].asString)
        assertEquals("/h2path", h2Stream.getAsJsonObject("xhttpSettings")["path"].asString)
        assertFalse(h2Stream.has("httpSettings"))

        val trojan = requireNotNull(LinkParser.parse(
            "trojan://p%40ss%2B1%2F2@[2001:db8::5]:443?type=grpc&serviceName=svcName&mode=multi&authority=auth.example&security=tls&sni=t.example#TrojanGRPC"
        ))
        assertEquals("p@ss+1/2", trojan.uuid)
        assertEquals("2001:db8::5", trojan.address)
        assertEquals("svcName", trojan.path)
        assertEquals("multi", trojan.mode)
        assertEquals("auth.example", trojan.host)
        val trojanStream = primaryOutbound(trojan).getAsJsonObject("streamSettings")
        assertEquals("svcName", trojanStream.getAsJsonObject("grpcSettings")["serviceName"].asString)
        assertTrue(trojanStream.getAsJsonObject("grpcSettings")["multiMode"].asBoolean)
        assertEquals("auth.example", trojanStream.getAsJsonObject("grpcSettings")["authority"].asString)
        val restoredTrojan = requireNotNull(LinkParser.parse(LinkParser.toShareLink(trojan.copy(rawLink = ""))))
        assertEquals(trojan.uuid, restoredTrojan.uuid)
        assertEquals(trojan.address, restoredTrojan.address)
        assertEquals(trojan.path, restoredTrojan.path)

        val vlessPq = requireNotNull(LinkParser.parse(
            "vless://11111111-2222-3333-4444-555555555555@pq.example:443?encryption=mlkem768x25519plus.native.0rtt.key+1&pbk=abc+def&security=reality#PQ"
        ))
        assertEquals("mlkem768x25519plus.native.0rtt.key+1", vlessPq.encryption)
        assertEquals("abc+def", vlessPq.publicKey)
        val restoredPq = requireNotNull(LinkParser.parse(LinkParser.toShareLink(vlessPq.copy(rawLink = ""))))
        assertEquals(vlessPq.encryption, restoredPq.encryption)
        assertEquals(vlessPq.publicKey, restoredPq.publicKey)

        assertNull(LinkParser.parse("vless://uuid@:443"))
        assertNull(LinkParser.parse("vless://uuid@example.com:70000"))
        assertNull(LinkParser.parse("trojan://pass@example.com:0"))
    }

    @Test
    fun socksSupportsBase64UserInfoAndRejectsInvalidEndpoints() {
        val b64 = Base64.getEncoder().encodeToString("myuser:mypass".toByteArray())
        val node = requireNotNull(LinkParser.parse("socks://$b64@socks.example:1080#B64Socks"))
        assertEquals("socks", node.protocol)
        assertEquals("myuser", node.encryption)
        assertEquals("mypass", node.uuid)
        assertEquals(1080, node.port)

        assertNull(LinkParser.parse("socks5://:1080"))
        assertNull(LinkParser.parse("socks5://socks.example:70000"))
    }

    @Test
    fun hysteria2AcceptsObfsNoneAndSha256PrefixedPin() {
        val pinHex = "ab".repeat(32)
        val node = requireNotNull(LinkParser.parse(
            "hy2://secret@hy.example:443/?obfs=none&sni=hy.example&pinSHA256=sha256:${pinHex.chunked(2).joinToString(":")}#Hy2"
        ))
        val outbound = Hysteria2Profile.outbound(node)
        val stream = outbound.getAsJsonObject("streamSettings")
        assertEquals(pinHex, stream.getAsJsonObject("tlsSettings")["pinnedPeerCertSha256"].asString)
        assertFalse(stream.has("finalmask"))
    }

    @Test
    fun wireGuardUriParsesIntoValidatedAmneziaWgProfile() {
        val key = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 7).toByte() })
        val encKey = java.net.URLEncoder.encode(key, "UTF-8")
        val link = "wireguard://$encKey@wg.example:51820?publickey=$encKey&address=10.0.0.2/32&mtu=1280&dns=1.1.1.1#WG-Node"
        val node = requireNotNull(LinkParser.parse(link))
        assertEquals("amneziawg", node.protocol)
        assertEquals("WG-Node", node.name)
        assertEquals("wg.example", node.address)
        assertEquals(51820, node.port)
        assertNotNull(node.amneziaWg)
        assertEquals("splate-amneziawg", primaryOutbound(node)["protocol"].asString)
    }

    @Test
    fun clashYamlHandlesNestedIndentationResetFlowStyleAndBom() {
        val blockYaml = "\uFEFF" + """
            proxies:
              - name: Block-VLESS
                type: vless
                ws-opts:
                  path: /ws
                  headers:
                    Host: cdn.example
                server: vless.example
                port: 443
                uuid: 11111111-2222-3333-4444-555555555555
                network: ws
                tls: true
                servername: vless.example
              - {name: "Flow-SS", type: shadowsocks, server: ss.example, port: 8388, cipher: aes-256-gcm, password: "pw"}
              - {name: "Flow-HY2", type: hysteria2, server: hy.example, port: 8443, password: "hy", sni: "hy.example"}
              - {name: "Flow-Socks", type: socks5, server: sk.example, port: 1080, username: "u", password: "p"}
              - {name: "Flow-Trojan", type: trojan, server: tr.example, port: 443, password: "tp", network: grpc, grpc-opts: {grpc-service-name: "gsvc"}}
        """.trimIndent()

        val nodes = LinkParser.parseSubscription(blockYaml)
        assertEquals(5, nodes.size)
        assertEquals("vless.example", nodes[0].address)
        assertEquals("/ws", nodes[0].path)
        assertEquals("cdn.example", nodes[0].host)
        assertEquals("shadowsocks", nodes[1].protocol)
        assertEquals("hysteria2", nodes[2].protocol)
        assertEquals(8443, nodes[2].port)
        assertEquals("socks", nodes[3].protocol)
        assertEquals("u", nodes[3].encryption)
        assertEquals("p", nodes[3].uuid)
        assertEquals("trojan", nodes[4].protocol)
        assertEquals("grpc", nodes[4].network)
        assertEquals("gsvc", nodes[4].path)
    }
}
