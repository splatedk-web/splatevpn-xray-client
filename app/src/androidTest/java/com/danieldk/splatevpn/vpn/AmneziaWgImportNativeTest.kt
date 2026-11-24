package com.danieldk.splatevpn.vpn

import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.data.AmneziaWg
import com.danieldk.splatevpn.data.LinkParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class AmneziaWgImportNativeTest {
    @Test fun warpStyleAddressesImportAndStartTheShippedCore() = runBlocking {
        val key = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() })
        val text = """[Interface]
PrivateKey = $key
Address = 172.16.0.2, 2606:4700:110:8c00:fa7a:1f23:2f4b:99b2
DNS = 1.1.1.1, 1.0.0.1, 2606:4700:4700::1111
MTU = 1280
I1 = <b 0x${"ab".repeat(1250)}>
[Peer]
PublicKey = $key
AllowedIPs = 0.0.0.0/0, ::/0
Endpoint = 127.0.0.1:52991
"""
        val fixture = InstrumentationRegistry.getArguments().getString("awgFixturePath")
        val advanced = text.replace("[Peer]", "RandomTrailers = on\nDisableCookies = on\nContentPaddingAddition = 10-37\nRekeyAfterTime = 118-141\n[Peer]")
        val input = if (fixture == null) advanced else java.io.File(fixture).readText()
        val node = LinkParser.parseBatch(input).single()
        val profile = requireNotNull(node.amneziaWg)
        if (fixture == null) {
            assertEquals("172.16.0.2/32", profile.addresses[0])
            assertTrue(profile.addresses[1].endsWith("/128"))
        }
        assertEquals(profile, LinkParser.parse("vpn://" + Base64.getUrlEncoder().withoutPadding().encodeToString(input.toByteArray()))?.amneziaWg)
        assertEquals(profile, AmneziaWg.parse(AmneziaWg.conf(profile)))
        val before = AmneziaWgRuntime.runningCount
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        PreparedTransports.prepare(app, XrayConfigGenerator().generateTestConfig(node, 26999)).use { prepared ->
            assertTrue(prepared.alive)
            assertEquals(before + 1, AmneziaWgRuntime.runningCount)
            assertFalse(prepared.json.contains("splate-amneziawg"))
        }
        assertEquals(before, AmneziaWgRuntime.runningCount)
    }
}
