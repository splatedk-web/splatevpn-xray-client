package com.danieldk.splatevpn.data

import org.junit.Assert.*
import org.junit.Test

class TorEditingPolicyTest {
    @Test fun ordinaryVpnCanStayConnectedWhilePreparingTor() {
        assertTrue(canEditTorSettings(true, false, false))
        assertFalse(canEditTorSettings(true, false, true))
        assertFalse(canEditTorSettings(true, true, true))
        assertFalse(canEditTorSettings(true, true, false))
        assertTrue(canEditTorSettings(false, true, true))
        assertTrue(canEditTorSettings(false, false, true))
    }
}
