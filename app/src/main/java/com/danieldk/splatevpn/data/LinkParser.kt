package com.danieldk.splatevpn.data

import android.net.Uri
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.net.URLDecoder
import java.util.UUID

object LinkParser {

    private val gson = Gson()

    fun parse(rawLink: String, subId: String = "", subName: String = ""): ServerNode? {
        val trimmed = rawLink.trim()
        return try {
            when {
                trimmed.startsWith("vless://", ignoreCase = true) -> parseVless(trimmed, subId, subName)
                trimmed.startsWith("trojan://", ignoreCase = true) -> parseTrojan(trimmed, subId, subName)
                trimmed.startsWith("vmess://", ignoreCase = true) -> parseVmess(trimmed, subId, subName)
                trimmed.startsWith("ss://", ignoreCase = true) -> parseShadowsocks(trimmed, subId, subName)
                else -> null
            }
        } catch (e: Exception) {
            e.printStackTrace()
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
        return String(Base64.decode(base64, Base64.DEFAULT or Base64.NO_WRAP or Base64.URL_SAFE), Charsets.UTF_8)
    }

    private fun parseVless(raw: String, subId: String, subName: String): ServerNode {
        val uri = Uri.parse(raw)
        val uuid = uri.userInfo ?: ""
        val host = uri.host ?: ""
        val port = if (uri.port > 0) uri.port else 443
        val rawName = uri.fragment?.let { URLDecoder.decode(it, "UTF-8") } ?: "$host:$port"

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
        val path = uri.getQueryParameter("path") ?: uri.getQueryParameter("serviceName") ?: "/"
        val httpHost = uri.getQueryParameter("host") ?: uri.getQueryParameter("obfsParam") ?: ""
        var mode = uri.getQueryParameter("mode") ?: "auto"
        var padding = uri.getQueryParameter("x_padding_bytes") ?: "100-1000"

        val extra = uri.getQueryParameter("extra")
        if (!extra.isNullOrEmpty()) {
            try {
                val decodedExtra = URLDecoder.decode(extra, "UTF-8")
                val json = gson.fromJson(decodedExtra, JsonObject::class.java)
                if (json.has("mode")) mode = json.get("mode").asString
                if (json.has("xPaddingBytes")) padding = json.get("xPaddingBytes").asString
            } catch (e: Exception) {
                // ignore extra parse errors
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
            path = path,
            host = httpHost,
            mode = mode,
            padding = padding,
            rawLink = raw,
            subId = subId,
            subName = subName
        )
    }

    private fun parseTrojan(raw: String, subId: String, subName: String): ServerNode {
        val uri = Uri.parse(raw)
        val password = uri.userInfo ?: ""
        val host = uri.host ?: ""
        val port = if (uri.port > 0) uri.port else 443
        val rawName = uri.fragment?.let { URLDecoder.decode(it, "UTF-8") } ?: "Trojan-$host"

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
        val path = uri.getQueryParameter("path") ?: uri.getQueryParameter("serviceName") ?: "/"
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
            path = path,
            host = httpHost,
            rawLink = raw,
            subId = subId,
            subName = subName
        )
    }

    private fun parseVmess(raw: String, subId: String, subName: String): ServerNode? {
        val base64Content = raw.substringAfter("vmess://").trim()
        val decoded = decodeBase64Safe(base64Content)
        val json = gson.fromJson(decoded, JsonObject::class.java)

        val host = json.get("add")?.asString ?: return null
        val port = json.get("port")?.asInt ?: 443
        val id = json.get("id")?.asString ?: ""
        val ps = json.get("ps")?.asString ?: "VMess-$host"
        val rawNet = json.get("net")?.asString ?: json.get("type")?.asString ?: "tcp"
        val network = normalizeNetwork(rawNet)
        val rawTls = json.get("tls")?.asString ?: "none"
        val security = if (rawTls.equals("tls", ignoreCase = true)) "tls" else "none"
        val httpHost = json.get("host")?.asString ?: ""
        val sni = json.get("sni")?.asString?.takeIf { it.isNotEmpty() }
            ?: httpHost.takeIf { it.isNotEmpty() }
            ?: host
        val path = json.get("path")?.asString ?: "/"
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
        // Shadowsocks SIP002 URIs:
        // Format A: ss://BASE64(method:password@host:port)[?plugin=...]#tag
        // Format B: ss://BASE64(method:password)@host:port[?plugin=...]#tag
        return try {
            val withoutScheme = raw.substringAfter("ss://")
            val fragment = if (withoutScheme.contains("#")) {
                URLDecoder.decode(withoutScheme.substringAfter("#"), "UTF-8")
            } else null

            val mainPart = withoutScheme.substringBefore("#")
            val connectionPart = mainPart.substringBefore("?")

            var method = "aes-256-gcm"
            var password = ""
            var host = ""
            var port = 8388

            if (connectionPart.contains("@")) {
                // Format B: BASE64(method:password)@host:port
                val userInfoEncoded = connectionPart.substringBeforeLast("@")
                val hostPort = connectionPart.substringAfterLast("@")

                val decodedUserInfo = decodeBase64Safe(userInfoEncoded)
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
                // Format A: BASE64(method:password@host:port)
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
}
