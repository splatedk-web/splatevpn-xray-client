package com.danieldk.splatevpn.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Only consent choices travel in a backup; installation secrets stay on the device. */
internal object SubscriptionHwidPreferences {
    private val sourceKey = Regex("[a-f0-9]{64}")

    fun decode(json: String): Map<String, Boolean> {
        require(json.length <= 100_000) { "HWID preferences are too large" }
        val root = JsonParser.parseString(json)
        require(root.isJsonObject && root.asJsonObject.size() <= 1_000) { "Invalid HWID preferences" }
        return root.asJsonObject.entrySet().associate { (key, value) ->
            require(sourceKey.matches(key) && value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) {
                "Invalid HWID preference"
            }
            key to value.asBoolean
        }
    }

    fun encode(overrides: Map<String, Boolean>): String = JsonObject().apply {
        overrides.toSortedMap().forEach { (key, enabled) -> addProperty(key, enabled) }
    }.toString().also { decode(it) }
}
