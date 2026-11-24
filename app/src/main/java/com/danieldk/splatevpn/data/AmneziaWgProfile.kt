package com.danieldk.splatevpn.data

import java.net.URI
import java.net.InetAddress
import java.util.Base64
import java.util.UUID

internal val ServerNode.isAmneziaWg: Boolean get() = protocol.equals("amneziawg", true)
internal val ServerNode.usesUdpTransport: Boolean get() = isHysteria2 || isAmneziaWg

/** A distinct profile: private keys never occupy UUID, rawLink or the Xray JSON column. */
data class AmneziaWgProfile(
    val privateKey: String,
    val addresses: List<String>,
    val dns: List<String>,
    val mtu: Int,
    val endpoint: String,
    val publicKey: String,
    val presharedKey: String?,
    val allowedIps: List<String>,
    val keepalive: Int,
    val parameters: Map<String, String>
) {
    override fun toString() = "AmneziaWgProfile([redacted])"
    fun validated(): AmneziaWgProfile = AmneziaWg.parse(AmneziaWg.conf(this))
}

object AmneziaWg {
    private val parameterNames = (listOf("Jc", "Jmin", "Jmax", "S1", "S2", "S3", "S4", "H1", "H2", "H3", "H4", "I1", "I2", "I3", "I4", "I5", "HeaderProtectionKey", "ContentPaddingAddition")
        + listOf("RekeyAfterTime", "RekeyTimeout", "RejectAfterTime", "KeepaliveTimeout", "MaxHandshakeAttempts", "RandomTrailers", "DisableCookies"))
        .associateBy { it.lowercase() }
    private fun key(value: String): String {
        require(runCatching { Base64.getDecoder().decode(value).size == 32 }.getOrDefault(false)) { "Invalid AWG key" }
        return value
    }
    private fun numericAddress(value: String): InetAddress {
        if (':' in value) require(value.matches(Regex("[0-9a-fA-F:.]+"))) { "Invalid AWG numeric address" }
        else {
            val parts = value.split('.')
            require(parts.size == 4 && parts.all { it.isNotEmpty() && it.all(Char::isDigit) && it.toIntOrNull() in 0..255 }) { "AWG requires a numeric IP address" }
        }
        return InetAddress.getByName(value)
    }
    private fun ip(value: String): String {
        val a = numericAddress(value)
        require(!a.isAnyLocalAddress && !a.isMulticastAddress) { "Invalid AWG IP address" }
        return a.hostAddress!!.substringBefore('%')
    }
    private fun cidr(value: String): String {
        require(value.count { it == '/' } <= 1) { "Invalid AWG CIDR prefix" }
        // Official AWG/WireGuard configs permit a bare address, meaning one host.
        // Explicit malformed prefixes are rejected rather than widened or replaced.
        val a = numericAddress(value.substringBefore('/'))
        val maxPrefix = if (a.address.size == 4) 32 else 128
        val prefix = if ('/' in value) value.substringAfter('/').toIntOrNull() else maxPrefix
        require(prefix != null && prefix in 0..maxPrefix) { "Invalid AWG CIDR prefix" }
        return "${a.hostAddress!!.substringBefore('%')}/$prefix"
    }
    private fun validateSignature(value: String) {
        require(value.length <= 4096) { "AWG signature too large" }
        val tags = Regex("<([^<>]+)>").findAll(value).toList()
        require(tags.isNotEmpty() && tags.size <= 64 && Regex("<[^<>]+>").replace(value, "").isBlank()) { "Invalid AWG signature" }
        var length = 0
        for (tag in tags) {
            val parts = tag.groupValues[1].trim().split(Regex("\\s+"))
            val argument = parts.getOrNull(1).orEmpty()
            require(parts.size <= 2) { "Invalid AWG signature tag" }
            length += when (parts[0]) {
                "b" -> {
                    val hex = argument.removePrefix("0x")
                    require(hex.isNotEmpty() && hex.length % 2 == 0 && hex.matches(Regex("[0-9a-fA-F]+"))) { "Invalid AWG signature bytes" }
                    hex.length / 2
                }
                "r", "rc", "rd" -> {
                    val size = argument.toIntOrNull(); require(size != null && size in 0..4096) { "Invalid AWG signature length" }; size
                }
                "dz" -> { val size = argument.toIntOrNull(); require(size != null && size in 1..8) { "Invalid AWG signature size field" }; size }
                "t" -> 4
                "d", "ds" -> 0
                else -> error("Unsupported AWG signature tag")
            }
            require(length <= 4096) { "AWG signature packet exceeds supported size" }
        }
    }
    fun endpoint(value: String): Pair<String, Int> {
        val uri = URI("udp://$value")
        require(uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.path.isNullOrEmpty() && !uri.host.isNullOrBlank() && uri.port in 1..65535) { "Invalid AWG endpoint" }
        return uri.host.removeSurrounding("[", "]") to uri.port
    }
    private fun clean(text: String) = text.trim().removePrefix("\uFEFF").trimStart()
    private fun isShare(text: String) = text.startsWith("awg://", true) || text.startsWith("vpn://", true)
    fun looksLike(text: String) = clean(text).let { it.startsWith("[Interface]", true) || isShare(it) }
    fun parse(input: String): AmneziaWgProfile {
        require(input.length <= 64 * 1024) { "AWG profile exceeds 64 KiB" }
        val normalized = clean(input)
        val text = if (isShare(normalized)) {
            val payload = normalized.substringAfter("://").substringBefore('#')
            val bytes = try { Base64.getUrlDecoder().decode(payload) } catch (_: IllegalArgumentException) { Base64.getDecoder().decode(payload) }
            clean(bytes.toString(Charsets.UTF_8))
        } else normalized
        require(text.length <= 64 * 1024) { "AWG profile exceeds 64 KiB" }
        var section = ""; var peers = 0
        val device = linkedMapOf<String, String>(); val peer = linkedMapOf<String, String>()
        for (raw in text.lineSequence()) {
            val line = raw.substringBefore('#').trim(); if (line.isEmpty()) continue
            if (line.startsWith('[')) {
                require(line.lowercase() in setOf("[interface]", "[peer]")) { "Unsupported AWG section" }
                section = line.lowercase(); if (section == "[peer]") { peers++; require(peers == 1) { "Only single-peer AWG client profiles are supported" } }; continue
            }
            require(section.isNotEmpty() && '=' in line) { "Malformed AWG profile" }
            val name = line.substringBefore('=').trim().lowercase(); val value = line.substringAfter('=').trim()
            require(value.isNotEmpty() && '\u0000' !in value && value.length <= 8192) { "Invalid AWG parameter" }
            val target = if (section == "[interface]") device else peer
            require(!target.containsKey(name)) { "Duplicate AWG parameter: $name" }; target[name] = value
        }
        require(peers == 1) { "AWG peer is missing" }
        val deviceFields = setOf("privatekey", "address", "dns", "mtu", "listenport") + parameterNames.keys
        require(device.keys.all { it in deviceFields }) { "Unsupported AWG interface parameter; scripts and routing overrides are not allowed" }
        require(peer.keys.all { it in setOf("publickey", "presharedkey", "allowedips", "endpoint", "persistentkeepalive") }) { "Unsupported AWG peer parameter" }
        fun list(value: String?) = value.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val addresses = list(device["address"]).map(::cidr); require(addresses.isNotEmpty() && addresses.size <= 16) { "AWG address is missing" }
        val dns = list(device["dns"]).map(::ip); require(dns.size <= 8) { "Too many AWG DNS servers" }
        val mtu = device["mtu"]?.toIntOrNull() ?: if (device.containsKey("mtu")) error("Invalid AWG MTU") else 1280
        require(mtu in 576..9000) { "Invalid AWG MTU" }
        // A client always uses an ephemeral listen port, preventing probe/main port collisions.
        device["listenport"]?.let { require(it.toIntOrNull() in 0..65535) { "Invalid AWG listen port" } }
        val keepalive = peer["persistentkeepalive"]?.toIntOrNull() ?: if (peer.containsKey("persistentkeepalive")) error("Invalid AWG keepalive") else 0
        require(keepalive in 0..65535) { "Invalid AWG keepalive" }
        val endpoint = requireNotNull(peer["endpoint"]) { "AWG endpoint is missing" }; endpoint(endpoint)
        val allowed = list(peer["allowedips"]).map(::cidr); require(allowed.isNotEmpty() && allowed.size <= 256) { "AWG AllowedIPs is missing" }
        val parameters = device.filterKeys { it in parameterNames }.mapKeys { parameterNames.getValue(it.key) }.mapValues { (name, value) ->
            if (name in setOf("RandomTrailers", "DisableCookies")) when (value.lowercase()) {
                "true", "on", "yes", "1" -> "true"
                "false", "off", "no", "0" -> "false"
                else -> error("Invalid AWG boolean")
            } else value
        }
        parameters.forEach { (name, value) ->
            when {
                name == "HeaderProtectionKey" -> key(value)
                name in setOf("RandomTrailers", "DisableCookies") -> require(value in setOf("true", "false")) { "Invalid AWG boolean" }
                name.startsWith("I") -> validateSignature(value)
                name in setOf("Jc", "Jmin", "Jmax", "S1", "S2", "S3", "S4") -> {
                    val limit = if (name == "Jc") 128 else 4096
                    require(value.toIntOrNull() in 0..limit) { "AWG $name exceeds supported bounds" }
                }
                else -> {
                    require(value.matches(Regex("[0-9]+(?:-[0-9]+)?"))) { "Invalid AWG numeric parameter: $name" }
                    val range = value.split('-').map { it.toLongOrNull() }
                    require(range.all { it != null && it in 0..4294967295L } && (range.size == 1 || range[0]!! <= range[1]!!)) { "Invalid AWG range: $name" }
                }
            }
        }
        val jmin = parameters["Jmin"]?.toInt() ?: 0; val jmax = parameters["Jmax"]?.toInt() ?: jmin
        require(jmax >= jmin) { "AWG Jmax must be at least Jmin" }
        return AmneziaWgProfile(key(requireNotNull(device["privatekey"]) { "AWG private key is missing" }), addresses, dns, mtu, endpoint,
            key(requireNotNull(peer["publickey"]) { "AWG public key is missing" }), peer["presharedkey"]?.let(::key), allowed, keepalive, parameters)
    }
    fun conf(p: AmneziaWgProfile): String = buildString {
        appendLine("[Interface]"); appendLine("PrivateKey = ${p.privateKey}"); appendLine("Address = ${p.addresses.joinToString(", ")}")
        if (p.dns.isNotEmpty()) appendLine("DNS = ${p.dns.joinToString(", ")}")
        appendLine("MTU = ${p.mtu}"); p.parameters.toSortedMap().forEach { (k, v) -> appendLine("$k = $v") }
        appendLine("[Peer]"); appendLine("PublicKey = ${p.publicKey}"); p.presharedKey?.let { appendLine("PresharedKey = $it") }
        appendLine("Endpoint = ${p.endpoint}"); appendLine("AllowedIPs = ${p.allowedIps.joinToString(", ")}"); appendLine("PersistentKeepalive = ${p.keepalive}")
    }
    fun share(profile: AmneziaWgProfile) = "awg://" + Base64.getUrlEncoder().withoutPadding().encodeToString(conf(profile).toByteArray())
    fun node(input: String, subId: String = "", subName: String = ""): ServerNode {
        val p = parse(input); val (host, port) = endpoint(p.endpoint)
        val normalized = clean(input)
        val name = if (isShare(normalized) && '#' in normalized) java.net.URLDecoder.decode(normalized.substringAfter('#'), "UTF-8").take(256) else "AmneziaWG · $host"
        return ServerNode(id = UUID.randomUUID().toString(), name = name, protocol = "amneziawg", address = host, port = port,
            network = "udp", subId = subId, subName = subName, amneziaWg = p)
    }
    private fun hex(key: String) = Base64.getDecoder().decode(key).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun uapi(p: AmneziaWgProfile, resolvedEndpoint: String): String = buildString {
        appendLine("private_key=${hex(p.privateKey)}")
        p.parameters.forEach { (k, v) ->
            val name = k.replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase()
            appendLine("$name=${if (k == "HeaderProtectionKey") hex(v) else v}")
        }
        appendLine("replace_peers=true"); appendLine("public_key=${hex(p.publicKey)}")
        p.presharedKey?.let { appendLine("preshared_key=${hex(it)}") }; appendLine("endpoint=$resolvedEndpoint")
        appendLine("persistent_keepalive_interval=${p.keepalive}"); appendLine("replace_allowed_ips=true")
        p.allowedIps.forEach { appendLine("allowed_ip=$it") }
    }
}
