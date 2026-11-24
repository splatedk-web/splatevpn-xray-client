package com.danieldk.splatevpn.data

import com.google.gson.Gson
import com.google.gson.JsonObject

/** Portable exports contain the profile; an automatic local snapshot contains only device-bound ciphertext. */
internal object AwgLocalVault {
    fun seal(document: JsonObject): JsonObject = document.deepCopy().apply {
        getAsJsonArray("servers")?.forEach { raw ->
            val node = raw.asJsonObject
            node["amneziaWg"]?.takeUnless { it.isJsonNull }?.let {
                val p = Gson().fromJson(it, AmneziaWgProfile::class.java)
                node.addProperty("amneziaWgSealed", AmneziaWgProfileConverter().encode(p)); node.remove("amneziaWg")
            }
        }
    }
    fun open(document: JsonObject): JsonObject = document.deepCopy().apply {
        getAsJsonArray("servers")?.forEach { raw ->
            val node = raw.asJsonObject
            node.remove("amneziaWgSealed")?.let { encrypted ->
                require(!node.has("amneziaWg")) { "Duplicate AWG vault profile" }
                node.add("amneziaWg", Gson().toJsonTree(AmneziaWgProfileConverter().decode(encrypted.asString)))
            }
        }
    }
}
