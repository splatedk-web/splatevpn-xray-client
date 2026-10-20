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
        val data = Gson().fromJson(obj, BackupManager.BackupData::class.java)
        require(data.servers.size <= 20_000 && data.subscriptions.size <= 1_000) { "Too many backup entries" }
        require(data.servers.map { it.id }.distinct().size == data.servers.size) { "Duplicate server IDs" }
        require(data.subscriptions.map { it.id }.distinct().size == data.subscriptions.size) { "Duplicate subscription IDs" }
        data.servers.forEach {
            require(!it.id.isNullOrBlank() && !it.name.isNullOrBlank() && !it.protocol.isNullOrBlank() &&
                !it.address.isNullOrBlank() && it.port in 1..65535) { "Invalid server entry" }
        }
        data.subscriptions.forEach {
            require(!it.id.isNullOrBlank() && !it.name.isNullOrBlank() && it.url != null) { "Invalid subscription entry" }
        }
        require(data.perAppPackages.size <= 10_000 && data.perAppPackages.all { !it.isNullOrBlank() }) { "Invalid app list" }
        require(data.customRoutes.size <= 10_000 && data.customRoutes.all {
            it.size == 2 && !it[0].isNullOrBlank() && it[1] in setOf("direct", "proxy", "block")
        }) { "Invalid custom routes" }
        val booleans = setOf("glass_blur_enabled", "is_global_app_proxy", "block_quic", "block_ipv6", "ad_blocker",
            "fake_dns", "enable_sniffing", "route_only_sniffing", "bypass_ai", "ai_tls_fragment", "auto_select_server",
            "auto_connect_on_boot", "tls_fragment_enabled", "mux_enabled", "share_lan_enabled")
        data.settings.forEach { (key, value) ->
            if (key in booleans) require(value is Boolean || value == 0.0 || value == 1.0 || value == "true" || value == "false") { "Invalid boolean: $key" }
            if (key == "glass_transparency") require(value.toString().toFloatOrNull()?.let { it.isFinite() && it in 0f..1f } == true) { "Invalid transparency" }
            if (key == "ad_block_allowlist") {
                val domains = value.toString().lineSequence().map(String::trim).filter { it.isNotEmpty() }.toList()
                require(domains.size <= 100 && domains.all { AdFilterCompiler.normalizeDomain(it) != null }) { "Invalid ad blocker exceptions" }
            }
            if (key == "sub_auto_update_interval") require(value in setOf("off", "6h", "12h", "24h")) { "Invalid update interval" }
            if (key == "mux_concurrency") require((value as? Number)?.toDouble() in setOf(4.0, 8.0, 16.0, 32.0)) { "Invalid Mux concurrency" }
            if (key == "app_language") require(value in setOf("system", "ru", "en")) { "Invalid language" }
        }
        return data
    }
}
