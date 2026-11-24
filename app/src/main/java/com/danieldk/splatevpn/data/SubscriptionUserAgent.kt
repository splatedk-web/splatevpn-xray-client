package com.danieldk.splatevpn.data

import com.danieldk.splatevpn.BuildConfig
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Product tokens for provider format negotiation. Device identity is configured separately. */
enum class SubscriptionUserAgent(val id: String, val label: String, val header: String?) {
    AUTO("auto", "Auto", null),
    SPLATE("splate", "SplateVPN", "SplateVPN/${BuildConfig.VERSION_NAME}"),
    HAPP("happ", "Happ", "Happ"),
    V2RAY("v2ray", "v2rayNG", "v2rayNG/1.8.5"),
    KARING("karing", "Karing", "Karing"),
    INCY("incy", "INCY", "INCY/3.9.9/Android"),
    CLASH("clash", "Clash Meta", "ClashMeta/1.18.0");

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: AUTO

        private val sourceKey = Regex("[a-f0-9]{64}")

        internal fun decodeOverrides(json: String): Map<String, SubscriptionUserAgent> {
            require(json.length <= 100_000) { "Subscription user agents are too large" }
            val root = JsonParser.parseString(json)
            require(root.isJsonObject && root.asJsonObject.size() <= 1_000) { "Invalid subscription user agents" }
            return root.asJsonObject.entrySet().associate { (key, value) ->
                require(sourceKey.matches(key) && value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                    "Invalid subscription user agent"
                }
                val agent = requireNotNull(entries.firstOrNull { it.id == value.asString && it != AUTO }) {
                    "Unknown subscription user agent"
                }
                key to agent
            }
        }

        internal fun encodeOverrides(overrides: Map<String, SubscriptionUserAgent>): String =
            JsonObject().apply {
                overrides.toSortedMap().forEach { (key, agent) -> addProperty(key, agent.id) }
            }.toString()
    }
}
