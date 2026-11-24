package com.danieldk.splatevpn.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class ConnectionProfile(val id: String, val name: String, val snapshot: String)

internal object NetworkPreferences {
    val keys = setOf("advanced_routes_v1", "routing_region", "routing_mode", "is_global_app_proxy", "per_app_mode", "per_app_packages", "block_quic", "block_ipv6", "ad_blocker", "ad_block_allowlist", "fake_dns", "bypass_ai", "ai_routing_mode", "ai_dns_provider", "ai_tls_fragment", "ai_dns_auto_switch", "enable_sniffing", "route_only_sniffing", "dns_provider", "xhttp_mode", "tls_fragment_enabled", "tls_fragment_preset", "tls_fragment_length", "tls_fragment_interval", "tls_fragment_packets", "custom_doh_url", "mux_enabled", "mux_concurrency", "custom_routes_json", "share_lan_enabled", "lan_interface", "lan_auth_enabled")
    fun capture(values: Map<String, *>): Map<String, *> = values.filterKeys { it in keys }
    fun validate(values: Map<String, *>) {
        require(values.keys.all { it in keys }) { "Unsupported profile fields" }
        val sets = setOf("per_app_packages", "ad_block_allowlist")
        val booleans = setOf("is_global_app_proxy", "block_quic", "block_ipv6", "ad_blocker", "fake_dns", "bypass_ai", "ai_tls_fragment", "ai_dns_auto_switch", "enable_sniffing", "route_only_sniffing", "tls_fragment_enabled", "mux_enabled", "share_lan_enabled", "lan_auth_enabled")
        values.forEach { (key, value) ->
            require(when (key) {
                in sets -> value is Set<*> && value.all { it is String && it.length <= 512 }
                in booleans -> value is Boolean
                "mux_concurrency" -> value is Int && value in -1..1024
                else -> value is String && value.length <= 256_000
            }) { "Invalid profile field: $key" }
        }
        require(values["routing_mode"] == null || values["routing_mode"] in setOf("smart_ru", "smart", "global"))
        (values["advanced_routes_v1"] as? String)?.let(AdvancedRoutingRules::validateEncoded)
        require(values["ai_routing_mode"] == null || values["ai_routing_mode"] in setOf("vpn", "dns_ai", "dns_vpn"))
        require(values["routing_region"] == null || RoutingRegion.entries.any { it.id == values["routing_region"] })
        val global = values["is_global_app_proxy"] as? Boolean ?: true
        val mode = values["per_app_mode"] as? String ?: "include"
        require(mode in setOf("include", "exclude"))
        require(global || mode != "include" || (values["per_app_packages"] as? Set<*>)?.isNotEmpty() == true) { "Select at least one VPN application" }
    }
    fun apply(current: Map<String, *>, profile: Map<String, *>): Map<String, *> {
        validate(profile)
        return current.filterKeys { it !in keys } + profile +
            if (profile["routing_mode"] == "smart_ru") mapOf("routing_mode" to "smart", "routing_region" to "ru") else emptyMap()
    }
    fun builtIn(id: String, current: Map<String, *>): Map<String, *> {
        val values = capture(current).toMutableMap()
        when (id) {
            "smart" -> { values["routing_mode"] = "smart" }
            "all" -> { values["routing_mode"] = "global"; values["is_global_app_proxy"] = true; values["ai_routing_mode"] = "vpn"; values["bypass_ai"] = true; values["share_lan_enabled"] = false }
            "ai" -> { values["routing_mode"] = "smart"; values["ai_routing_mode"] = "vpn"; values["bypass_ai"] = true }
            else -> error("Unknown connection profile")
        }
        validate(values)
        return values
    }
}

@Singleton
class ConnectionProfiles @Inject constructor(private val settings: SettingsManager) {
    private val gson = Gson()
    private val _profiles = MutableStateFlow(read())
    val profiles = _profiles.asStateFlow()
    private val _canUndo = MutableStateFlow(settings.connectionProfileUndo() != null)
    val canUndo = _canUndo.asStateFlow()
    internal fun reload() { _profiles.value = read(); _canUndo.value = settings.connectionProfileUndo() != null }
    internal companion object {
        fun validateEncodedProfiles(json: String) {
            require(json.length <= 2_100_000) { "Profiles are too large" }
            val entries = com.google.gson.JsonParser.parseString(json).asJsonArray
            require(entries.size() <= 8)
            val ids = mutableSetOf<String>()
            entries.forEach { raw ->
                val entry = raw.asJsonObject
                val id = entry["id"].asString
                require(id.isNotBlank() && ids.add(id))
                require(entry["name"].asString.trim().length in 1..48)
                val snapshot = entry["snapshot"].asString
                require(snapshot.length <= 256_000)
                NetworkPreferences.validate(PreferenceValues.decode(snapshot))
            }
        }
    }
    private fun read(): List<ConnectionProfile> = runCatching {
        gson.fromJson<List<ConnectionProfile>>(settings.connectionProfilesJson(), object : TypeToken<List<ConnectionProfile>>() {}.type)
            ?.filter { it.id.isNotBlank() && it.name.length in 1..48 && it.snapshot.length <= 256_000 }?.take(8).orEmpty()
    }.getOrDefault(emptyList())

    @Synchronized fun save(name: String) {
        val trimmed = name.trim()
        require(trimmed.length in 1..48) { "Profile name must contain 1–48 characters" }
        require(_profiles.value.size < 8) { "A maximum of eight profiles is supported" }
        val snapshot = NetworkPreferences.capture(settings.preferenceSnapshot())
        NetworkPreferences.validate(snapshot)
        val encoded = PreferenceValues.encode(snapshot)
        require(encoded.length <= 256_000) { "Profile is too large" }
        val profile = ConnectionProfile(UUID.randomUUID().toString(), trimmed, encoded)
        val next = _profiles.value + profile
        settings.storeConnectionProfiles(gson.toJson(next))
        _profiles.value = next
    }

    @Synchronized fun delete(id: String) {
        val next = _profiles.value.filterNot { it.id == id }
        settings.storeConnectionProfiles(gson.toJson(next)); _profiles.value = next
    }

    fun target(id: String): Map<String, *> = (_profiles.value.firstOrNull { it.id == id }?.let { PreferenceValues.decode(it.snapshot) }
        ?: NetworkPreferences.builtIn(id, settings.preferenceSnapshot())).also(NetworkPreferences::validate)

    @Synchronized fun apply(id: String, reviewed: Map<String, *>? = null): Boolean {
        val current = settings.preferenceSnapshot()
        val target = target(id)
        require(reviewed == null || reviewed == target) { "Settings changed since the preview. Review the profile again." }
        val merged = NetworkPreferences.apply(current, target).toMutableMap()
        merged["connection_profile_undo_v1"] = PreferenceValues.encode(NetworkPreferences.capture(current))
        settings.restorePreferenceSnapshot(merged)
        _canUndo.value = true
        return NetworkPreferences.capture(current) != target
    }

    @Synchronized fun undo(): Boolean {
        val previous = settings.connectionProfileUndo() ?: return false
        val current = settings.preferenceSnapshot()
        val restored = NetworkPreferences.apply(current, PreferenceValues.decode(previous)).toMutableMap()
        restored.remove("connection_profile_undo_v1")
        settings.restorePreferenceSnapshot(restored)
        _canUndo.value = false
        return NetworkPreferences.capture(current) != NetworkPreferences.capture(restored)
    }
}
