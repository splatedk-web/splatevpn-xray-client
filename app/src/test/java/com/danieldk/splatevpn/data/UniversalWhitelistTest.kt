package com.danieldk.splatevpn.data

import com.danieldk.splatevpn.SplateConstants
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class UniversalWhitelistTest {
    @Test fun bundledSubscriptionImportsEveryVlessLinkWithItsSource() {
        val asset = File("src/main/assets/preset_white_universal.txt")
            .takeIf { it.exists() } ?: File("app/src/main/assets/preset_white_universal.txt")
        val content = asset.readText(Charsets.UTF_8)
        val links = content.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val nodes = LinkParser.parseSubscription(content, SplateConstants.PRESET_SUB_UNIVERSAL_ID, SplateConstants.PRESET_SUB_UNIVERSAL_NAME)
        assertTrue("The bundled whitelist must provide usable configurations offline", links.isNotEmpty())
        assertEquals("No configurations from the bundled source may be silently dropped", links.size, nodes.size)
        nodes.forEach { node ->
            assertEquals("vless", node.protocol)
            assertEquals(SplateConstants.PRESET_SUB_UNIVERSAL_ID, node.subId)
            assertEquals(SplateConstants.PRESET_SUB_UNIVERSAL_NAME, node.subName)
            assertTrue(node.address.isNotBlank())
            assertTrue(node.port in 1..65535)
        }
        assertEquals(links.toSet(), nodes.map { it.rawLink }.toSet())
    }
}
