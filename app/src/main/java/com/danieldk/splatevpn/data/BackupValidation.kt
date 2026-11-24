package com.danieldk.splatevpn.data

import com.google.gson.Gson
import com.google.gson.JsonParser

/** Validate the complete document before the first database/preference write. */
internal object BackupValidation {
    fun decode(json: String): BackupManager.BackupData {
        val root = JsonParser.parseString(json)
        require(root.isJsonObject) { "Invalid backup document" }
        val obj = root.asJsonObject
        require(obj["app"]?.asString in setOf("SplateVPN", "SplateClient")) { "Not a SplateVPN backup" }
        for (key in listOf("servers", "subscriptions", "perAppPackages", "customRoutes")) {
            require(obj[key]?.isJsonArray == true) { "Invalid backup field: $key" }
            require(obj[key].asJsonArray.none { it.isJsonNull }) { "Null backup entry: $key" }
        }
        require(obj["settings"]?.isJsonObject == true) { "Invalid backup settings" }
        obj["settings"].asJsonObject.entrySet().forEach { (key, value) ->
            require(value.isJsonPrimitive) { "Invalid setting: $key" }
        }
        val rawData = Gson().fromJson(obj, BackupManager.BackupData::class.java)
        require(rawData.servers.size <= 20_000 && rawData.subscriptions.size <= 1_000) { "Too many backup entries" }
        require(rawData.servers.map { it.id }.distinct().size == rawData.servers.size) { "Duplicate server IDs" }
        require(rawData.subscriptions.map { it.id }.distinct().size == rawData.subscriptions.size) { "Duplicate subscription IDs" }

        val rawSubsJson = obj["subscriptions"].asJsonArray
        val subsWithDefaults = rawData.subscriptions.mapIndexed { index, sub ->
            val subJson = rawSubsJson.get(index)?.asJsonObject
            val retainWorking = if (subJson?.has("retainWorkingNodes") == true) sub.retainWorkingNodes else true
            val count = rawData.servers.count { it.subId == sub.id && it.isRetained }
            sub.copy(
                retainWorkingNodes = retainWorking,
                autoUpdate = if (subJson?.has("autoUpdate") == true) sub.autoUpdate else true,
                enabled = if (subJson?.has("enabled") == true) sub.enabled else true,
                lastError = sub.lastError.orEmpty(),
                retainedNodeCount = count,
                importedRulesJson = sub.importedRulesJson ?: "",
                publisherMetadataJson = sub.publisherMetadataJson?.takeIf { it.isNotBlank() }
                    ?.let { SubscriptionMetadata.decode(it).encode() }.orEmpty()
            )
        }

        subsWithDefaults.forEach { sub ->
            require(!sub.id.isNullOrBlank() && !sub.name.isNullOrBlank() && sub.url != null) { "Invalid subscription entry" }
            if (sub.useSubscriptionRules) {
                require(sub.importedRulesSkipped == 0) { "Partially imported rules cannot be enabled" }
                require(!sub.importedRulesJson.isNullOrBlank() && sub.importedRulesJson != "[]") { "Empty rules cannot be enabled" }
                require(runCatching { SubscriptionRoutingRules.validateStored(sub.importedRulesJson) }.isSuccess) { "Invalid routing rules" }
            }
        }

        rawData.servers.forEach {
            require(!it.id.isNullOrBlank() && !it.name.isNullOrBlank() && !it.protocol.isNullOrBlank() &&
                !it.address.isNullOrBlank() && it.port in 1..65535) { "Invalid server entry" }
            require(it.lastVerifiedAt >= 0) { "Invalid lastVerifiedAt" }
            if (it.isRetained) {
                require(it.retainedAt > 0) { "Invalid retainedAt for retained node" }
            } else {
                require(it.retainedAt == 0L) { "Non-retained node must have retainedAt == 0" }
            }
        }

        rawData.servers.forEach { node ->
            if (node.isAmneziaWg) {
                val profile = requireNotNull(node.amneziaWg) { "AWG profile is missing" }.validated()
                val endpoint = AmneziaWg.endpoint(profile.endpoint)
                require(node.address == endpoint.first && node.port == endpoint.second && node.rawLink.isEmpty() && node.fullConfigJson.isNullOrEmpty()) { "Inconsistent AWG profile" }
            } else require(node.amneziaWg == null) { "Unexpected AWG profile" }
        }
        val data = rawData.copy(subscriptions = subsWithDefaults)

        require(data.perAppPackages.size <= 10_000 && data.perAppPackages.all { !it.isNullOrBlank() }) { "Invalid app list" }
        require(data.customRoutes.size <= 10_000 && data.customRoutes.all {
            it.size == 2 && !it[0].isNullOrBlank() && it[1] in setOf("direct", "proxy", "block")
        }) { "Invalid custom routes" }

        val booleans = setOf("glass_blur_enabled", "is_global_app_proxy", "block_quic", "block_ipv6", "ad_blocker",
            "fake_dns", "enable_sniffing", "route_only_sniffing", "bypass_ai", "ai_tls_fragment", "auto_select_server",
            "auto_connect_on_boot", "auto_connect_on_open", "tls_fragment_enabled", "tls_mixed_sni", "mux_enabled", "share_lan_enabled",
            "ui_animations_enabled", "ui_decorative_background_enabled", "ui_glass_highlights_enabled",
            "updates_wifi_only", "checks_wifi_only", "balancer_warm_standby",
            "regional_presets_enabled", "servers_sort_by_ping")

        data.settings.forEach { (key, value) ->
            if (key == "subscription_hwid_v1") {
                require(value is String) { "Invalid HWID preferences" }
                SubscriptionHwidPreferences.decode(value)
            }
            if (key == "subscription_ua_overrides_v1") {
                require(value is String) { "Invalid subscription user agents" }
                SubscriptionUserAgent.decodeOverrides(value)
            }
            if (key == "ui_mode") require(AppUiMode.entries.any { it.id == value }) { "Invalid interface mode" }
            if (key == "last_sub_update_time") {
                require((value as? String)?.toLongOrNull()?.let { it >= 0L } == true) { "Invalid last update time" }
            }
            if (key == "balancer_metric") require(value in BalancerMetric.entries.map { it.name }) { "Invalid balancer metric" }
            if (key == "balancer_goal") require(value in BalancerGoal.entries.map { it.name }) { "Invalid balancer goal" }
            if (key == "balancer_speed_budget_mib") require(value.toString().toDoubleOrNull()?.let { it.isFinite() && it in 16.0..256.0 } == true) { "Invalid probe budget" }
            if (key in booleans || key in NotificationPreferences.keys) {
                require(value is Boolean || value == 0.0 || value == 1.0 || value == "true" || value == "false") { "Invalid boolean: $key" }
            }
            if (key == "glass_transparency") require(value.toString().toFloatOrNull()?.let { it.isFinite() && it in 0f..1f } == true) { "Invalid transparency" }
            if (key == "ad_block_allowlist") {
                val domains = value.toString().lineSequence().map(String::trim).filter { it.isNotEmpty() }.toList()
                require(domains.size <= 100 && domains.all { AdFilterCompiler.normalizeDomain(it) != null }) { "Invalid ad blocker exceptions" }
            }
            if (key == "sub_auto_update_interval") require(value in setOf("off", "1h", "3h", "6h", "12h", "24h")) { "Invalid update interval" }
            if (key == "mux_concurrency") require((value as? Number)?.toDouble() in setOf(4.0, 8.0, 16.0, 32.0)) { "Invalid Mux concurrency" }
            if (key == "app_language") require(value in SettingsManager.APP_LANGUAGES) { "Invalid language" }
        }
        return data
    }
}
