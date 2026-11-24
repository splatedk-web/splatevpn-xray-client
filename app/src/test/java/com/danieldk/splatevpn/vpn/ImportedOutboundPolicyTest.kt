package com.danieldk.splatevpn.vpn

import com.danieldk.splatevpn.data.ServerNode
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class ImportedOutboundPolicyTest {
    private val node = ServerNode("id", "test", "trojan", "example.com", 443, uuid = "test-password", security = "tls")
    private val generator = XrayConfigGenerator()
    private val proxy = """{"protocol":"trojan","tag":"original","settings":{"servers":[{"address":"example.com","port":443,"password":"test-password"}]},"streamSettings":{"network":"tcp","security":"tls","sockopt":{"dialerProxy":"hop"}}} """.trim()

    @Test fun importsOnlyReachableChainAndRenamesItsReferences() {
        val raw = """{"outbounds":[$proxy,{"protocol":"freedom","tag":"hop"},{"protocol":"freedom","tag":"unused"}]}"""
        val config = JsonParser.parseString(generator.generateConfig(node.copy(fullConfigJson = raw), enableTlsFragment = true)).asJsonObject
        val outbounds = config.getAsJsonArray("outbounds").map { it.asJsonObject }
        assertEquals("import-hop", outbounds.first()["streamSettings"].asJsonObject["sockopt"].asJsonObject["dialerProxy"].asString)
        assertEquals("fragment", outbounds.first { it["tag"].asString == "import-hop" }["streamSettings"].asJsonObject["sockopt"].asJsonObject["dialerProxy"].asString)
        assertFalse(outbounds.any { it["tag"].asString == "unused" })
    }

    @Test fun rejectsCyclesAndMalformedProfilesInsteadOfSilentlyChangingProxy() {
        val raw = """{"outbounds":[$proxy,{"protocol":"freedom","tag":"hop","proxySettings":{"tag":"original"}}]}"""
        assertThrows(IllegalArgumentException::class.java) { generator.generateConfig(node.copy(fullConfigJson = raw)) }
        assertThrows(IllegalStateException::class.java) { generator.generateConfig(node.copy(fullConfigJson = "{}")) }
    }

    @Test fun lanSharingCanDisableAuthenticationWithoutExposingTheTunnelListener() {
        val config = JsonParser.parseString(generator.generateConfig(node, shareLan = true,
            lanAccess = LanAccess("192.168.43.1", "", "", authentication = false))).asJsonObject
        val inbounds = config.getAsJsonArray("inbounds").map { it.asJsonObject }
        assertEquals("127.0.0.1", inbounds.first()["listen"].asString)
        inbounds.drop(1).forEach {
            assertEquals("192.168.43.1", it["listen"].asString)
            assertFalse(it.getAsJsonObject("settings").has("accounts"))
            if (it["protocol"].asString == "socks") assertEquals("noauth", it.getAsJsonObject("settings")["auth"].asString)
        }
    }

    @Test fun lanSharingRequiresCredentialsAndLeavesTunnelOnLoopback() {
        assertThrows(IllegalArgumentException::class.java) { generator.generateConfig(node, shareLan = true) }
        val config = JsonParser.parseString(generator.generateConfig(node, shareLan = true,
            lanAccess = LanAccess("192.168.43.1", "splate", "test-password"))).asJsonObject
        val inbounds = config.getAsJsonArray("inbounds").map { it.asJsonObject }
        assertEquals("127.0.0.1", inbounds.first()["listen"].asString)
        assertEquals("noauth", inbounds.first()["settings"].asJsonObject["auth"].asString)
        assertEquals(2, inbounds.drop(1).size)
        inbounds.drop(1).forEach {
            assertEquals("192.168.43.1", it["listen"].asString)
            assertEquals("test-password", it["settings"].asJsonObject["accounts"].asJsonArray[0].asJsonObject["pass"].asString)
        }
        assertThrows(IllegalArgumentException::class.java) { LanAccess("0.0.0.0", "splate", "test-password") }
    }
}
