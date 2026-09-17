package com.danieldk.splatevpn.vpn

import com.danieldk.splatevpn.data.ServerNode
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class XrayConfigGenerator @Inject constructor() {
    
    private val gson = Gson()

    fun generateConfig(
        node: ServerNode?,
        mode: String = "smart_ru",
        enableSniffing: Boolean = true,
        routeOnlySniffing: Boolean = false,
        blockQuic: Boolean = true,
        blockIpv6: Boolean = true,
        dnsProvider: String = "cloudflare",
        isFakeDns: Boolean = true,
        xhttpModeOverride: String = "auto"
    ): String {
        val config = JsonObject()

        // 1. Logging
        val log = JsonObject().apply {
            addProperty("loglevel", "info")  // info нужен чтобы видеть ошибки outbound соединений
        }
        config.add("log", log)

        // 2. DNS (Split-DNS: .ru → Yandex, rest → selected provider DoH)
        // Using +local for main DoH: DNS goes directly (not through proxy) to break the
        // circular dependency where dead proxy → dead DNS → nothing resolves.
        // With FakeDNS + sniffing enabled, real DNS lookups are rare (FakeDNS answers instantly).
        val (dohUrl, fallbackIp) = when (dnsProvider.lowercase()) {
            "google" -> "https+local://8.8.8.8/dns-query" to "8.8.8.8"
            "quad9" -> "https+local://9.9.9.9/dns-query" to "9.9.9.9"
            "adguard" -> "https+local://94.140.14.14/dns-query" to "94.140.14.14"
            else -> "https+local://1.1.1.1/dns-query" to "1.1.1.1" // cloudflare
        }

        val dns = JsonObject().apply {
            val servers = JsonArray().apply {
                // 1. FakeDNS (if enabled) — default handler for foreign & proxied domains (<1ms instant answer)
                if (isFakeDns) add("fakedns")

                // 2. Russian domains → Yandex DNS directly (correct geo-answers for .ru CDNs)
                // Prioritized for any domain matching the Russian domains filter!
                val ruDns = JsonObject().apply {
                    addProperty("address", "https+local://77.88.8.8/dns-query")
                    val domains = JsonArray().apply {
                        add("geosite:category-ru")
                        add("geosite:tld-ru")
                        add("geosite:yandex")
                        add("geosite:vk")
                        add("geosite:mailru")
                        add("domain:ru")
                        add("domain:su")
                        add("domain:xn--p1ai")
                    }
                    add("domains", domains)
                }
                add(ruDns)

                // 3. Main DoH server for everything else (+local: direct, not through proxy)
                // This ensures DNS works even when proxy outbound is temporarily dead.
                val mainDoh = JsonObject().apply {
                    addProperty("address", dohUrl)
                }
                add(mainDoh)

                // 4. Plain DNS fallback
                add(fallbackIp)
            }
            add("servers", servers)
            addProperty("queryStrategy", "UseIPv4")
        }
        config.add("dns", dns)

        // 2.1 FakeDNS Pool definition (only when enabled)
        if (isFakeDns) {
            val fakedns = JsonArray().apply {
                val pool = JsonObject().apply {
                    addProperty("ipPool", "198.18.0.0/15")
                    addProperty("poolSize", 65535)
                }
                add(pool)
            }
            config.add("fakedns", fakedns)
        }

        // 3. Inbounds: SOCKS5 127.0.0.1:10808 for hev-socks5-tunnel
        val inbounds = JsonArray()
        val socksInbound = JsonObject().apply {
            addProperty("tag", "socks-in")
            addProperty("protocol", "socks")
            addProperty("port", 10808)
            addProperty("listen", "127.0.0.1")
            val settings = JsonObject().apply {
                addProperty("auth", "noauth")
                addProperty("udp", true)
            }
            add("settings", settings)

            if (enableSniffing) {
                val sniffing = JsonObject().apply {
                    addProperty("enabled", true)
                    val destOverride = JsonArray().apply {
                        add("http")
                        add("tls")
                        if (isFakeDns) add("fakedns")
                        if (!blockQuic) add("quic")
                    }
                    add("destOverride", destOverride)
                    addProperty("metadataOnly", false)
                    addProperty("routeOnly", routeOnlySniffing)
                }
                add("sniffing", sniffing)
            }
        }
        inbounds.add(socksInbound)
        config.add("inbounds", inbounds)

        // 4. Outbounds: Proxy, Direct, Dns-out, Block
        val outbounds = JsonArray()
        val proxyOutbound = buildProxyOutbound(node, xhttpModeOverride)
        outbounds.add(proxyOutbound)

        val directOutbound = JsonObject().apply {
            addProperty("tag", "direct")
            addProperty("protocol", "freedom")
            val streamSettings = JsonObject().apply {
                val sockopt = JsonObject().apply {
                    addProperty("domainStrategy", "UseIP")
                }
                add("sockopt", sockopt)
            }
            add("streamSettings", streamSettings)
        }
        outbounds.add(directOutbound)

        val dnsOutbound = JsonObject().apply {
            addProperty("tag", "dns-out")
            addProperty("protocol", "dns")
        }
        outbounds.add(dnsOutbound)

        val blockOutbound = JsonObject().apply {
            addProperty("tag", "block")
            addProperty("protocol", "blackhole")
        }
        outbounds.add(blockOutbound)
        config.add("outbounds", outbounds)

        // 5. Routing Rules (Smart RU vs Global)
        val routing = JsonObject().apply {
            addProperty("domainStrategy", "IPIfNonMatch")
            val rules = JsonArray()

            // 5.1 Route incoming DNS (UDP port 53) to Xray internal DNS
            val dnsRule = JsonObject().apply {
                addProperty("type", "field")
                val inboundArr = JsonArray().apply { add("socks-in") }
                add("inboundTag", inboundArr)
                addProperty("port", "53")
                addProperty("outboundTag", "dns-out")
            }
            rules.add(dnsRule)

            // 5.2 Block IPv6 if enabled to prevent Happy Eyeballs latency & leaks
            if (blockIpv6) {
                val blockIpv6Rule = JsonObject().apply {
                    addProperty("type", "field")
                    val ipArr = JsonArray().apply { add("::/0") }
                    add("ip", ipArr)
                    addProperty("outboundTag", "block")
                }
                rules.add(blockIpv6Rule)
            }

            // 5.3 Block QUIC (UDP 443) globally: MUST precede all domain/IP rules!
            // Chrome and apps use HTTP/3 (QUIC) by default. When UDP 443 is blocked,
            // browsers instantly fall back to TCP (HTTP/2 / HTTP/1.1) with 0ms delay.
            // If QUIC is not blocked here, it matches proxy domain rules, gets routed to
            // proxy (which fails/stalls over XHTTP or gets throttled by TSPU), hanging websites!
            if (blockQuic) {
                val blockQuicRule = JsonObject().apply {
                    addProperty("type", "field")
                    addProperty("network", "udp")
                    addProperty("port", "443")
                    addProperty("outboundTag", "block")
                }
                rules.add(blockQuicRule)
            }

            // 5.4 NTP (UDP 123) direct to prevent time desynchronization
            val ntpRule = JsonObject().apply {
                addProperty("type", "field")
                addProperty("network", "udp")
                addProperty("port", "123")
                addProperty("outboundTag", "direct")
            }
            rules.add(ntpRule)

            val isStreamingTransport = node?.network?.lowercase() in listOf("xhttp", "splithttp", "ws", "grpc", "h2", "http")

            if (mode == "smart_ru") {
                // Russian services and banks direct
                val directRuDomains = JsonObject().apply {
                    addProperty("type", "field")
                    addProperty("outboundTag", "direct")
                    val domainArr = JsonArray().apply {
                        add("geosite:category-ru")
                        add("geosite:tld-ru")
                        add("geosite:yandex")
                        add("geosite:vk")
                        add("geosite:mailru")
                        add("domain:gosuslugi.ru")
                        add("domain:sberbank.ru")
                        add("domain:tinkoff.ru")
                        add("domain:vtb.ru")
                        add("domain:alfabank.ru")
                        add("domain:raiffeisen.ru")
                        add("domain:gazprombank.ru")
                        add("domain:mos.ru")
                        add("domain:nalog.ru")
                        add("domain:ru")
                        add("domain:su")
                        add("domain:xn--p1ai")
                    }
                    add("domain", domainArr)
                }
                rules.add(directRuDomains)

                val directRuIps = JsonObject().apply {
                    addProperty("type", "field")
                    addProperty("outboundTag", "direct")
                    val ipArr = JsonArray().apply {
                        add("geoip:ru")
                        add("geoip:private")
                    }
                    add("ip", ipArr)
                }
                rules.add(directRuIps)

                // Telegram DC IPs & Domains (highest priority for voice messages, calls & media)
                // For streaming transport nodes (XHTTP/WS), route Telegram TCP to proxy and let UDP voice go direct
                val proxyTelegramIps = JsonObject().apply {
                    addProperty("type", "field")
                    addProperty("outboundTag", "proxy")
                    val ipArr = JsonArray().apply {
                        add("geoip:telegram")
                    }
                    add("ip", ipArr)
                    if (isStreamingTransport) {
                        addProperty("network", "tcp")
                    }
                }
                rules.add(proxyTelegramIps)

                val proxyTelegramDomains = JsonObject().apply {
                    addProperty("type", "field")
                    addProperty("outboundTag", "proxy")
                    val domainArr = JsonArray().apply {
                        add("geosite:telegram")
                    }
                    add("domain", domainArr)
                }
                rules.add(proxyTelegramDomains)

                // Blocked / foreign services through proxy
                val proxyTargetDomains = JsonObject().apply {
                    addProperty("type", "field")
                    addProperty("outboundTag", "proxy")
                    val domainArr = JsonArray().apply {
                        add("geosite:youtube")
                        add("geosite:google")
                        add("geosite:discord")
                        add("geosite:instagram")
                        add("geosite:twitter")
                        add("geosite:facebook")
                        add("geosite:openai")
                        add("geosite:notion")
                        add("geosite:spotify")
                        add("geosite:netflix")
                        add("domain:rutracker.org")
                        add("domain:nnmclub.to")
                        add("domain:kinozal.tv")
                    }
                    add("domain", domainArr)
                }
                rules.add(proxyTargetDomains)
            } else {
                // Global mode: direct only private IPs
                val privateRule = JsonObject().apply {
                    addProperty("type", "field")
                    addProperty("outboundTag", "direct")
                    val ipArr = JsonArray().apply { add("geoip:private") }
                    add("ip", ipArr)
                }
                rules.add(privateRule)
            }

            // If using streaming transport (XHTTP/WS), route any remaining non-DNS UDP traffic to direct.
            // Streaming transports handle HTTP/stream multiplexing: handling raw UDP breaks connections
            // or is not supported by reverse proxies/CDNs (e.g. Cloudflare).
            if (isStreamingTransport) {
                val directRemainingUdp = JsonObject().apply {
                    addProperty("type", "field")
                    addProperty("network", "udp")
                    addProperty("outboundTag", "direct")
                }
                rules.add(directRemainingUdp)
            }

            // Default route everything else to proxy (TCP-only for streaming transports, TCP+UDP for other protocols)
            val defaultProxyRule = JsonObject().apply {
                addProperty("type", "field")
                addProperty("network", if (isStreamingTransport) "tcp" else "tcp,udp")
                addProperty("outboundTag", "proxy")
            }
            rules.add(defaultProxyRule)

            add("rules", rules)
        }
        config.add("routing", routing)

        // 6. Policy: connection timeouts to prevent zombie/hanging connections
        // Without these, dead proxy outbound connections accumulate forever (TIME_WAIT/CLOSE_WAIT leak)
        val policy = JsonObject().apply {
            val levels = JsonObject().apply {
                val level0 = JsonObject().apply {
                    addProperty("handshake", 4)        // TLS/VLESS handshake timeout (seconds)
                    addProperty("connIdle", 30)        // Close idle connections after 30s (quicker recovery on network change)
                    addProperty("uplinkOnly", 2)        // Close half-closed (upload only) after 2s
                    addProperty("downlinkOnly", 4)      // Close half-closed (download only) after 4s
                    addProperty("bufferSize", 10240)     // 10KB per-connection buffer
                }
                add("0", level0)
            }
            add("levels", levels)
            val system = JsonObject().apply {
                addProperty("statsOutboundUplink", false)
                addProperty("statsOutboundDownlink", false)
            }
            add("system", system)
        }
        config.add("policy", policy)

        return gson.toJson(config)
    }

    private fun buildProxyOutbound(node: ServerNode?, xhttpModeOverride: String = "auto"): JsonObject {
        val proxy = JsonObject()
        proxy.addProperty("tag", "proxy")

        val protocol = node?.protocol?.lowercase() ?: "vless"
        proxy.addProperty("protocol", protocol)

        val rawNet = node?.network?.lowercase()?.trim() ?: "tcp"
        val network: String = when (rawNet) {
            "splithttp", "xhttp" -> "xhttp"
            "websocket", "ws" -> "ws"
            "h2", "http" -> "h2"
            "grpc" -> "grpc"
            else -> "tcp"
        }

        val rawSec = node?.security?.lowercase()?.trim() ?: "none"
        val security: String = when {
            rawSec == "reality" -> "reality"
            rawSec == "tls" -> "tls"
            rawSec == "none" -> "none"
            !node?.publicKey.isNullOrEmpty() -> "reality"
            node?.port == 443 -> "tls"
            else -> "none"
        }

        val settings = JsonObject()
        when (protocol) {
            "vless" -> {
                val vnext = JsonArray()
                val serverItem = JsonObject().apply {
                    addProperty("address", node?.address ?: "127.0.0.1")
                    addProperty("port", node?.port ?: 443)
                    val users = JsonArray()
                    val user = JsonObject().apply {
                        addProperty("id", node?.uuid ?: "00000000-0000-0000-0000-000000000000")
                        addProperty("encryption", if (node?.encryption.isNullOrEmpty()) "none" else node?.encryption)
                        // xtls-rprx-vision is strictly for TCP network with TLS or Reality
                        if (!node?.flow.isNullOrEmpty()) {
                            if (network == "tcp" && (security == "reality" || security == "tls")) {
                                addProperty("flow", node?.flow)
                            }
                        } else if ((security == "reality" || security == "tls") && network == "tcp") {
                            addProperty("flow", "xtls-rprx-vision")
                        }
                    }
                    users.add(user)
                    add("users", users)
                }
                vnext.add(serverItem)
                settings.add("vnext", vnext)
            }
            "trojan" -> {
                val servers = JsonArray()
                val serverItem = JsonObject().apply {
                    addProperty("address", node?.address ?: "127.0.0.1")
                    addProperty("port", node?.port ?: 443)
                    addProperty("password", node?.uuid ?: "")
                }
                servers.add(serverItem)
                settings.add("servers", servers)
            }
            "vmess" -> {
                val vnext = JsonArray()
                val serverItem = JsonObject().apply {
                    addProperty("address", node?.address ?: "127.0.0.1")
                    addProperty("port", node?.port ?: 443)
                    val users = JsonArray()
                    val user = JsonObject().apply {
                        addProperty("id", node?.uuid ?: "")
                        addProperty("alterId", 0)
                        addProperty("security", if (!node?.encryption.isNullOrEmpty()) node?.encryption else "auto")
                    }
                    users.add(user)
                    add("users", users)
                }
                vnext.add(serverItem)
                settings.add("vnext", vnext)
            }
            "shadowsocks" -> {
                val servers = JsonArray()
                val serverItem = JsonObject().apply {
                    addProperty("address", node?.address ?: "127.0.0.1")
                    addProperty("port", node?.port ?: 8388)
                    addProperty("method", if (!node?.encryption.isNullOrEmpty()) node?.encryption else "aes-256-gcm")
                    addProperty("password", node?.uuid ?: "")
                    addProperty("uot", true)
                }
                servers.add(serverItem)
                settings.add("servers", servers)
            }
            else -> {
                val vnext = JsonArray()
                val serverItem = JsonObject().apply {
                    addProperty("address", node?.address ?: "127.0.0.1")
                    addProperty("port", node?.port ?: 443)
                    val users = JsonArray()
                    val user = JsonObject().apply {
                        addProperty("id", node?.uuid ?: "")
                    }
                    users.add(user)
                    add("users", users)
                }
                vnext.add(serverItem)
                settings.add("vnext", vnext)
            }
        }
        proxy.add("settings", settings)

        // StreamSettings
        val streamSettings = JsonObject()
        streamSettings.addProperty("network", network)
        streamSettings.addProperty("security", security)

        if (security == "reality") {
            val reality = JsonObject().apply {
                addProperty("show", false)
                addProperty("fingerprint", if (node?.fingerprint.isNullOrEmpty()) "chrome" else node?.fingerprint)
                val sniVal = if (!node?.sni.isNullOrEmpty()) node?.sni else (node?.address ?: "")
                addProperty("serverName", sniVal)
                addProperty("publicKey", node?.publicKey ?: "")
                addProperty("shortId", node?.shortId ?: "")
                addProperty("spiderX", node?.spiderX ?: "")
            }
            streamSettings.add("realitySettings", reality)
        } else if (security == "tls") {
            val tls = JsonObject().apply {
                val serverName = if (!node?.sni.isNullOrEmpty()) node?.sni else if (!node?.host.isNullOrEmpty()) node?.host else (node?.address ?: "")
                addProperty("serverName", serverName)
                addProperty("fingerprint", if (node?.fingerprint.isNullOrEmpty()) "chrome" else node?.fingerprint)
                // In Xray 26+, "allowInsecure" has been removed and migrated to "verifyPeerCertByName"
                // When camouflage SNI (e.g. dl.google.com) is used, verify against the actual server IP/host certificate
                if (!node?.sni.isNullOrEmpty() && node?.sni != node?.address) {
                    val peerName = if (!node?.host.isNullOrEmpty()) node?.host else node?.address
                    if (!peerName.isNullOrEmpty()) {
                        addProperty("verifyPeerCertByName", peerName)
                    }
                }
            }
            streamSettings.add("tlsSettings", tls)
        }

        // TCP keepalive: detect dead connections faster at OS level
        val sockopt = JsonObject().apply {
            addProperty("tcpKeepAliveInterval", 5)
            addProperty("tcpKeepAliveIdle", 10)
        }
        streamSettings.add("sockopt", sockopt)

        when (network) {
            "xhttp" -> {
                val xhttp = JsonObject().apply {
                    addProperty("path", if (node?.path.isNullOrEmpty()) "/" else node?.path)
                    val hostVal = if (!node?.host.isNullOrEmpty()) node?.host else node?.sni
                    if (!hostVal.isNullOrEmpty()) addProperty("host", hostVal)
                    val effectiveMode = if (xhttpModeOverride != "auto") {
                        xhttpModeOverride
                    } else if (!node?.mode.isNullOrEmpty() && node?.mode?.lowercase() !in listOf("stream-up", "stream-down")) {
                        node?.mode
                    } else {
                        "auto"
                    }
                    addProperty("mode", effectiveMode)
                    if (!node?.padding.isNullOrEmpty()) {
                        val extra = JsonObject().apply {
                            addProperty("xPaddingBytes", node?.padding)
                        }
                        add("extra", extra)
                    }
                    val xmux = JsonObject().apply {
                        addProperty("maxConnections", 1)
                        addProperty("hKeepAlivePeriod", 10)
                    }
                    add("xmux", xmux)
                }
                streamSettings.add("xhttpSettings", xhttp)
            }
            "ws" -> {
                val ws = JsonObject().apply {
                    val rawPath = if (node?.path.isNullOrEmpty()) "/" else node?.path!!
                    val normalizedPath = if (rawPath.startsWith("/")) rawPath else "/$rawPath"
                    addProperty("path", normalizedPath)
                    val hostVal = if (!node?.host.isNullOrEmpty()) node?.host else if (!node?.sni.isNullOrEmpty()) node?.sni else node?.address
                    if (!hostVal.isNullOrEmpty()) {
                        val headers = JsonObject().apply {
                            addProperty("Host", hostVal)
                        }
                        add("headers", headers)
                    }
                }
                streamSettings.add("wsSettings", ws)
            }
            "grpc" -> {
                val grpc = JsonObject().apply {
                    addProperty("serviceName", if (node?.path.isNullOrEmpty()) "gun" else node?.path)
                    addProperty("multiMode", false)
                }
                streamSettings.add("grpcSettings", grpc)
            }
            "h2" -> {
                val http = JsonObject().apply {
                    val rawPath = if (node?.path.isNullOrEmpty()) "/" else node?.path!!
                    val normalizedPath = if (rawPath.startsWith("/")) rawPath else "/$rawPath"
                    val pathArr = JsonArray().apply { add(normalizedPath) }
                    add("path", pathArr)
                    val hostVal = if (!node?.host.isNullOrEmpty()) node?.host else if (!node?.sni.isNullOrEmpty()) node?.sni else node?.address
                    if (!hostVal.isNullOrEmpty()) {
                        val hostArr = JsonArray().apply { add(hostVal) }
                        add("host", hostArr)
                    }
                }
                streamSettings.add("httpSettings", http)
            }
        }

        proxy.add("streamSettings", streamSettings)

        return proxy
    }
}
