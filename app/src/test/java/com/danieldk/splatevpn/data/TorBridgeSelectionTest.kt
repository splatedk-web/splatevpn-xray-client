package com.danieldk.splatevpn.data

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class TorBridgeSelectionTest {
    private val fingerprint = "A".repeat(40)
    private val vanilla = "198.51.100.2:443 $fingerprint"
    private val webtunnel = "webtunnel [2001:db8:75a1:8038:7326:46ce:b078:370b]:443 $fingerprint url=https://bridge.example/path ver=0.0.7"
    private val cert = Base64.getEncoder().withoutPadding().encodeToString(ByteArray(52) { it.toByte() })
    private val obfs4 = "obfs4 198.51.100.3:8443 $fingerprint cert=$cert iat-mode=0"

    @Test fun publicListKeepsValidBridgesAndReportsMalformedFingerprint() {
        val source = "# profile-title: TOP 100 TOR BRIDGES\n$webtunnel\n$vanilla\n198.51.100.4:443 ${"B".repeat(41)}\n$obfs4"
        val imported = TorConfigParser.parseSubscription(source)
        assertEquals(listOf(webtunnel, vanilla, obfs4), imported.bridges)
        assertEquals(1, imported.skippedBridges)
        assertEquals(true, imported.useBridges)
        assertThrows(IllegalArgumentException::class.java) { TorConfigParser.parse(source) }
    }

    @Test fun chatMessageImportsMarkdownWebtunnelWithoutItsSurroundingGuide() {
        val linked = webtunnel.replace("url=https://bridge.example/path", "url=[https://bridge.example/path](https://bridge.example/path)")
        val message = "**Сначала VANILLA:**\n$vanilla\n\n**Затем WEBTUNNEL:**\n$linked\n\nПодождите завершения подключения."
        val imported = TorConfigParser.parsePasted(message)
        assertEquals(listOf(vanilla, webtunnel), imported.bridges)
    }

    @Test fun unsafeDirectivesAreRejectedInSubscriptionAndChatImports() {
        assertThrows(IllegalArgumentException::class.java) { TorConfigParser.parseSubscription("$vanilla\nSocksPort 0.0.0.0:9050") }
        assertThrows(IllegalArgumentException::class.java) { TorConfigParser.parsePasted("Мои мосты\n$vanilla\nClientTransportPlugin obfs4 exec /bin/sh") }
        assertThrows(IllegalArgumentException::class.java) { TorConfigParser.parseSubscription("198.51.100.2:443 BAD") }
        assertThrows(IllegalArgumentException::class.java) { TorConfigParser.validateBridge(webtunnel.replace("url=https://bridge.example/path", "url=[https://visible.example/path](https://other.example/path)")) }
    }

    @Test fun ipv6BridgeListIsNotMistakenForJson() {
        val bridge = "[2001:db8::2]:443 $fingerprint"
        assertEquals(listOf(bridge), TorConfigParser.parseSubscription(bridge).bridges)
        assertEquals(listOf(bridge), TorConfigParser.parsePasted(bridge).bridges)
        assertEquals(listOf(vanilla), TorConfigParser.parseSubscription("[\"$vanilla\"]").bridges)
    }

    @Test fun automaticChoiceUsesOneVanillaBridgeAndKeepsTheSubscriptionIntact() {
        val settings = TorSettings(useBridges = true, bridges = listOf(webtunnel, obfs4, vanilla))
        assertEquals(listOf(vanilla), settings.forConnection().bridges)
        assertEquals(3, settings.bridges.size)
        assertEquals(listOf(webtunnel), settings.copy(bridgeTransport = "webtunnel").forConnection().bridges)
        assertEquals(listOf(obfs4), settings.copy(selectedBridge = obfs4).forConnection().bridges)
    }

    @Test fun missingTransportDoesNotFallBackToDirectTor() {
        assertThrows(IllegalStateException::class.java) {
            TorSettings(useBridges = true, bridges = listOf(vanilla), bridgeTransport = "webtunnel").forConnection()
        }
        assertThrows(IllegalArgumentException::class.java) { TorConfigParser.validate(TorSettings(bridgeTransport = "unknown")) }
    }

    @Test fun githubRawAndBlobLinksBothNormalizeToRawContent() {
        val expected = "https://raw.githubusercontent.com/owner/repo/refs/heads/main/tor.txt"
        assertEquals(expected, TorConfigParser.normalizeSubscriptionUrl("https://github.com/owner/repo/raw/refs/heads/main/tor.txt"))
        assertEquals(expected, TorConfigParser.normalizeSubscriptionUrl("https://github.com/owner/repo/blob/refs/heads/main/tor.txt"))
    }
}
