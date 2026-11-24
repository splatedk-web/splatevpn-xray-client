package com.danieldk.splatevpn.data

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import com.danieldk.splatevpn.vpn.XrayConfigGenerator
import com.google.gson.JsonParser

class AmneziaWgProfileTest {
    @Test fun vpnLinksAndConfBooleanAliasesKeepAdvancedOptions() {
        val source = conf.replace("[Peer]", "RandomTrailers = on\nDisableCookies = OFF\nContentPaddingAddition = 10-37\nRekeyAfterTime = 118-141\n[Peer]")
        val expected = AmneziaWg.parse(source)
        assertEquals("true", expected.parameters["RandomTrailers"])
        assertEquals("false", expected.parameters["DisableCookies"])
        for (encoder in listOf(Base64.getEncoder(), Base64.getUrlEncoder().withoutPadding())) {
            val link = "vpn://" + encoder.encodeToString(source.toByteArray()) + "#Imported"
            assertEquals(expected, LinkParser.parseBatch(link).single().amneziaWg)
            assertEquals("Imported", LinkParser.parse(link)?.name)
        }
        assertEquals(expected, LinkParser.parseBatch("\uFEFF" + source).single().amneziaWg)
        assertTrue(AmneziaWg.uapi(expected, "127.0.0.1:9").contains("random_trailers=true"))
    }
    private val key = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() })
    private val conf = """[Interface]
PrivateKey = $key
Address = 10.77.0.2/32, fd00::2/128
DNS = 10.77.0.1
Jc = 2
Jmin = 40
Jmax = 80
S1 = 16
S2 = 24
S3 = 8
S4 = 8
H1 = 100001-100010
H2 = 100011
H3 = 100012
H4 = 100013
I1 = <b 0x1234>
[Peer]
PublicKey = $key
PresharedKey = $key
Endpoint = [::1]:51820
AllowedIPs = 0.0.0.0/0, ::/0
PersistentKeepalive = 25
"""
    @Test fun confAndShareRoundTripPreserveAllFields() {
        val p = AmneziaWg.parse(conf)
        assertEquals(p, AmneziaWg.parse(AmneziaWg.conf(p)))
        assertEquals(p, AmneziaWg.parse(AmneziaWg.share(p)))
        val n = LinkParser.parseBatch(conf).single()
        assertTrue(n.uuid.isEmpty() && n.rawLink.isEmpty() && n.fullConfigJson.isNullOrBlank())
        assertEquals(p, LinkParser.parse(LinkParser.toShareLink(n))?.amneziaWg)
        assertFalse(n.toString().contains(key))
        assertTrue(n.usesUdpTransport)
        assertEquals("::1" to 51820, AmneziaWg.endpoint(p.endpoint))
    }
    @Test fun rejectUnsupportedOrDangerousConfigWithoutDroppingParameters() {
        for (bad in listOf(conf + "PostUp = arbitrary", conf.replace("Jc = 2", "Jc = 4294967295"), conf.replace("Address =", "Bogus ="),
            conf.replace("Jmin = 40", "Jmin = 99"), conf.replace("DNS = 10.77.0.1", "DNS = dns.example.com"), conf + "\n[Peer]\nPublicKey = $key",
            conf.replace("51820", "65536"), conf.replace("S1 = 16", "S1 = nope"), conf.replace("I1 = <b 0x1234>", "I1 = <r 999999999>"), conf.replace("I1 = <b 0x1234>", "I1 = <unknown>"), conf.replace("Jc = 2", "Jc = 2\nJc = 3"))) {
            assertTrue(runCatching { AmneziaWg.parse(bad) }.isFailure)
        }
    }
    @Test fun batchAcceptsMultipleAwgLinksAndKeepsNames() {
        val node = AmneziaWg.node(conf).copy(name = "My AWG")
        val link = LinkParser.toShareLink(node)
        val batch = LinkParser.parseBatch("$link\n$link")
        assertEquals(2, batch.size); assertTrue(batch.all { it.name == node.name && it.amneziaWg == node.amneziaWg })
    }
    @Test fun warpAddressesWithoutMasksBecomeHostPrefixes() {
        val bare = conf.replace("Address = 10.77.0.2/32, fd00::2/128", "Address = 172.16.0.2, 2606:4700:110:8c00:fa7a:1f23:2f4b:99b2")
            .replace("I1 = <b 0x1234>", "I1 = <b 0x${"ab".repeat(1250)}>")
        val explicit = bare.replace("Address = 172.16.0.2, 2606:4700:110:8c00:fa7a:1f23:2f4b:99b2",
            "Address = 172.16.0.2/32, 2606:4700:110:8c00:fa7a:1f23:2f4b:99b2/128")
        val p = AmneziaWg.parse(bare)
        assertEquals(AmneziaWg.parse(explicit), p)
        assertEquals("172.16.0.2/32", p.addresses[0]); assertTrue(p.addresses[1].endsWith("/128"))
        assertEquals(p, LinkParser.parseBatch(bare).single().amneziaWg)
        assertEquals(p, AmneziaWg.parse(AmneziaWg.share(p)))
    }
    @Test fun bareAllowedIpIsOneHostAndInvalidExplicitMasksRemainRejected() {
        val p = AmneziaWg.parse(conf.replace("AllowedIPs = 0.0.0.0/0, ::/0", "AllowedIPs = 192.0.2.3, 2001:db8::1"))
        assertEquals("192.0.2.3/32", p.allowedIps[0]); assertTrue(p.allowedIps[1].endsWith("/128"))
        for (address in listOf("10.0.0.2/", "10.0.0.2/-1", "10.0.0.2/33", "fd00::2/129", "10.0.0.2/32/1", "vpn.example")) {
            assertTrue(runCatching { AmneziaWg.parse(conf.replace("10.77.0.2/32", address)) }.isFailure)
        }
    }
    @Test fun suppliedWarpFileImportsCompletelyWhenExplicitlyProvided() {
        val file = System.getenv("SPLATE_AWG_IMPORT_FIXTURE")
        org.junit.Assume.assumeTrue("Optional private local import fixture", !file.isNullOrBlank())
        val text = java.io.File(file!!).readText().removePrefix("\uFEFF")
        val profile = AmneziaWg.parse(text)
        assertEquals(profile, LinkParser.parseBatch(text).single().amneziaWg)
        assertTrue(profile.addresses.all { '/' in it })
        assertEquals(profile, profile.validated())
    }
    @Test fun coreGetsExplicitManagedTransportAndNoTlsOrMux() {
        val node = AmneziaWg.node(conf)
        val config = JsonParser.parseString(XrayConfigGenerator().generateTestConfig(node, 25010, enableTlsFragment = true, enableMux = true)).asJsonObject
        val outbound = config.getAsJsonArray("outbounds").first { it.asJsonObject["tag"].asString == "proxy" }.asJsonObject
        assertEquals("splate-amneziawg", outbound["protocol"].asString)
        assertFalse(outbound.has("streamSettings")); assertFalse(outbound.has("mux"))
        val uapi = AmneziaWg.uapi(node.amneziaWg!!, "[::1]:51820")
        assertTrue(uapi.contains("s4=8\n")); assertTrue(uapi.contains("h1=100001-100010\n")); assertTrue(uapi.contains("i1=<b 0x1234>\n"))
        assertFalse(uapi.contains(key))
    }
}
