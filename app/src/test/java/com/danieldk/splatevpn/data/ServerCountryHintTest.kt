package com.danieldk.splatevpn.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ServerCountryHintTest {

    private fun createNode(id: String, name: String, address: String = "1.2.3.4"): ServerNode =
        ServerNode(id = id, name = name, protocol = "vless", address = address, port = 443)

    @Test
    fun testExtractCountry_fromFlagEmoji() {
        val nodeRu = createNode("1", "🇷🇺 Russia Fast-01")
        val nodeDe = createNode("2", "🇩🇪 Frankfurt 10G")
        val nodeNl = createNode("3", "Amsterdam 🇳🇱")
        val nodeFi = createNode("4", "Helsinki [🇫🇮]")

        assertEquals("RU", ServerCountryHint.extractCountry(nodeRu))
        assertEquals("DE", ServerCountryHint.extractCountry(nodeDe))
        assertEquals("NL", ServerCountryHint.extractCountry(nodeNl))
        assertEquals("FI", ServerCountryHint.extractCountry(nodeFi))
    }

    @Test
    fun testExtractCountry_fromTagOrBracket() {
        val nodeKz = createNode("1", "Almaty [KZ] #12")
        val nodeUs = createNode("2", "US_Ashburn-02")
        val nodeSe = createNode("3", "Stockholm (SE)")

        assertEquals("KZ", ServerCountryHint.extractCountry(nodeKz))
        assertEquals("US", ServerCountryHint.extractCountry(nodeUs))
        assertEquals("SE", ServerCountryHint.extractCountry(nodeSe))
    }

    @Test
    fun testExtractCountry_fromAddressTld() {
        val nodeTld = createNode("1", "Fast Server", address = "node1.example.ru")
        val nodeDe = createNode("2", "Secure Node", address = "de-01.tunnel.de")

        assertEquals("RU", ServerCountryHint.extractCountry(nodeTld))
        assertEquals("DE", ServerCountryHint.extractCountry(nodeDe))
    }

    @Test
    fun testGeoTier_regionRu() {
        val nodeRu = createNode("1", "🇷🇺 Moscow")
        val nodeFi = createNode("2", "🇫🇮 Helsinki")
        val nodeDe = createNode("3", "🇩🇪 Germany")
        val nodeUs = createNode("4", "🇺🇸 United States")

        assertEquals(0, ServerCountryHint.geoTier(nodeRu, preferredRegion = "ru"))
        assertEquals(0, ServerCountryHint.geoTier(nodeFi, preferredRegion = "ru"))
        assertEquals(1, ServerCountryHint.geoTier(nodeDe, preferredRegion = "ru"))
        assertEquals(2, ServerCountryHint.geoTier(nodeUs, preferredRegion = "ru"))
    }

    @Test
    fun testGeoTier_clientCountryOverride() {
        val nodeUs = createNode("1", "🇺🇸 United States")
        // If the client device is located in the US, US node becomes tier 0
        assertEquals(0, ServerCountryHint.geoTier(nodeUs, preferredRegion = "international", clientCountry = "US"))
    }
}
