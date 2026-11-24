package com.danieldk.splatevpn.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Explicit primitive tags avoid converting Long/Int/Set to Gson's generic Double/List. */
internal object PreferenceValues {
    fun encode(values: Map<String, *>): String {
        val root = JsonObject().apply { addProperty("schema", 1) }
        val entries = JsonObject()
        values.forEach { (key, value) ->
            val item = JsonObject()
            when (value) {
                is String -> { item.addProperty("type", "string"); item.addProperty("value", value) }
                is Boolean -> { item.addProperty("type", "boolean"); item.addProperty("value", value) }
                is Int -> { item.addProperty("type", "int"); item.addProperty("value", value) }
                is Long -> { item.addProperty("type", "long"); item.addProperty("value", value.toString()) }
                is Float -> { require(value.isFinite()); item.addProperty("type", "float"); item.addProperty("value", value) }
                is Set<*> -> {
                    require(value.all { it is String })
                    item.addProperty("type", "set")
                    item.add("value", com.google.gson.JsonArray().apply { value.filterIsInstance<String>().sorted().forEach { add(it) } })
                }
                else -> error("Unsupported preference type")
            }
            entries.add(key, item)
        }
        root.add("values", entries)
        return root.toString()
    }

    fun decode(json: String): Map<String, Any> {
        require(json.length <= 4 * 1024 * 1024) { "Preference snapshot is too large" }
        val root = JsonParser.parseString(json).asJsonObject
        require(root["schema"].asInt == 1)
        val entries = root.getAsJsonObject("values")
        require(entries.size() <= 256)
        return entries.entrySet().associate { (key, raw) ->
            require(key.length in 1..128)
            val item = raw.asJsonObject
            val value = item["value"]
            val decoded: Any = when (item["type"].asString) {
                "string" -> { require(value.isJsonPrimitive && value.asJsonPrimitive.isString); value.asString }
                "boolean" -> { require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean); value.asBoolean }
                "int" -> value.asString.toInt()
                "long" -> value.asString.toLong()
                "float" -> value.asFloat.also { require(it.isFinite()) }
                "set" -> value.asJsonArray.map { require(it.isJsonPrimitive && it.asJsonPrimitive.isString); it.asString }.toSet()
                else -> error("Unknown preference type")
            }
            key to decoded
        }
    }
}
