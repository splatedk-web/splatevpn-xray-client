package com.danieldk.splatevpn.data

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.IDN
import java.net.InetAddress
import java.security.MessageDigest
import java.util.Locale

/** Lists are alternatives inside a section; [operation] combines the populated sections. */
data class AdvancedRouteRule(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val operation: String = "and",
    val outbound: String = "proxy",
    val domains: List<String> = emptyList(),
    val suffixes: List<String> = emptyList(),
    val keywords: List<String> = emptyList(),
    val ips: List<String> = emptyList(),
    val ports: String = "",
    val networks: Set<String> = emptySet(),
    val protocols: Set<String> = emptySet(),
    val packages: List<String> = emptyList(),
    val targetServerId: String? = null
)

/** Compiles genuine section-wise AND/OR. Package identities are authenticated SOCKS usernames,
 * supplied by the TUN's per-flow Android UID resolver, not native Xray process-name matching.
 * Manual suffixes accept a leading dot as an alias and include the apex and dot-boundary children.
 * Imported subscription-rule suffix semantics are deliberately independent of this editor.
 */
object AdvancedRoutingRules {
    const val MAX_RULES = 32
    const val MAX_VALUES = 64
    const val MAX_PACKAGES = 256
    const val MAX_ENCODED_CHARS = 200_000
    const val MAX_COMPILED_CHARS = 512_000
    private const val MAX_COMPILED_RULE_CHARS = 128 * 1024
    private val keys = setOf("id", "name", "enabled", "operation", "outbound", "domains", "suffixes", "keywords", "ips", "ports", "networks", "protocols", "packages", "targetServerId")
    private val actions = setOf("direct", "proxy", "block")
    private val protocolNames = setOf("http", "tls", "quic", "bittorrent", "stun")

