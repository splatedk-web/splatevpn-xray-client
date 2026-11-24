package com.danieldk.splatevpn.vpn
import org.junit.Assert.*
import org.junit.Test
class TorTrafficHealthCounterTest {
    @Test fun aLiveButStalledConnectionRequiresThreeTrafficFailures() {
        val health = TorTrafficHealthCounter<String>()
        assertFalse(health.record("wifi", "wifi", false))
        assertFalse(health.record("wifi", "wifi", false))
        assertTrue(health.record("wifi", "wifi", false))
    }
    @Test fun handoverAndAbsentNetworkCannotAccumulateFailures() {
        val health = TorTrafficHealthCounter<String>()
        repeat(2) { assertFalse(health.record("wifi", "wifi", false)) }
        assertFalse(health.record("wifi", "cell", false))
        repeat(2) { assertFalse(health.record("cell", "cell", false)) }
        assertFalse(health.record(null, null, false))
        repeat(2) { assertFalse(health.record("cell", "cell", false)) }
    }
    @Test fun successAndPausedChecksResetTheFailureSequence() {
        val health = TorTrafficHealthCounter<String>()
        repeat(2) { health.record("wifi", "wifi", false) }
        assertFalse(health.record("wifi", "wifi", true))
        repeat(2) { assertFalse(health.record("wifi", "wifi", false)) }
        health.reset()
        repeat(2) { assertFalse(health.record("wifi", "wifi", false)) }
    }
}
