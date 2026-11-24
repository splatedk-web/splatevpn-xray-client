package com.danieldk.splatevpn.data

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.IDN
import java.net.InetAddress

/** A deliberately bounded projection of remote routing onto the app's three managed outbounds.
 * Unknown conditions are never dropped from a rule. A partially supported set cannot be activated.
 * Remote listeners, DNS, scripts, rule providers, balancers and outbound chains are not imported here.
 */
object SubscriptionRoutingRules {
    const val MAX_RULES = 512
    const val MAX_STORED_CHARS = 256 * 1024
    private const val MAX_VALUES = 128
    private val actions = setOf("direct", "proxy", "block")
    private val proxyTypes = setOf("vless", "vmess", "trojan", "shadowsocks", "socks", "http", "wireguard", "hysteria", "hysteria2", "hy2", "tuic", "shadowsocksr", "anytls", "ssh")
    // These are categories already required by the app's bundled routing/geodata.
    private val geosites = setOf("category-ru", "tld-ru", "yandex", "vk", "mailru", "cn", "geolocation-cn", "tld-cn", "category-ir",
        "openai", "telegram", "youtube", "google", "discord", "instagram", "twitter", "facebook", "notion", "spotify", "netflix", "category-ads-all")
    private val geoips = setOf("private", "ru", "cn", "ir", "telegram")
    private val nativeKeys = setOf("type", "domain", "ip", "port", "network", "protocol", "outboundTag", "ruleTag")

    data class Parsed(val json: String = "", val skipped: Int = 0)
    data class PreviewRule(val target: String, val conditions: String)

    private class Collector {
        val rules = mutableListOf<JsonObject>()
        var skipped = 0
        private var chars = 2
        fun add(rule: () -> JsonObject) {
            try {
                val value = rule()
                val size = value.toString().length + 1
                require(rules.size < MAX_RULES && chars + size <= MAX_STORED_CHARS)
                rules += value
                chars += size
            } catch (_: Exception) { skipped++ }
        }
        fun result() = Parsed(if (rules.isEmpty()) "" else JsonArray().apply { rules.forEach(::add) }.toString(), skipped.coerceAtMost(10_000))
    }

    fun parse(content: String): Parsed = try {
        require(content.length <= 8 * 1024 * 1024)
        val decoded = LinkParser.decodeSubscriptionContent(content)
        when {
            decoded.startsWith("{") -> parseJson(JsonParser.parseString(decoded).asJsonObject)
            decoded.startsWith("[") -> {
                val profiles = JsonParser.parseString(decoded).asJsonArray
                require(profiles.size() <= LinkParser.MAX_SUBSCRIPTION_NODES)
                val parsed = profiles.map { if (it.isJsonObject) parseJson(it.asJsonObject) else Parsed(skipped = 1) }
                // A per-subscription switch cannot represent different policies for individual profiles.
                if (parsed.map { it.json }.distinct().size <= 1 && parsed.all { it.skipped == 0 }) parsed.firstOrNull() ?: Parsed()
                else Parsed(skipped = parsed.sumOf { it.skipped + if (it.json.isNotEmpty()) 1 else 0 }.coerceIn(1, 10_000))
            }
            Regex("(?m)^rules\\s*:").containsMatchIn(decoded) -> parseClash(decoded)
            else -> Parsed()
        }
    } catch (_: Exception) { Parsed(skipped = 1) }

    /** Canonicalize untrusted backup/database content; invalid content is rejected as a whole. */
    fun validateStored(raw: String): String {
        if (raw.isBlank()) return ""
        require(raw.length <= MAX_STORED_CHARS)
        val source = JsonParser.parseString(raw).asJsonArray
        require(source.size() <= MAX_RULES)
        val canonical = JsonArray()
        source.forEach { element ->
            val rule = element.asJsonObject
            canonical.add(nativeRule(rule, requireNotNull(string(rule, "outboundTag")?.takeIf { it in actions }) { "Unknown route target" }))
        }
        return if (canonical.size() == 0) "" else canonical.toString().also { require(it.length <= MAX_STORED_CHARS) }
    }

    fun rules(raw: String): List<JsonObject> = validateStored(raw).takeIf { it.isNotEmpty() }
        ?.let { JsonParser.parseString(it).asJsonArray.map { element -> element.asJsonObject } } ?: emptyList()

    fun preview(raw: String): List<PreviewRule> = rules(raw).map { rule ->
        PreviewRule(rule["outboundTag"].asString, listOf("domain", "ip", "port", "network", "protocol").mapNotNull { key ->
            rule[key]?.let { value -> "$key: " + if (value.isJsonArray) value.asJsonArray.joinToString(", ") { it.asString } else value.asString }
        }.joinToString(" · "))
    }

