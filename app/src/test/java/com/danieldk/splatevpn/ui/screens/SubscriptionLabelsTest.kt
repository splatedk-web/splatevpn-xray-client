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

    @Test fun additionalWhitelistPublishersKeepTheirBrandAndUserRenames() {
        listOf(
            Triple(SplateConstants.PRESET_SUB_LTE_ID, SplateConstants.PRESET_SUB_LTE_NAME, "LTE Checked"),
            Triple(SplateConstants.PRESET_SUB_BLACK_ID, SplateConstants.PRESET_SUB_BLACK_NAME, "Black VLESS")
        ).forEach { (id, name, publisher) ->
            val expected = if (id == SplateConstants.PRESET_SUB_BLACK_ID) "Blacklist ($publisher)" else "Allowlist ($publisher)"
            assertEquals(expected, subscriptionDisplayName(id, name, EnglishAppStrings()))
            assertEquals(name, subscriptionDisplayName(id, name, RussianAppStrings()))
            assertEquals("My source", subscriptionDisplayName(id, "My source", EnglishAppStrings()))
        }
    }
}