    fun validate(rule: AdvancedRouteRule): AdvancedRouteRule {
        require(rule.id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))) { "Invalid rule ID" }
        val trimmedName = rule.name.trim()
        require(trimmedName.length in 1..80 && trimmedName.none(Char::isISOControl)) { "Enter a rule name (1–80 characters)" }
        val operation = rule.operation.lowercase(Locale.ROOT)
        val outbound = rule.outbound.lowercase(Locale.ROOT)
        require(operation in setOf("and", "or")) { "Choose AND or OR" }
        require(outbound in actions) { "Unknown rule action" }
        val targetServerId = rule.targetServerId?.trim()?.takeIf { it.isNotEmpty() }
        if (targetServerId != null) {
            require(targetServerId.length <= 64 && targetServerId.none(Char::isISOControl)) { "Invalid target server ID" }
        }
        fun normalized(values: List<String>, transform: (String) -> String): List<String> {
            require(values.size <= MAX_VALUES) { "At most $MAX_VALUES values are allowed in each section" }
            return values.map { value ->
                require(value.isNotBlank() && value.length <= 4096 && value.none(Char::isISOControl)) { "Invalid rule value" }
                transform(value.trim())
            }.distinct().sorted()
        }
        val result = rule.copy(
            name = trimmedName, operation = operation, outbound = outbound,
            domains = normalized(rule.domains, ::host),
            suffixes = normalized(rule.suffixes) { host(it.removePrefix(".")) },
            keywords = normalized(rule.keywords) { keyword ->
                keyword.lowercase(Locale.ROOT).also {
                    require(it.length <= 128 && it.matches(Regex("[a-z0-9_.-]+")) && ".." !in it) {
                        "Keywords must use Latin letters, digits, dots, hyphens or underscores"
                    }
                }
            },
            ips = normalized(rule.ips, ::ip),
            ports = ports(rule.ports),
            networks = normalized(rule.networks.toList()) { it.lowercase(Locale.ROOT).also { value ->
                require(value in setOf("tcp", "udp")) { "Unknown transport network" }
            } }.toSet(),
            protocols = normalized(rule.protocols.toList()) { it.lowercase(Locale.ROOT).also { value ->
                require(value in protocolNames) { "Unknown sniffed protocol" }
            } }.toSet(),
            packages = normalized(rule.packages, ::validatePackageName),
            targetServerId = if (outbound == "proxy") targetServerId else null
        )
        val compiled = compileValidated(result)
        require(compiled.sumOf { it.toString().length + 1 } <= MAX_COMPILED_RULE_CHARS) {
            "This rule is too complex; reduce the number or length of its values"
        }
        return result
    }

    fun decode(raw: String): List<AdvancedRouteRule> {
        require(raw.length <= MAX_ENCODED_CHARS) { "Advanced routing settings exceed the size limit" }
        if (raw.isBlank()) return emptyList()
        val root = JsonParser.parseString(raw)
        require(root.isJsonArray && root.asJsonArray.size() <= MAX_RULES) { "Invalid advanced routing list" }
        val rules = root.asJsonArray.map { element ->
            require(element.isJsonObject) { "Invalid advanced rule" }
            val obj = element.asJsonObject
            require(obj.keySet().all { it in keys }) { "Unsupported advanced rule condition" }
            fun text(key: String, default: String? = null): String {
                val value = obj[key] ?: return requireNotNull(default) { "Missing rule field: $key" }
                require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "Invalid rule field: $key" }
                return value.asString
            }
            fun values(key: String): List<String> {
                val value = obj[key] ?: return emptyList()
                require(value.isJsonArray && value.asJsonArray.size() <= MAX_VALUES) { "Invalid rule list: $key" }
                return value.asJsonArray.map { item ->
                    require(item.isJsonPrimitive && item.asJsonPrimitive.isString) { "Invalid value in $key" }
                    item.asString
                }
            }
            val enabled = obj["enabled"]?.let {
                require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean) { "Invalid enabled switch" }; it.asBoolean
            } ?: true
            val targetServerId = obj["targetServerId"]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.takeIf { it.isNotBlank() }
            AdvancedRouteRule(text("id"), text("name"), enabled, text("operation", "and"), text("outbound", "proxy"),
                values("domains"), values("suffixes"), values("keywords"), values("ips"), text("ports", ""),
                values("networks").toSet(), values("protocols").toSet(), values("packages"),
                targetServerId = targetServerId)
        }
        return canonical(rules)
    }

    fun encode(rules: List<AdvancedRouteRule>): String = JsonArray().apply {
        canonical(rules).forEach { rule -> add(JsonObject().apply {
            addProperty("id", rule.id); addProperty("name", rule.name); addProperty("enabled", rule.enabled)
            addProperty("operation", rule.operation); addProperty("outbound", rule.outbound)
            rule.targetServerId?.let { addProperty("targetServerId", it) }
            add("domains", array(rule.domains)); add("suffixes", array(rule.suffixes)); add("keywords", array(rule.keywords))
            add("ips", array(rule.ips)); addProperty("ports", rule.ports)
            add("networks", array(rule.networks)); add("protocols", array(rule.protocols))
            // Preserve the canonical representation of pre-package rules.
            if (rule.packages.isNotEmpty()) add("packages", array(rule.packages))
        }) }
    }.toString().also { require(it.length <= MAX_ENCODED_CHARS) { "Advanced routing settings exceed the size limit" } }

    fun validateEncoded(raw: String): String = encode(decode(raw))

    fun compile(rules: List<AdvancedRouteRule>): List<JsonObject> = canonical(rules)
        .filter { it.enabled }.flatMap(::compileValidated)

    fun requiresDomainSniffing(rules: List<AdvancedRouteRule>): Boolean = canonical(rules).any {
        it.enabled && (it.domains.isNotEmpty() || it.suffixes.isNotEmpty() || it.keywords.isNotEmpty() || it.protocols.isNotEmpty())
    }
    fun requiresIpResolution(rules: List<AdvancedRouteRule>): Boolean = canonical(rules).any { it.enabled && it.ips.isNotEmpty() }

    fun requiresAppIdentity(rules: List<AdvancedRouteRule>): Boolean = packageNames(rules).isNotEmpty()

    /** Keep uninstalled packages: their condition remains unmatched instead of becoming broader. */
    fun packageNames(rules: List<AdvancedRouteRule>): List<String> = canonical(rules)
        .filter { it.enabled }.flatMap { it.packages }.distinct().sorted()

    /** Xray routes SOCKS requests by the authenticated username (not accounts[].email).
     * Hashing preserves Android's case-sensitive package identity even in case-folding matchers.
     * This public name is an identity, never an authentication secret.
     */
    fun appIdentity(packageName: String): String = "splate-app-" + MessageDigest.getInstance("SHA-256")
        .digest(validatePackageName(packageName).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun canonical(rules: List<AdvancedRouteRule>): List<AdvancedRouteRule> {
        require(rules.size <= MAX_RULES) { "At most $MAX_RULES advanced rules are allowed" }
        val result = rules.map(::validate)
        require(result.map { it.id }.distinct().size == result.size) { "Duplicate advanced rule IDs" }
        require(result.filter { it.enabled }.flatMap { it.packages }.distinct().size <= MAX_PACKAGES) {
            "At most $MAX_PACKAGES different apps are allowed across enabled rules"
        }
        require(result.filter { it.enabled }.sumOf { rule -> compileValidated(rule).sumOf { it.toString().length + 1 } } <= MAX_COMPILED_CHARS) {
            "Compiled advanced routing exceeds the size limit"
        }
        return result
    }

    private fun compileValidated(rule: AdvancedRouteRule): List<JsonObject> {
        val sections = linkedMapOf<String, JsonElement>()
        if (rule.operation == "and") {
            val domains = intersectDomains(rule)
            if (domains.isNotEmpty()) sections["domain"] = array(domains)
        } else {
            // The three domain sections have OR semantics here, exactly as native Xray's domain list.
            val domains = rule.domains.map { "full:$it" } + rule.suffixes.map { "domain:$it" } + rule.keywords
            if (domains.isNotEmpty()) sections["domain"] = array(domains)
        }
        if (rule.ips.isNotEmpty()) sections["ip"] = array(rule.ips)
        if (rule.ports.isNotEmpty()) sections["port"] = com.google.gson.JsonPrimitive(rule.ports)
        if (rule.networks.isNotEmpty()) sections["network"] = com.google.gson.JsonPrimitive(rule.networks.joinToString(","))
        if (rule.protocols.isNotEmpty()) sections["protocol"] = array(rule.protocols)
        if (rule.packages.isNotEmpty()) sections["user"] = array(rule.packages.map(::appIdentity))
        require(sections.isNotEmpty()) { "Fill at least one rule section" }
        val effectiveOutbound = if (rule.outbound == "proxy" && !rule.targetServerId.isNullOrBlank()) {
            "proxy-server-${rule.targetServerId}"
        } else {
            rule.outbound
        }
        fun base(suffix: String = "") = JsonObject().apply {
            addProperty("type", "field"); addProperty("ruleTag", "adv-rule-${rule.id}$suffix")
            addProperty("outboundTag", effectiveOutbound)
        }
        return if (rule.operation == "and") listOf(base().apply { sections.forEach { (key, value) -> add(key, value) } })
        else sections.map { (key, value) -> base("-$key").apply { add(key, value) } }
    }

    private fun intersectDomains(rule: AdvancedRouteRule): List<String> {
        if (rule.domains.isNotEmpty()) {
            val matching = rule.domains.filter { domain ->
                (rule.suffixes.isEmpty() || rule.suffixes.any { suffix -> domain == suffix || domain.endsWith(".$suffix") }) &&
                    (rule.keywords.isEmpty() || rule.keywords.any(domain::contains))
            }
            require(matching.isNotEmpty()) { "No exact domain matches the other domain sections; change AND or the values" }
            return matching.map { "full:$it" }
        }
        if (rule.suffixes.isEmpty()) return rule.keywords
        if (rule.keywords.isEmpty()) return rule.suffixes.map { "domain:$it" }
        val result = linkedSetOf<String>()
        var chars = 0
        fun add(value: String) {
            if (result.add(value)) chars += value.length + 3
            require(result.size <= 4096 && chars <= MAX_COMPILED_RULE_CHARS) {
                "Domain intersection is too complex; reduce suffixes or keywords"
            }
        }
        for (suffix in rule.suffixes) for (keyword in rule.keywords) {
            if (suffix.contains(keyword)) add("domain:$suffix")
            else {
                val tail = ".$suffix"
                // A keyword can be wholly in the prefix, cross prefix|suffix, or start at the dot.
                add("regexp:^.*${literal(keyword)}.*${literal(tail)}$")
                for (overlap in 1..minOf(keyword.length, tail.length)) {
                    if (keyword.endsWith(tail.take(overlap))) {
                        add("regexp:^.*${literal(keyword.dropLast(overlap))}${literal(tail)}$")
                    }
                }
            }
        }
        return result.sorted()
    }

    private fun host(raw: String): String {
        val value = raw.removeSuffix(".")
        require(value.isNotEmpty() && value.length <= 253 && ':' !in value && value.none(Char::isWhitespace)) { "Invalid domain name" }
        return IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT).also { ascii ->
            require(ascii.length in 1..253 && ascii.split('.').all { it.length in 1..63 }) { "Invalid domain name" }
        }
    }

    private fun validatePackageName(value: String): String = value.also {
        require(it.length in 3..255 && it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+"))) {
            "Enter an Android package ID, for example org.example.app"
        }
    }

    private fun ip(raw: String): String {
        require(raw.count { it == '/' } <= 1) { "Invalid IP prefix" }
        val address = raw.substringBefore('/')
        val normalized: String
        val bits: Int
        if (':' in address) {
            require(address.matches(Regex("[0-9a-fA-F:]+"))) { "Enter a numeric IPv6 address" }
            val parsed = InetAddress.getByName(address)
            require(parsed.address.size == 16) { "Invalid IPv6 address" }
            normalized = requireNotNull(parsed.hostAddress).lowercase(Locale.ROOT); bits = 128
        } else {
            val octets = address.split('.')
            require(octets.size == 4 && octets.all { it.matches(Regex("[0-9]{1,3}")) && it.toInt() in 0..255 }) { "Enter a numeric IPv4 address" }
            normalized = octets.joinToString(".") { it.toInt().toString() }; bits = 32
        }
        if ('/' !in raw) return normalized
        val prefix = raw.substringAfter('/').also { require(it.matches(Regex("[0-9]{1,3}"))) }.toInt()
        require(prefix in 0..bits) { "Invalid IP prefix" }
        return "$normalized/$prefix"
    }

    private fun ports(raw: String): String {
        if (raw.isBlank()) return ""
        require(raw.length <= 2048) { "Port list is too long" }
        val tokens = raw.split(','); require(tokens.size <= MAX_VALUES)
        val ranges = tokens.map { part ->
            val token = part.trim()
            require(token.matches(Regex("[0-9]+(?:-[0-9]+)?"))) { "Use ports or ranges such as 443,8000-8100" }
            val from = token.substringBefore('-').toInt(); val to = token.substringAfter('-', token).toInt()
            require(from in 1..65535 && to in from..65535) { "Invalid port range" }
            from..to
        }.sortedBy { it.first }
        val merged = mutableListOf<IntRange>()
        for (range in ranges) {
            val old = merged.lastOrNull()
            if (old != null && range.first <= old.last + 1) merged[merged.lastIndex] = old.first..maxOf(old.last, range.last)
            else merged += range
        }
        return merged.joinToString(",") { if (it.first == it.last) "${it.first}" else "${it.first}-${it.last}" }
    }

    private fun literal(value: String): String = buildString {
        value.forEach { char -> if (!char.isLetterOrDigit() && char != '_') append('\\'); append(char) }
    }
    private fun array(values: Collection<String>) = JsonArray().apply { values.distinct().sorted().forEach(::add) }
}
