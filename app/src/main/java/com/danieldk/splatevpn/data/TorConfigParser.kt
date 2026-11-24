package com.danieldk.splatevpn.data

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.net.URI
import java.util.Base64

/** Parses bridge lists, safe torrc client options and JSON { bridges: [], entryNodes, exitNodes }. */
object TorConfigParser {
    const val MAX_BYTES = 1024 * 1024
    const val MAX_BRIDGES = 256
    const val MAX_SUBSCRIPTIONS = 8
    private const val MAX_SAVED_BRIDGES = 1024
    private val fingerprint = Regex("[A-Fa-f0-9]{40}")
    private val ipv4 = Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")
    private val ipv6 = Regex("[0-9A-Fa-f:]+")
    private val sniHost = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$")
    val supportedTransports = setOf("obfs4", "webtunnel", "snowflake")

    fun normalizeSubscriptionUrl(input: String): String {
        val uri = try { URI(input.trim()) } catch (_: Exception) { throw IllegalArgumentException("Invalid subscription URL") }
        require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null) {
            "Tor subscriptions require an HTTPS URL without credentials"
        }
        val path = uri.rawPath.orEmpty()
        if (uri.host.equals("github.com", true)) {
            val parts = path.trim('/').split('/')
            require(parts.size >= 5 && parts[2] in setOf("blob", "raw")) { "Use a GitHub file link or a raw HTTPS URL" }
            return "https://raw.githubusercontent.com/${parts[0]}/${parts[1]}/${parts.drop(3).joinToString("/")}"
        }
        return uri.toASCIIString()
    }

    fun validateNodes(input: String): String {
        require(input.length <= 4096) { "Too many relay selectors" }
        if (input.isBlank()) return ""
        return input.split(',').map { it.trim() }.onEach { selector ->
            val fp = selector.removePrefix("$")
            require(fingerprint.matches(fp)) {
                "Use 40-character relay fingerprints, separated by commas"
            }
        }.distinct().joinToString(",")
    }

    fun validateBridge(input: String): String {
        val bridge = Regex("url=\\[(https://\\S+?)]\\((https://[^)\\s]+)\\)").replace(
            input.trim().replaceFirst(Regex("^Bridge\\s+", RegexOption.IGNORE_CASE), "")) { match ->
            require(match.groupValues[1] == match.groupValues[2]) { "Bridge URL label must match its target" }
            "url=${match.groupValues[2]}"
        }.trim()
        require(bridge.length in 1..4096 && bridge.none { it.isISOControl() }) { "Invalid bridge line" }
        val parts = bridge.split(Regex("\\s+"))
        val transported = parts.first() in supportedTransports
        val offset = if (transported) 1 else 0
        require(parts.size >= offset + 2) { "Bridge must include an address and relay fingerprint" }
        val address = parts[offset]
        val colon = address.lastIndexOf(':')
        require(colon > 0) { "Bridge address must include a port" }
        val host = address.substring(0, colon).removeSurrounding("[", "]")
        val port = address.substring(colon + 1).toIntOrNull()
        require(port != null && port in 1..65535) { "Invalid bridge port" }
        require((ipv4.matches(host) && host.split('.').all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }) ||
            (host.contains(':') && ipv6.matches(host) && runCatching { java.net.InetAddress.getByName(host) is java.net.Inet6Address }.getOrDefault(false))) {
            "Bridge requires an IPv4 or IPv6 address; supported transports: ${supportedTransports.joinToString(", ")}"
        }
        require(fingerprint.matches(parts[offset + 1])) { "Bridge requires a 40-character relay fingerprint" }
        val options = parts.drop(offset + 2)
        require(options.all { Regex("[A-Za-z][A-Za-z0-9_-]*=[^\\s#\"\\\\]+").matches(it) }) { "Invalid bridge parameters" }
        if (parts.first() == "obfs4") {
            val cert = options.firstOrNull { it.startsWith("cert=") }?.substringAfter('=')
            require(cert != null && runCatching { Base64.getDecoder().decode(cert).size == 52 }.getOrDefault(false)) { "obfs4 bridge requires a valid cert" }
            require(options.any { it == "iat-mode=0" || it == "iat-mode=1" || it == "iat-mode=2" }) { "obfs4 bridge requires iat-mode=0, 1 or 2" }
        }
        if (parts.first() == "webtunnel") {
            val url = options.firstOrNull { it.startsWith("url=") }?.substringAfter('=')
            require(url != null && runCatching { URI(url).let { it.scheme == "https" && !it.host.isNullOrBlank() && it.rawUserInfo == null } }.getOrDefault(false)) { "webtunnel bridge requires an HTTPS url" }
        }
        return parts.joinToString(" ")
    }

    fun extractBridgeAddress(bridge: String): Pair<String, Int>? {
        val line = bridge.trim().replaceFirst(Regex("^Bridge\\s+", RegexOption.IGNORE_CASE), "").trim()
        val parts = line.split(Regex("\\s+"))
        if (parts.isEmpty()) return null
        val transported = parts.first() in supportedTransports
        val offset = if (transported) 1 else 0
        if (parts.size <= offset) return null
        val address = parts[offset]
        val colon = address.lastIndexOf(':')
        if (colon <= 0) return null
        val host = address.substring(0, colon).removeSurrounding("[", "]")
        val port = address.substring(colon + 1).toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return Pair(host, port)
    }

    fun extractBridgeSni(bridge: String): String? {
        val line = bridge.trim().replaceFirst(Regex("^Bridge\\s+", RegexOption.IGNORE_CASE), "").trim()
        val parts = line.split(Regex("\\s+"))
        if (parts.isEmpty()) return null
        val transported = parts.first() in supportedTransports
        val offset = if (transported) 2 else 1
        val options = parts.drop(offset)
        options.firstOrNull { it.startsWith("servername=") }?.substringAfter("servername=")?.takeIf { it.isNotBlank() }?.let { return it }
        options.firstOrNull { it.startsWith("sni=") }?.substringAfter("sni=")?.takeIf { it.isNotBlank() }?.let { return it }
        options.firstOrNull { it.startsWith("front=") }?.substringAfter("front=")?.takeIf { it.isNotBlank() }?.let { return it }
        options.firstOrNull { it.startsWith("fronts=") }?.substringAfter("fronts=")?.substringBefore(',')?.takeIf { it.isNotBlank() }?.let { return it }
        val url = options.firstOrNull { it.startsWith("url=") }?.substringAfter("url=")
        if (!url.isNullOrBlank()) {
            runCatching { URI(url).host }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    fun validateCustomSni(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return ""
        require(trimmed.length <= 253 && sniHost.matches(trimmed)) {
            "Custom SNI / Fronting must be a valid hostname"
        }
        return trimmed
    }

    private fun applyCustomSni(bridge: String, customSni: String): String {
        if (customSni.isEmpty()) return bridge
        val parts = bridge.split(Regex("\\s+"))
        val transport = parts.firstOrNull()?.lowercase() ?: return bridge
        return when (transport) {
            "webtunnel" -> {
                val filtered = parts.filterNot { it.startsWith("servername=") }
                (filtered + "servername=$customSni").joinToString(" ")
            }
            "snowflake" -> {
                val filtered = parts.filterNot { it.startsWith("front=") || it.startsWith("fronts=") }
                (filtered + "front=$customSni").joinToString(" ")
            }
            else -> bridge
        }
    }

    fun validate(settings: TorSettings): TorSettings {
        require(settings.bridges.size <= MAX_SAVED_BRIDGES) { "Too many saved bridges (maximum $MAX_SAVED_BRIDGES)" }
        val bridges = settings.bridges.map(::validateBridge).distinct()
        val legacyUrl = settings.subscriptionUrl.takeIf { it.isNotBlank() }?.let(::normalizeSubscriptionUrl)
        val sources = settings.bridgeSubscriptions.ifEmpty {
            legacyUrl?.let { listOf(TorBridgeSubscription(it, bridges)) }.orEmpty()
        }
        require(sources.size <= MAX_SUBSCRIPTIONS) { "Too many Tor subscriptions (maximum $MAX_SUBSCRIPTIONS)" }
        val normalizedSources = sources.map { source ->
            require(source.bridges.size <= MAX_BRIDGES) { "Too many bridges in a Tor subscription" }
            TorBridgeSubscription(normalizeSubscriptionUrl(source.url), source.bridges.map(::validateBridge).distinct())
        }
        require(normalizedSources.map { it.url }.distinct().size == normalizedSources.size) { "Duplicate Tor subscription" }
        val manual = settings.manualBridgeEntries?.let { entries ->
            require(entries.size <= MAX_SAVED_BRIDGES) { "Too many manual bridges" }
            entries.map(::validateBridge).distinct()
        }
        require(!settings.useBridges || bridges.isNotEmpty()) { "Add at least one bridge or disable bridges" }
        require(settings.bridgeTransport in setOf("auto", "vanilla", "webtunnel", "obfs4", "snowflake")) { "Unsupported bridge transport" }
        return settings.copy(bridges = bridges, entryNodes = validateNodes(settings.entryNodes),
            exitNodes = validateNodes(settings.exitNodes), excludeNodes = validateNodes(settings.excludeNodes),
            selectedBridge = settings.selectedBridge.takeIf { it in bridges }.orEmpty(),
            lastImportSkipped = settings.lastImportSkipped.coerceAtLeast(0),
            subscriptionUrl = "", bridgeSubscriptions = normalizedSources, manualBridgeEntries = manual,
            optimisticData = settings.optimisticData,
            autoSelectFastestBridge = settings.autoSelectFastestBridge,
            bridgeSelectionMetric = TorBridgeMetric.fromId(settings.bridgeSelectionMetric).id,
            circuitBuildTimeout = settings.circuitBuildTimeout.coerceIn(10, 60),
            maxCircuitDirtiness = settings.maxCircuitDirtiness.coerceIn(60, 600),
            customBridgeSni = validateCustomSni(settings.customBridgeSni))
    }

    fun parse(input: String): TorImport {
        require(input.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Tor configuration exceeds 1 MB" }
        val text = input.trim().removePrefix("\uFEFF")
        require(text.isNotEmpty()) { "Tor configuration is empty" }
        val result = if (looksLikeJson(text)) parseJson(JsonParser.parseString(text)) else parseLines(text)
        require(result.bridges.isNotEmpty() || !result.entryNodes.isNullOrBlank() || !result.exitNodes.isNullOrBlank() || !result.excludeNodes.isNullOrBlank()) {
            "No Tor bridges or relay selectors found"
        }
        require(result.bridges.size <= MAX_BRIDGES) { "Too many bridges (maximum $MAX_BRIDGES)" }
        return result.copy(bridges = result.bridges.distinct())
    }

    /** Public bridge lists may contain a malformed entry. Keep valid bridges and report omissions. */
    fun parseSubscription(input: String): TorImport {
        require(input.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Tor configuration exceeds 1 MB" }
        val text = input.trim().removePrefix("\uFEFF")
        if (looksLikeJson(text)) return parse(text)
        val lines = text.lineSequence().map { it.substringBefore('#').trim() }.filter { it.isNotBlank() }.toList()
        if (lines.any { it.substringBefore(' ').substringBefore('\t').lowercase() in setOf("entrynodes", "exitnodes", "excludenodes", "usebridges", "strictnodes") }) return parse(text)
        require(lines.isNotEmpty()) { "Tor configuration is empty" }
        val bridges = mutableListOf<String>()
        var skipped = 0
        lines.forEachIndexed { index, line ->
            require(isBridgeCandidate(line)) { "Unsupported Tor option on line ${index + 1}" }
            try { bridges += validateBridge(line) } catch (_: IllegalArgumentException) { skipped++ }
            require(bridges.size + skipped <= MAX_BRIDGES) { "Too many bridges (maximum $MAX_BRIDGES)" }
        }
        require(bridges.isNotEmpty()) { "No valid Tor bridges found" }
        return TorImport(bridges.distinct(), useBridges = true, skippedBridges = skipped)
    }

    /** Extract bridge lines from a chat message; client directives still use the strict parser. */
    fun parsePasted(input: String): TorImport {
        require(input.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Tor configuration exceeds 1 MB" }
        val text = input.trim()
        if (looksLikeJson(text)) return parse(text)
        val lines = text.lineSequence().map { it.trim().removePrefix("> ").trim('`', '*', ' ') }.toList()
        val directives = setOf("entrynodes", "exitnodes", "excludenodes", "usebridges", "strictnodes")
        val forbidden = setOf("socksport", "datadirectory", "clienttransportplugin", "hiddenservicedir", "%include", "controlport", "cookieauthentication", "log", "runasdaemon", "pidfile", "servertransportplugin", "extorport")
        val keys = lines.map { it.substringBefore(' ').substringBefore('\t').lowercase() }
        require(keys.none { it in forbidden }) { "Unsupported Tor configuration directive" }
        if (keys.any { it in directives }) return parse(text)
        return parseSubscription(lines.filter(::isBridgeCandidate).joinToString("\n"))
    }

    private fun isBridgeCandidate(line: String): Boolean {
        val key = line.substringBefore(' ').substringBefore('\t').lowercase()
        return key in supportedTransports || key == "bridge" || Regex("(?:\\d{1,3}\\.){3}\\d{1,3}:.*").matches(key) || key.startsWith("[") && key.contains("]:")
    }

    private fun looksLikeJson(text: String): Boolean = text.startsWith("{") ||
        text.startsWith("[") && !Regex("^\\[[0-9A-Fa-f:]+]:\\d+\\s").containsMatchIn(text)

    private fun parseJson(value: JsonElement): TorImport {
        if (value.isJsonArray) return TorImport(value.asJsonArray.map { validateBridge(it.asString) })
        require(value.isJsonObject) { "Expected a Tor configuration object" }
        val obj = value.asJsonObject
        val allowed = setOf("bridges", "entryNodes", "exitNodes", "excludeNodes", "useBridges", "strictNodes", "torrc")
        require(obj.keySet().all { it in allowed }) { "Unsupported Tor JSON option" }
        val torrc = obj.get("torrc")?.takeIf { !it.isJsonNull }?.let { parseLines(it.asString) }
        fun nodes(key: String): String? = obj.get(key)?.takeIf { !it.isJsonNull }?.let { validateNodes(it.asString) }
        fun bool(key: String): Boolean? = obj.get(key)?.takeIf { !it.isJsonNull }?.let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean) { "$key must be true or false" }; it.asBoolean
        }
        val bridges = obj.get("bridges")?.takeIf { !it.isJsonNull }?.let { list ->
            if (list.isJsonArray) list.asJsonArray.map { validateBridge(it.asString) }
            else list.asString.lineSequence().filter { it.isNotBlank() }.map(::validateBridge).toList()
        }.orEmpty()
        return TorImport((torrc?.bridges.orEmpty() + bridges).distinct(), nodes("entryNodes") ?: torrc?.entryNodes,
            nodes("exitNodes") ?: torrc?.exitNodes, nodes("excludeNodes") ?: torrc?.excludeNodes,
            bool("useBridges") ?: torrc?.useBridges, bool("strictNodes") ?: torrc?.strictNodes)
    }

    private fun parseLines(text: String): TorImport {
        val bridges = mutableListOf<String>()
        var entry: String? = null; var exit: String? = null; var exclude: String? = null
        var use: Boolean? = null; var strict: Boolean? = null
        text.lineSequence().forEachIndexed { index, raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@forEachIndexed
            val key = line.substringBefore(' ').substringBefore('\t').lowercase()
            val value = line.drop(key.length).trim()
            when (key) {
                "bridge" -> bridges += validateBridge(value)
                "entrynodes" -> entry = validateNodes(value)
                "exitnodes" -> exit = validateNodes(value)
                "excludenodes" -> exclude = validateNodes(value)
                "usebridges", "strictnodes" -> {
                    require(value == "0" || value == "1") { "Expected 0 or 1 on line ${index + 1}" }
                    if (key == "usebridges") use = value == "1" else strict = value == "1"
                }
                else -> {
                    // Bare bridge lists from BridgeDB are accepted. Other torrc directives are rejected.
                    require(key in supportedTransports || key.contains(':')) { "Unsupported Tor option on line ${index + 1}" }
                    bridges += validateBridge(line)
                }
            }
            require(bridges.size <= MAX_BRIDGES) { "Too many bridges (maximum $MAX_BRIDGES)" }
        }
        return TorImport(bridges.distinct(), entry, exit, exclude, use, strict)
    }

    fun buildTorrc(settings: TorSettings, transports: Map<String, Int>, socksUnixPath: String? = null): String {
        val safe = validate(settings)
        val unixSocket = socksUnixPath?.trim()?.takeIf { it.isNotEmpty() }
        if (unixSocket != null) {
            require(unixSocket.startsWith("/") && unixSocket.none { it.isISOControl() || it == '"' || it.isWhitespace() }) {
                "Invalid Tor Unix socket path"
            }
        }
        return buildString {
            appendLine("ClientOnly 1")
            if (unixSocket != null) {
                appendLine("SocksPort unix:$unixSocket IsolateSOCKSAuth")
            } else {
                appendLine("SocksPort 127.0.0.1:auto IsolateSOCKSAuth")
            }
            appendLine("HTTPTunnelPort 0")
            appendLine("SafeLogging 1")
            appendLine("AvoidDiskWrites 1")
            appendLine("OptimisticData ${if (safe.optimisticData) 1 else 0}")
            appendLine("NumEntryGuards 4")
            appendLine("NumDirectoryGuards 3")
            appendLine("LearnCircuitBuildTimeout 0")
            appendLine("CircuitBuildTimeout ${safe.circuitBuildTimeout.coerceIn(10, 60)}")
            appendLine("MaxCircuitDirtiness ${safe.maxCircuitDirtiness.coerceIn(60, 600)}")
            appendLine("KeepalivePeriod 60")
            appendLine("ConnectionPadding 1")
            appendLine("ReducedConnectionPadding 1")
            appendLine("EnforceDistinctSubnets 0")
            appendLine("UseBridges ${if (safe.useBridges) 1 else 0}")
            if (safe.useBridges) {
                safe.bridges.map { it.substringBefore(' ') }.filter { it in supportedTransports }.distinct().forEach { transport ->
                    val port = transports[transport]
                    require(port != null && port in 1..65535) { "Bridge transport has not started: $transport" }
                    appendLine("ClientTransportPlugin $transport socks5 127.0.0.1:$port")
                }
                safe.bridges.forEach { appendLine("Bridge ${applyCustomSni(it, safe.customBridgeSni)}") }
            }
            if (safe.entryNodes.isNotEmpty()) appendLine("EntryNodes ${safe.entryNodes}")
            if (safe.exitNodes.isNotEmpty()) appendLine("ExitNodes ${safe.exitNodes}")
            if (safe.excludeNodes.isNotEmpty()) appendLine("ExcludeNodes ${safe.excludeNodes}")
            appendLine("StrictNodes ${if (safe.strictNodes) 1 else 0}")
        }
    }
}
