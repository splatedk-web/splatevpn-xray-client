package com.danieldk.splatevpn.vpn

import com.danieldk.splatevpn.data.LinkParser
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class XDriveImportPolicyTest {
    private fun profile(service: String = "google-drive", encryption: String = "mlkem768x25519plus.native.0rtt.test-key", flow: String = ""): String =
        """{"outbounds":[{"protocol":"vless","settings":{"address":"cloud.example","port":443,"id":"11111111-2222-3333-4444-555555555555","encryption":"$encryption","flow":"$flow"},"streamSettings":{"network":"xdrive","xdriveSettings":{"service":"$service","remoteFolder":"folder","secrets":["client","secret","refresh"]}},"mux":{"enabled":false,"concurrency":4}}]}"""

    @Test fun keepsRequiredMuxAndStorageWithGlobalMuxOffAndFragmentOn() {
        val node = LinkParser.parseSubscription(profile()).single()
        val config = JsonParser.parseString(XrayConfigGenerator().generateConfig(node,
            enableMux = false, enableTlsFragment = true)).asJsonObject
        val proxy = config.getAsJsonArray("outbounds")[0].asJsonObject
        assertEquals("xdrive", proxy.getAsJsonObject("streamSettings")["network"].asString)
        assertTrue(proxy.getAsJsonObject("mux")["enabled"].asBoolean)
        assertEquals(4, proxy.getAsJsonObject("mux")["concurrency"].asInt)
        assertFalse(proxy.getAsJsonObject("streamSettings").getAsJsonObject("sockopt")?.has("dialerProxy") == true)
        assertEquals("folder", proxy.getAsJsonObject("streamSettings").getAsJsonObject("xdriveSettings")["remoteFolder"].asString)
    }

    @Test fun rejectsLocalFilesystemTransportAndUnencryptedOrFlowProfiles() {
        listOf(profile(service = "local"), profile(encryption = "none"), profile(flow = "xtls-rprx-vision")).forEach { raw ->
            val config = JsonParser.parseString("""{"outbounds":[{"tag":"proxy","protocol":"vless"}]}""").asJsonObject
            assertThrows(IllegalArgumentException::class.java) {
                ImportedOutboundPolicy.apply(config, raw, mux = false, fragment = false, xhttpMode = "auto")
            }
        }
    }
}
