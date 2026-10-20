package com.danieldk.splatevpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class VpnProtectionStatusTest {
    @Test fun disconnectedFalseFlagsAreNotProofThatProtectionIsDisabled() {
        assertEquals(VpnProtectionStatus.Level.UNKNOWN, VpnProtectionStatus.fromSystem(false, false, false).level)
        assertEquals(VpnProtectionStatus.Level.OFF, VpnProtectionStatus.fromSystem(false, false, true).level)
        assertEquals(VpnProtectionStatus.Level.PROTECTED, VpnProtectionStatus.fromSystem(true, true, false).level)
    }
    @Test fun alwaysOnWithoutBlockingIsNotKillSwitchProtection() {
        assertEquals(VpnProtectionStatus.Level.ALWAYS_ON_ONLY, VpnProtectionStatus(true, false).level)
        assertEquals(VpnProtectionStatus.Level.OFF, VpnProtectionStatus(false, false).level)
    }

    @Test fun onlyBothConfirmedSystemFlagsReportProtection() {
        assertEquals(VpnProtectionStatus.Level.PROTECTED, VpnProtectionStatus(true, true).level)
        assertEquals(VpnProtectionStatus.Level.UNKNOWN, VpnProtectionStatus(false, true).level)
    }

    @Test fun MissingOrPartialObservationsNeverReportProtection() {
        listOf(VpnProtectionStatus(), VpnProtectionStatus(true, null), VpnProtectionStatus(null, true)).forEach {
            assertEquals(VpnProtectionStatus.Level.UNKNOWN, it.level)
        }
    }
}
