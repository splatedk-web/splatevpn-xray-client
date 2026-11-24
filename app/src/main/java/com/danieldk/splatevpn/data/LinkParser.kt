package com.danieldk.splatevpn.data

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.net.URLDecoder
import java.util.Base64
import java.util.UUID

object LinkParser {

    private val gson = Gson()
    const val MAX_SUBSCRIPTION_NODES = 20_000
    private fun MutableList<ServerNode>.addBounded(node: ServerNode): Boolean {
        require(size < MAX_SUBSCRIPTION_NODES) { "Too many subscription nodes" }
        return add(node)
    }

    /** Returns the payload inside an unencrypted Happ/INCY import deep link. */
    fun unwrapImportLink(raw: String): String? {
        val trimmed = raw.trim()
        val lower = trimmed.lowercase()
        val payload = when {
            IncyLinkCodec.isSupportedLink(trimmed) -> return runCatching { IncyLinkCodec.decode(trimmed).url }.getOrNull()
            lower.startsWith("happ://add/") -> trimmed.substringAfter("happ://add/", "")
            lower.startsWith("incy://import/") -> trimmed.substringAfter("incy://import/", "")
            lower.startsWith("incy://add/") -> trimmed.substringAfter("incy://add/", "")
            else -> return null
        }.trim()

        if (payload.matches(Regex("(?i)^https?%3a%2f%2f.*"))) {
            return try {
                URLDecoder.decode(payload.replace("+", "%2B"), "UTF-8")
            } catch (_: Exception) {
                payload
            }
        }
        return payload
    }

    fun isSubscriptionImportLink(raw: String): Boolean {
        val lower = raw.trim().lowercase()
        return lower.startsWith("happ://add/") ||
                isEncryptedSubscriptionLink(lower) ||
                lower.startsWith("incy://import/") ||
                lower.startsWith("incy://add/")
    }

    fun isEncryptedSubscriptionLink(raw: String): Boolean {
        val lower = raw.trim().lowercase()
        return Regex("^happ://(?:crypto|crypt[0-9]*)(/|$)").containsMatchIn(lower) ||
                lower.startsWith("incy://crypt1/")
    }

    fun isUnsupportedEncryptedSubscriptionLink(raw: String): Boolean =
        isEncryptedSubscriptionLink(raw) && !IncyLinkCodec.isSupportedLink(raw)

    fun normalizeSubscriptionUrl(raw: String): String? {
        val trimmed = raw.trim()
        val candidate = unwrapImportLink(trimmed) ?: trimmed
        return candidate.takeIf {
            it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
        }
    }

    internal class ParsedUri(raw: String) {
        val scheme: String
        val userInfo: String?
        val host: String?
        val port: Int
        val hasExplicitPort: Boolean
        val path: String?
        val fragment: String?
        private val queryParams = mutableMapOf<String, String>()

        init {
            val hashIdx = raw.indexOf('#')
            val withoutFrag = if (hashIdx >= 0) {
                fragment = raw.substring(hashIdx + 1)
                raw.substring(0, hashIdx)
            } else {
                fragment = null
                raw
            }

            val qIdx = withoutFrag.indexOf('?')
            val beforeQuery = if (qIdx >= 0) {
                val qStr = withoutFrag.substring(qIdx + 1)
                for (pair in qStr.split('&')) {
                    val eq = pair.indexOf('=')
                    if (eq > 0) {
                        val key = pair.substring(0, eq).trim()
                        val value = try {
                            // RFC 3986 query values keep literal '+' intact (Base64 keys, paths, seeds).
                            URLDecoder.decode(pair.substring(eq + 1).replace("+", "%2B"), "UTF-8")
                        } catch (e: Exception) {
                            pair.substring(eq + 1)
                        }
                        queryParams[key] = value
                    }
                }
                withoutFrag.substring(0, qIdx)
            } else {
                withoutFrag
            }

            val schemeEnd = beforeQuery.indexOf("://")
            val authorityAndPath = if (schemeEnd >= 0) {
                scheme = beforeQuery.substring(0, schemeEnd)
                beforeQuery.substring(schemeEnd + 3)
            } else {
                scheme = ""
                beforeQuery
            }

            val slashIdx = authorityAndPath.indexOf('/')
            val authority = if (slashIdx >= 0) {
                path = authorityAndPath.substring(slashIdx)
                authorityAndPath.substring(0, slashIdx)
            } else {
                path = ""
                authorityAndPath
            }

            val atIdx = authority.lastIndexOf('@')
            val hostPort = if (atIdx >= 0) {
                userInfo = authority.substring(0, atIdx)
                authority.substring(atIdx + 1)
            } else {
                userInfo = null
                authority
            }

            if (hostPort.startsWith("[")) {
                val closeBracket = hostPort.indexOf(']')
                if (closeBracket >= 0) {
                    host = hostPort.substring(1, closeBracket)
                    val colonIdx = hostPort.indexOf(':', closeBracket)
                    hasExplicitPort = colonIdx >= 0
                    port = if (colonIdx >= 0) hostPort.substring(colonIdx + 1).toIntOrNull() ?: -1 else -1
                } else {
                    host = hostPort
                    hasExplicitPort = false
                    port = -1
                }
            } else {
                val colonIdx = hostPort.lastIndexOf(':')
                if (colonIdx >= 0) {
                    host = hostPort.substring(0, colonIdx)
                    hasExplicitPort = true
                    port = hostPort.substring(colonIdx + 1).toIntOrNull() ?: -1
                } else {
                    host = hostPort
                    hasExplicitPort = false
                    port = -1
                }
            }
        }

        fun getQueryParameter(key: String): String? = queryParams[key]
    }

