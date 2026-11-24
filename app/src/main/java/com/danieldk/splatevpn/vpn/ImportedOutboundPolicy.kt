package com.danieldk.splatevpn.vpn

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Imports transport settings only. Listeners, DNS and routing always belong to the app. */
internal object ImportedOutboundPolicy {
    private val supported = setOf("vless", "vmess", "trojan", "shadowsocks", "socks", "http", "hysteria")

    fun apply(config: JsonObject, raw: String?, mux: Boolean, fragment: Boolean, xhttpMode: String) {
        if (raw.isNullOrBlank()) return
        val source = JsonParser.parseString(raw).asJsonObject.getAsJsonArray("outbounds")
            ?: error("Imported profile has no outbounds")
        val primary = source.firstOrNull { it.isJsonObject && it.asJsonObject["protocol"]?.asString in supported }
            ?.asJsonObject ?: error("Imported profile has no supported proxy outbound")
        val tagged = source.filter { it.isJsonObject }.map { it.asJsonObject }
            .filter { !it["tag"]?.asString.isNullOrBlank() }
        require(tagged.map { it["tag"].asString }.distinct().size == tagged.size) { "Duplicate outbound tags" }
        val byTag = tagged.associateBy { it["tag"].asString }
        val generated = config.getAsJsonArray("outbounds")
        val managedProxy = generated.first { it.asJsonObject["tag"]?.asString == "proxy" }.asJsonObject
        val imported = mutableListOf<JsonObject>()
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        val primaryTag = primary["tag"]?.asString

        fun visit(outbound: JsonObject, tag: String) {
            if (tag in visited) return
            require(visiting.add(tag)) { "Cyclic proxy chain" }
            val copy = outbound.deepCopy()
            val hysteria = copy["protocol"]?.asString == "hysteria"
            if (hysteria) {
                val stream = copy.getAsJsonObject("streamSettings") ?: error("Missing Hysteria transport")
                require(stream["network"]?.asString == "hysteria") { "Invalid Hysteria transport" }
                require((copy.getAsJsonObject("settings")?.get("version")?.asInt ?: 2) == 2 &&
                    (stream.getAsJsonObject("hysteriaSettings")?.get("version")?.asInt ?: 2) == 2) { "Unsupported Hysteria version" }
            }
            val xdrive = prepareXDrive(copy)
            copy.getAsJsonObject("streamSettings")?.takeIf { it["security"]?.asString.equals("reality", ignoreCase = true) }
                ?.getAsJsonObject("realitySettings")?.let { reality ->
                reality["mldsa65Verify"]?.takeUnless { it.isJsonNull }?.let { key ->
                    require(key.isJsonPrimitive && key.asJsonPrimitive.isString) { "Invalid REALITY mldsa65Verify: expected a public-key string" }
                    if (key.asString.isNotEmpty()) reality.addProperty("mldsa65Verify",
                        com.danieldk.splatevpn.data.RealityVerificationKey.normalize(key.asString))
                }
            }
            copy.addProperty("tag", if (outbound === primary) "proxy" else "import-$tag")
            val sockopt = copy.getAsJsonObject("streamSettings")?.getAsJsonObject("sockopt")
            val references = listOfNotNull(sockopt?.takeIf { it.has("dialerProxy") }?.let { it to "dialerProxy" },
                copy.getAsJsonObject("proxySettings")?.takeIf { it.has("tag") }?.let { it to "tag" })
            references.forEach { (holder, key) ->
                val dependency = holder[key].asString
                val target = byTag[dependency] ?: error("Missing proxy chain outbound")
                visit(target, dependency)
                holder.addProperty(key, if (dependency == primaryTag) "proxy" else "import-$dependency")
            }
            if (outbound === primary) {
                if (!xdrive) {
                    copy.remove("mux")
                    if (mux && !hysteria && managedProxy.has("mux")) copy.add("mux", managedProxy["mux"].deepCopy())
                }
                if (xhttpMode != "auto") copy.getAsJsonObject("streamSettings")
                    ?.getAsJsonObject("xhttpSettings")?.addProperty("mode", xhttpMode)
            }
            // Fragment only the terminal dial, preserving an imported proxy chain.
            if (fragment && !xdrive && !hysteria && references.isEmpty() && managedProxy.getAsJsonObject("streamSettings")
                    ?.getAsJsonObject("sockopt")?.get("dialerProxy")?.asString == "fragment") {
                val stream = copy.getAsJsonObject("streamSettings") ?: JsonObject().also { copy.add("streamSettings", it) }
                val options = stream.getAsJsonObject("sockopt") ?: JsonObject().also { stream.add("sockopt", it) }
                options.addProperty("dialerProxy", "fragment")
            }
            imported.add(copy)
            visiting.remove(tag)
            visited.add(tag)
        }
        visit(primary, primaryTag ?: "primary")
        val result = com.google.gson.JsonArray()
        result.add(imported.first { it["tag"].asString == "proxy" })
        generated.filter { it.asJsonObject["tag"]?.asString != "proxy" }.forEach { result.add(it) }
        imported.filter { it["tag"].asString != "proxy" }.forEach { result.add(it) }
        config.add("outbounds", result)
    }

    private fun prepareXDrive(outbound: JsonObject): Boolean {
        val stream = outbound.getAsJsonObject("streamSettings") ?: return false
        if (!stream["network"]?.asString.equals("xdrive", ignoreCase = true)) return false
        require(outbound["protocol"]?.asString == "vless") { "XDrive requires VLESS" }
        val storage = stream.getAsJsonObject("xdriveSettings") ?: error("XDrive storage settings are missing")
        val service = storage["service"]?.asString?.trim()?.lowercase()
        require(service != "local") { "XDrive local storage is not allowed in imported profiles" }
        require(service in setOf("google-drive", "google drive", "s3", "template")) { "Unsupported XDrive storage service" }
        if (service == "google drive") storage.addProperty("service", "google-drive")
        val settings = outbound.getAsJsonObject("settings") ?: error("XDrive VLESS settings are missing")
        val user = settings.getAsJsonArray("vnext")?.firstOrNull()?.asJsonObject
            ?.getAsJsonArray("users")?.firstOrNull()?.asJsonObject ?: settings
        require(!user["encryption"]?.asString.isNullOrBlank() && user["encryption"].asString != "none") {
            "XDrive requires VLESS encryption"
        }
        require(user["flow"]?.asString.isNullOrBlank()) { "XDrive does not support VLESS flow" }
        // XDrive transports multiplexed VLESS sessions even when the optional app-wide Mux switch is off.
        val mux = outbound.getAsJsonObject("mux") ?: JsonObject().also { outbound.add("mux", it) }
        mux.addProperty("enabled", true)
        if (!mux.has("concurrency")) mux.addProperty("concurrency", 8)
        return true
    }
}
