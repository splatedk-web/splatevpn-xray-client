package com.danieldk.splatevpn.data

import org.junit.Assert.*
import org.junit.Test

class ConnectionProfilesTest {
    @Test fun tunneledSmartDnsSurvivesProfileRoundTrip() {
        val snapshot = mapOf("ai_routing_mode" to "dns_vpn", "ai_dns_provider" to "geohide", "bypass_ai" to true, "ai_dns_auto_switch" to false)
        val decoded = PreferenceValues.decode(PreferenceValues.encode(snapshot))
        assertEquals(snapshot, NetworkPreferences.apply(emptyMap<String, Any>(), decoded))
    }
    @Test fun snapshotRoundTripPreservesTypesAndLargeLongValues() {
        val source = mapOf("long" to Long.MAX_VALUE, "int" to 7, "float" to 0.42f,
            "bool" to false, "string" to "Привет", "set" to setOf("a", "b"))
        assertEquals(source, PreferenceValues.decode(PreferenceValues.encode(source)))
    }

    @Test fun applyingNetworkProfilePreservesAppearanceServersAndSubscriptions() {
        val source = mapOf("app_theme" to "dark", "selected_server_name" to "private", "sub_url" to "secret",
            "routing_mode" to "smart_ru", "is_global_app_proxy" to false, "per_app_mode" to "include",
            "per_app_packages" to setOf("browser"), "custom_routes_json" to "[[\"bank.ru\",\"direct\"]]")
        val target = NetworkPreferences.builtIn("all", source)
        val result = NetworkPreferences.apply(source, target)
        assertEquals("global", result["routing_mode"])
        assertEquals(true, result["is_global_app_proxy"])
        assertEquals("dark", result["app_theme"])
        assertEquals("private", result["selected_server_name"])
        assertEquals("secret", result["sub_url"])
        assertEquals(source["custom_routes_json"], result["custom_routes_json"])
        assertFalse(target.containsKey("sub_url"))
    }

    @Test fun malformedTypesAndEmptyIncludeFailBeforeChangesAreApplied() {
        listOf(mapOf("block_ipv6" to "false"), mapOf("mux_concurrency" to "8"),
            mapOf("per_app_packages" to setOf(42)), mapOf("unrecognized" to true),
            mapOf("is_global_app_proxy" to false, "per_app_mode" to "include", "per_app_packages" to emptySet<String>()))
            .forEach { bad ->
                try { NetworkPreferences.apply(mapOf("app_theme" to "dark"), bad); fail("Invalid profile accepted: $bad") }
                catch (_: IllegalArgumentException) { }
            }
    }

    @Test fun aiProfileUsesVpnAndRetainsApplicationScope() {
        val current = mapOf("is_global_app_proxy" to false, "per_app_mode" to "exclude", "per_app_packages" to setOf("bank"))
        val ai = NetworkPreferences.builtIn("ai", current)
        assertEquals("vpn", ai["ai_routing_mode"])
        assertEquals("smart", ai["routing_mode"])
        assertEquals(false, ai["is_global_app_proxy"])
        assertEquals(setOf("bank"), ai["per_app_packages"])
    }
}