    fun parse(rawLink: String, subId: String = "", subName: String = ""): ServerNode? {
        val trimmed = rawLink.trim()
        return try {
            when {
                AmneziaWg.looksLike(trimmed) -> AmneziaWg.node(trimmed, subId, subName)
                trimmed.startsWith("wireguard://", ignoreCase = true) ||
                trimmed.startsWith("wg://", ignoreCase = true) -> parseWireGuardUri(trimmed, subId, subName)
                trimmed.startsWith("hy2://", true) || trimmed.startsWith("hysteria2://", true) ->
                    Hysteria2Profile.parse(trimmed, subId, subName)
                trimmed.startsWith("vless://", ignoreCase = true) -> parseVless(trimmed, subId, subName)
                trimmed.startsWith("trojan://", ignoreCase = true) -> parseTrojan(trimmed, subId, subName)
                trimmed.startsWith("vmess://", ignoreCase = true) -> parseVmess(trimmed, subId, subName)
                trimmed.startsWith("ss://", ignoreCase = true) -> parseShadowsocks(trimmed, subId, subName)
                trimmed.startsWith("socks://", ignoreCase = true) ||
                trimmed.startsWith("socks5://", ignoreCase = true) -> parseSocks(trimmed, subId, subName)
                else -> null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** A transport path alone does not opt a TCP peer into HTTP header camouflage. */
    internal fun usesTcpHttpHeader(node: ServerNode): Boolean {
        val raw = node.rawLink.trim()
        val header = runCatching {
            when {
                raw.startsWith("vmess://", ignoreCase = true) ->
                    gson.fromJson(decodeBase64Safe(raw.substringAfter("://").substringBefore('#')), JsonObject::class.java)
                        ?.get("type")?.asString
                raw.startsWith("vless://", ignoreCase = true) || raw.startsWith("trojan://", ignoreCase = true) ->
                    ParsedUri(raw).getQueryParameter("headerType")
                else -> null
            }
        }.getOrNull()
        if (header != null) return header.equals("http", ignoreCase = true)
        // Preserve manually configured VMess HTTP transports from existing installations.
        return raw.isBlank() && node.protocol.equals("vmess", ignoreCase = true) &&
            (node.path.isNotEmpty() || node.host.isNotEmpty())
    }

    fun extractExtraJson(rawLink: String): JsonObject? {
        return try {
            val uri = ParsedUri(rawLink)
            val extraParam = uri.getQueryParameter("extra") ?: return null
            val decoded = URLDecoder.decode(extraParam, "UTF-8")
            gson.fromJson(decoded, JsonObject::class.java)
        } catch (e: Exception) {
            null
        }
    }

    /** Keep the optional REALITY verification key in rawLink without a database migration. */
    fun extractRealityVerificationKey(rawLink: String): String? {
        if (!rawLink.trim().startsWith("vless://", ignoreCase = true)) return null
        return runCatching {
            val uri = ParsedUri(rawLink.trim())
            listOf("pqv", "mldsa65Verify").firstNotNullOfOrNull { key ->
                uri.getQueryParameter(key)?.takeIf { it.isNotEmpty() }?.let { value ->
                    // Preserve malformed input for an explicit error, never silently disable verification.
                    runCatching { RealityVerificationKey.normalize(value) }.getOrDefault(value)
                }
            }
        }.getOrNull()
    }

    internal fun extractAlpn(rawLink: String): List<String> {
        val raw = rawLink.trim()
        if (raw.isEmpty()) return emptyList()
        val alpnStr = runCatching {
            when {
                raw.startsWith("vmess://", ignoreCase = true) -> {
                    val decoded = decodeBase64Safe(raw.substringAfter("://").substringBefore('#').substringBefore('?').trim())
                    jsonString(gson.fromJson(decoded, JsonObject::class.java), "alpn")
                }
                raw.contains("://") -> ParsedUri(raw).getQueryParameter("alpn")
                else -> null
            }
        }.getOrNull().orEmpty()
        return alpnStr.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    internal fun extractKcpParams(rawLink: String): Pair<String, String> {
        val raw = rawLink.trim()
        if (raw.isEmpty()) return "none" to ""
        return runCatching {
            when {
                raw.startsWith("vmess://", ignoreCase = true) -> {
                    val decoded = decodeBase64Safe(raw.substringAfter("://").substringBefore('#').substringBefore('?').trim())
                    val json = gson.fromJson(decoded, JsonObject::class.java)
                    val headerType = jsonString(json, "type")?.takeIf { it.isNotBlank() } ?: "none"
                    val seed = jsonString(json, "path").orEmpty()
                    headerType to seed
                }
                raw.contains("://") -> {
                    val uri = ParsedUri(raw)
                    val headerType = uri.getQueryParameter("headerType")?.takeIf { it.isNotBlank() } ?: "none"
                    val seed = uri.getQueryParameter("seed").orEmpty()
                    headerType to seed
                }
                else -> "none" to ""
            }
        }.getOrDefault("none" to "")
    }

    private fun normalizeNetwork(net: String?): String {
        return when (net?.lowercase()?.trim()) {
            "splithttp", "xhttp" -> "xhttp"
            "websocket", "ws" -> "ws"
            "h2", "http" -> "h2"
            "grpc", "gun" -> "grpc"
            "kcp", "mkcp" -> "kcp"
            "httpupgrade" -> "httpupgrade"
            "xdrive" -> "xdrive"
            "hysteria" -> "hysteria"
            else -> "tcp"
        }
    }

    private fun decodeBase64Safe(input: String): String {
        var base64 = input.replace('-', '+').replace('_', '/').trim()
        while (base64.length % 4 != 0) {
            base64 += "="
        }
        return try {
            String(Base64.getDecoder().decode(base64), Charsets.UTF_8)
        } catch (e: Throwable) {
            try {
                String(Base64.getUrlDecoder().decode(base64), Charsets.UTF_8)
            } catch (ex: Throwable) {
                ""
            }
        }
    }

    private fun decodeUrlSafe(input: String): String {
        return try {
            URLDecoder.decode(input, "UTF-8")
        } catch (e: Exception) {
            input
        }
    }

    private const val SERVER_DESCRIPTION_SUFFIX = "?serverDescription="
    private val serverDescriptionBase64 = Regex("[A-Za-z0-9+/_-]{1,256}={0,2}")

    internal fun splitVlessLabel(label: String): Pair<String, String?> {
        val index = label.lastIndexOf(SERVER_DESCRIPTION_SUFFIX)
        if (index <= 0) return label to null
        val encoded = label.substring(index + SERVER_DESCRIPTION_SUFFIX.length)
        val decoded = decodeServerDescriptionLayer(encoded)?.let(::cleanServerDescription) ?: return label to null
        // Some publishers encode the already encoded field. Peel at most one extra layer.
        val description = cleanServerDescription(decodeServerDescriptionLayer(decoded) ?: decoded)
            ?: return label to null
        return label.substring(0, index).trimEnd() to description
    }

    private fun decodeServerDescriptionLayer(encoded: String): String? {
        if (!serverDescriptionBase64.matches(encoded)) return null
        val decoded = decodeBase64Safe(encoded)
        val canonical = Base64.getEncoder().withoutPadding().encodeToString(decoded.toByteArray(Charsets.UTF_8))
        if (canonical != encoded.trimEnd('=').replace('-', '+').replace('_', '/')) return null
        return decoded.trim().takeIf { value ->
            value.isNotEmpty() && value.none {
                it.isISOControl() || it == '\uFFFD' || Character.getType(it) == Character.FORMAT.toInt()
            }
        }
    }

    private fun cleanServerDescription(text: String): String? {
        val value = text.trim()
        val label = if (value.length > 4 && value.startsWith("**") && value.endsWith("**")) {
            value.substring(2, value.length - 2).trim()
        } else value
        return label.takeIf { it.isNotEmpty() && it.none { char ->
            char.isISOControl() || char == '\uFFFD' || Character.getType(char) == Character.FORMAT.toInt()
        } }
    }

    internal fun jsonServerDescription(config: String?): String? {
        if (config == null || !config.contains("serverDescription")) return null
        return runCatching {
            val profile = com.google.gson.JsonParser.parseString(config).asJsonObject
            jsonString(jsonObject(profile, "meta"), "serverDescription")?.let(::cleanServerDescription)
        }.getOrNull()
    }

    internal fun vlessServerDescription(rawLink: String): String? =
        rawLink.takeIf { it.startsWith("vless://", ignoreCase = true) }
            ?.substringAfter('#', "")
            ?.let { splitVlessLabel(decodeUrlSafe(it.replace("+", "%2B"))).second }

    private fun decodeUrlPreservePlus(input: String): String =
        decodeUrlSafe(input.replace("+", "%2B"))

    private fun parseVless(raw: String, subId: String, subName: String): ServerNode? {
        val uri = ParsedUri(raw)
        val uuid = uri.userInfo?.let(::decodeUrlPreservePlus) ?: ""
        val host = uri.host?.trim() ?: ""
        if (host.isEmpty()) return null
        if (uri.hasExplicitPort && uri.port !in 1..65535) return null
        val port = if (uri.port in 1..65535) uri.port else 443
        val rawName = uri.fragment?.let { fragment ->
            val (name, description) = splitVlessLabel(decodeUrlPreservePlus(fragment))
            if (description == null) decodeUrlSafe(fragment) else name
        } ?: "$host:$port"

        val flow = uri.getQueryParameter("flow") ?: ""
        val encryption = uri.getQueryParameter("encryption") ?: "none"
        val rawNet = uri.getQueryParameter("type")
            ?: uri.getQueryParameter("net")
            ?: uri.getQueryParameter("transport")
            ?: uri.getQueryParameter("network")
        val network = normalizeNetwork(rawNet)
        require(network != "xdrive") { "XDrive requires a JSON profile with storage settings" }

        val pbk = uri.getQueryParameter("pbk") ?: uri.getQueryParameter("publicKey") ?: ""
        val sid = uri.getQueryParameter("sid") ?: uri.getQueryParameter("shortId") ?: ""
        val spx = uri.getQueryParameter("spx") ?: uri.getQueryParameter("spiderX") ?: ""

        val rawSec = uri.getQueryParameter("security") ?: uri.getQueryParameter("sec")
        val security = when {
            rawSec != null -> rawSec.lowercase().trim()
            pbk.isNotEmpty() -> "reality"
            port == 443 -> "tls"
            else -> "none"
        }

        val sni = uri.getQueryParameter("sni")
            ?: uri.getQueryParameter("peer")
            ?: uri.getQueryParameter("serverName")
            ?: ""
        val fp = uri.getQueryParameter("fp") ?: uri.getQueryParameter("fingerprint") ?: "chrome"

        val pathParam = uri.getQueryParameter("path") ?: uri.getQueryParameter("serviceName")
        val rawUriPath = uri.path?.trim()?.takeIf { it.isNotEmpty() && it != "/" }
        val finalPath = if (network == "grpc") {
            pathParam?.trim()?.takeIf { it.isNotEmpty() }
                ?: rawUriPath?.removePrefix("/")
                ?: ""
        } else {
            when {
                !pathParam.isNullOrEmpty() -> if (pathParam.startsWith("/")) pathParam else "/$pathParam"
                !rawUriPath.isNullOrEmpty() -> if (rawUriPath.startsWith("/")) rawUriPath else "/$rawUriPath"
                else -> "/"
            }
        }

        val httpHost = uri.getQueryParameter("host")
            ?: uri.getQueryParameter("authority")
            ?: uri.getQueryParameter("obfsParam")
            ?: ""
        var mode = uri.getQueryParameter("mode") ?: "auto"
        var padding = uri.getQueryParameter("x_padding_bytes") ?: "100-1000"

        val extra = uri.getQueryParameter("extra")
        if (!extra.isNullOrEmpty()) {
            try {
                val decodedExtra = decodeUrlPreservePlus(extra)
                val json = gson.fromJson(decodedExtra, JsonObject::class.java)
                if (json != null) {
                    jsonString(json, "mode")?.let { mode = it }
                    jsonString(json, "xPaddingBytes")?.let { padding = it }
                }
            } catch (_: Exception) {
            }
        }

        return ServerNode(
            id = UUID.randomUUID().toString(),
            name = rawName,
            protocol = "vless",
            address = host,
            port = port,
            uuid = uuid,
            flow = flow,
            encryption = encryption,
            network = network,
            security = security,
            sni = sni,
            fingerprint = fp,
            publicKey = pbk,
            shortId = sid,
            spiderX = spx,
            path = finalPath,
            host = httpHost,
            mode = mode,
            padding = padding,
            rawLink = raw,
            subId = subId,
            subName = subName
        )
    }

    private fun parseTrojan(raw: String, subId: String, subName: String): ServerNode? {
        val uri = ParsedUri(raw)
        val password = uri.userInfo?.let(::decodeUrlPreservePlus) ?: ""
        val host = uri.host?.trim() ?: ""
        if (host.isEmpty()) return null
        if (uri.hasExplicitPort && uri.port !in 1..65535) return null
        val port = if (uri.port in 1..65535) uri.port else 443
        val rawName = uri.fragment?.let { decodeUrlSafe(it) } ?: "Trojan-$host"

        val rawNet = uri.getQueryParameter("type")
            ?: uri.getQueryParameter("net")
            ?: uri.getQueryParameter("transport")
        val network = normalizeNetwork(rawNet)

        val pbk = uri.getQueryParameter("pbk") ?: uri.getQueryParameter("publicKey") ?: ""
        val sid = uri.getQueryParameter("sid") ?: uri.getQueryParameter("shortId") ?: ""
        val spx = uri.getQueryParameter("spx") ?: uri.getQueryParameter("spiderX") ?: ""

        val rawSec = uri.getQueryParameter("security") ?: uri.getQueryParameter("sec")
        val security = when {
            rawSec != null -> rawSec.lowercase().trim()
            pbk.isNotEmpty() -> "reality"
            else -> "tls"
        }

        val sni = uri.getQueryParameter("sni")
            ?: uri.getQueryParameter("peer")
            ?: uri.getQueryParameter("serverName")
            ?: host
        val fp = uri.getQueryParameter("fp") ?: uri.getQueryParameter("fingerprint") ?: "chrome"

        val pathParam = uri.getQueryParameter("path") ?: uri.getQueryParameter("serviceName")
        val rawUriPath = uri.path?.trim()?.takeIf { it.isNotEmpty() && it != "/" }
        val finalPath = if (network == "grpc") {
            pathParam?.trim()?.takeIf { it.isNotEmpty() }
                ?: rawUriPath?.removePrefix("/")
                ?: ""
        } else {
            when {
                !pathParam.isNullOrEmpty() -> if (pathParam.startsWith("/")) pathParam else "/$pathParam"
                !rawUriPath.isNullOrEmpty() -> if (rawUriPath.startsWith("/")) rawUriPath else "/$rawUriPath"
                else -> "/"
            }
        }

        val httpHost = uri.getQueryParameter("host")
            ?: uri.getQueryParameter("authority")
            ?: ""
        var mode = uri.getQueryParameter("mode") ?: "auto"
        var padding = uri.getQueryParameter("x_padding_bytes") ?: "100-1000"

        val extra = uri.getQueryParameter("extra")
        if (!extra.isNullOrEmpty()) {
            try {
                val decodedExtra = decodeUrlPreservePlus(extra)
                val json = gson.fromJson(decodedExtra, JsonObject::class.java)
                if (json != null) {
                    jsonString(json, "mode")?.let { mode = it }
                    jsonString(json, "xPaddingBytes")?.let { padding = it }
                }
            } catch (_: Exception) {
            }
        }

        return ServerNode(
            id = UUID.randomUUID().toString(),
            name = rawName,
            protocol = "trojan",
            address = host,
            port = port,
            uuid = password,
            network = network,
            security = security,
            sni = sni,
            fingerprint = fp,
            publicKey = pbk,
            shortId = sid,
            spiderX = spx,
            path = finalPath,
            host = httpHost,
            mode = mode,
            padding = padding,
            rawLink = raw,
            subId = subId,
            subName = subName
        )
    }

    fun parseBatch(rawText: String, subId: String = "", subName: String = ""): List<ServerNode> {
        return parseSubscription(rawText, subId, subName)
    }

    fun parseSubscription(rawContent: String, subId: String = "", subName: String = ""): List<ServerNode> {
        require(rawContent.length <= 8 * 1024 * 1024) { "Subscription exceeds 8 MB" }
        val decoded = decodeSubscriptionContent(rawContent).trimStart().removePrefix("\uFEFF").trimStart()
        if (decoded.startsWith("[Interface]", true)) return listOf(AmneziaWg.node(decoded, subId, subName))
        val nodes = mutableListOf<ServerNode>()

        // 1. Clash YAML
        if (decoded.contains("proxies:") || (decoded.contains("proxy-groups:") && decoded.contains("server:"))) {
            val clashNodes = parseClashYaml(decoded, subId, subName)
            if (clashNodes.isNotEmpty()) {
                return clashNodes
            }
        }

        // 2. Sing-box / Xray JSON (including arrays of complete Xray profiles).
        if (decoded.startsWith("{") || decoded.startsWith("[")) {
            val jsonNodes = parseJsonSubscription(decoded, subId, subName)
            if (jsonNodes.isNotEmpty()) {
                return jsonNodes
            }
        }

        // 3. Fallback: Parse as URI links
        val linkPattern = Regex("""(?i)(?:vless|vmess|trojan|ss|socks5?|wireguard|wg|awg|vpn|hy2|hysteria2)://[^\s"'<>]+""")
        val matches = linkPattern.findAll(decoded).map { it.value.trim() }.iterator()

        if (matches.hasNext()) {
            matches.forEach { link ->
                parse(link, subId, subName)?.let { nodes.addBounded(it) }
            }
        } else {
            decoded.lineSequence().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotBlank() && !trimmed.startsWith("#")) {
                    parse(trimmed, subId, subName)?.let { nodes.addBounded(it) }
                }
            }
        }

        return nodes
    }

    fun decodeSubscriptionContent(raw: String): String {
        var trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""

        // Strip data: prefix if user/source formatted payload as a data URI
        if (trimmed.startsWith("data:", ignoreCase = true) && trimmed.contains(";base64,")) {
            trimmed = trimmed.substringAfter(";base64,").trim()
        }

        // If raw content already contains protocol schemes or JSON/YAML markers, it is plain text.
        if (trimmed.contains("://") ||
            trimmed.contains("proxies:") ||
            trimmed.contains("proxy-groups:") ||
            trimmed.contains("rules:") ||
            trimmed.startsWith("{") || trimmed.startsWith("[")
        ) {
            return trimmed
        }

        fun tryDecode(input: String): String? {
            val noWs = input.filterNot { it.isWhitespace() }
            if (noWs.isEmpty()) return null
            // Base64 (standard and url-safe) alphabet check: must not contain colons, commas, etc.
            if (!noWs.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '/' || it == '=' || it == '-' || it == '_' }) {
                return null
            }
            val standardized = StringBuilder(noWs.replace('-', '+').replace('_', '/')).apply {
                while (length % 4 != 0) {
                    append('=')
                }
            }.toString()

            val decodedBytes = try {
                Base64.getDecoder().decode(standardized)
            } catch (_: Throwable) {
                try {
                    val urlPadded = StringBuilder(noWs).apply {
                        while (length % 4 != 0) append('=')
                    }.toString()
                    Base64.getUrlDecoder().decode(urlPadded)
                } catch (_: Throwable) {
                    try {
                        Base64.getUrlDecoder().decode(noWs)
                    } catch (_: Throwable) {
                        try {
                            android.util.Base64.decode(standardized, android.util.Base64.DEFAULT)
                        } catch (_: Throwable) {
                            try {
                                android.util.Base64.decode(noWs, android.util.Base64.URL_SAFE)
                            } catch (_: Throwable) {
                                null
                            }
                        }
                    }
                }
            } ?: return null

            val decoded = String(decodedBytes, Charsets.UTF_8).trim()
            if (decoded.contains("://") ||
                decoded.contains("proxies:") ||
                decoded.contains("proxy-groups:") ||
                decoded.contains("rules:") ||
                decoded.startsWith("{") || decoded.startsWith("[")
            ) {
                return decoded
            }
            if (decoded.isNotEmpty() && !decoded.contains('\u0000') && decoded.any { it.isLetterOrDigit() }) {
                return decoded
            }
            return null
        }

        val firstPass = tryDecode(trimmed) ?: return trimmed
        // Some providers double-encode their base64 subscriptions
        val secondPass = if (!firstPass.contains("://") && !firstPass.contains("proxies:") &&
            !firstPass.contains("rules:") &&
            !firstPass.startsWith("{") && !firstPass.startsWith("[")
        ) {
            tryDecode(firstPass) ?: firstPass
        } else firstPass

        return secondPass
    }

    fun isBase64Content(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.length < 16) return false
        if (trimmed.contains("://") || trimmed.contains("rules:") || trimmed.startsWith("{") || trimmed.startsWith("[")) return false
        val decoded = decodeSubscriptionContent(trimmed)
        return decoded != trimmed && (decoded.contains("://") || decoded.contains("proxies:") || decoded.contains("rules:") || decoded.startsWith("{") || decoded.startsWith("["))
    }