    fun activeFor(node: ServerNode?, subscriptions: List<SubscriptionEntity>): List<JsonObject> {
        val subscription = subscriptions.firstOrNull { it.id == node?.subId && it.enabled && it.useSubscriptionRules } ?: return emptyList()
        if (subscription.importedRulesSkipped != 0) return emptyList()
        return runCatching { rules(subscription.importedRulesJson.orEmpty()) }.getOrDefault(emptyList())
    }

    private fun parseJson(profile: JsonObject): Parsed {
        val collector = Collector()
        val outbounds = profile["outbounds"]?.takeIf { it.isJsonArray }?.asJsonArray?.toList().orEmpty()
        val targets = mutableMapOf<String, String>()
        val duplicates = mutableSetOf<String>()
        val seen = mutableSetOf<String>()
        for (entry in outbounds) if (entry.isJsonObject) {
            val outbound = entry.asJsonObject
            val tag = string(outbound, "tag") ?: continue
            if (!seen.add(tag)) duplicates += tag
            val protocol = string(outbound, "protocol") ?: string(outbound, "type")
            val target = when (protocol) { "freedom", "direct" -> "direct"; "blackhole", "block" -> "block"; in proxyTypes -> "proxy"; else -> null }
            if (target != null) targets[tag] = target
        }
        duplicates.forEach(targets::remove)
        when {
            profile.has("routing") -> {
                val routing = profile.getAsJsonObject("routing")
                if (routing.has("script") && !string(routing, "script").isNullOrBlank()) collector.skipped++
                val source = routing["rules"]?.asJsonArray ?: JsonArray()
                source.forEach { element -> collector.add {
                    val rule = element.asJsonObject
                    nativeRule(rule, targets[string(rule, "outboundTag")] ?: error("Unresolved route target"))
                } }
                if (source.size() > 0) collector.add {
                    val first = outbounds.firstOrNull()?.asJsonObject ?: error("Missing default outbound")
                    catchAll(targets[string(first, "tag")] ?: error("Unresolved default outbound"))
                }
            }
            profile.has("route") -> {
                val routing = profile.getAsJsonObject("route")
                val source = routing["rules"]?.asJsonArray ?: JsonArray()
                source.forEach { element -> collector.add { singBoxRule(element.asJsonObject, targets) } }
                val final = string(routing, "final")
                if (!final.isNullOrBlank()) collector.add { catchAll(targets[final] ?: error("Unresolved final outbound")) }
                else if (source.size() > 0) collector.add {
                    val first = outbounds.firstOrNull()?.asJsonObject ?: error("Missing default outbound")
                    catchAll(targets[string(first, "tag")] ?: error("Unresolved default outbound"))
                }
                if (routing.has("final") && final == null) collector.skipped++
            }
        }
        return collector.result()
    }

    private fun nativeRule(source: JsonObject, target: String): JsonObject {
        require(source.keySet().all { it in nativeKeys }) { "Unsupported rule condition" }
        require(!source.has("type") || string(source, "type") == "field")
        val result = base(target)
        if (source.has("domain")) result.add("domain", array(values(source["domain"]).map(::domain)))
        if (source.has("ip")) result.add("ip", array(values(source["ip"]).map(::ip)))
        if (source.has("port")) {
            require(!source["port"].isJsonArray)
            result.addProperty("port", ports(values(source["port"], allowNumbers = true).single()))
        }
        if (source.has("network")) result.addProperty("network", network(values(source["network"]).flatMap { it.split(',') }))
        if (source.has("protocol")) result.add("protocol", array(values(source["protocol"]).map {
            it.lowercase().also { value -> require(value in setOf("http", "tls", "quic", "bittorrent")) }
        }))
        require(result.size() > 2) { "Empty match" }
        return result
    }

