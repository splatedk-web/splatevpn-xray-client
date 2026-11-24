package com.danieldk.splatevpn.data

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID

internal val ServerNode.isHysteria2: Boolean get() = protocol.lowercase() in setOf("hysteria2", "hy2")

/** Hysteria 2 links mapped to the Hysteria transport in the pinned Xray core. */
object Hysteria2Profile {
    private fun decode(value: String) = URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
    private fun params(raw: String): Map<String, String> = raw.substringBefore('#').substringAfter('?', "").split('&')
        .filter { it.contains('=') }.associate { decode(it.substringBefore('=')) to decode(it.substringAfter('=')) }

    private data class Ports(val ranges: List<IntRange>) {
        val first get() = ranges.first().first
        val multiple get() = ranges.size > 1 || ranges.first().last > first
        val text get() = ranges.joinToString(",") { if (it.first == it.last) "${it.first}" else "${it.first}-${it.last}" }
    }

    /** Keep ranges compact and merge overlaps before the core expands them into individual ports. */
    private fun ports(value: String): Ports {
        require(value.isNotBlank() && value.length <= 4096) { "Invalid Hysteria ports" }
        val sorted = value.split(',').map { token ->
            require(token.matches(Regex("[0-9]+(?:-[0-9]+)?"))) { "Invalid Hysteria port range" }
            val from = token.substringBefore('-').toInt()
            val to = token.substringAfter('-', token).toInt()
            require(from in 1..65535 && to in from..65535) { "Invalid Hysteria port range" }
            from..to
        }.sortedBy { it.first }
        val merged = mutableListOf<IntRange>()
        for (range in sorted) {
            val previous = merged.lastOrNull()
            if (previous != null && range.first <= previous.last + 1) {
                merged[merged.lastIndex] = previous.first..maxOf(previous.last, range.last)
            } else merged += range
        }
        return Ports(merged)
    }

    private data class Endpoint(val uri: URI, val ports: Ports)

    private fun hasSourceLink(node: ServerNode) = node.rawLink.substringBefore("://").lowercase() in setOf("hy2", "hysteria2")
    private fun sourceQuery(node: ServerNode) = if (hasSourceLink(node)) params(node.rawLink) else emptyMap()
    private fun hoppingPorts(node: ServerNode, query: Map<String, String>): Ports? =
        if (hasSourceLink(node)) endpoint(node.rawLink, query).ports.takeIf { it.multiple && it.first == node.port }
        else null

    /** java.net.URI cannot parse a bracketed IPv6 authority with a multi-port suffix. */
    private fun endpoint(raw: String, query: Map<String, String>): Endpoint {
        val scheme = raw.substringBefore("://").lowercase()
        require(scheme in setOf("hy2", "hysteria2") && raw.contains("://"))
        val rest = raw.substringAfter("://")
        val authority = rest.takeWhile { it !in "/?#" }
        val hostPort = authority.substringAfterLast('@')
        val auth = authority.substringBeforeLast('@', "").let { if ('@' in authority) "$it@" else "" }
        val host: String
        val portText: String
        if (hostPort.startsWith('[')) {
            val close = hostPort.indexOf(']')
            require(close > 1)
            host = hostPort.substring(0, close + 1)
            val tail = hostPort.substring(close + 1)
            require(tail.isEmpty() || tail.startsWith(':'))
            portText = if (tail.isEmpty()) "443" else tail.substring(1)
        } else {
            require(hostPort.count { it == ':' } <= 1) { "IPv6 requires brackets" }
            host = hostPort.substringBefore(':')
            portText = hostPort.substringAfter(':', "443")
        }
        val authorityPorts = ports(portText)
        val aliases = listOfNotNull(query["mport"], query["ports"]).map(::ports)
        require(aliases.distinct().size <= 1) { "Conflicting Hysteria port lists" }
        val effective = aliases.firstOrNull() ?: authorityPorts
        require(!authorityPorts.multiple || aliases.isEmpty() || effective == authorityPorts) {
            "Conflicting Hysteria port lists"
        }
        val uri = URI("$scheme://$auth$host:${effective.first}${rest.substring(authority.length)}")
        require(!uri.host.isNullOrBlank()) { "Invalid Hysteria host" }
        return Endpoint(uri, effective)
    }

    /** Common share-link extensions; the transport defaults to the Hysteria interval of 30 seconds. */
    private fun hopInterval(query: Map<String, String>): Int {
        val values = listOfNotNull(query["hop-interval"], query["hopInterval"]).map { value ->
            require(value.matches(Regex("[0-9]+s?"))) { "Invalid Hysteria hopping interval" }
            value.removeSuffix("s").toInt().also { require(it >= 5) { "Hopping interval must be at least 5 seconds" } }
        }
        require(values.distinct().size <= 1) { "Conflicting Hysteria hopping intervals" }
        return values.firstOrNull() ?: 30
    }