    private fun splitHostPort(hostPort: String, defaultPort: Int): Pair<String, Int>? {
        val cleaned = hostPort.substringBefore('/').trim()
        if (cleaned.isEmpty()) return null
        return if (cleaned.startsWith("[")) {
            val closeBracket = cleaned.indexOf(']')
            if (closeBracket <= 1) return null
            val host = cleaned.substring(1, closeBracket).trim()
            val tail = cleaned.substring(closeBracket + 1)
            val port = when {
                tail.isEmpty() -> defaultPort
                tail.startsWith(":") -> tail.substring(1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
                else -> return null
            }
            if (host.isEmpty()) null else host to port
        } else {
            val colonIdx = cleaned.lastIndexOf(':')
            if (colonIdx >= 0) {
                val host = cleaned.substring(0, colonIdx).trim()
                val port = cleaned.substring(colonIdx + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
                if (host.isEmpty()) null else host to port
            } else {
                cleaned to defaultPort
            }
        }
    }

    private fun parseVmess(raw: String, subId: String, subName: String): ServerNode? {
        val fragmentName = if (raw.contains("#")) {
            try {
                URLDecoder.decode(raw.substringAfter("#"), "UTF-8")
            } catch (_: Exception) {
                null
            }
        } else null

        val base64Content = raw.substringAfter("://")
            .substringBefore("#")
            .substringBefore("?")
            .trim()
        val decoded = decodeBase64Safe(base64Content)
        val json = runCatching { gson.fromJson(decoded, JsonObject::class.java) }.getOrNull() ?: return null

        val host = jsonString(json, "add")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val port = jsonInt(json, "port")?.takeIf { it in 1..65535 }
            ?: jsonString(json, "port")?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: if (json.has("port") && !json.get("port").isJsonNull && jsonString(json, "port")?.isNotBlank() == true) return null else 443
        val id = jsonString(json, "id") ?: ""
        val ps = jsonString(json, "ps")?.takeIf { it.isNotBlank() }
            ?: fragmentName
            ?: "VMess-$host"
        val rawNet = jsonString(json, "net") ?: jsonString(json, "type") ?: "tcp"
        val network = normalizeNetwork(rawNet)
        val rawTls = jsonString(json, "tls")
        val tlsBool = jsonBoolean(json, "tls") == true
        val security = if (tlsBool || rawTls.equals("tls", ignoreCase = true) || rawTls == "1" || rawTls.equals("true", ignoreCase = true)) {
            "tls"
        } else {
            "none"
        }
        val httpHost = jsonString(json, "host") ?: ""
        val sni = jsonString(json, "sni")?.takeIf { it.isNotEmpty() }
            ?: httpHost.substringBefore(',').trim().takeIf { it.isNotEmpty() }
            ?: host
        val rawPath = jsonString(json, "path") ?: if (network == "grpc") "" else "/"
        val path = if (network == "grpc") {
            rawPath.trim()
        } else {
            if (rawPath.startsWith("/")) rawPath else "/$rawPath"
        }
        val scy = jsonString(json, "scy")?.takeIf { it.isNotBlank() } ?: "auto"
        val fp = jsonString(json, "fp")?.takeIf { it.isNotBlank() } ?: "chrome"
        val mode = jsonString(json, "mode")?.takeIf { it.isNotBlank() } ?: "auto"

        return ServerNode(
            id = UUID.randomUUID().toString(),
            name = ps,
            protocol = "vmess",
            address = host,
            port = port,
            uuid = id,
            encryption = scy,
            network = network,
            security = security,
            sni = sni,
            host = httpHost,
            fingerprint = fp,
            path = path,
            mode = mode,
            rawLink = raw,
            subId = subId,
            subName = subName
        )
    }

    private fun parseShadowsocks(raw: String, subId: String, subName: String): ServerNode? {
        return try {
            val withoutScheme = raw.substringAfter("://")
            val fragment = if (withoutScheme.contains("#")) {
                decodeUrlSafe(withoutScheme.substringAfter("#"))
            } else null

            val mainPart = withoutScheme.substringBefore("#")
            val connectionPart = mainPart.substringBefore("?")

            var method = ""
            var password = ""
            var host = ""
            var port = 8388

            if (connectionPart.contains("@")) {
                val userInfoEncoded = connectionPart.substringBeforeLast("@")
                val hostPort = connectionPart.substringAfterLast("@")

                val decodedUserInfo = when {
                    userInfoEncoded.contains(":") -> {
                        val m = decodeUrlPreservePlus(userInfoEncoded.substringBefore(":"))
                        val p = decodeUrlPreservePlus(userInfoEncoded.substringAfter(":"))
                        "$m:$p"
                    }
                    userInfoEncoded.contains("%3A", ignoreCase = true) -> decodeUrlPreservePlus(userInfoEncoded)
                    else -> decodeBase64Safe(userInfoEncoded)
                }
                if (decodedUserInfo.contains(":")) {
                    method = decodedUserInfo.substringBefore(":")
                    password = decodedUserInfo.substringAfter(":")
                }

                val endpoint = splitHostPort(hostPort, 8388) ?: return null
                host = endpoint.first
                port = endpoint.second
            } else {
                val decoded = decodeBase64Safe(connectionPart.substringBefore('/'))
                if (decoded.contains("@")) {
                    val userInfo = decoded.substringBeforeLast("@")
                    val hostPort = decoded.substringAfterLast("@")

                    if (userInfo.contains(":")) {
                        method = userInfo.substringBefore(":")
                        password = userInfo.substringAfter(":")
                    }

                    val endpoint = splitHostPort(hostPort, 8388) ?: return null
                    host = endpoint.first
                    port = endpoint.second
                }
            }

            if (host.isEmpty() || method.isEmpty()) return null

            val rawName = fragment?.takeIf { it.isNotBlank() } ?: "Shadowsocks-$host:$port"

            ServerNode(
                id = UUID.randomUUID().toString(),
                name = rawName,
                protocol = "shadowsocks",
                address = host,
                port = port,
                uuid = password,
                encryption = method,
                network = "tcp",
                security = "none",
                rawLink = raw,
                subId = subId,
                subName = subName
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun cleanLinkForSharing(raw: String): String {
        var cleaned = raw.trim()
        if (cleaned.contains("extra=")) {
            cleaned = cleaned.replace(Regex("&?extra=[^&#]*"), "")
                .replace("?&", "?")
                .replace("&&", "&")
                .removeSuffix("?")
                .removeSuffix("&")
        }
        return cleaned
    }

    fun toShareLink(node: ServerNode): String {
        if (node.isAmneziaWg) return AmneziaWg.share(requireNotNull(node.amneziaWg).validated()) + "#" + java.net.URLEncoder.encode(node.name, "UTF-8").replace("+", "%20")
        if (node.protocol.lowercase() in setOf("hysteria2", "hy2")) return Hysteria2Profile.share(node)
        if (node.protocol.lowercase() !in setOf("socks", "socks5") && node.rawLink.isNotBlank() && node.rawLink.contains("://")) {
            val cleaned = cleanLinkForSharing(node.rawLink)
            if (cleaned.length <= 1500) {
                return cleaned
            }
            // If raw link is excessively large, proceed to canonical generation below
        }

        val nameEncoded = try {
            java.net.URLEncoder.encode(node.name, "UTF-8").replace("+", "%20")
        } catch (e: Exception) {
            node.name
        }

        fun formatHost(address: String): String =
            address.removeSurrounding("[", "]").let { if (':' in it) "[$it]" else it }
        fun encodeQuery(value: String): String =
            java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        return when (node.protocol.lowercase()) {
            "hysteria2", "hy2" -> Hysteria2Profile.share(node)
            "vless" -> {
                val params = mutableListOf<String>()
                if (node.security.isNotBlank()) params.add("security=${encodeQuery(node.security)}")
                if (node.encryption.isNotBlank() && node.encryption != "none") params.add("encryption=${encodeQuery(node.encryption)}")
                if (node.sni.isNotBlank()) params.add("sni=${encodeQuery(node.sni)}")
                if (node.fingerprint.isNotBlank() && node.fingerprint != "chrome") params.add("fp=${encodeQuery(node.fingerprint)}")
                if (node.network.isNotBlank() && node.network != "tcp") params.add("type=${encodeQuery(node.network)}")
                if (node.flow.isNotBlank()) params.add("flow=${encodeQuery(node.flow)}")
                if (node.publicKey.isNotBlank()) params.add("pbk=${encodeQuery(node.publicKey)}")
                extractRealityVerificationKey(node.rawLink)?.let {
                    // Escape underscores too: messenger Markdown can remove paired __ from a raw key.
                    params.add("pqv=${java.net.URLEncoder.encode(it, "UTF-8").replace("_", "%5F")}")
                }
                if (node.shortId.isNotBlank()) params.add("sid=${encodeQuery(node.shortId)}")
                if (node.spiderX.isNotBlank()) params.add("spx=${encodeQuery(node.spiderX)}")
                if (node.path.isNotBlank() && node.path != "/") {
                    val key = if (node.network == "grpc") "serviceName" else "path"
                    params.add("$key=${encodeQuery(node.path)}")
                }
                if (node.host.isNotBlank()) params.add("host=${encodeQuery(node.host)}")
                if (node.mode.isNotBlank() && node.mode != "auto") params.add("mode=${encodeQuery(node.mode)}")
                val q = if (params.isNotEmpty()) "?" + params.joinToString("&") else ""
                "vless://${encodeQuery(node.uuid)}@${formatHost(node.address)}:${node.port}$q#$nameEncoded"
            }
            "trojan" -> {
                val params = mutableListOf<String>()
                if (node.security.isNotBlank()) params.add("security=${encodeQuery(node.security)}")
                if (node.sni.isNotBlank()) params.add("sni=${encodeQuery(node.sni)}")
                if (node.fingerprint.isNotBlank() && node.fingerprint != "chrome") params.add("fp=${encodeQuery(node.fingerprint)}")
                if (node.network.isNotBlank() && node.network != "tcp") params.add("type=${encodeQuery(node.network)}")
                if (node.publicKey.isNotBlank()) params.add("pbk=${encodeQuery(node.publicKey)}")
                if (node.shortId.isNotBlank()) params.add("sid=${encodeQuery(node.shortId)}")
                if (node.spiderX.isNotBlank()) params.add("spx=${encodeQuery(node.spiderX)}")
                if (node.path.isNotBlank() && node.path != "/") {
                    val key = if (node.network == "grpc") "serviceName" else "path"
                    params.add("$key=${encodeQuery(node.path)}")
                }
                if (node.host.isNotBlank()) params.add("host=${encodeQuery(node.host)}")
                if (node.mode.isNotBlank() && node.mode != "auto") params.add("mode=${encodeQuery(node.mode)}")
                val q = if (params.isNotEmpty()) "?" + params.joinToString("&") else ""
                "trojan://${encodeQuery(node.uuid)}@${formatHost(node.address)}:${node.port}$q#$nameEncoded"
            }
            "shadowsocks", "ss" -> {
                val userPass = "${node.encryption}:${node.uuid}"
                val b64 = java.util.Base64.getEncoder().encodeToString(userPass.toByteArray(Charsets.UTF_8))
                "ss://$b64@${formatHost(node.address)}:${node.port}#$nameEncoded"
            }
            "vmess" -> {
                val json = com.google.gson.JsonObject().apply {
                    addProperty("v", "2")
                    addProperty("ps", node.name)
                    addProperty("add", node.address)
                    addProperty("port", node.port)
                    addProperty("id", node.uuid)
                    addProperty("aid", 0)
                    addProperty("scy", node.encryption.ifBlank { "auto" })
                    addProperty("net", node.network.ifBlank { "tcp" })
                    addProperty("type", "none")
                    if (node.host.isNotBlank()) addProperty("host", node.host)
                    if (node.path.isNotBlank()) addProperty("path", node.path)
                    if (node.security == "tls") addProperty("tls", "tls")
                    if (node.sni.isNotBlank()) addProperty("sni", node.sni)
                    if (node.fingerprint.isNotBlank() && node.fingerprint != "chrome") addProperty("fp", node.fingerprint)
                }
                val b64 = java.util.Base64.getEncoder().encodeToString(json.toString().toByteArray(Charsets.UTF_8))
                "vmess://$b64"
            }
            "socks", "socks5" -> {
                val auth = if (node.encryption.isNotEmpty() || node.uuid.isNotEmpty()) {
                    "${encodeQuery(node.encryption)}:${encodeQuery(node.uuid)}@"
                } else ""
                "socks5://$auth${formatHost(node.address)}:${node.port}#$nameEncoded"
            }
            "tuic" -> {
                val params = mutableListOf<String>()
                if (node.sni.isNotBlank()) params.add("sni=${encodeQuery(node.sni)}")
                if (node.flow.isNotBlank()) params.add("congestion_control=${encodeQuery(node.flow)}")
                val q = if (params.isNotEmpty()) "?" + params.joinToString("&") else ""
                "tuic://${encodeQuery(node.uuid)}@${formatHost(node.address)}:${node.port}$q#$nameEncoded"
            }
            "wireguard", "wg" -> {
                "wireguard://${encodeQuery(node.publicKey)}@${formatHost(node.address)}:${node.port}#$nameEncoded"
            }
            else -> cleanLinkForSharing(node.rawLink)
        }
    }

    private fun parseSocks(raw: String, subId: String, subName: String): ServerNode? {
        return try {
            val uri = ParsedUri(raw)
            val host = uri.host?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (uri.hasExplicitPort && uri.port !in 1..65535) return null
            val port = if (uri.port in 1..65535) uri.port else 1080
            val userInfo = uri.userInfo ?: ""
            val rawName = uri.fragment?.let { decodeUrlSafe(it) } ?: "SOCKS5-$host:$port"

            val (username, password) = when {
                userInfo.contains(":") ->
                    decodeUrlPreservePlus(userInfo.substringBefore(":")) to decodeUrlPreservePlus(userInfo.substringAfter(":"))
                userInfo.isNotEmpty() && !userInfo.contains("%") -> {
                    val decoded = decodeBase64Safe(userInfo)
                    val canonical = Base64.getEncoder().withoutPadding().encodeToString(decoded.toByteArray(Charsets.UTF_8))
                    val normalizedInput = userInfo.trimEnd('=').replace('-', '+').replace('_', '/')
                    if (decoded.contains(":") && canonical == normalizedInput && decoded.none { it.isISOControl() }) {
                        decoded.substringBefore(":") to decoded.substringAfter(":")
                    } else {
                        decodeUrlPreservePlus(userInfo) to ""
                    }
                }
                else -> decodeUrlPreservePlus(userInfo) to ""
            }

            ServerNode(
                id = UUID.randomUUID().toString(),
                name = rawName,
                protocol = "socks",
                address = host,
                port = port,
                uuid = password,
                encryption = username,
                network = "tcp",
                security = "none",
                rawLink = raw,
                subId = subId,
                subName = subName
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseWireGuardUri(raw: String, subId: String, subName: String): ServerNode? {
        return try {
            val uri = ParsedUri(raw)
            val privateKey = uri.userInfo?.let(::decodeUrlPreservePlus)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val host = uri.host?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (uri.hasExplicitPort && uri.port !in 1..65535) return null
            val port = if (uri.port in 1..65535) uri.port else 51820
            val publicKey = (uri.getQueryParameter("publickey") ?: uri.getQueryParameter("publicKey"))?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val addresses = (uri.getQueryParameter("address") ?: uri.getQueryParameter("addresses"))?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val presharedKey = (uri.getQueryParameter("presharedkey") ?: uri.getQueryParameter("presharedKey") ?: uri.getQueryParameter("psk"))?.trim()?.takeIf { it.isNotEmpty() }
            val mtu = uri.getQueryParameter("mtu")?.trim()?.toIntOrNull()
            val dns = uri.getQueryParameter("dns")?.trim()?.takeIf { it.isNotEmpty() }
            val allowedIps = (uri.getQueryParameter("allowedips") ?: uri.getQueryParameter("allowed_ips"))?.trim()?.takeIf { it.isNotEmpty() } ?: "0.0.0.0/0, ::/0"
            val keepalive = (uri.getQueryParameter("keepalive") ?: uri.getQueryParameter("persistentkeepalive"))?.trim()?.toIntOrNull()
            val endpointHost = if (':' in host) "[$host]" else host
            val conf = buildString {
                appendLine("[Interface]")
                appendLine("PrivateKey = $privateKey")
                appendLine("Address = $addresses")
                if (dns != null) appendLine("DNS = $dns")
                if (mtu != null) appendLine("MTU = $mtu")
                appendLine("[Peer]")
                appendLine("PublicKey = $publicKey")
                if (presharedKey != null) appendLine("PresharedKey = $presharedKey")
                appendLine("Endpoint = $endpointHost:$port")
                appendLine("AllowedIPs = $allowedIps")
                if (keepalive != null) appendLine("PersistentKeepalive = $keepalive")
            }
            val node = AmneziaWg.node(conf, subId, subName)
            val name = uri.fragment?.let(::decodeUrlSafe)?.trim()?.takeIf { it.isNotEmpty() }?.take(256) ?: "WireGuard · $host"
            node.copy(name = name)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseClashInlineMap(text: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        fun parseObject(body: String, prefix: String) {
            val inner = body.trim().removePrefix("{").removeSuffix("}")
            val entries = mutableListOf<String>()
            var depth = 0
            var inSingle = false
            var inDouble = false
            var start = 0
            for (i in inner.indices) {
                val c = inner[i]
                when {
                    c == '\'' && !inDouble -> inSingle = !inSingle
                    c == '"' && !inSingle && (i == 0 || inner[i - 1] != '\\') -> inDouble = !inDouble
                    !inSingle && !inDouble && (c == '{' || c == '[') -> depth++
                    !inSingle && !inDouble && (c == '}' || c == ']') -> if (depth > 0) depth--
                    !inSingle && !inDouble && depth == 0 && c == ',' -> {
                        entries.add(inner.substring(start, i))
                        start = i + 1
                    }
                }
            }
            if (start <= inner.length) entries.add(inner.substring(start))
            for (entry in entries) {
                val colon = entry.indexOf(':')
                if (colon <= 0) continue
                val key = entry.substring(0, colon).trim().trim('"', '\'').lowercase()
                val rawVal = entry.substring(colon + 1).trim()
                if (key.isEmpty()) continue
                val fullKey = if (prefix.isNotEmpty()) "$prefix.$key" else key
                if (rawVal.startsWith("{") && rawVal.endsWith("}")) {
                    parseObject(rawVal, fullKey)
                } else {
                    result[fullKey] = rawVal.trim('"', '\'')
                }
            }
        }
        parseObject(text, "")
        return result
    }

    private fun parseClashYaml(yamlText: String, subId: String, subName: String): List<ServerNode> {
        val nodes = mutableListOf<ServerNode>()
        val lines = yamlText.lineSequence()
        var inProxies = false
        val currentProps = mutableMapOf<String, String>()
        val sectionStack = mutableListOf<Pair<Int, String>>()

        fun finishBlock() {
            if (currentProps.isNotEmpty() && currentProps.containsKey("server")) {
                parseClashProxyMap(currentProps, subId, subName)?.let { nodes.addBounded(it) }
            }
            currentProps.clear()
            sectionStack.clear()
        }

        for (line in lines) {
            val stripped = line.trim()
            if (stripped.isEmpty() || stripped.startsWith("#")) continue
            if (stripped == "proxies:" || stripped.startsWith("proxies:")) {
                inProxies = true
                continue
            } else if (inProxies && !line.startsWith(" ") && !line.startsWith("\t") && !stripped.startsWith("-")) {
                inProxies = false
                finishBlock()
                break
            }

            if (inProxies) {
                val rawIndent = line.takeWhile { it == ' ' || it == '\t' }.length
                var working = stripped
                var effectiveIndent = rawIndent
                if (working.startsWith("- ")) {
                    finishBlock()
                    working = working.substring(2).trim()
                    effectiveIndent = rawIndent + 2
                    if (working.startsWith("{") && working.endsWith("}")) {
                        val inlineProps = parseClashInlineMap(working)
                        if (inlineProps.containsKey("server")) {
                            parseClashProxyMap(inlineProps, subId, subName)?.let { nodes.addBounded(it) }
                        }
                        continue
                    }
                }

                while (sectionStack.isNotEmpty() && effectiveIndent <= sectionStack.last().first) {
                    sectionStack.removeAt(sectionStack.lastIndex)
                }

                if (working.contains(":")) {
                    val colonIdx = working.indexOf(':')
                    val key = working.substring(0, colonIdx).trim().trim('"', '\'').lowercase()
                    val value = working.substring(colonIdx + 1).trim().trim('"', '\'')
                    val prefix = sectionStack.joinToString(".") { it.second }
                    val fullKey = if (prefix.isNotEmpty()) "$prefix.$key" else key
                    if (value.isEmpty()) {
                        sectionStack.add(effectiveIndent to key)
                    } else if (value.startsWith("{") && value.endsWith("}")) {
                        parseClashInlineMap(value).forEach { (k, v) ->
                            currentProps["$fullKey.$k"] = v
                        }
                    } else {
                        currentProps[fullKey] = value
                    }
                }
            }
        }
        finishBlock()
        return nodes
    }

    private fun parseClashProxyMap(props: Map<String, String>, subId: String, subName: String): ServerNode? {
        val type = props["type"]?.lowercase() ?: return null
        val server = props["server"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val port = props["port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: if (type in setOf("socks", "socks5")) 1080 else 443
        val name = props["name"] ?: "$type-$server"

        return when (type) {
            "vless" -> {
                val uuid = props["uuid"] ?: ""
                val network = normalizeNetwork(props["network"])
                val flow = props["flow"] ?: ""
                val pbk = props["reality-opts.public-key"] ?: props["public-key"] ?: ""
                val sid = props["reality-opts.short-id"] ?: props["short-id"] ?: ""
                val isTls = props["tls"]?.equals("true", ignoreCase = true) == true
                val security = when {
                    pbk.isNotEmpty() -> "reality"
                    isTls -> "tls"
                    else -> "none"
                }
                val sni = props["servername"] ?: props["sni"] ?: props["peer"] ?: ""
                val fp = props["client-fingerprint"] ?: props["fp"] ?: "chrome"
                val path = props["ws-opts.path"]
                    ?: props["grpc-opts.grpc-service-name"]
                    ?: props["h2-opts.path"]
                    ?: props["http-opts.path"]
                    ?: if (network == "grpc") "" else "/"
                val host = props["ws-opts.headers.host"]
                    ?: props["headers.host"]
                    ?: props["h2-opts.host"]
                    ?: ""

                ServerNode(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    protocol = "vless",
                    address = server,
                    port = port,
                    uuid = uuid,
                    flow = flow,
                    encryption = props["encryption"] ?: "none",
                    network = network,
                    security = security,
                    sni = sni,
                    fingerprint = fp,
                    publicKey = pbk,
                    shortId = sid,
                    path = path,
                    host = host,
                    rawLink = "",
                    subId = subId,
                    subName = subName
                )
            }
            "trojan" -> {
                val password = props["password"] ?: ""
                val network = normalizeNetwork(props["network"])
                val pbk = props["reality-opts.public-key"] ?: props["public-key"] ?: ""
                val sid = props["reality-opts.short-id"] ?: props["short-id"] ?: ""
                val security = if (pbk.isNotEmpty()) "reality" else "tls"
                val sni = props["sni"] ?: props["servername"] ?: ""
                val fp = props["client-fingerprint"] ?: props["fp"] ?: "chrome"
                val path = props["ws-opts.path"]
                    ?: props["grpc-opts.grpc-service-name"]
                    ?: if (network == "grpc") "" else "/"
                val host = props["ws-opts.headers.host"] ?: props["headers.host"] ?: ""

                ServerNode(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    protocol = "trojan",
                    address = server,
                    port = port,
                    uuid = password,
                    network = network,
                    security = security,
                    sni = sni,
                    fingerprint = fp,
                    publicKey = pbk,
                    shortId = sid,
                    path = path,
                    host = host,
                    rawLink = "",
                    subId = subId,
                    subName = subName
                )
            }
            "ss", "shadowsocks" -> {
                val cipher = props["cipher"] ?: "aes-256-gcm"
                val password = props["password"] ?: ""

                ServerNode(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    protocol = "shadowsocks",
                    address = server,
                    port = port,
                    uuid = password,
                    encryption = cipher,
                    network = "tcp",
                    security = "none",
                    rawLink = "",
                    subId = subId,
                    subName = subName
                )
            }
            "vmess" -> {
                val uuid = props["uuid"] ?: ""
                val cipher = props["cipher"] ?: "auto"
                val network = normalizeNetwork(props["network"])
                val isTls = props["tls"]?.equals("true", ignoreCase = true) == true
                val security = if (isTls) "tls" else "none"
                val sni = props["servername"] ?: props["sni"] ?: ""
                val fp = props["client-fingerprint"] ?: props["fp"] ?: "chrome"
                val path = props["ws-opts.path"]
                    ?: props["grpc-opts.grpc-service-name"]
                    ?: props["h2-opts.path"]
                    ?: if (network == "grpc") "" else "/"
                val host = props["ws-opts.headers.host"] ?: props["headers.host"] ?: ""

                ServerNode(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    protocol = "vmess",
                    address = server,
                    port = port,
                    uuid = uuid,
                    encryption = cipher,
                    network = network,
                    security = security,
                    sni = sni,
                    fingerprint = fp,
                    path = path,
                    host = host,
                    rawLink = "",
                    subId = subId,
                    subName = subName
                )
            }
            "socks", "socks5" -> {
                val username = props["username"] ?: ""
                val password = props["password"] ?: ""

                ServerNode(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    protocol = "socks",
                    address = server,
                    port = port,
                    uuid = password,
                    encryption = username,
                    network = "tcp",
                    security = "none",
                    rawLink = "",
                    subId = subId,
                    subName = subName
                )
            }
            "hysteria2", "hy2" -> {
                fun enc(v: String) = java.net.URLEncoder.encode(v, "UTF-8").replace("+", "%20")
                val password = props["password"] ?: ""
                val sni = props["sni"] ?: ""
                val obfs = props["obfs"] ?: ""
                val obfsPassword = props["obfs-password"] ?: ""
                val ports = props["ports"] ?: ""
                val pin = props["fingerprint"] ?: ""
                val hostPart = if (':' in server) "[$server]" else server
                val portPart = if (ports.isNotBlank()) ports else port.toString()
                val query = buildList {
                    if (sni.isNotBlank()) add("sni=${enc(sni)}")
                    if (obfs.isNotBlank()) add("obfs=${enc(obfs)}")
                    if (obfsPassword.isNotBlank()) add("obfs-password=${enc(obfsPassword)}")
                    if (pin.isNotBlank()) add("pinSHA256=${enc(pin)}")
                }.joinToString("&")
                val suffix = if (query.isNotEmpty()) "?$query" else ""
                Hysteria2Profile.parse("hy2://${enc(password)}@$hostPart:$portPart/$suffix#${enc(name)}", subId, subName)
            }
            else -> null
        }
    }

    private fun parseJsonSubscription(jsonText: String, subId: String, subName: String): List<ServerNode> {
        val root = try {
            com.google.gson.JsonParser.parseString(jsonText)
        } catch (_: Exception) {
            return emptyList()
        }

        val profiles = when {
            root.isJsonArray -> root.asJsonArray.toList()
            root.isJsonObject -> listOf(root)
            else -> return emptyList()
        }
        val nodes = mutableListOf<ServerNode>()

        for (profileElement in profiles) {
            if (!profileElement.isJsonObject) continue
            val profile = profileElement.asJsonObject
            val inboundArray = jsonArray(profile, "inbounds")
            val outboundArray = jsonArray(profile, "outbounds")
            val isXrayOutboundList = outboundArray?.any { element ->
                element.isJsonObject && element.asJsonObject.has("protocol")
            } == true
            val fullConfig = isXrayOutboundList &&
                    (inboundArray != null || profile.has("routing") || profile.has("dns"))
            val candidates = when {
                fullConfig -> listOfNotNull(firstSupportedXrayOutbound(outboundArray))
                outboundArray != null -> outboundArray.toList()
                jsonArray(profile, "servers") != null -> jsonArray(profile, "servers")!!.toList()
                jsonArray(profile, "proxies") != null -> jsonArray(profile, "proxies")!!.toList()
                else -> listOf(profileElement)
            }

            val configName = jsonString(profile, "remarks", "name", "profile-title")
            for (candidate in candidates) {
                if (!candidate.isJsonObject) continue
                val node = parseJsonOutbound(
                    candidate.asJsonObject,
                    subId = subId,
                    subName = subName,
                    nameOverride = configName,
                    fullConfigJson = when {
                        fullConfig -> profile.toString()
                        isXrayOutboundList -> profile.deepCopy().apply {
                            // Keep every transport option and chain dependency while selecting this node.
                            add("outbounds", com.google.gson.JsonArray().apply {
                                add(candidate.deepCopy())
                                outboundArray!!.filter { it !== candidate }.forEach { add(it.deepCopy()) }
                            })
                        }.toString()
                        candidate.asJsonObject.has("protocol") &&
                                (candidate.asJsonObject.has("settings") || candidate.asJsonObject.has("streamSettings")) ->
                            JsonObject().apply {
                                add("outbounds", com.google.gson.JsonArray().apply { add(candidate.deepCopy()) })
                            }.toString()
                        else -> ""
                    }
                )
                if (node != null) nodes.addBounded(node)
            }
        }
        return nodes
    }

    private fun firstSupportedXrayOutbound(outbounds: com.google.gson.JsonArray?): com.google.gson.JsonElement? {
        if (outbounds == null) return null
        return outbounds.firstOrNull { element ->
            if (!element.isJsonObject) return@firstOrNull false
            jsonString(element.asJsonObject, "protocol", "type")?.lowercase() in
                    setOf("vless", "vmess", "trojan", "shadowsocks", "ss", "socks", "socks5") ||
                isXrayHysteria2(element.asJsonObject)
        }
    }

    private fun isXrayHysteria2(outbound: JsonObject): Boolean =
        jsonString(outbound, "protocol")?.lowercase() == "hysteria" &&
            jsonString(jsonObject(outbound, "streamSettings"), "network")?.lowercase() == "hysteria" &&
            (jsonInt(jsonObject(outbound, "settings"), "version") ?: 2) == 2 &&
            (jsonInt(jsonObject(jsonObject(outbound, "streamSettings"), "hysteriaSettings"), "version") ?: 2) == 2

    private fun parseJsonOutbound(
        outbound: JsonObject,
        subId: String,
        subName: String,
        nameOverride: String? = null,
        fullConfigJson: String = ""
    ): ServerNode? {
        val rawType = jsonString(outbound, "protocol", "type")?.lowercase() ?: return null
        val protocol = when (rawType) {
            "ss" -> "shadowsocks"
            "socks5" -> "socks"
            "hysteria" -> if (isXrayHysteria2(outbound)) "hysteria2" else return null
            else -> rawType
        }
        if (protocol == "hysteria2" && !isXrayHysteria2(outbound)) return null
        if (protocol !in setOf("vless", "vmess", "trojan", "shadowsocks", "socks", "hysteria2")) return null

        val settings = jsonObject(outbound, "settings")
        val endpoint = jsonArray(settings, "vnext")?.firstObject()
            ?: jsonArray(settings, "servers")?.firstObject()
            ?: settings
            ?: outbound
        val user = jsonArray(endpoint, "users")?.firstObject()
            ?: jsonArray(settings, "servers")?.firstObject()
            ?: endpoint
        val address = jsonString(outbound, "server", "address")
            ?: jsonString(endpoint, "address", "server", "host")
            ?: return null
        val port = jsonInt(outbound, "server_port", "port")
            ?: jsonInt(endpoint, "port", "server_port")
            ?: if (protocol == "socks") 1080 else 443

        val stream = jsonObject(outbound, "streamSettings")
        val xrayTls = jsonObject(stream, "tlsSettings")
        val xrayReality = jsonObject(stream, "realitySettings")
        val singboxTls = jsonObject(outbound, "tls")
        val singboxReality = jsonObject(singboxTls, "reality")
        val publicKey = jsonString(xrayReality, "publicKey", "public_key")
            ?: jsonString(singboxReality, "public_key", "publicKey")
            ?: ""
        val securityValue = jsonString(stream, "security")?.lowercase()
        val tlsEnabled = jsonBoolean(singboxTls, "enabled")
        val security = when {
            publicKey.isNotBlank() || xrayReality != null || jsonBoolean(singboxReality, "enabled") == true -> "reality"
            securityValue == "reality" -> "reality"
            securityValue == "tls" || xrayTls != null || tlsEnabled == true -> "tls"
            else -> "none"
        }
        val sni = jsonString(xrayTls, "serverName", "server_name")
            ?: jsonString(xrayReality, "serverName", "server_name")
            ?: jsonString(singboxTls, "server_name")
            ?: ""
        val fingerprint = jsonString(xrayTls, "fingerprint")
            ?: jsonString(xrayReality, "fingerprint")
            ?: jsonString(jsonObject(singboxTls, "utls"), "fingerprint")
            ?: "chrome"
        val shortId = jsonString(xrayReality, "shortId", "short_id")
            ?: jsonString(singboxReality, "short_id", "shortId")
            ?: ""
        val spiderX = jsonString(xrayReality, "spiderX", "spider_x") ?: ""

        val singboxTransport = jsonObject(outbound, "transport")
        val network = normalizeNetwork(
            jsonString(stream, "network")
                ?: jsonString(outbound, "network")
                ?: jsonString(singboxTransport, "type")
        )
        val wsSettings = jsonObject(stream, "wsSettings")
        val grpcSettings = jsonObject(stream, "grpcSettings")
        val xhttpSettings = jsonObject(stream, "xhttpSettings")
        val httpSettings = jsonObject(stream, "httpSettings")
        val httpUpgradeSettings = jsonObject(stream, "httpupgradeSettings")
        val path = jsonString(wsSettings, "path")
            ?: jsonString(grpcSettings, "serviceName")
            ?: jsonString(xhttpSettings, "path")
            ?: jsonString(httpUpgradeSettings, "path")
            ?: jsonString(singboxTransport, "path")
            ?: httpSettings?.get("path")?.let { firstJsonString(it) }
            ?: "/"
        val wsHeaders = jsonObject(wsSettings, "headers")
        val singboxHeaders = jsonObject(singboxTransport, "headers")
        val httpHosts = httpSettings?.get("host")
        val host = jsonString(wsHeaders, "Host", "host")
            ?: jsonString(singboxHeaders, "Host", "host")
            ?: jsonString(xhttpSettings, "host")
            ?: httpHosts?.let { firstJsonString(it) }
            ?: ""

        val nodeName = nameOverride?.takeIf { it.isNotBlank() }
            ?: jsonString(outbound, "tag", "remarks", "name")
            ?: "$protocol-$address"
        val userId = jsonString(user, "id", "uuid") ?: jsonString(outbound, "uuid") ?: ""
        val password = jsonString(user, "password") ?: jsonString(outbound, "password") ?: ""
        val uuidOrPassword = if (protocol == "hysteria2") jsonString(jsonObject(stream, "hysteriaSettings"), "auth").orEmpty() else if (protocol == "trojan" || protocol == "shadowsocks" || protocol == "socks") password else userId
        val socksUsername = jsonString(user, "user", "username") ?: jsonString(outbound, "username") ?: ""
        val encryption = when (protocol) {
            "vless" -> jsonString(user, "encryption") ?: "none"
            "vmess" -> jsonString(user, "security", "cipher") ?: jsonString(outbound, "security", "cipher") ?: "auto"
            "shadowsocks" -> jsonString(user, "method", "cipher")
                ?: jsonString(outbound, "method", "cipher") ?: "aes-256-gcm"
            "socks" -> socksUsername
            else -> "none"
        }
        val extra = jsonObject(xhttpSettings, "extra")

        return ServerNode(
            id = UUID.randomUUID().toString(),
            name = nodeName,
            protocol = protocol,
            address = address,
            port = port,
            uuid = uuidOrPassword,
            flow = jsonString(user, "flow") ?: jsonString(outbound, "flow") ?: "",
            encryption = encryption,
            network = network,
            security = security,
            sni = sni,
            fingerprint = fingerprint,
            publicKey = publicKey,
            shortId = shortId,
            spiderX = spiderX,
            path = path,
            host = host,
            mode = jsonString(xhttpSettings, "mode") ?: jsonString(extra, "mode") ?: "auto",
            padding = jsonString(extra, "xPaddingBytes") ?: "100-1000",
            rawLink = "",
            subId = subId,
            subName = subName,
            fullConfigJson = fullConfigJson
        )
    }

    private fun jsonObject(parent: JsonObject?, key: String): JsonObject? =
        parent?.get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun jsonArray(parent: JsonObject?, key: String): com.google.gson.JsonArray? =
        parent?.get(key)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun jsonString(parent: JsonObject?, vararg keys: String): String? {
        for (key in keys) {
            val value = parent?.get(key)
            if (value != null && value.isJsonPrimitive && !value.isJsonNull) {
                try {
                    return value.asString
                } catch (_: Exception) {
                    // Try the next alias.
                }
            }
        }
        return null
    }

    private fun jsonInt(parent: JsonObject?, vararg keys: String): Int? {
        for (key in keys) {
            val value = parent?.get(key) ?: continue
            try {
                return value.asInt
            } catch (_: Exception) {
                // Try the next alias.
            }
        }
        return null
    }

    private fun jsonBoolean(parent: JsonObject?, key: String): Boolean? = try {
        parent?.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
    } catch (_: Exception) {
        null
    }

    private fun com.google.gson.JsonArray.firstObject(): JsonObject? =
        firstOrNull { it.isJsonObject }?.asJsonObject

    private fun firstJsonString(value: com.google.gson.JsonElement): String? = when {
        value.isJsonArray -> value.asJsonArray.firstOrNull()?.let { element ->
            if (element.isJsonPrimitive) element.asString else null
        }
        value.isJsonPrimitive -> value.asString
        else -> null
    }
}
