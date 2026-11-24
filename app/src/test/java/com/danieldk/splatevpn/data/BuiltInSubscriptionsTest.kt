package com.danieldk.splatevpn.data

import com.danieldk.splatevpn.SplateConstants
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BuiltInSubscriptionsTest {
    @Test fun everyBundledSourceHasImportableProfilesAndStableDistinctIdentity() {
        val expectedIds = if (SplateConstants.PRESET_SUB_SPONSOR_URL.isBlank()) {
            SplateConstants.PRESET_SUB_IDS - SplateConstants.PRESET_SUB_SPONSOR_ID
        } else {
            SplateConstants.PRESET_SUB_IDS
        }
        assertEquals(expectedIds, builtInSubscriptions.map { it.id }.toSet())
        assertEquals(builtInSubscriptions.size, builtInSubscriptions.map { it.id }.distinct().size)
        assertEquals(4, builtInSubscriptions.count { it.asset.isNotBlank() && it.id != SplateConstants.PRESET_SUB_HARVESTER_ID })
        for (source in builtInSubscriptions) {
            assertTrue(source.url.startsWith("https://"))
            if (source.group in setOf(PresetGroup.SPONSOR, PresetGroup.RKP)) {
                assertTrue("Remote-only source must not ship a cached profile list", source.asset.isBlank())
                continue
            }
            val asset = File("src/main/assets/${source.asset}").takeIf { it.isFile }
                ?: File("app/src/main/assets/${source.asset}")
            assertTrue("Missing snapshot for ${source.id}", asset.isFile)
            assertTrue(asset.length() <= SubscriptionTransport.MAX_BYTES)
            val nodes = parseUsableSubscription(asset.readText(Charsets.UTF_8), source.id, source.name)
            assertTrue("Not a VPN subscription: ${source.id}", nodes.isNotEmpty())
            assertTrue(nodes.size <= SubscriptionTransport.MAX_NODES)
            nodes.forEach {
                assertEquals(source.id, it.subId)
                assertEquals(source.name, it.subName)
                assertTrue(it.address.isNotBlank())
                assertTrue(it.port in 1..65535)
            }
        }
    }
}
