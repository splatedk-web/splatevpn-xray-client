package com.danieldk.splatevpn.vpn

import org.junit.Assert.*
import org.junit.Test

class PhysicalNetworkSelectionTest {
    private val mobile = PhysicalNetworkCandidate("mobile", true, true, true, true, false)
    private val wifi = PhysicalNetworkCandidate("wifi", true, true, true, false, true)

    @Test fun captiveWifiCannotDisplaceValidatedMobile() {
        assertEquals("mobile", selectPhysicalNetwork(listOf(wifi, mobile), "wifi"))
    }
    @Test fun auxiliaryVpnAndBlockedNetworksAreNeverFallbacks() {
        assertNull(selectPhysicalNetwork(listOf(wifi.copy(internet = false), mobile.copy(unrestricted = false),
            mobile.copy(notVpn = false), mobile.copy(blocked = true)), "mobile"))
    }
    @Test fun unvalidatedInternetStillWorksWhenAndroidValidationIsBlocked() {
        assertEquals("wifi", selectPhysicalNetwork(listOf(wifi), null))
    }
    @Test fun sameQualityNetworkDoesNotCauseRepeatedHandovers() {
        assertEquals("mobile", selectPhysicalNetwork(listOf(mobile.copy(network = "mobile2"), mobile), "mobile"))
        assertEquals("wifi", selectPhysicalNetwork(listOf(wifi.copy(validated = true), mobile), "mobile"))
        assertEquals("wifi", selectPhysicalNetwork(listOf(wifi.copy(validated = true)), "mobile"))
        assertNull(selectPhysicalNetwork(emptyList(), "mobile"))
    }
}
