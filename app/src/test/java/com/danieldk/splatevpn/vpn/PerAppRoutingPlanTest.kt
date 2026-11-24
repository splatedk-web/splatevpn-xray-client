package com.danieldk.splatevpn.vpn

import org.junit.Assert.*
import org.junit.Test

class PerAppRoutingPlanTest {
    @Test(expected = IllegalArgumentException::class)
    fun emptyIncludeCannotBecomeAllApplications() {
        PerAppRoutingPlan.resolve(false, "include", emptySet(), "vpn") { true }
    }
    @Test(expected = IllegalArgumentException::class)
    fun allSelectedApplicationsUninstalledCannotBecomeAllApplications() {
        PerAppRoutingPlan.resolve(false, "include", setOf("removed"), "vpn") { false }
    }
    @Test fun stalePackagesAreRemovedAndAllowedAndExcludedNeverMix() {
        val include = PerAppRoutingPlan.resolve(false, "include", setOf("present", "removed", "vpn"), "vpn") { it == "present" }
        assertEquals(setOf("present"), include.allowed)
        assertTrue(include.excluded.isEmpty())
        val exclude = PerAppRoutingPlan.resolve(false, "exclude", setOf("present", "removed"), "vpn") { it == "present" }
        assertEquals(setOf("present", "vpn"), exclude.excluded)
        assertTrue(exclude.allowed.isEmpty())
    }
    @Test fun globalAlwaysExcludesTheVpnOwner() {
        assertEquals(setOf("vpn"), PerAppRoutingPlan.resolve(true, "include", emptySet(), "vpn") { false }.excluded)
    }
}