    private fun singBoxRule(source: JsonObject, targets: Map<String, String>): JsonObject {
        val allowed = setOf("domain", "domain_suffix", "domain_keyword", "ip_cidr", "port", "port_range", "network", "protocol", "action", "outbound")
        require(source.keySet().all { it in allowed })
        val action = string(source, "action")
        require(action == null || action in setOf("route", "reject"))
        val target = if (action == "reject") "block" else targets[string(source, "outbound")] ?: error("Unresolved outbound")
        val normalized = base(target)
        val domains = mutableListOf<String>()
        if (source.has("domain")) domains += values(source["domain"]).map { domain("full:$it") }
        if (source.has("domain_suffix")) domains += values(source["domain_suffix"]).map {
            if (it.startsWith('.')) strictSubdomains(it.substring(1)) else domain("domain:$it")
        }
        if (source.has("domain_keyword")) domains += values(source["domain_keyword"]).map(::domain)
        // sing-box ORs domain and IP matchers, whereas Xray ANDs those fields. Do not narrow
        // a PROXY exception and accidentally expose its former IP branch to a later DIRECT.
        require(domains.isEmpty() || !source.has("ip_cidr")) { "Mixed domain/IP union is unsupported" }
        if (domains.isNotEmpty()) normalized.add("domain", array(domains))
        if (source.has("ip_cidr")) normalized.add("ip", array(values(source["ip_cidr"]).map(::ip)))
        val portList = mutableListOf<String>()
        if (source.has("port")) portList += values(source["port"], allowNumbers = true)
        if (source.has("port_range")) portList += values(source["port_range"]).map { it.replace(':', '-') }
        if (portList.isNotEmpty()) normalized.addProperty("port", ports(portList.joinToString(",")))
        if (source.has("network")) normalized.addProperty("network", network(values(source["network"])))
        if (source.has("protocol")) normalized.add("protocol", array(values(source["protocol"])))
        // Reuse the same strict schema used when restoring portable backups.
        return nativeRule(normalized, target)
    }

    private fun parseClash(content: String): Parsed {
        val collector = Collector()
        val sections = mutableMapOf<String, MutableList<String>>()
        var section = ""
        for (line in content.lineSequence()) {
            val header = Regex("^([A-Za-z][A-Za-z0-9_-]*):\\s*(.*)$").matchEntire(line)
            if (header != null) {
                section = header.groupValues[1]
                sections.getOrPut(section) { mutableListOf() }
                if (section == "rules" && header.groupValues[2].let { it.isNotBlank() && !it.startsWith('#') && it != "[]" }) collector.skipped++
            } else if (section in setOf("rules", "proxies", "proxy-groups")) sections.getOrPut(section) { mutableListOf() }.add(line)
        }
        val targets = mutableMapOf("DIRECT" to "direct", "REJECT" to "block", "REJECT-DROP" to "block")
        // Only explicitly listed proxy names are known; dynamic provider names remain unresolved.
        targets.putAll(clashProxyTargets(sections["proxies"].orEmpty()).filterKeys { it !in targets })
        // Groups consisting solely of known proxy entries have the same route action. Mixed or
        // provider-backed groups are intentionally not guessed from their name.
        val groups = clashGroups(sections["proxy-groups"].orEmpty())
        repeat(groups.size.coerceAtMost(128)) {
            groups.forEach { (name, members) ->
                val resolved = members.map { member -> targets[member] }
                if (members.isNotEmpty() && resolved.all { it != null } && resolved.distinct().size == 1 && name !in targets) targets[name] = resolved.first()!!
            }
        }
        sections["rules"].orEmpty().forEach { line ->
            val text = line.trim()
            if (text.isNotEmpty() && !text.startsWith('#')) collector.add {
                require(text.startsWith("- "))
                clashRule(scalar(text.substring(2)), targets)
            }
        }
        // In rule mode mihomo falls back to DIRECT when no rule matched. Show that action
        // explicitly in the preview so enabling a source cannot conceal its default bypass.
        if (sections["rules"].orEmpty().any { it.trim().startsWith("- ") } && collector.rules.none {
                it.size() == 3 && it["network"]?.asString == "tcp,udp"
            }) collector.add { catchAll("direct") }
        return collector.result()
    }

    private fun clashProxyTargets(lines: List<String>): Map<String, String> {
        val targets = mutableMapOf<String, String>()
        val property = Regex("^(name|type)\\s*:\\s*(.+)$")
        val props = mutableMapOf<String, String>()
        var itemIndent = -1
        val clashProxyTypes = proxyTypes + setOf("ss", "ssr", "socks5", "snell", "mieru", "tuic")
        fun finish() {
            val name = props["name"]
            val action = when (props["type"]) { "direct" -> "direct"; "reject" -> "block"; in clashProxyTypes -> "proxy"; else -> null }
            if (!name.isNullOrBlank() && action != null) targets[name] = action
            props.clear()
        }
        for (line in lines) {
            val text = line.trim()
            if (text.isBlank() || text.startsWith('#')) continue
            val indent = line.indexOfFirst { !it.isWhitespace() }
            if (text.startsWith("- ") && (itemIndent < 0 || indent <= itemIndent)) { finish(); itemIndent = indent }
            if (indent > itemIndent + 2) continue
            val source = if (indent == itemIndent) text.removePrefix("- ") else text
            if (source.startsWith('{')) props.putAll(runCatching { inlineProxyFields(source) }.getOrDefault(emptyMap()))
            else property.matchEntire(source)?.let { match ->
                runCatching { scalar(match.groupValues[2]) }.getOrNull()?.let { props[match.groupValues[1]] = it }
            }
        }
        finish()
        return targets
    }

