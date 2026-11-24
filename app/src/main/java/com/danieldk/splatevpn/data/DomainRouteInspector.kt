package com.danieldk.splatevpn.data

import com.google.gson.JsonParser
import java.io.File

data class DomainRouteMatch(val rule: Int, val outbound: String, val selector: String)

/** Local domain-only inspection. Does not resolve a hostname or claim to predict IP routing. */
internal object DomainRouteInspector {
    fun inspect(host: String, rules: String, assets: File): DomainRouteMatch? {
        val domain = requireNotNull(AdFilterCompiler.normalizeDomain(host))
        val databases = mutableMapOf<String, ByteArray>()
        val parsed = JsonParser.parseString(rules).asJsonArray
        parsed.forEachIndexed { index, raw ->
            val rule = raw.asJsonObject
            // The UI inspects an ordinary HTTPS TCP connection from the local SOCKS inbound.
            if (rule["network"]?.asString?.split(',')?.contains("tcp") == false) return@forEachIndexed
            if (rule["port"] != null && rule["port"].asString != "443") return@forEachIndexed
            if (rule["ip"] != null) return@forEachIndexed
            if (rule.getAsJsonArray("protocol")?.none { it.asString == "tls" } == true) return@forEachIndexed
            val inbound = rule.getAsJsonArray("inboundTag")
            if (inbound != null && inbound.none { it.asString == "socks-in" }) return@forEachIndexed
            rule.getAsJsonArray("domain")?.forEach { selector ->
                val value = selector.asString
                val matched = when {
                    value.startsWith("geosite:") -> geoMatch(domain, value.substringAfter(':'), databases.getOrPut("geosite.dat") { File(assets, "geosite.dat").readBytes() })
                    value.startsWith("ext:") -> {
                        val parts = value.split(':')
                        parts.size == 3 && parts[1] == "splate-ads.dat" &&
                            geoMatch(domain, parts[2], databases.getOrPut(parts[1]) { File(assets, parts[1]).readBytes() })
                    }
                    value.startsWith("full:") -> domain == value.substringAfter(':')
                    value.startsWith("domain:") -> suffix(domain, value.substringAfter(':'))
                    value.startsWith("regexp:") -> regexMatch(value.substringAfter(':'), domain)
                    else -> domain.contains(value)
                }
                if (matched) return DomainRouteMatch(index + 1, rule["outboundTag"].asString, value)
            }
        }
        return null
    }
    private fun suffix(host: String, root: String) = host == root || host.endsWith(".$root")
    private fun regexMatch(pattern: String, domain: String): Boolean = runCatching {
        // Xray also uses RE2 semantics. Avoid Java backtracking on geodata patterns.
        com.google.re2j.Pattern.compile(pattern).matcher(domain).find()
    }.getOrDefault(false)
    private fun geoMatch(domain: String, category: String, bytes: ByteArray): Boolean {
        if ('@' in category) return false // Attribute-filtered categories require full attribute semantics.
        var matched = false
        fields(bytes, 0, bytes.size) { field, _, start, end, _ ->
            if (field == 1 && !matched) {
                var code: String? = null
                fields(bytes, start, end) { key, _, a, b, _ -> if (key == 1) code = String(bytes, a, b - a, Charsets.UTF_8) }
                if (code.equals(category, true)) fields(bytes, start, end) { key, _, a, b, _ ->
                    if (key == 2 && !matched) {
                        var type = 0L
                        var value = ""
                        fields(bytes, a, b) { k, wire, c, d, number ->
                            if (k == 1 && wire == 0) type = number
                            if (k == 2 && wire == 2) value = String(bytes, c, d - c, Charsets.UTF_8)
                        }
                        matched = when (type) {
                            0L -> domain.contains(value)
                            1L -> regexMatch(value, domain)
                            2L -> suffix(domain, value)
                            3L -> domain == value
                            else -> false
                        }
                    }
                }
            }
        }
        return matched
    }
    private inline fun fields(bytes: ByteArray, start: Int, end: Int, visit: (Int, Int, Int, Int, Long) -> Unit) {
        var cursor = start
        while (cursor < end) {
            var tag = 0L; var shift = 0
            do { require(cursor < end && shift < 64); val part = bytes[cursor++].toInt(); tag = tag or ((part and 127).toLong() shl shift); shift += 7 } while (part and 128 != 0)
            val wire = (tag and 7).toInt(); val field = (tag ushr 3).toInt()
            var number = 0L
            var a = cursor
            when (wire) {
                0, 2 -> {
                    shift = 0
                    do { require(cursor < end && shift < 64); val part = bytes[cursor++].toInt(); number = number or ((part and 127).toLong() shl shift); shift += 7 } while (part and 128 != 0)
                    a = cursor
                    if (wire == 2) { require(number <= end - cursor && number >= 0); cursor += number.toInt() }
                }
                1 -> cursor += 8
                5 -> cursor += 4
                else -> error("Unsupported protobuf wire type")
            }
            require(cursor <= end)
            visit(field, wire, a, cursor, number)
        }
    }
}
