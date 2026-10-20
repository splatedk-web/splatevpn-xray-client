package com.danieldk.splatevpn.data

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.net.URLDecoder
import java.util.Base64
import java.util.UUID

object LinkParser {

    private val gson = Gson()

    /** Returns the payload inside an unencrypted Happ/INCY import deep link. */
    fun unwrapImportLink(raw: String): String? {
        val trimmed = raw.trim()
        val lower = trimmed.lowercase()
        val payload = when {
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
                lower.startsWith("happ://crypto") ||
                Regex("^happ://crypt[2-5](/|$)").containsMatchIn(lower) ||
                lower.startsWith("incy://import/") ||
                lower.startsWith("incy://add/") ||
                lower.startsWith("incy://crypt1/")
    }

    fun isEncryptedSubscriptionLink(raw: String): Boolean {
        val lower = raw.trim().lowercase()
        return lower.startsWith("happ://crypto") ||
                Regex("^happ://crypt[2-5](/|$)").containsMatchIn(lower) ||
                lower.startsWith("incy://crypt1/")
    }

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
                            URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
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
                    port = if (colonIdx >= 0) hostPort.substring(colonIdx + 1).toIntOrNull() ?: -1 else -1
                } else {
                    host = hostPort
                    port = -1
                }
            } else {
                val colonIdx = hostPort.lastIndexOf(':')
                if (colonIdx >= 0) {
                    host = hostPort.substring(0, colonIdx)
                    port = hostPort.substring(colonIdx + 1).toIntOrNull() ?: -1
                } else {
                    host = hostPort
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

    private fun normalizeNetwork(net: String?): String {
        return when (net?.lowercase()?.trim()) {
            "splithttp", "xhttp" -> "xhttp"
            "websocket", "ws" -> "ws"
            "h2", "http" -> "h2"
            "grpc", "gun" -> "grpc"
            "kcp", "mkcp" -> "kcp"
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

    private fun parseVless(raw: String, subId: String, subName: String): ServerNode {
        val uri = ParsedUri(raw)
        val uuid = uri.userInfo ?: ""
        val host = uri.host ?: ""
        val port = if (uri.port > 0) uri.port else 443
        val rawName = uri.fragment?.let { decodeUrlSafe(it) } ?: "$host:$port"

        val flow = uri.getQueryParameter("flow") ?: ""
        val encryption = uri.getQueryParameter("encryption") ?: "none"
        val rawNet = uri.getQueryParameter("type")
            ?: uri.getQueryParameter("net")
            ?: uri.getQueryParameter("transport")
            ?: uri.getQueryParameter("network")
        val network = normalizeNetwork(rawNet)

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
        val finalPath = when {
            !pathParam.isNullOrEmpty() -> if (pathParam.startsWith("/")) pathParam else "/$pathParam"
            !rawUriPath.isNullOrEmpty() -> if (rawUriPath.startsWith("/")) rawUriPath else "/$rawUriPath"
            else -> "/"
        }

        val httpHost = uri.getQueryParameter("host") ?: uri.getQueryParameter("obfsParam") ?: ""
        var mode = uri.getQueryParameter("mode") ?: "auto"
        var padding = uri.getQueryParameter("x_padding_bytes") ?: "100-1000"

        val extra = uri.getQueryParameter("extra")
        if (!extra.isNullOrEmpty()) {
            try {
                val decodedExtra = URLDecoder.decode(extra, "UTF-8")
                val json = gson.fromJson(decodedExtra, JsonObject::class.java)
                if (json.has("mode")) {
                    mode = json.get("mode").asString
                }
                if (json.has("xPaddingBytes")) padding = json.get("xPaddingBytes").asString
            } catch (e: Exception) {
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

    private fun parseTrojan(raw: String, subId: String, subName: String): ServerNode {
        val uri = ParsedUri(raw)
        val password = uri.userInfo ?: ""
        val host = uri.host ?: ""
        val port = if (uri.port > 0) uri.port else 443
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
        val finalPath = when {
            !pathParam.isNullOrEmpty() -> if (pathParam.startsWith("/")) pathParam else "/$pathParam"
            !rawUriPath.isNullOrEmpty() -> if (rawUriPath.startsWith("/")) rawUriPath else "/$rawUriPath"
            else -> "/"
        }

        val httpHost = uri.getQueryParameter("host") ?: ""

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
            rawLink = raw,
            subId = subId,
            subName = subName
        )
    }

    fun parseBatch(rawText: String, subId: String = "", subName: String = ""): List<ServerNode> {
        return parseSubscription(rawText, subId, subName)
    }

    fun parseSubscription(rawContent: String, subId: String = "", subName: String = ""): List<ServerNode> {
        val decoded = decodeSubscriptionContent(rawContent)
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
        val linkPattern = Regex("""(?i)(?:vless|vmess|trojan|ss|socks5?|wireguard)://[^\s"'<>]+""")
        val matches = linkPattern.findAll(decoded).map { it.value.trim() }.toList()

        if (matches.isNotEmpty()) {
            matches.forEach { link ->
                parse(link, subId, subName)?.let { nodes.add(it) }
            }
        } else {
            decoded.lines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotBlank() && !trimmed.startsWith("#")) {
                    parse(trimmed, subId, subName)?.let { nodes.add(it) }
                }
            }
        }

        return nodes
    }

    fun decodeSubscriptionContent(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""

        // If raw content already contains protocol schemes or JSON/YAML markers, it is plain text.
        if (trimmed.contains("://") ||
            trimmed.contains("proxies:") ||
            trimmed.contains("proxy-groups:") ||
            trimmed.startsWith("{") || trimmed.startsWith("[")
        ) {
            return trimmed
        }

        // Standardize Base64: sanitize newlines/spaces, map URL-safe (-/_) to standard (+//), pad with =
        val noWs = trimmed.replace("\r", "").replace("\n", "").replace(" ", "").trim()
        val standardized = StringBuilder(noWs.replace('-', '+').replace('_', '/')).apply {
            while (length % 4 != 0) {
                append('=')
            }
        }.toString()

        try {
            val decodedBytes = try {
                android.util.Base64.decode(standardized, android.util.Base64.DEFAULT)
            } catch (e: Throwable) {
                try {
                    android.util.Base64.decode(noWs, android.util.Base64.URL_SAFE)
                } catch (ex: Throwable) {
                    Base64.getDecoder().decode(standardized)
                }
            }
            val decoded = String(decodedBytes, Charsets.UTF_8).trim()
            if (decoded.contains("://") ||
                decoded.contains("proxies:") ||
                decoded.startsWith("{") || decoded.startsWith("[")
            ) {
                return decoded
            }
            if (decoded.isNotEmpty() && !decoded.contains('\u0000')) {
                return decoded
            }
        } catch (e: Throwable) {
            // Keep original
        }

        return trimmed
    }

    private fun parseVmess(raw: String, subId: String, subName: String): ServerNode? {
        val fragmentName = if (raw.contains("#")) {
            try {
                URLDecoder.decode(raw.substringAfter("#"), "UTF-8")
            } catch (e: Exception) {
                null
            }
        } else null

        val base64Content = raw.substringAfter("vmess://")
            .substringBefore("#")
            .substringBefore("?")
            .trim()
        val decoded = decodeBase64Safe(base64Content)
        val json = gson.fromJson(decoded, JsonObject::class.java)

        val host = json.get("add")?.asString ?: return null
        val port = json.get("port")?.asInt ?: 443
        val id = json.get("id")?.asString ?: ""
        val ps = json.get("ps")?.asString?.takeIf { it.isNotBlank() }
            ?: fragmentName
            ?: "VMess-$host"
        val rawNet = json.get("net")?.asString ?: json.get("type")?.asString ?: "tcp"
        val network = normalizeNetwork(rawNet)
        val rawTls = json.get("tls")?.asString ?: "none"
        val security = if (rawTls.equals("tls", ignoreCase = true)) "tls" else "none"
        val httpHost = json.get("host")?.asString ?: ""
        val sni = json.get("sni")?.asString?.takeIf { it.isNotEmpty() }
            ?: httpHost.takeIf { it.isNotEmpty() }
            ?: host
        val rawPath = json.get("path")?.asString ?: "/"
        val path = if (rawPath.startsWith("/")) rawPath else "/$rawPath"
        val scy = json.get("scy")?.asString ?: "auto"
        val fp = json.get("fp")?.asString ?: "chrome"

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
            rawLink = raw,
            subId = subId,
            subName = subName
        )
    }

    private fun parseShadowsocks(raw: String, subId: String, subName: String): ServerNode? {
        return try {
            val withoutScheme = raw.substringAfter("ss://")
            val fragment = if (withoutScheme.contains("#")) {
                decodeUrlSafe(withoutScheme.substringAfter("#"))
            } else null

            val mainPart = withoutScheme.substringBefore("#")
            val connectionPart = mainPart.substringBefore("?")

            var method = "aes-256-gcm"
            var password = ""
            var host = ""
            var port = 8388

            if (connectionPart.contains("@")) {
                val userInfoEncoded = connectionPart.substringBeforeLast("@")
                val hostPort = connectionPart.substringAfterLast("@")

                val decodedUserInfo = if (userInfoEncoded.contains(":")) {
                    userInfoEncoded
                } else {
                    decodeBase64Safe(userInfoEncoded)
                }
                if (decodedUserInfo.contains(":")) {
                    method = decodedUserInfo.substringBefore(":")
                    password = decodedUserInfo.substringAfter(":")
                }

                if (hostPort.contains(":")) {
                    host = hostPort.substringBefore(":")
                    port = hostPort.substringAfter(":").toIntOrNull() ?: 8388
                } else {
                    host = hostPort
                }
            } else {
                val decoded = decodeBase64Safe(connectionPart)
                if (decoded.contains("@")) {
                    val userInfo = decoded.substringBeforeLast("@")
                    val hostPort = decoded.substringAfterLast("@")

                    if (userInfo.contains(":")) {
                        method = userInfo.substringBefore(":")
                        password = userInfo.substringAfter(":")
                    }

                    if (hostPort.contains(":")) {
                        host = hostPort.substringBefore(":")
                        port = hostPort.substringAfter(":").toIntOrNull() ?: 8388
                    } else {
                        host = hostPort
                    }
                }
            }

            if (host.isEmpty()) return null

            val rawName = fragment ?: "Shadowsocks-$host:$port"

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
        // If raw link is reasonably sized and contains scheme, clean any bloated extra param and return
        if (node.rawLink.isNotBlank() && node.rawLink.contains("://")) {
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

        return when (node.protocol.lowercase()) {
            "vless" -> {
                val params = mutableListOf<String>()
                if (node.security.isNotBlank()) params.add("security=${node.security}")
                if (node.sni.isNotBlank()) params.add("sni=${node.sni}")
                if (node.fingerprint.isNotBlank() && node.fingerprint != "chrome") params.add("fp=${node.fingerprint}")
                if (node.network.isNotBlank() && node.network != "tcp") params.add("type=${node.network}")
                if (node.flow.isNotBlank()) params.add("flow=${node.flow}")
                if (node.publicKey.isNotBlank()) params.add("pbk=${node.publicKey}")
                if (node.shortId.isNotBlank()) params.add("sid=${node.shortId}")
                if (node.spiderX.isNotBlank()) params.add("spx=${node.spiderX}")
                if (node.path.isNotBlank() && node.path != "/") {
                    val encPath = java.net.URLEncoder.encode(node.path, "UTF-8")
                    params.add("path=$encPath")
                }
                if (node.host.isNotBlank()) params.add("host=${node.host}")
                if (node.mode.isNotBlank() && node.mode != "auto") params.add("mode=${node.mode}")
                val q = if (params.isNotEmpty()) "?" + params.joinToString("&") else ""
                "vless://${node.uuid}@${node.address}:${node.port}$q#$nameEncoded"
            }
            "trojan" -> {
                val params = mutableListOf<String>()
                if (node.security.isNotBlank()) params.add("security=${node.security}")
                if (node.sni.isNotBlank()) params.add("sni=${node.sni}")
                if (node.network.isNotBlank() && node.network != "tcp") params.add("type=${node.network}")
                if (node.publicKey.isNotBlank()) params.add("pbk=${node.publicKey}")
                if (node.shortId.isNotBlank()) params.add("sid=${node.shortId}")
                if (node.path.isNotBlank() && node.path != "/") {
                    val encPath = java.net.URLEncoder.encode(node.path, "UTF-8")
                    params.add("path=$encPath")
                }
                if (node.host.isNotBlank()) params.add("host=${node.host}")
                val q = if (params.isNotEmpty()) "?" + params.joinToString("&") else ""
                "trojan://${node.uuid}@${node.address}:${node.port}$q#$nameEncoded"
            }
            "shadowsocks", "ss" -> {
                val userPass = "${node.encryption}:${node.uuid}"
                val b64 = java.util.Base64.getEncoder().encodeToString(userPass.toByteArray(Charsets.UTF_8))
                "ss://$b64@${node.address}:${node.port}#$nameEncoded"
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
                val auth = if (node.uuid.isNotBlank()) {
                    if (node.encryption.isNotBlank()) "${node.uuid}:${node.encryption}@" else "${node.uuid}@"
                } else ""
                "socks5://${auth}${node.address}:${node.port}#$nameEncoded"
            }
            "tuic" -> {
                val params = mutableListOf<String>()
                if (node.sni.isNotBlank()) params.add("sni=${node.sni}")
                if (node.flow.isNotBlank()) params.add("congestion_control=${node.flow}")
                val q = if (params.isNotEmpty()) "?" + params.joinToString("&") else ""
                "tuic://${node.uuid}@${node.address}:${node.port}$q#$nameEncoded"
            }
            "hysteria2", "hy2" -> {
                val params = mutableListOf<String>()
                if (node.sni.isNotBlank()) params.add("sni=${node.sni}")
                val q = if (params.isNotEmpty()) "?" + params.joinToString("&") else ""
                "hysteria2://${node.uuid}@${node.address}:${node.port}$q#$nameEncoded"
            }
            "wireguard", "wg" -> {
                "wireguard://${node.publicKey}@${node.address}:${node.port}#$nameEncoded"
            }
            else -> cleanLinkForSharing(node.rawLink)
        }
    }

    private fun parseSocks(raw: String, subId: String, subName: String): ServerNode? {
        return try {
            val uri = ParsedUri(raw)
            val host = uri.host ?: return null
            val port = if (uri.port > 0) uri.port else 1080
            val userInfo = uri.userInfo ?: ""
            val rawName = uri.fragment?.let { decodeUrlSafe(it) } ?: "SOCKS5-$host:$port"

            val username = if (userInfo.contains(":")) userInfo.substringBefore(":") else userInfo
            val password = if (userInfo.contains(":")) userInfo.substringAfter(":") else ""

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

    private fun parseClashYaml(yamlText: String, subId: String, subName: String): List<ServerNode> {
        val nodes = mutableListOf<ServerNode>()
        val lines = yamlText.lines()
        var inProxies = false
        val currentProps = mutableMapOf<String, String>()
        var currentSection = ""

        fun finishBlock() {
            if (currentProps.isNotEmpty() && currentProps.containsKey("server")) {
                parseClashProxyMap(currentProps, subId, subName)?.let { nodes.add(it) }
            }
            currentProps.clear()
            currentSection = ""
        }

        for (line in lines) {
            val stripped = line.trim()
            if (stripped == "proxies:" || stripped.startsWith("proxies:")) {
                inProxies = true
                continue
            } else if (inProxies && line.isNotEmpty() && !line.startsWith(" ") && !line.startsWith("\t") && !stripped.startsWith("-")) {
                inProxies = false
                finishBlock()
                break
            }

            if (inProxies) {
                var working = stripped
                if (working.startsWith("- ")) {
                    finishBlock()
                    working = working.substring(2).trim()
                }

                if (working.contains(":")) {
                    val colonIdx = working.indexOf(':')
                    val key = working.substring(0, colonIdx).trim().lowercase()
                    val value = working.substring(colonIdx + 1).trim().trim('"', '\'')
                    if (value.isEmpty()) {
                        currentSection = key
                    } else {
                        val fullKey = if (currentSection.isNotEmpty()) "$currentSection.$key" else key
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
        val server = props["server"] ?: return null
        val port = props["port"]?.toIntOrNull() ?: 443
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
                val path = props["ws-opts.path"] ?: props["grpc-opts.grpc-service-name"] ?: "/"
                val host = props["ws-opts.headers.host"] ?: props["headers.host"] ?: ""

                ServerNode(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    protocol = "vless",
                    address = server,
                    port = port,
                    uuid = uuid,
                    flow = flow,
                    encryption = "none",
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
                val path = props["ws-opts.path"] ?: "/"
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
            "ss" -> {
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
                val path = props["ws-opts.path"] ?: "/"
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
                    path = path,
                    host = host,
                    rawLink = "",
                    subId = subId,
                    subName = subName
                )
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
                    fullConfigJson = if (fullConfig) profile.toString() else ""
                )
                if (node != null) nodes.add(node)
            }
        }
        return nodes
    }

    private fun firstSupportedXrayOutbound(outbounds: com.google.gson.JsonArray?): com.google.gson.JsonElement? {
        if (outbounds == null) return null
        return outbounds.firstOrNull { element ->
            if (!element.isJsonObject) return@firstOrNull false
            jsonString(element.asJsonObject, "protocol", "type")?.lowercase() in
                    setOf("vless", "vmess", "trojan", "shadowsocks", "ss", "socks", "socks5")
        }
    }

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
            else -> rawType
        }
        if (protocol !in setOf("vless", "vmess", "trojan", "shadowsocks", "socks")) return null

        val settings = jsonObject(outbound, "settings")
        val endpoint = jsonArray(settings, "vnext")?.firstObject()
            ?: jsonArray(settings, "servers")?.firstObject()
            ?: outbound
        val user = jsonArray(endpoint, "users")?.firstObject()
            ?: jsonArray(settings, "servers")?.firstObject()
            ?: outbound
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
        val uuidOrPassword = if (protocol == "trojan" || protocol == "shadowsocks" || protocol == "socks") password else userId
        val socksUsername = jsonString(user, "user", "username") ?: jsonString(outbound, "username") ?: ""
        val encryption = when (protocol) {
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