    /** Read only top-level name/type in a flow map; nested header keys are not proxy metadata. */
    private fun inlineProxyFields(raw: String): Map<String, String> {
        require(raw.startsWith('{') && raw.endsWith('}'))
        val text = raw.substring(1, raw.lastIndex)
        val fields = mutableListOf<String>()
        var start = 0; var depth = 0; var quote: Char? = null; var escaped = false
        for ((index, char) in text.withIndex()) {
            if (quote != null) {
                if (escaped) escaped = false
                else if (char == '\\' && quote == '"') escaped = true
                else if (char == quote) quote = null
            } else when (char) {
                '\'', '"' -> quote = char
                '{', '[' -> depth++
                '}', ']' -> { depth--; require(depth >= 0) }
                ',' -> if (depth == 0) { fields += text.substring(start, index); start = index + 1 }
            }
        }
        require(depth == 0 && quote == null)
        fields += text.substring(start)
        val result = mutableMapOf<String, String>()
        fields.forEach { field ->
            val key = field.substringBefore(':').trim()
            if (key in setOf("name", "type")) {
                require(key !in result)
                result[key] = scalar(field.substringAfter(':'))
            }
        }
        return result
    }

    private fun clashGroups(lines: List<String>): Map<String, List<String>> {
        val result = linkedMapOf<String, List<String>>()
        var name: String? = null
        var members = mutableListOf<String>()
        var collecting = false
        var valid = true
        var itemIndent = -1
        fun finish() { name?.let { if (valid) result[it] = members.toList() }; name = null; members = mutableListOf(); collecting = false; valid = true }
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isBlank() || trimmed.startsWith('#')) continue
            val indent = line.indexOfFirst { !it.isWhitespace() }
            if (trimmed.startsWith("- ") && (itemIndent < 0 || indent <= itemIndent)) { finish(); itemIndent = indent }
            val value = if (indent == itemIndent) trimmed.removePrefix("- ") else trimmed
            try {
                when {
                    value.startsWith("name:") -> { name = scalar(value.substringAfter(':')); collecting = false }
                    value.startsWith("use:") -> { valid = false; collecting = false }
                    value.startsWith("proxies:") -> {
                        val raw = value.substringAfter(':').trim()
                        collecting = raw.isEmpty()
                        if (raw.isNotEmpty()) {
                            require(raw.startsWith('[') && raw.endsWith(']'))
                            members.addAll(raw.substring(1, raw.length - 1).split(',').map(::scalar))
                        }
                    }
                    collecting && trimmed.startsWith("- ") && indent > itemIndent -> members += scalar(trimmed.substring(2))
                    else -> collecting = false
                }
            } catch (_: Exception) { valid = false }
            if (members.size > MAX_VALUES) valid = false
        }
        finish()
        return result
    }

    private fun clashRule(text: String, targets: Map<String, String>): JsonObject {
        val parts = text.split(',').map { it.trim() }
        require(parts.size in 2..4)
        val kind = parts[0].uppercase()
        if (kind == "MATCH") { require(parts.size == 2); return catchAll(targets[parts[1]] ?: error("Unresolved policy")) }
        // Xray has no per-rule equivalent of Clash's no-resolve/src flags.
        require(parts.size == 3)
        val result = base(targets[parts[2]] ?: error("Unresolved policy"))
        when (kind) {
            "DOMAIN" -> result.add("domain", array(listOf(domain("full:${parts[1]}"))))
            "DOMAIN-SUFFIX" -> result.add("domain", array(listOf(domain("domain:${parts[1]}"))))
            "DOMAIN-KEYWORD" -> result.add("domain", array(listOf(domain(parts[1]))))
            "GEOSITE" -> result.add("domain", array(listOf(domain("geosite:${parts[1].lowercase()}"))))
            "IP-CIDR", "IP-CIDR6" -> result.add("ip", array(listOf(ip(parts[1]))))
            "GEOIP" -> result.add("ip", array(listOf(ip("geoip:${parts[1].lowercase()}"))))
            "DST-PORT" -> result.addProperty("port", ports(parts[1]))
            "NETWORK" -> result.addProperty("network", network(listOf(parts[1])))
            else -> error("Unsupported Clash matcher")
        }
        return result
    }

    private fun base(target: String) = JsonObject().apply {
        require(target in actions)
        addProperty("type", "field"); addProperty("outboundTag", target)
    }
    private fun catchAll(target: String) = base(target).apply { addProperty("network", "tcp,udp") }
    private fun array(values: List<String>) = JsonArray().apply { values.distinct().sorted().forEach(::add) }
    private fun values(value: JsonElement?, allowNumbers: Boolean = false): List<String> {
        require(value != null)
        val list = if (value.isJsonArray) value.asJsonArray.toList() else listOf(value)
        require(list.isNotEmpty() && list.size <= MAX_VALUES)
        return list.map {
            require(it.isJsonPrimitive && (it.asJsonPrimitive.isString || allowNumbers && it.asJsonPrimitive.isNumber))
            it.asString.also { text -> require(text.isNotBlank() && text.length <= 4096 && text.none { ch -> ch.isISOControl() }) }
        }
    }
    private fun string(obj: JsonObject, key: String): String? = obj[key]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    private fun domain(raw: String): String {
        require(raw.length <= 253 && raw.isNotBlank())
        if (raw.startsWith("geosite:")) return raw.lowercase().also { require(it.substringAfter(':') in geosites) }
        if (raw.startsWith("regexp:^.+\\.") && raw.endsWith('$')) {
            val host = raw.removePrefix("regexp:^.+\\.").removeSuffix("$").replace("\\.", ".")
            return strictSubdomains(host).also { require(it == raw) }
        }
        val prefix = when { raw.startsWith("domain:") -> "domain:"; raw.startsWith("full:") -> "full:"; else -> "" }
        val value = raw.removePrefix(prefix)
        require(':' !in value && value.none { it.isWhitespace() })
        if (prefix.isEmpty()) return value.lowercase().also { require(it.matches(Regex("[a-z0-9_.-]+"))) }
        val ascii = IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES).lowercase()
        require(ascii.isNotBlank() && ascii.length <= 253 && ascii.matches(Regex("[a-z0-9_.-]+")))
        return prefix + ascii
    }
    private fun strictSubdomains(host: String): String = "regexp:^.+\\." + domain("full:$host").removePrefix("full:").replace(".", "\\.") + "$"
    private fun ip(raw: String): String {
        if (raw.startsWith("geoip:")) return raw.lowercase().also { require(it.substringAfter(':') in geoips) }
        val address = raw.substringBefore('/')
        val bits: Int
        val normalized: String
        if (':' in address) {
            require(address.matches(Regex("[0-9a-fA-F:]+")))
            val parsed = InetAddress.getByName(address)
            require(parsed.address.size == 16)
            bits = 128; normalized = requireNotNull(parsed.hostAddress)
        } else {
            val octets = address.split('.')
            require(octets.size == 4 && octets.all { it.matches(Regex("[0-9]{1,3}")) && it.toInt() in 0..255 })
            bits = 32; normalized = octets.joinToString(".") { it.toInt().toString() }
        }
        if ('/' !in raw) return normalized
        require(raw.count { it == '/' } == 1)
        val prefix = raw.substringAfter('/').toInt()
        require(prefix in 0..bits)
        return "$normalized/$prefix"
    }
    private fun ports(raw: String): String {
        require(raw.isNotBlank() && raw.length <= 4096)
        return raw.split(',').map { token ->
            require(token.matches(Regex("[0-9]+(?:-[0-9]+)?")))
            val from = token.substringBefore('-').toInt(); val to = token.substringAfter('-', token).toInt()
            require(from in 1..65535 && to in from..65535)
            if (from == to) "$from" else "$from-$to"
        }.distinct().sorted().joinToString(",")
    }
    private fun network(values: List<String>): String = values.map { it.lowercase() }.distinct().sorted().also {
        require(it.isNotEmpty() && it.all { value -> value in setOf("tcp", "udp") })
    }.joinToString(",")
    private fun scalar(raw: String): String {
        val value = raw.trim()
        require(value.isNotEmpty() && value.length <= 4096)
        if (value.startsWith('"')) return JsonParser.parseString(value).asString
        if (value.startsWith('\'')) { require(value.endsWith('\'') && value.length >= 2); return value.substring(1, value.length - 1).replace("''", "'") }
        require(value.first() !in "{[&*!>|" && '\t' !in value)
        return value.substringBefore(" #").trim().also { require(it.isNotEmpty()) }
    }
}
