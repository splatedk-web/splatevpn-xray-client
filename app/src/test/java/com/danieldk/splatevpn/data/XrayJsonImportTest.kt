package com.danieldk.splatevpn.data

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class XrayJsonImportTest {
    @Test fun modernFlatVlessSettingsAndXDriveSurviveSubscriptionImport() {
        val profile = """{"remarks":"Cloud transport","outbounds":[{"tag":"cloud","protocol":"vless","settings":{"address":"cloud.example","port":443,"id":"11111111-2222-3333-4444-555555555555","encryption":"mlkem768x25519plus.native.0rtt.test-key"},"streamSettings":{"network":"xdrive","xdriveSettings":{"service":"google-drive","remoteFolder":"folder","secrets":["client","secret","refresh"]}}}]}"""
        val node = LinkParser.parseSubscription(profile).single()
        assertEquals("cloud.example", node.address)
        assertEquals("11111111-2222-3333-4444-555555555555", node.uuid)
        assertEquals("xdrive", node.network)
        assertEquals("mlkem768x25519plus.native.0rtt.test-key", node.encryption)
        assertEquals("Cloud transport", node.name)
        assertEquals("folder", JsonParser.parseString(node.fullConfigJson).asJsonObject
            .getAsJsonArray("outbounds")[0].asJsonObject.getAsJsonObject("streamSettings")
            .getAsJsonObject("xdriveSettings")["remoteFolder"].asString)
    }

    @Test fun outboundOnlyListsKeepIndependentNodesAndAllChainSettings() {
        val profile = """{"outbounds":[{"tag":"one","protocol":"trojan","settings":{"servers":[{"address":"one.example","port":443,"password":"one"}]},"streamSettings":{"network":"httpupgrade","httpupgradeSettings":{"path":"/tunnel"},"sockopt":{"dialerProxy":"hop"}}},{"tag":"two","protocol":"trojan","settings":{"address":"two.example","port":443,"password":"two"}},{"tag":"hop","protocol":"freedom"}]}"""
        val nodes = LinkParser.parseSubscription(profile)
        assertEquals(2, nodes.size)
        nodes.forEach { node ->
            val outbounds = JsonParser.parseString(node.fullConfigJson).asJsonObject.getAsJsonArray("outbounds")
            assertEquals(node.name, outbounds[0].asJsonObject["tag"].asString)
            assertEquals(3, outbounds.size())
        }
        assertEquals("httpupgrade", nodes[0].network)
        assertEquals("two", nodes[1].uuid)
    }

    @Test fun bareXrayOutboundRetainsTransportExtensions() {
        val raw = """{"protocol":"trojan","settings":{"address":"flat.example","port":8443,"password":"test"},"streamSettings":{"network":"tcp","security":"tls","tlsSettings":{"serverName":"flat.example","alpn":["h2"]}}}"""
        val node = LinkParser.parseSubscription(raw).single()
        assertEquals(8443, node.port)
        assertEquals("h2", JsonParser.parseString(node.fullConfigJson).asJsonObject.getAsJsonArray("outbounds")
            [0].asJsonObject.getAsJsonObject("streamSettings").getAsJsonObject("tlsSettings")
            .getAsJsonArray("alpn")[0].asString)
    }

    @Test fun detectsEncryptedClientLinksWithoutMistakingThemForPlainUrls() {
        listOf("happ://crypt/payload", "HAPP://crypt5/payload", "happ://crypto/payload", "incy://crypt1/payload").forEach {
            assertTrue(LinkParser.isEncryptedSubscriptionLink(it))
            assertTrue(LinkParser.isSubscriptionImportLink(it))
            assertNull(LinkParser.normalizeSubscriptionUrl(it))
        }
        assertEquals("https://example.com/sub", LinkParser.normalizeSubscriptionUrl("happ://add/https://example.com/sub"))
    }
}
