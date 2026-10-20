package com.danieldk.splatevpn.ui.screens

import com.danieldk.splatevpn.SplateConstants
import com.danieldk.splatevpn.ui.theme.EnglishAppStrings
import com.danieldk.splatevpn.ui.theme.RussianAppStrings
import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionLabelsTest {
    @Test fun builtInNamesFollowTheInterfaceLanguage() {
        val id = SplateConstants.PRESET_SUB_CIDR_ID
        val name = SplateConstants.PRESET_SUB_CIDR_NAME
        assertEquals("Allowlist (CIDR RU)", subscriptionDisplayName(id, name, EnglishAppStrings()))
        assertEquals(name, subscriptionDisplayName(id, name, RussianAppStrings()))
    }

    @Test fun userNamesArePreservedEvenForBuiltInSubscriptions() {
        assertEquals("My custom name", subscriptionDisplayName(SplateConstants.PRESET_SUB_CIDR_ID, "My custom name", EnglishAppStrings()))
        assertEquals(SplateConstants.PRESET_SUB_CIDR_NAME,
            subscriptionDisplayName("custom-id", SplateConstants.PRESET_SUB_CIDR_NAME, EnglishAppStrings()))
    }
}