    fun parse(raw: String, subId: String = "", subName: String = ""): ServerNode? = try {
        val query = params(raw)
        validate(query)
        val endpoint = endpoint(raw, query)
        val uri = endpoint.uri
        val host = requireNotNull(uri.host).removeSurrounding("[", "]")
        ServerNode(id = UUID.randomUUID().toString(), name = uri.rawFragment?.let(::decode)?.takeIf { it.isNotBlank() }
            ?: "Hysteria 2 · $host", protocol = "hysteria2", address = host, port = endpoint.ports.first,
            uuid = uri.rawUserInfo?.let(::decode).orEmpty(), network = "hysteria", security = "tls",
            sni = query["sni"].orEmpty(), fingerprint = "", rawLink = raw, subId = subId, subName = subName)
    } catch (_: Exception) { null }

    private fun normalizePinSha256(raw: String?): String =
        raw.orEmpty().trim().replaceFirst(Regex("^sha256:", RegexOption.IGNORE_CASE), "").replace(":", "")

    private fun validate(query: Map<String, String>) {
        hopInterval(query)
        val obfs = query["obfs"]?.trim()?.lowercase().orEmpty()
        require(obfs.isEmpty() || obfs == "none" || obfs == "salamander") { "Unsupported Hysteria obfuscation" }
        if (obfs == "salamander") require(!query["obfs-password"].isNullOrEmpty()) { "Missing Salamander password" }
        val pin = normalizePinSha256(query["pinSHA256"])
        require(pin.isEmpty() || pin.matches(Regex("[0-9a-fA-F]{64}"))) { "Invalid certificate fingerprint" }
        require(query["insecure"] !in setOf("1", "true") || pin.isNotEmpty()) {
            "This core requires a valid certificate or pinSHA256"
        }
    }

    fun share(node: ServerNode): String {
        fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
        val query = sourceQuery(node).toMutableMap()
        // A different numeric port in the editor is an explicit single-port override.
        val pool = hoppingPorts(node, query)
        query.remove("mport"); query.remove("ports")
        if (node.sni.isBlank()) query.remove("sni") else query["sni"] = node.sni
        val host = if (':' in node.address) "[${node.address}]" else node.address
        val suffix = query.entries.joinToString("&") { encode(it.key) + "=" + encode(it.value) }
        return "hysteria2://${encode(node.uuid)}@$host:${pool?.text ?: node.port}/" +
            (if (suffix.isEmpty()) "" else "?$suffix") + "#${encode(node.name)}"
    }

    fun outbound(node: ServerNode): JsonObject {
        val query = sourceQuery(node)
        validate(query)
        require(node.address.isNotBlank() && node.port in 1..65535) { "Invalid Hysteria endpoint" }
        val pool = hoppingPorts(node, query)
        return JsonObject().apply {
            addProperty("tag", "proxy")
            addProperty("protocol", "hysteria")
            add("settings", JsonObject().apply {
                addProperty("version", 2); addProperty("address", node.address); addProperty("port", node.port)
            })
            add("streamSettings", JsonObject().apply {
                addProperty("network", "hysteria"); addProperty("security", "tls")
                add("hysteriaSettings", JsonObject().apply { addProperty("version", 2); addProperty("auth", node.uuid) })
                add("tlsSettings", JsonObject().apply {
                    addProperty("serverName", node.sni.ifBlank { node.address })
                    add("alpn", JsonArray().apply { add("h3") })
                    normalizePinSha256(query["pinSHA256"]).takeIf { it.isNotEmpty() }?.let {
                        addProperty("pinnedPeerCertSha256", it)
                    }
                })
                val masks = JsonArray().apply {
                    if (query["obfs"]?.trim()?.equals("salamander", ignoreCase = true) == true) add(JsonObject().apply {
                        addProperty("type", "salamander")
                        add("settings", JsonObject().apply { addProperty("password", query["obfs-password"]) })
                    })
                    // Xray reverses masks: the dialing mask must be last, beneath Salamander.
                    if (pool != null) add(JsonObject().apply {
                        addProperty("type", "udphop")
                        add("settings", JsonObject().apply {
                            addProperty("mode", "intervalLocal,intervalRemote")
                            addProperty("interval", hopInterval(query))
                            addProperty("remotePorts", pool.text)
                        })
                    })
                }
                if (masks.size() > 0) add("finalmask", JsonObject().apply { add("udp", masks) })
            })
        }
    }
}
