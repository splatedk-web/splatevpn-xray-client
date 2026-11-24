package com.danieldk.splatevpn.data

import org.junit.Assert.*
import org.junit.Test

class BalancerPreferencesTest {
    @Test fun initialOnlyNeverMaintainsStandbyAndGeneralNeedsNoExtraQualification() {
        assertFalse(BalancerPreferences(mode = BalancerMode.INITIAL_ONLY).maintainsStandby)
        assertFalse(BalancerPreferences(warmStandby = false).maintainsStandby)
        assertTrue(BalancerPreferences(mode = BalancerMode.FAILOVER).maintainsStandby)
        assertFalse(BalancerPreferences(metric = BalancerMetric.DOWNLOAD).needsServiceQualification)
        assertTrue(BalancerPreferences(goal = BalancerGoal.YOUTUBE).needsServiceQualification)
    }
    @Test fun defaultsPreserveAutomaticPolicy() {
        val value = BalancerPreferences()
        assertTrue(value.monitorsConnection)
        assertTrue(value.optimizesConnection)
        assertEquals(10, value.healthIntervalSeconds)
        assertEquals(3, value.failureConfirmations)
        assertEquals(5, value.optimizationMinutes)
        assertEquals(60, value.cooldownSeconds)
        assertEquals(30, value.improvementPercent)
    }
    @Test fun failoverKeepsWorkingNodeAndInitialOnlyNeverSwitches() {
        assertTrue(BalancerPreferences(mode = BalancerMode.FAILOVER).monitorsConnection)
        assertFalse(BalancerPreferences(mode = BalancerMode.FAILOVER).optimizesConnection)
        assertFalse(BalancerPreferences(mode = BalancerMode.INITIAL_ONLY).monitorsConnection)
        assertFalse(BalancerPreferences(mode = BalancerMode.INITIAL_ONLY).optimizesConnection)
    }
    @Test fun invalidImportedValuesAreBounded() {
        val value = BalancerPreferences(healthIntervalSeconds = -1, failureConfirmations = 999,
            optimizationMinutes = 0, cooldownSeconds = -100, improvementPercent = 101).validated()
        assertEquals(2, value.healthIntervalSeconds)
        assertEquals(5, value.failureConfirmations)
        assertEquals(5, value.optimizationMinutes)
        assertEquals(60, value.cooldownSeconds)
        assertEquals(80, value.improvementPercent)
    }
}
