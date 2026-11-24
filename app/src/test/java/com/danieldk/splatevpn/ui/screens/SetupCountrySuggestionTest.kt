package com.danieldk.splatevpn.ui.screens

import com.danieldk.splatevpn.data.RoutingRegion
import org.junit.Assert.*
import org.junit.Test

class SetupCountrySuggestionTest {
    @Test fun networkCountryWinsWhenRoaming() {
        val result = requireNotNull(selectSetupCountry("de", "ru", "CN"))
        assertEquals("DE", result.countryCode)
        assertEquals(SetupCountrySource.NETWORK, result.source)
        assertEquals(RoutingRegion.INTERNATIONAL, result.region)
    }
    @Test fun simCountryUsedWhenNetworkMissing() {
        val result = requireNotNull(selectSetupCountry("", " ir ", "US"))
        assertEquals("IR", result.countryCode)
        assertEquals(SetupCountrySource.SIM, result.source)
        assertEquals(RoutingRegion.IRAN, result.region)
    }
    @Test fun tabletsCanFallBackToDeviceRegion() {
        val result = requireNotNull(selectSetupCountry(null, null, "RU"))
        assertEquals(SetupCountrySource.DEVICE, result.source)
        assertEquals(RoutingRegion.RUSSIA, result.region)
    }
    @Test fun malformedAndUnknownCountryCodesAreIgnored() {
        assertNull(selectSetupCountry("ZZ", "123", ""))
        assertEquals("CN", selectSetupCountry("../ru", "zz", "CN")?.countryCode)
        assertEquals("🌐", countryFlag("international"))
    }
    @Test fun countryFlagUsesNormalizedIsoCode() {
        assertEquals("🇩🇪", countryFlag("de"))
    }
}
