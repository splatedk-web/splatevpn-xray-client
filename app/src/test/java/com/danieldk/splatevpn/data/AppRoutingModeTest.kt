package com.danieldk.splatevpn.data

import org.junit.Assert.*
import org.junit.Test

class AppRoutingModeTest {
    @Test fun selectedAndUnselectedApplicationsHaveOppositeRulesInSelectiveModes() {
        val packages = setOf("browser")
        assertTrue(AppRoutingMode.INCLUDE.usesVpn("browser", packages))
        assertFalse(AppRoutingMode.INCLUDE.usesVpn("bank", packages))
        assertFalse(AppRoutingMode.EXCLUDE.usesVpn("browser", packages))
        assertTrue(AppRoutingMode.EXCLUDE.usesVpn("bank", packages))
        assertTrue(AppRoutingMode.ALL.usesVpn("browser", packages))
        assertTrue(AppRoutingMode.ALL.usesVpn("bank", packages))
    }

    @Test fun emptyIncludeCannotBeSavedAsAnAccidentalGlobalRule() {
        assertFalse(AppRoutingMode.INCLUDE.canApply(emptySet()))
        assertTrue(AppRoutingMode.EXCLUDE.canApply(emptySet()))
        assertTrue(AppRoutingMode.ALL.canApply(emptySet()))
        assertTrue(AppRoutingMode.INCLUDE.canApply(setOf("browser")))
    }

    @Test fun existingPreferencesMapToThreeExplicitModes() {
        assertEquals(AppRoutingMode.ALL, AppRoutingMode.fromSettings(true, "include"))
        assertEquals(AppRoutingMode.ALL, AppRoutingMode.fromSettings(true, "exclude"))
        assertEquals(AppRoutingMode.INCLUDE, AppRoutingMode.fromSettings(false, "include"))
        assertEquals(AppRoutingMode.EXCLUDE, AppRoutingMode.fromSettings(false, "exclude"))
    }
}
