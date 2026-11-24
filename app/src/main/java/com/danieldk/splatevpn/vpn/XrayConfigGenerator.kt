package com.danieldk.splatevpn.vpn

import com.danieldk.splatevpn.SplateConstants
import com.danieldk.splatevpn.data.ServerNode
import com.danieldk.splatevpn.data.SmartDnsProviders
import com.danieldk.splatevpn.data.aiSmartDnsDomains
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class XrayConfigGenerator @Inject constructor() {

    private val gson = Gson()

    /** Isolated SmartDNS probe; VPN mode requires a complete immutable transport snapshot. */
    fun generateAiDnsProbeConfig(providerKey: String, fragment: Boolean, socksPort: Int,
        localProxyAccess: LocalProxyCredentials? = null, viaVpn: Boolean = false,
        vpnRoute: com.danieldk.splatevpn.data.AiDnsVpnRoute? = null): String {
        require(socksPort in 1..65535)
        if (viaVpn) return generateAiDnsVpnProbeConfig(providerKey, socksPort, localProxyAccess,
            requireNotNull(vpnRoute) { "A connected VPN profile is required for SmartDNS via VPN" })
        val provider = SmartDnsProviders.forAi(providerKey)
        val targetDomains = aiSmartDnsDomains
        return gson.toJson(JsonObject().apply {
            add("log", JsonObject().apply { addProperty("loglevel", "warning") })
            add("dns", JsonObject().apply {
                addProperty("queryStrategy", "UseIPv4")
                addProperty("disableFallbackIfMatch", true)
                add("hosts", JsonObject().apply {
                    add(provider.host, JsonArray().apply { provider.bootstrapIpv4.forEach { add(it) } })
                })
                add("servers", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("address", provider.dohUrl)
                        addProperty("skipFallback", true)
                        add("domains", JsonArray().apply { targetDomains.forEach { add(it) } })
                    })
                    // Browser scripts/fonts/sign-in resources need ordinary DNS. Matched AI
                    // names must never fall back from the selected SmartDNS resolver.
                    add(JsonObject().apply { addProperty("address", "https://1.1.1.1/dns-query") })
                })
            })
            add("inbounds", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("listen", "127.0.0.1")
                    addProperty("port", socksPort)
                    addProperty("protocol", "socks")
                    add("settings", localProxyAccess?.socksSettings(false)
                        ?: JsonObject().apply { addProperty("auth", "noauth"); addProperty("udp", false) })
                })
            })
            add("outbounds", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("tag", "ai-probe")
                    addProperty("protocol", "freedom")
                    add("settings", JsonObject().apply {
                        if (fragment) add("fragment", JsonObject().apply {
                            addProperty("packets", "tlshello"); addProperty("length", "10-20"); addProperty("interval", "1-2")
                        })
                    })
                    add("streamSettings", JsonObject().apply {
                        add("sockopt", JsonObject().apply { addProperty("domainStrategy", "ForceIPv4") })
                    })
                })
                if (fragment) add(JsonObject().apply {
                    addProperty("tag", "provider-probe"); addProperty("protocol", "freedom")
                    add("settings", JsonObject().apply { add("fragment", JsonObject().apply {
                        addProperty("packets", "tlshello"); addProperty("length", "10-20"); addProperty("interval", "1-2")
                    }) })
                    add("streamSettings", JsonObject().apply { add("sockopt", JsonObject().apply { addProperty("domainStrategy", "ForceIPv4") }) })
                })
                add(JsonObject().apply {
                    addProperty("tag", "dns-probe")
                    addProperty("protocol", "freedom")
                    add("streamSettings", JsonObject().apply {
                        add("sockopt", JsonObject().apply { addProperty("domainStrategy", "ForceIPv4") })
                    })
                })
            })
            add("routing", JsonObject().apply {
                add("rules", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("type", "field"); addProperty("port", "443"); addProperty("outboundTag", if (fragment) "provider-probe" else "dns-probe")
                        add("domain", JsonArray().apply { add("full:${provider.host}") })
                    })
                    add(JsonObject().apply {
                        addProperty("type", "field"); addProperty("outboundTag", "ai-probe")
                        add("domain", JsonArray().apply { targetDomains.forEach { add(it) } })
                    })
                    add(JsonObject().apply {
                        addProperty("type", "field"); addProperty("network", "tcp,udp"); addProperty("outboundTag", "dns-probe")
                    })
                })
            })
        })
    }

    private fun generateAiDnsVpnProbeConfig(providerKey: String, socksPort: Int,
        localProxyAccess: LocalProxyCredentials?, route: com.danieldk.splatevpn.data.AiDnsVpnRoute): String {
        val config = com.google.gson.JsonParser.parseString(generateAiDnsProbeConfig(providerKey,
            fragment = false, socksPort = socksPort, localProxyAccess = localProxyAccess)).asJsonObject
        val transport = com.google.gson.JsonParser.parseString(generateTestConfig(route.node, socksPort,
            xhttpModeOverride = route.xhttpMode, enableTlsFragment = route.tlsFragment,
            tlsFragmentLength = route.tlsFragmentLength, tlsFragmentInterval = route.tlsFragmentInterval,
            tlsFragmentPackets = route.tlsFragmentPackets, tlsMixedSni = route.tlsMixedSni, enableMux = route.mux,
            muxConcurrency = route.muxConcurrency, localProxyAccess = localProxyAccess)).asJsonObject
        val aiOutbound = config.getAsJsonArray("outbounds")[0].asJsonObject.deepCopy()
        aiOutbound.getAsJsonObject("streamSettings").getAsJsonObject("sockopt").addProperty("dialerProxy", "proxy")
        val outbounds = transport.getAsJsonArray("outbounds").deepCopy()
        outbounds.add(aiOutbound)
        config.add("outbounds", outbounds)

        val dns = config.getAsJsonObject("dns")
        val hosts = dns.getAsJsonObject("hosts")
        transport.getAsJsonObject("dns").getAsJsonObject("hosts")?.entrySet()?.forEach { (name, value) ->
            if (!hosts.has(name)) hosts.add(name, value.deepCopy())
        }
        val servers = JsonArray()
        // Only VPN transport endpoints can use the pre-tunnel resolver. AI names cannot fall back to it.
        transport.getAsJsonObject("dns").getAsJsonArray("servers").filter {
            it.isJsonObject && it.asJsonObject["address"]?.asString == "localhost"
        }.forEach { servers.add(it.deepCopy()) }
        dns.getAsJsonArray("servers").forEach { servers.add(it.deepCopy()) }
        dns.add("servers", servers)
        config.getAsJsonObject("routing").getAsJsonArray("rules").forEach { element ->
            val rule = element.asJsonObject
            if (rule["outboundTag"]?.asString == "dns-probe") rule.addProperty("outboundTag", "proxy")
        }
        return gson.toJson(config)
    }

    fun generateConfig(
        node: ServerNode?,
        mode: String = "smart_ru",
        enableSniffing: Boolean = true,
        routeOnlySniffing: Boolean = false,
        blockQuic: Boolean = true,
        blockIpv6: Boolean = true,
        adBlocker: Boolean = true,
        dnsProvider: String = "cloudflare",
        customDohUrl: String = "",
        isFakeDns: Boolean = false,
        xhttpModeOverride: String = "auto",
        bypassAi: Boolean = true,
        aiRoutingMode: String = "dns_ai",
        customRoutes: List<Pair<String, String>> = emptyList(),
        enableTlsFragment: Boolean = false,
        tlsFragmentLength: String = "100-200",
        tlsFragmentInterval: String = "10-20",
        tlsFragmentPackets: String = "tlshello",
        enableMux: Boolean = false,
        muxConcurrency: Int = 8,
        shareLan: Boolean = false,
        lanHttpPort: Int = SplateConstants.XRAY_LAN_HTTP_PORT,
        aiDnsProvider: String = "dns_ai",
        aiTlsFragment: Boolean = false,
        adBlockMatcher: String? = null,
        adBlockAllowlist: List<String> = emptyList(),
        lanAccess: LanAccess? = null,
        routingRegion: String = "ru",
        diagnosticLogging: Boolean = node?.security.equals("reality", ignoreCase = true) || !node?.publicKey.isNullOrBlank(),
        dnsProbePort: Int? = null,
        localProxyAccess: LocalProxyCredentials? = null,
        subscriptionRules: List<JsonObject> = emptyList(),
        advancedRules: List<com.danieldk.splatevpn.data.AdvancedRouteRule> = emptyList(),
        appIdentityAccess: AppIdentityAccess? = null,
        allAvailableServers: List<ServerNode> = emptyList(),
        tlsMixedSni: Boolean = false,
        socksPort: Int = SplateConstants.XRAY_SOCKS_PORT
    ): String {
        require(socksPort in 1..65535) { "Invalid local SOCKS port" }
        val region = com.danieldk.splatevpn.data.RoutingRegion.fromId(if (mode == "smart_ru") "ru" else routingRegion)
        val localRouting = mode in setOf("smart_ru", "smart")
        val config = JsonObject()

        val appPackages = com.danieldk.splatevpn.data.AdvancedRoutingRules.packageNames(advancedRules).toSet()
        require(appPackages.isEmpty() || appIdentityAccess != null && appIdentityAccess.packages == appPackages) {
            "Для правил приложений требуется отдельный внутренний TUN-вход с определением Android UID"
        }
        require(appIdentityAccess == null || appIdentityAccess.port != socksPort &&
            appIdentityAccess.port != dnsProbePort && (!shareLan || appIdentityAccess.port !in setOf(lanHttpPort, SplateConstants.XRAY_LAN_SOCKS_PORT))) {
            "Private app identity port conflicts with another listener"
        }
        val compiledAdvancedRules = com.danieldk.splatevpn.data.AdvancedRoutingRules.compile(advancedRules).map { rule ->
            if (rule.has("user")) rule.apply {
                // Even a manually chosen Mixed/LAN username must not impersonate a TUN app.
                add("inboundTag", JsonArray().apply { add(AppIdentityAccess.INBOUND_TAG) })
            } else rule
        }
        require(enableSniffing || !com.danieldk.splatevpn.data.AdvancedRoutingRules.requiresDomainSniffing(advancedRules)) {
            "Для составных доменных правил включите определение доменов (sniffing)"
        }
        val targetServerIds = advancedRules.filter { it.enabled && it.outbound == "proxy" && !it.targetServerId.isNullOrBlank() }
            .mapNotNull { it.targetServerId }
            .distinct()

        // Transport failures are info-level in Xray; retain them when diagnosing a profile.
        val log = JsonObject().apply {
            addProperty("loglevel", if (diagnosticLogging) "info" else "warning")
            addProperty("access", "")
        }
        config.add("log", log)

        // Remote DNS endpoint (tunneled via proxy)
        // Standard DNS (UDP/TCP) is more reliable over Xray proxy than DoH
        val selectedSmartDns = SmartDnsProviders.forAi(aiDnsProvider)
        val generalSmartDns = SmartDnsProviders.find(dnsProvider)
        val remoteDnsUrl = generalSmartDns?.dohUrl ?: when (dnsProvider.lowercase()) {
            "google"  -> "https://8.8.8.8/dns-query"
            "quad9"   -> "https://9.9.9.9/dns-query"
            "adguard" -> "https://94.140.14.14/dns-query"
            "yandex"  -> "tcp://77.88.8.8:53"
            "custom"  -> if (customDohUrl.isNotBlank()) customDohUrl.trim() else "https://1.1.1.1/dns-query"
            else      -> "https://1.1.1.1/dns-query" // Cloudflare default
        }
        val customDnsBootstrapHost = if (dnsProvider.equals("custom", ignoreCase = true)) {
            runCatching { java.net.URI(customDohUrl.trim()).host }.getOrNull()
                ?.takeIf { it.isNotBlank() && !isIpAddress(it) }
        } else null

        val useSmartDns = bypassAi && aiRoutingMode != "vpn"
        val smartDnsViaVpn = useSmartDns && aiRoutingMode == "dns_vpn"
        val smartDnsInUse = (listOfNotNull(generalSmartDns) + if (useSmartDns) listOf(selectedSmartDns) else emptyList()).distinctBy { it.key }
        // Keep the direct exception limited to AI hosts, not entire vendor domains.
        val aiDirectDomainList = aiSmartDnsDomains

        // These services use the VPN and ordinary remote DNS. Do not resolve them
        // to Smart DNS proxy addresses before sending them through the VPN.
        val aiProxyDomainList = listOf(
            "domain:openai.com",
            "domain:chatgpt.com",
            "domain:oaistatic.com",
            "domain:oaiusercontent.com",
            "domain:sora.com",
            "domain:ai.com",
            "domain:claude.ai",
            "domain:claude.com",
            "domain:anthropic.com",
            "domain:anthropic.ai",
            "domain:perplexity.ai",
            "domain:x.ai",
            "domain:grok.com",
            "domain:poe.com",
            "domain:cursor.sh",
            "domain:cursor.com",
            "domain:midjourney.com",
            "domain:suno.com",
            "domain:suno.ai",
            "domain:elevenlabs.io",
            "domain:character.ai",
            "domain:duck.ai",
            "domain:githubcopilot.com",
            "full:copilot-proxy.githubusercontent.com",
            "full:search.brave.com",
            "full:leo.brave.com",
            "full:ai-chat.bsg.brave.com",
            "domain:lumo.proton.me",
            "full:lumo-api.proton.me",
            "geosite:openai"
        )

        // 2. DNS Configuration
        // RU domains -> Yandex directly; direct AI domains -> DNS-AI DoH directly;
        // VPN AI domains and other traffic → remote DNS through the VPN.
        val dns = JsonObject().apply {
            val servers = JsonArray().apply {
                if (isFakeDns) {
                    add("fakedns")
                }

                // Regional DNS is scoped to local exceptions. Other regions keep DNS in the tunnel.
                if (localRouting && region == com.danieldk.splatevpn.data.RoutingRegion.RUSSIA) {
                    add(JsonObject().apply {
                        addProperty("address", "tcp://77.88.8.8:53")
                        addProperty("skipFallback", true)
                        add("domains", JsonArray().apply { region.domains.forEach { add(it) } })
                    })
                }

                // A user proxy override must not inherit a Smart DNS relay address.
                val proxyDnsDomains = customRoutes.filter { it.second == "proxy" }.map { it.first } +
                    if (bypassAi && !useSmartDns) aiProxyDomainList + aiDirectDomainList else emptyList()
                if (proxyDnsDomains.isNotEmpty()) {
                    add(JsonObject().apply {
                        addProperty("address", if (generalSmartDns != null) "https://1.1.1.1/dns-query" else remoteDnsUrl)
                        addProperty("skipFallback", true)
                        add("domains", JsonArray().apply { proxyDnsDomains.forEach { add(it) } })
                    })
                }

                // Keep Smart DNS answers scoped to services routed directly.
                if (useSmartDns) {
                    val aiDns = JsonObject().apply {
                        addProperty("address", selectedSmartDns.dohUrl)
                        addProperty("port", 443)
                        addProperty("skipFallback", true)
                        val domains = JsonArray().apply {
                            aiDirectDomainList.forEach { add(it) }
                        }
                        add("domains", domains)
                    }
                    add(aiDns)
                }

                // Remote DNS for all other domains — routed through proxy tunnel.
                add(remoteDnsUrl)
                // Retain TCP support for VPN servers without UDP, without silently
                // switching to a different provider (including for custom/SmartDNS).
                if (!remoteDnsUrl.contains("://") && isIpAddress(remoteDnsUrl)) {
                    val host = if (remoteDnsUrl.contains(':')) "[${remoteDnsUrl.removeSurrounding("[", "]")}]" else remoteDnsUrl
                    add("tcp://$host:53")
                }
            }
            add("servers", servers)

            val hosts = JsonObject().apply {
                if (adBlocker) AdBlockingPolicy.addDnsHosts(this, adBlockMatcher, adBlockAllowlist)
                // Bootstrap DoH server IPs to avoid chicken-and-egg DNS resolution
                addProperty("cloudflare-dns.com", "1.1.1.1")
                addProperty("dns.google", "8.8.8.8")
                addProperty("dns.quad9.net", "9.9.9.9")
                addProperty("dns.adguard.com", "94.140.14.14")
                smartDnsInUse.forEach { provider ->
                    add(provider.host, JsonArray().apply { provider.bootstrapIpv4.forEach { add(it) } })
                }
            }
            add("hosts", hosts)
            addProperty("queryStrategy", "UseIPv4")
            // Keep scoped RU/bootstrap, explicit routes and AI DNS isolated even
            // when their resolver fails. Unmatched names use the selected provider.
            addProperty("disableFallbackIfMatch", true)
        }
        config.add("dns", dns)

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

        // 3. Inbounds: SOCKS5 for hev-socks5-tunnel + optional HTTP & SOCKS LAN sharing
        val inbounds = JsonArray()
        val socksInbound = JsonObject().apply {
            addProperty("tag", "socks-in")
            addProperty("port", socksPort)
            addProperty("listen", SplateConstants.XRAY_SOCKS_ADDRESS)
            addProperty("protocol", "socks")
            val settings = localProxyAccess?.socksSettings(true) ?: JsonObject().apply {
                addProperty("auth", "noauth")
                addProperty("udp", true)
            }
            add("settings", settings)

            if (enableSniffing || isFakeDns || useSmartDns || adBlocker) {
                val sniffing = JsonObject().apply {
                    addProperty("enabled", true)
                    val destOverride = JsonArray().apply {
                        if (isFakeDns) add("fakedns")
                        add("http")
                        add("tls")
                        if (!blockQuic) add("quic")
                    }
                    add("destOverride", destOverride)
                    addProperty("metadataOnly", false)
                    addProperty("routeOnly", routeOnlySniffing && !isFakeDns)
                }
                add("sniffing", sniffing)
            }
        }
        inbounds.add(socksInbound)

        if (dnsProbePort != null) {
            require(dnsProbePort in 1..65535 && dnsProbePort != socksPort &&
                (!shareLan || dnsProbePort != lanHttpPort))
            inbounds.add(JsonObject().apply {
                addProperty("tag", "dns-check-in")
                addProperty("listen", SplateConstants.XRAY_SOCKS_ADDRESS)
                addProperty("port", dnsProbePort)
                addProperty("protocol", "socks")
                add("settings", localProxyAccess?.socksSettings(false)
                    ?: JsonObject().apply { addProperty("auth", "noauth"); addProperty("udp", false) })
            })
        }

        // The tunnel keeps its private listener; shared listeners bind only the selected address.
        if (shareLan) {
            val access = requireNotNull(lanAccess) { "LAN interface and credentials are required" }
            val accounts = JsonArray().apply { add(JsonObject().apply {
                addProperty("user", access.username); addProperty("pass", access.password)
            }) }
            inbounds.add(socksInbound.deepCopy().apply {
                addProperty("tag", "socks-lan")
                addProperty("listen", access.bindAddress)
                addProperty("port", SplateConstants.XRAY_LAN_SOCKS_PORT)
                getAsJsonObject("settings").apply {
                    addProperty("auth", if (access.authentication) "password" else "noauth")
                    if (access.authentication) add("accounts", accounts.deepCopy()) else remove("accounts")
                    addProperty("ip", access.bindAddress)
                }
            })
            val httpLanInbound = JsonObject().apply {
                addProperty("tag", "http-in")
                addProperty("port", lanHttpPort)
                addProperty("listen", access.bindAddress)
                addProperty("protocol", "http")
                val settings = JsonObject().apply {
                    addProperty("timeout", 300)
                    addProperty("allowTransparent", false)
                    if (access.authentication) add("accounts", accounts.deepCopy())
                }
                add("settings", settings)

                if (enableSniffing || isFakeDns || useSmartDns || adBlocker) {
                    val sniffing = JsonObject().apply {
                        addProperty("enabled", true)
                        val destOverride = JsonArray().apply {
                            if (isFakeDns) add("fakedns")
                            add("http")
                            add("tls")
                            if (!blockQuic) add("quic")
                        }
                        add("destOverride", destOverride)
                        addProperty("metadataOnly", false)
                        addProperty("routeOnly", routeOnlySniffing && !isFakeDns)
                    }
                    add("sniffing", sniffing)
                }
            }
            inbounds.add(httpLanInbound)
        }
        if (appIdentityAccess != null) inbounds.add(socksInbound.deepCopy().apply {
            addProperty("tag", AppIdentityAccess.INBOUND_TAG)
            addProperty("port", appIdentityAccess.port)
            addProperty("listen", "127.0.0.1")
            add("settings", appIdentityAccess.settings())
        })
        config.add("inbounds", inbounds)

        // 4. Outbounds
        val outbounds = JsonArray()
        val rawSec = node?.security?.lowercase()?.trim() ?: "none"
        val security: String = when {
            rawSec == "reality"              -> "reality"
            rawSec == "tls"                  -> "tls"
            rawSec == "none"                 -> "none"
            !node?.publicKey.isNullOrEmpty() -> "reality"
            node?.port == 443                -> "tls"
            else                             -> "none"
        }
        val hasTls = security == "tls" || security == "reality"
        val effectiveTlsFragment = enableTlsFragment && hasTls

        val proxyOutbound = buildProxyOutbound(
            node = node,
            xhttpModeOverride = xhttpModeOverride,
            enableTlsFragment = effectiveTlsFragment,
            enableMux = enableMux,
            muxConcurrency = muxConcurrency,
            resolveTargets = dnsProvider == "custom" || generalSmartDns != null
        )
        outbounds.add(proxyOutbound)

        if (effectiveTlsFragment) {
            val fragmentOutbound = JsonObject().apply {
                addProperty("tag", "fragment")
                addProperty("protocol", "freedom")
                val fragmentSettings = JsonObject().apply {
                    addProperty("domainStrategy", "AsIs")
                    val fragmentObj = JsonObject().apply {
                        val twoParts = tlsFragmentPackets == com.danieldk.splatevpn.data.TLS_HELLO_TWO_PARTS
                        addProperty("packets", if (twoParts) "tlshello" else tlsFragmentPackets.ifBlank { "tlshello" })
                        if (twoParts) addProperty("maxSplit", "2")
                        addProperty("length", tlsFragmentLength.ifBlank { "100-200" })
                        addProperty("interval", tlsFragmentInterval.ifBlank { "10-20" })
                    }
                    add("fragment", fragmentObj)
                }
                add("settings", fragmentSettings)
            }
            outbounds.add(fragmentOutbound)
        }

        val directOutbound = JsonObject().apply {
            addProperty("tag", "direct")
            addProperty("protocol", "freedom")
            val sockopt = JsonObject().apply {
                addProperty("domainStrategy", "UseIP")
            }
            val streamSettings = JsonObject().apply {
                add("sockopt", sockopt)
            }
            add("streamSettings", streamSettings)
        }
        outbounds.add(directOutbound)

        if (useSmartDns) {
            outbounds.add(JsonObject().apply {
                addProperty("tag", "ai-direct")
                addProperty("protocol", "freedom")
                add("settings", JsonObject().apply {
                    if (aiTlsFragment && !smartDnsViaVpn) add("fragment", JsonObject().apply {
                        addProperty("packets", "tlshello")
                        addProperty("length", "10-20")
                        addProperty("interval", "1-2")
                    })
                })
                add("streamSettings", JsonObject().apply {
                    // Preserve the relay selection: never fall back to OS DNS after a SmartDNS failure.
                    add("sockopt", JsonObject().apply {
                        addProperty("domainStrategy", "ForceIPv4")
                        // Resolve the SmartDNS relay here, before the VPN outbound can
                        // resolve the original AI hostname using ordinary remote DNS.
                        if (smartDnsViaVpn) addProperty("dialerProxy", "proxy")
                    })
                })
            })
        }

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

        // 5. Routing
        val routing = JsonObject().apply {
            addProperty("domainStrategy", if (com.danieldk.splatevpn.data.AdvancedRoutingRules.requiresIpResolution(advancedRules)) "IPOnDemand" else "IPIfNonMatch")
            val rules = JsonArray()

            // Resolver comparisons must use the VPN even when user routes send DNS directly.
            if (dnsProbePort != null) rules.add(JsonObject().apply {
                addProperty("type", "field")
                add("inboundTag", JsonArray().apply { add("dns-check-in") })
                addProperty("outboundTag", "dns-check-proxy")
            })

            // 5.1 Prevent proxy loop: server address must go direct
            if (!node?.address.isNullOrBlank()) {
                val nodeAddrRule = JsonObject().apply {
                    addProperty("type", "field")
                    addProperty("outboundTag", "direct")
                    if (isIpAddress(node!!.address)) {
                        val ipArr = JsonArray().apply { add(node.address) }
                        add("ip", ipArr)
                    } else {
                        val domainArr = JsonArray().apply { add("domain:${node.address}") }
                        add("domain", domainArr)
                    }
                }
                rules.add(nodeAddrRule)
            }

            targetServerIds.forEach { targetId ->
                if (node?.id != targetId) {
                    val targetNode = allAvailableServers.firstOrNull { it.id == targetId }
                    if (targetNode != null && !targetNode.address.isNullOrBlank()) {
                        val targetAddrRule = JsonObject().apply {
                            addProperty("type", "field")
                            addProperty("outboundTag", "direct")
                            if (isIpAddress(targetNode.address)) {
                                val ipArr = JsonArray().apply { add(targetNode.address) }
                                add("ip", ipArr)
                            } else {
                                val domainArr = JsonArray().apply { add("domain:${targetNode.address}") }
                                add("domain", domainArr)
                            }
                        }
                        rules.add(targetAddrRule)
                    }
                }
            }

            // 5.1b XDrive cloud-storage API endpoints must bypass the VPN (they form the tunnel itself).
            if (node?.network?.lowercase() == "xdrive") {
                val xdriveApiRule = com.google.gson.JsonParser.parseString(
                    node.fullConfigJson.orEmpty()
                ).takeIf { it.isJsonObject }
                    ?.asJsonObject?.getAsJsonArray("outbounds")
                    ?.firstOrNull { it.asJsonObject["tag"]?.asString == "proxy" }
                    ?.asJsonObject?.getAsJsonObject("streamSettings")
                    ?.getAsJsonObject("xdriveSettings")
                    ?.getAsJsonObject("template")
                    ?.entrySet()
                    ?.flatMap { (_, v) -> if (!v.isJsonObject) emptyList() else listOfNotNull(v.asJsonObject["url"]?.asString) }
                    ?.mapNotNull { url -> runCatching { java.net.URI(url.substringBefore("{")).host?.takeIf { it.isNotBlank() && !isIpAddress(it) } }.getOrNull() }
                    ?.distinct()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { hosts ->
                        JsonObject().apply {
                            addProperty("type", "field")
                            addProperty("outboundTag", "direct")
                            add("domain", JsonArray().apply { hosts.forEach { add("domain:$it") } })
                        }
                    }
                if (xdriveApiRule != null) rules.add(xdriveApiRule)
            }

            // Filter before DNS/IP/direct exceptions; never allow ads through a RU bypass.
            if (adBlocker) AdBlockingPolicy.rule("block", adBlockMatcher, adBlockAllowlist)
                .takeIf { it.getAsJsonArray("domain").size() > 0 }?.let { rules.add(it) }

            // 5.2 Intercept DNS queries from TUN → internal Xray DNS resolver
            val dnsInterceptionRule = JsonObject().apply {
                addProperty("type", "field")
                val inboundArr = JsonArray().apply {
                    add("socks-in")
                    if (appIdentityAccess != null) add(AppIdentityAccess.INBOUND_TAG)
                }
                add("inboundTag", inboundArr)
                addProperty("port", "53")
                addProperty("outboundTag", "dns-out")
            }
            rules.add(dnsInterceptionRule)

            // The virtual resolver supports intercepted DNS, not DoT. Reject Android's
            // opportunistic TLS probe immediately so it falls back to port 53.
            rules.add(JsonObject().apply {
                addProperty("type", "field")
                add("ip", JsonArray().apply { add(SplateConstants.TUN_DNS_ADDRESS) })
                addProperty("port", "853")
                addProperty("outboundTag", "block")
            })

            // 5.3 Bootstrap DNS servers (Yandex, Google, Cloudflare port 53) → direct (for server resolving before tunnel)
            val directBootstrapDnsRule = JsonObject().apply {
                addProperty("type", "field")
                addProperty("outboundTag", "direct")
                val ipArr = JsonArray().apply {
                    if (routingRegion == "ru") {
                        add("77.88.8.8")
                        add("77.88.8.1")
                    }
                    add("172.65.9.208")
                    add("8.8.8.8")
                    add("1.1.1.1")
                }
                add("ip", ipArr)
                addProperty("port", "53")
            }
            rules.add(directBootstrapDnsRule)

            // Route DoH by hostname alone: Xray combines domain and IP fields with AND.
            if (smartDnsInUse.isNotEmpty()) {
                smartDnsInUse.groupBy { provider ->
                    when {
                        smartDnsViaVpn && provider.key == selectedSmartDns.key -> "proxy"
                        useSmartDns && aiTlsFragment && provider.key == selectedSmartDns.key -> "ai-direct"
                        else -> "direct"
                    }
                }.forEach { (outbound, providers) ->
                    val aiDnsRule = JsonObject().apply {
                        addProperty("type", "field")
                        addProperty("outboundTag", outbound)
                        addProperty("port", "443")
                        addProperty("network", "tcp")
                        val domainArr = JsonArray().apply {
                            providers.forEach { add("full:${it.host}") }
                        }
                        add("domain", domainArr)
                    }
                    rules.add(aiDnsRule)
                }
            }

            // 5.5 Remote DoH DNS servers → proxy (encrypted DNS via VPN tunnel)
            val remoteDnsRule = JsonObject().apply {
                addProperty("type", "field")
                addProperty("outboundTag", "proxy")
                val ipArr = JsonArray().apply {
                    add("8.8.8.8")
                    add("8.8.4.4")
                    add("1.1.1.1")
                    add("1.0.0.1")
                    add("9.9.9.9")
                    add("149.112.112.112")
                    add("94.140.14.14")
                    add("94.140.15.15")
                }
                add("ip", ipArr)
            }
            rules.add(remoteDnsRule)

            // Custom DoH host routing via proxy tunnel
            if (dnsProvider.lowercase() == "custom" && customDohUrl.isNotBlank()) {
                try {
                    val customDnsHost = java.net.URI(customDohUrl.trim()).host
                    if (!customDnsHost.isNullOrBlank() && !isIpAddress(customDnsHost)) {
                        val customDnsHostRule = JsonObject().apply {
                            addProperty("type", "field")
                            addProperty("outboundTag", "dns-bootstrap")
                            val domainArr = JsonArray().apply { add("full:$customDnsHost") }
                            add("domain", domainArr)
                        }
                        rules.add(customDnsHostRule)
                    }
                } catch (e: Exception) {
                    // Ignore URI parse exceptions
                }
            }

            // 5.6 Android captive portal, connectivity & speedtest endpoints → proxy
            val captivePortalRule = JsonObject().apply {
                addProperty("type", "field")
                addProperty("outboundTag", "proxy")
                val domainArr = JsonArray().apply {
                    add("domain:connectivitycheck.gstatic.com")
                    add("domain:cp.cloudflare.com")
                    add("domain:msftconnecttest.com")
                    add("domain:clients3.google.com")
                    add("domain:www.gstatic.com")
                    add("domain:speed.cloudflare.com")
                    add("domain:cachefly.net")
                    add("domain:cachefly.cachefly.net")
                }
                add("domain", domainArr)
            }
            rules.add(captivePortalRule)

            // 5.8 Block IPv6 if configured
            if (blockIpv6) {
                val blockIpv6Rule = JsonObject().apply {
                    addProperty("type", "field")
                    val ipArr = JsonArray().apply { add("::/0") }
                    add("ip", ipArr)
                    addProperty("outboundTag", "block")
                }
                rules.add(blockIpv6Rule)
            }

            // 5.9 Block QUIC if configured (reduces UDP 443 issues on some servers)
            if (blockQuic) {
                val blockQuicRule = JsonObject().apply {
                    addProperty("type", "field")
                    addProperty("network", "udp")
                    addProperty("port", "443")
                    addProperty("outboundTag", "block")
                }
                rules.add(blockQuicRule)
            }

            // 5.10 NTP time sync → direct (must not go through proxy to avoid time drift issues)
            val ntpRule = JsonObject().apply {
                addProperty("type", "field")
                addProperty("network", "udp")
                addProperty("port", "123")
                addProperty("outboundTag", "direct")
            }
            if (localRouting) rules.add(ntpRule)

            // 5.11 User custom domain routes (highest priority after DNS rules)
            // Preserve rule order, including expiring overrides placed before permanent rules.
            customRoutes.forEach { (domain, outbound) ->
                if (outbound in setOf("direct", "proxy", "block")) rules.add(JsonObject().apply {
                    addProperty("type", "field")
                    addProperty("outboundTag", outbound)
                    add("domain", JsonArray().apply { add(domain) })
                })
            }

            // Validated subscription rules follow explicit user overrides, before broad presets.
            compiledAdvancedRules.forEach { rules.add(it.deepCopy()) }
            subscriptionRules.forEach { rules.add(it.deepCopy()) }

            // 5.12 AI services bypass:
            // Smart DNS uses a separate direct outbound, independent of server TLS.
            if (useSmartDns) {
                rules.add(JsonObject().apply {
                    addProperty("type", "field")
                    addProperty("network", "udp")
                    addProperty("port", "443")
                    addProperty("outboundTag", "block")
                    add("domain", JsonArray().apply { aiDirectDomainList.forEach { add(it) } })
                })
                val directAiRule = JsonObject().apply {
                    addProperty("type", "field")
                    addProperty("outboundTag", "ai-direct")
                    val domainArr = JsonArray().apply {
                        aiDirectDomainList.forEach { add(it) }
                    }
                    add("domain", domainArr)
                }
                rules.add(directAiRule)
            }

            // Explicit VPN mode also covers Google AI before broad Google/RU rules.
            if (bypassAi) {
                val proxyAiRule = JsonObject().apply {
                    addProperty("type", "field")
                    addProperty("outboundTag", "proxy")
                    val domainArr = JsonArray().apply {
                        aiProxyDomainList.forEach { add(it) }
                        if (!useSmartDns) aiDirectDomainList.forEach { add(it) }
                    }
                    add("domain", domainArr)
                }
                rules.add(proxyAiRule)
            }


            if (localRouting) {
                // RU domains → direct (no VPN overhead for local services)
                val directRuDomains = JsonObject().apply {
                    addProperty("type", "field")
                    addProperty("outboundTag", "direct")
                    val domainArr = JsonArray().apply {
                        region.domains.forEach { add(it) }
                    }
                    add("domain", domainArr)
                }
                if (region.domains.isNotEmpty()) rules.add(directRuDomains)

                // RU IPs & private LAN → direct
                val directRuIps = JsonObject().apply {
                    addProperty("type", "field")
                    addProperty("outboundTag", "direct")
                    val ipArr = JsonArray().apply {
                        region.geoIp?.let { add(it) }
                        add("geoip:private")
                    }
                    add("ip", ipArr)
                }
                rules.add(directRuIps)

                // Telegram → proxy (blocked in Russia, needs VPN)
                val proxyTelegramIps = JsonObject().apply {
                    addProperty("type", "field")
                    addProperty("outboundTag", "proxy")
                    val ipArr = JsonArray().apply { add("geoip:telegram") }
                    add("ip", ipArr)
                }
                rules.add(proxyTelegramIps)

                val proxyTelegramDomains = JsonObject().apply {
                    addProperty("type", "field")
                    addProperty("outboundTag", "proxy")
                    val domainArr = JsonArray().apply { add("geosite:telegram") }
                    add("domain", domainArr)
                }
                rules.add(proxyTelegramDomains)

                // Popular blocked/foreign services → proxy
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
                        add("geosite:notion")
                        add("geosite:spotify")
                        add("geosite:netflix")
                        add("domain:rutracker.org")
                        add("domain:nnmclub.to")
                        add("domain:kinozal.tv")
                        add("domain:linkedin.com")
                        add("domain:pinterest.com")
                    }
                    add("domain", domainArr)
                }
                rules.add(proxyTargetDomains)
            } else {
                // Global mode: only private/LAN is direct, everything else → proxy
                val privateRule = JsonObject().apply {
                    addProperty("type", "field")
                    addProperty("outboundTag", "direct")
                    val ipArr = JsonArray().apply { add("geoip:private") }
                    add("ip", ipArr)
                }
                rules.add(privateRule)
            }

            // Default fallback → proxy
            val defaultProxyRule = JsonObject().apply {
                addProperty("type", "field")
                addProperty("network", "tcp,udp")
                addProperty("outboundTag", "proxy")
            }
            rules.add(defaultProxyRule)

            add("rules", rules)
        }
        config.add("routing", routing)

        // 6. Policy — tune connection lifecycle and buffer sizes for Xray 26.x
        val policy = JsonObject().apply {
            val levels = JsonObject().apply {
                val level0 = JsonObject().apply {
                    addProperty("handshake", 8)
                    addProperty("connIdle", 300)
                    addProperty("uplinkOnly", 5)
                    addProperty("downlinkOnly", 30)
                    addProperty("bufferSize", 2048)
                }
                add("0", level0)
            }
            add("levels", levels)
            val system = JsonObject().apply {
                addProperty("statsOutboundUplink", true)
                addProperty("statsOutboundDownlink", true)
            }
            add("system", system)
        }
        config.add("policy", policy)

        ImportedOutboundPolicy.apply(config, node?.fullConfigJson, enableMux, enableTlsFragment, xhttpModeOverride)
        // Copy the effective imported transport/chain as well. Resolver probes and the
        // custom resolver's own hostname must not depend on that resolver to connect.
        val effectiveOutbounds = config.getAsJsonArray("outbounds")
        val effectiveProxy = effectiveOutbounds.first { it.asJsonObject["tag"].asString == "proxy" }.asJsonObject
        fun addDnsProxy(tag: String) = effectiveOutbounds.add(effectiveProxy.deepCopy().apply {
            addProperty("tag", tag)
            addProperty("targetStrategy", "AsIs")
        })
        if (dnsProbePort != null) addDnsProxy("dns-check-proxy")
        if (customDnsBootstrapHost != null) addDnsProxy("dns-bootstrap")

        targetServerIds.forEach { targetId ->
            val targetTag = "proxy-server-$targetId"
            if (node != null && node.id == targetId) {
                effectiveOutbounds.add(effectiveProxy.deepCopy().apply {
                    addProperty("tag", targetTag)
                })
            } else {
                val targetNode = allAvailableServers.firstOrNull { it.id == targetId }
                if (targetNode != null) {
                    val importedTarget = if (!targetNode.fullConfigJson.isNullOrBlank()) {
                        runCatching {
                            val parsed = com.google.gson.JsonParser.parseString(targetNode.fullConfigJson).asJsonObject.getAsJsonArray("outbounds")
                            parsed?.firstOrNull {
                                it.isJsonObject && it.asJsonObject["protocol"]?.asString in setOf("vless", "vmess", "trojan", "shadowsocks", "socks", "http", "hysteria")
                            }?.asJsonObject?.deepCopy()?.apply {
                                addProperty("tag", targetTag)
                            }
                        }.getOrNull()
                    } else null

                    if (importedTarget != null) {
                        effectiveOutbounds.add(importedTarget)
                    } else {
                        effectiveOutbounds.add(buildProxyOutbound(
                            node = targetNode,
                            xhttpModeOverride = "auto",
                            enableTlsFragment = false,
                            tag = targetTag
                        ))
                    }
                } else {
                    effectiveOutbounds.add(effectiveProxy.deepCopy().apply {
                        addProperty("tag", targetTag)
                    })
                }
            }
        }
        if (tlsMixedSni) applyMixedSni(config)
        addProxyBootstrapDns(config)
        return gson.toJson(config)
    }

    private fun isIpAddress(address: String): Boolean {
        val trimmed = address.trim().removeSurrounding("[", "]")
        return trimmed.matches(Regex("""^(\d{1,3}\.){3}\d{1,3}$""")) || trimmed.contains(':')
    }

    private fun applyMixedSni(config: JsonObject) {
        val dnsLabel = Regex("[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?")
        config.getAsJsonArray("outbounds").forEach { outbound ->
            val stream = outbound.asJsonObject.getAsJsonObject("streamSettings") ?: return@forEach
            if (stream["security"]?.asString != "tls" || stream["network"]?.asString == "xdrive") return@forEach
            val tls = stream.getAsJsonObject("tlsSettings") ?: return@forEach
            val host = tls["serverName"]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                ?: return@forEach
            val name = host.removeSuffix(".")
            if (name.length !in 1..253 || isIpAddress(name) || !name.split('.').all(dnsLabel::matches)) return@forEach
            var upper = true
            val mixed = buildString(host.length) {
                host.forEach { char ->
                    if (char in 'a'..'z' || char in 'A'..'Z') {
                        append(if (upper) char.uppercaseChar() else char.lowercaseChar())
                        upper = !upper
                    } else append(char)
                }
            }
            tls.addProperty("serverName", mixed)
        }
    }

    private fun buildProxyOutbound(
        node: ServerNode?,
        xhttpModeOverride: String,
        enableTlsFragment: Boolean = false,
        enableMux: Boolean = false,
        muxConcurrency: Int = 8,
        resolveTargets: Boolean = false,
        tag: String = "proxy"
    ): JsonObject {
        if (node?.protocol.equals("amneziawg", true)) {
            return JsonObject().apply {
                addProperty("tag", tag); addProperty("protocol", "splate-amneziawg")
                add("awg", gson.toJsonTree(requireNotNull(node?.amneziaWg) { "AWG profile is missing" }.validated()))
            }
        }
        if (node?.protocol?.lowercase() in setOf("hysteria2", "hy2")) {
            return com.danieldk.splatevpn.data.Hysteria2Profile.outbound(requireNotNull(node)).apply {
                addProperty("tag", tag)
            }
        }
        val proxy = JsonObject()
        proxy.addProperty("tag", tag)
        // Ordinary tunnels must work without a reachable DNS resolver on the
        // physical network. Only explicit custom/Smart DNS needs local answers.
        proxy.addProperty("targetStrategy", if (resolveTargets) "ForceIP" else "AsIs")

        val protocol = node?.protocol?.lowercase() ?: "vless"
        proxy.addProperty("protocol", protocol)

        val rawNet = node?.network?.lowercase()?.trim() ?: "tcp"
        val migratedFromH2 = rawNet == "h2" || rawNet == "http"
        val network: String = when (rawNet) {
            "splithttp", "xhttp", "h2", "http" -> "xhttp"
            "websocket", "ws"                  -> "ws"
            "httpupgrade"                      -> "httpupgrade"
            "grpc", "gun"                      -> "grpc"
            "kcp", "mkcp"                      -> "kcp"
            // XDrive is a cloud-storage transport; the managed outbound is a placeholder
            // replaced wholesale by ImportedOutboundPolicy from fullConfigJson.
            "xdrive"                           -> "xdrive"
            else                               -> "tcp"
        }

        val rawSec = node?.security?.lowercase()?.trim() ?: "none"
        val security: String = when {
            rawSec == "reality"              -> "reality"
            rawSec == "tls"                  -> "tls"
            rawSec == "none"                 -> "none"
            !node?.publicKey.isNullOrEmpty() -> "reality"
            node?.port == 443                -> "tls"
            else                             -> "none"
        }

        val settings = JsonObject()
        when (protocol) {
            "vless" -> {
                val vnext = JsonArray()
                val serverItem = JsonObject().apply {
                    // Keep the actual peer so Xray can reject unsafe public VLESS transports.
                    addProperty("address", node?.address ?: "127.0.0.1")
                    addProperty("port", node?.port ?: 443)
                    val users = JsonArray()
                    val user = JsonObject().apply {
                        addProperty("id", node?.uuid ?: "00000000-0000-0000-0000-000000000000")
                        addProperty("encryption", if (node?.encryption.isNullOrEmpty()) "none" else node?.encryption)
                        // XTLS Vision flow only valid for TCP+Reality/TLS when explicitly configured on the node
                        if (!node?.flow.isNullOrEmpty()) {
                            if (network == "tcp" && (security == "reality" || security == "tls")) {
                                addProperty("flow", node?.flow)
                            }
                        }
                    }
                    users.add(user)
                    add("users", users)
                }
                vnext.add(serverItem)
                settings.add("vnext", vnext)
                settings.addProperty("packetEncoding", "xudp")
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
                settings.addProperty("packetEncoding", "xudp")
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
                settings.addProperty("packetEncoding", "xudp")
            }
            "shadowsocks" -> {
                val servers = JsonArray()
                val serverItem = JsonObject().apply {
                    addProperty("address", node?.address ?: "127.0.0.1")
                    addProperty("port", node?.port ?: 8388)
                    addProperty("method", if (!node?.encryption.isNullOrEmpty()) node?.encryption else "aes-256-gcm")
                    addProperty("password", node?.uuid ?: "")
                    addProperty("uot", true) // UDP over TCP for better compatibility
                }
                servers.add(serverItem)
                settings.add("servers", servers)
            }
            "socks" -> {
                val servers = JsonArray()
                val serverItem = JsonObject().apply {
                    addProperty("address", node?.address ?: "127.0.0.1")
                    addProperty("port", node?.port ?: 1080)
                    if (!node?.encryption.isNullOrBlank() || !node?.uuid.isNullOrBlank()) {
                        val users = JsonArray()
                        val user = JsonObject().apply {
                            addProperty("user", node?.encryption ?: "")
                            addProperty("pass", node?.uuid ?: "")
                        }
                        users.add(user)
                        add("users", users)
                    }
                }
                servers.add(serverItem)
                settings.add("servers", servers)
                settings.addProperty("version", "5")
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
                node?.rawLink?.let(com.danieldk.splatevpn.data.LinkParser::extractRealityVerificationKey)?.let {
                    addProperty("mldsa65Verify", com.danieldk.splatevpn.data.RealityVerificationKey.normalize(it))
                }
                addProperty("shortId", node?.shortId ?: "")
                addProperty("spiderX", node?.spiderX ?: "")
                if (network == "xhttp" || network == "h2") {
                    val alpnArr = JsonArray().apply {
                        add("h2")
                        add("http/1.1")
                    }
                    add("alpn", alpnArr)
                }
            }
            streamSettings.add("realitySettings", reality)
        } else if (security == "tls") {
            val tls = JsonObject().apply {
                val serverName = when {
                    !node?.sni.isNullOrEmpty()  -> node?.sni
                    !node?.host.isNullOrEmpty() -> node?.host
                    else                         -> node?.address ?: ""
                }
                addProperty("serverName", serverName)
                addProperty("fingerprint", if (node?.fingerprint.isNullOrEmpty()) "chrome" else node?.fingerprint)
                val explicitAlpn = com.danieldk.splatevpn.data.LinkParser.extractAlpn(node?.rawLink.orEmpty())
                if (explicitAlpn.isNotEmpty()) {
                    add("alpn", JsonArray().apply { explicitAlpn.forEach { add(it) } })
                } else if (network == "xhttp") {
                    val alpnArr = JsonArray().apply {
                        add("h2")
                        add("http/1.1")
                    }
                    add("alpn", alpnArr)
                }
                // verifyPeerCertByName: when using camouflage SNI (e.g. dl.google.com),
                // verify TLS certificate against the real server host, not SNI.
                val realHost = if (!node?.host.isNullOrEmpty()) node.host else (node?.address ?: "")
                if (!node?.sni.isNullOrEmpty() && realHost.isNotBlank() && node?.sni != realHost && !isIpAddress(realHost)) {
                    addProperty("verifyPeerCertByName", realHost)
                }
            }
            streamSettings.add("tlsSettings", tls)
        }

        // Socket-level optimizations for Xray 26.x
        val canFragmentTransport = enableTlsFragment && (security == "tls" || security == "reality") && network != "kcp"
        val sockopt = JsonObject().apply {
            addProperty("domainStrategy", "AsIs")
            addProperty("tcpNoDelay", true)
            addProperty("tcpKeepAliveInterval", 15)
            if (canFragmentTransport) {
                addProperty("dialerProxy", "fragment")
            }
        }
        streamSettings.add("sockopt", sockopt)

        // XDrive outbound is fully replaced by ImportedOutboundPolicy; skip transport block.
        if (network != "xdrive") when (network) {
            "xhttp" -> {
                val xhttp = JsonObject().apply {
                    val rawPath = if (node?.path.isNullOrEmpty()) "/" else node?.path!!
                    val normalizedPath = if (rawPath.startsWith("/")) rawPath else "/$rawPath"
                    addProperty("path", normalizedPath)
                    if (!node?.host.isNullOrEmpty()) {
                        addProperty("host", node.host)
                    }
                    val effectiveMode = when {
                        xhttpModeOverride.isNotBlank() && xhttpModeOverride != "auto" -> xhttpModeOverride
                        migratedFromH2 -> "stream-one"
                        !node?.mode.isNullOrEmpty() -> node.mode!!
                        else -> "auto"
                    }
                    addProperty("mode", effectiveMode)

                    val extra = JsonObject().apply {
                        if (!node?.padding.isNullOrEmpty()) {
                            addProperty("xPaddingBytes", node?.padding)
                        }
                    }

                    // Merge extra xhttp parameters from rawLink (3x-ui panel format)
                    if (!node?.rawLink.isNullOrBlank()) {
                        try {
                            val parsedExtra = com.danieldk.splatevpn.data.LinkParser.extractExtraJson(node.rawLink)
                            if (parsedExtra != null) {
                                for ((key, value) in parsedExtra.entrySet()) {
                                    if (!extra.has(key)) {
                                        extra.add(key, value)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            // ignore parse errors silently
                        }
                    }

                    // Rename deprecated sessionPlacement → sessionIDPlacement (Xray 26.6.22+ #6258)
                    if (extra.has("sessionPlacement") && !extra.has("sessionIDPlacement")) {
                        extra.add("sessionIDPlacement", extra.get("sessionPlacement"))
                    }
                    if (extra.has("sessionKey") && !extra.has("sessionIDKey")) {
                        extra.add("sessionIDKey", extra.get("sessionKey"))
                    }

                    if (extra.size() > 0) {
                        add("extra", extra)
                        // Also set top-level keys for direct unmarshaling compatibility
                        for ((key, value) in extra.entrySet()) {
                            if (!has(key)) {
                                add(key, value)
                            }
                        }
                    }
                }
                streamSettings.add("xhttpSettings", xhttp)
            }
            "ws" -> {
                val ws = JsonObject().apply {
                    val rawPath = if (node?.path.isNullOrEmpty()) "/" else node?.path!!
                    val normalizedPath = if (rawPath.startsWith("/")) rawPath else "/$rawPath"
                    addProperty("path", normalizedPath)
                    val hostVal = when {
                        !node?.host.isNullOrEmpty() -> node?.host
                        !node?.sni.isNullOrEmpty()  -> node?.sni
                        else                         -> node?.address
                    }
                    if (!hostVal.isNullOrEmpty()) {
                        addProperty("host", hostVal)
                    }
                }
                streamSettings.add("wsSettings", ws)
            }
            "httpupgrade" -> {
                val httpUpgrade = JsonObject().apply {
                    val rawPath = if (node?.path.isNullOrEmpty()) "/" else node?.path!!
                    val normalizedPath = if (rawPath.startsWith("/")) rawPath else "/$rawPath"
                    addProperty("path", normalizedPath)
                    val hostVal = when {
                        !node?.host.isNullOrEmpty() -> node?.host
                        !node?.sni.isNullOrEmpty()  -> node?.sni
                        else                         -> node?.address
                    }
                    if (!hostVal.isNullOrEmpty()) {
                        addProperty("host", hostVal)
                    }
                }
                streamSettings.add("httpupgradeSettings", httpUpgrade)
            }
            "grpc" -> {
                val grpc = JsonObject().apply {
                    val serviceName = when {
                        node?.path.isNullOrBlank() -> "gun"
                        node?.path == "/" -> ""
                        else -> node.path
                    }
                    addProperty("serviceName", serviceName)
                    addProperty("multiMode", node?.mode.equals("multi", ignoreCase = true))
                    if (!node?.host.isNullOrBlank()) {
                        addProperty("authority", node.host)
                    }
                    addProperty("idle_timeout", 60)
                    addProperty("health_check_timeout", 20)
                }
                streamSettings.add("grpcSettings", grpc)
            }
            "kcp" -> {
                val (headerType, seed) = com.danieldk.splatevpn.data.LinkParser.extractKcpParams(node?.rawLink.orEmpty())
                val kcp = JsonObject().apply {
                    addProperty("mtu", 1350)
                    addProperty("tti", 20)
                    addProperty("uplinkCapacity", 5)
                    addProperty("downlinkCapacity", 20)
                    addProperty("congestion", false)
                    addProperty("readBufferSize", 2)
                    addProperty("writeBufferSize", 2)
                    add("header", JsonObject().apply {
                        addProperty("type", headerType.ifBlank { "none" })
                    })
                    if (seed.isNotEmpty()) {
                        addProperty("seed", seed)
                    }
                }
                streamSettings.add("kcpSettings", kcp)
            }
            "tcp" -> {
                // HTTP camouflage must agree with the peer's configured TCP header type.
                if (node != null && com.danieldk.splatevpn.data.LinkParser.usesTcpHttpHeader(node)) {
                    val tcpSettings = JsonObject().apply {
                        val header = JsonObject().apply {
                            addProperty("type", "http")
                            val request = JsonObject().apply {
                                val version = "1.1"
                                addProperty("version", version)
                                addProperty("method", "GET")
                                val pathArr = JsonArray().apply {
                                    add(if (node?.path.isNullOrEmpty()) "/" else node?.path)
                                }
                                add("path", pathArr)
                                val headers = JsonObject().apply {
                                    if (!node?.host.isNullOrEmpty()) {
                                        val hostArr = JsonArray().apply { add(node.host) }
                                        add("Host", hostArr)
                                    }
                                    val ua = JsonArray().apply {
                                        add("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                                    }
                                    add("User-Agent", ua)
                                }
                                add("headers", headers)
                            }
                            add("request", request)
                        }
                        add("header", header)
                    }
                    streamSettings.add("tcpSettings", tcpSettings)
                }
            }
        }

        if (canFragmentTransport) {
            val sockoptObj = streamSettings.getAsJsonObject("sockopt") ?: JsonObject().also {
                streamSettings.add("sockopt", it)
            }
            sockoptObj.addProperty("dialerProxy", "fragment")
        }

        // Mux settings (incompatible with xtls-rprx-vision flow)
        if (enableMux) {
            val hasVision = node?.flow?.contains("vision", ignoreCase = true) == true
            if (!hasVision) {
                val muxObj = JsonObject().apply {
                    addProperty("enabled", true)
                    addProperty("concurrency", muxConcurrency.coerceIn(1, 128))
                    addProperty("xudpConcurrency", muxConcurrency.coerceIn(1, 128))
                    addProperty("xudpProxyUDP443", "reject")
                }
                proxy.add("mux", muxObj)
            }
        }

        proxy.add("streamSettings", streamSettings)

        return proxy
    }

    /**
     * Generates an isolated, ephemeral lightweight test config for measuring Real Delay (HTTP 204).
     * Binds a local SOCKS5 inbound strictly on 127.0.0.1:[socksPort], routes through [node],
     * and bypasses TUN / system routing entirely.
     *
     * Transport parameters ([xhttpModeOverride], TLS fragment, Mux) must mirror the production config
     * produced by [generateConfig], otherwise the probe may pass while the real tunnel fails (or vice versa).
     */
    private fun addProxyBootstrapDns(config: JsonObject) {
        val initial = config.getAsJsonArray("outbounds").flatMap { raw ->
            val ob = raw.asJsonObject
            val settings = ob.getAsJsonObject("settings") ?: return@flatMap emptyList<String>()
            val directAddresses = listOfNotNull(settings["address"]?.asString) +
                listOf("vnext", "servers").flatMap { key ->
                    settings.getAsJsonArray(key)?.mapNotNull { it.asJsonObject["address"]?.asString }.orEmpty()
                }
            // XDrive transport connects to cloud-storage API endpoints defined in the template.
            // Extract hostnames from template URL fields so they can be resolved before the
            // first dial without depending on the VPN tunnel that is not yet established.
            val xdriveHosts = ob.getAsJsonObject("streamSettings")
                ?.takeIf { it["network"]?.asString.equals("xdrive", ignoreCase = true) }
                ?.getAsJsonObject("xdriveSettings")
                ?.getAsJsonObject("template")
                ?.entrySet()
                ?.flatMap { (_, v) ->
                    if (!v.isJsonObject) return@flatMap emptyList()
                    listOfNotNull(v.asJsonObject["url"]?.asString)
                }
                ?.mapNotNull { url ->
                    runCatching { java.net.URI(url.substringBefore("{")).host?.takeIf { it.isNotBlank() && !isIpAddress(it) } }.getOrNull()
                }
                .orEmpty()
            directAddresses + xdriveHosts
        }
        val endpoints = linkedSetOf<String>()
        val hosts = config.getAsJsonObject("dns").getAsJsonObject("hosts")
        fun follow(address: String) {
            if (address.isBlank() || isIpAddress(address) || !endpoints.add(address)) return
            hosts?.get(address)?.let { mapped ->
                if (mapped.isJsonArray) mapped.asJsonArray.forEach { follow(it.asString) }
                else if (mapped.isJsonPrimitive) follow(mapped.asString)
            }
        }
        initial.forEach(::follow)
        if (endpoints.isEmpty()) return
        val servers = config.getAsJsonObject("dns").getAsJsonArray("servers")
        val domainArray = JsonArray().apply { endpoints.forEach { add("full:$it") } }
        val bootstrapServer = JsonObject().apply {
            addProperty("address", "localhost")
            addProperty("skipFallback", true)
            add("domains", domainArray)
        }
        // Bootstrap must work before the VPN server can be contacted. It cannot resolve other domains.
        val ordered = JsonArray().apply {
            add(bootstrapServer)
            servers.forEach { add(it) }
        }
        config.getAsJsonObject("dns").add("servers", ordered)
    }

    fun generateTestConfig(
        node: ServerNode,
        socksPort: Int,
        xhttpModeOverride: String = "auto",
        enableTlsFragment: Boolean = false,
        tlsFragmentLength: String = "100-200",
        tlsFragmentInterval: String = "10-20",
        tlsFragmentPackets: String = "tlshello",
        enableMux: Boolean = false,
        muxConcurrency: Int = 8,
        routingRegion: String = "ru",
        localProxyAccess: LocalProxyCredentials? = null,
        tlsMixedSni: Boolean = false
    ): String {
        val config = JsonObject()

        // 1. Silent logging
        val log = JsonObject().apply {
            addProperty("loglevel", "none")
        }
        config.add("log", log)

        // 2. Ephemeral SOCKS inbound
        val inbounds = JsonArray()
        val socksInbound = JsonObject().apply {
            addProperty("tag", "socks-test")
            addProperty("port", socksPort)
            addProperty("listen", "127.0.0.1")
            addProperty("protocol", "socks")
            val inSettings = localProxyAccess?.socksSettings(false) ?: JsonObject().apply {
                addProperty("auth", "noauth")
                addProperty("udp", false)
            }
            add("settings", inSettings)
        }
        inbounds.add(socksInbound)
        config.add("inbounds", inbounds)

        // 3. Outbounds: Target node proxy outbound + fragment + direct + dns-out
        val outbounds = JsonArray()
        val rawSec = node.security.lowercase().trim()
        val security: String = when {
            rawSec == "reality"           -> "reality"
            rawSec == "tls"               -> "tls"
            rawSec == "none"              -> "none"
            node.publicKey.isNotEmpty()   -> "reality"
            node.port == 443              -> "tls"
            else                          -> "none"
        }

        val effectiveTlsFragment = enableTlsFragment && (security == "tls" || security == "reality")
        val proxyOutbound = buildProxyOutbound(
            node = node,
            xhttpModeOverride = xhttpModeOverride,
            enableTlsFragment = effectiveTlsFragment,
            enableMux = enableMux,
            muxConcurrency = muxConcurrency
        )
        outbounds.add(proxyOutbound)

        if (effectiveTlsFragment) {
            val fragmentOutbound = JsonObject().apply {
                addProperty("tag", "fragment")
                addProperty("protocol", "freedom")
                val fragmentSettings = JsonObject().apply {
                    addProperty("domainStrategy", "AsIs")
                    val fragmentObj = JsonObject().apply {
                        val twoParts = tlsFragmentPackets == com.danieldk.splatevpn.data.TLS_HELLO_TWO_PARTS
                        addProperty("packets", if (twoParts) "tlshello" else tlsFragmentPackets.ifBlank { "tlshello" })
                        if (twoParts) addProperty("maxSplit", "2")
                        addProperty("length", tlsFragmentLength.ifBlank { "100-200" })
                        addProperty("interval", tlsFragmentInterval.ifBlank { "10-20" })
                    }
                    add("fragment", fragmentObj)
                }
                add("settings", fragmentSettings)
            }
            outbounds.add(fragmentOutbound)
        }

        val directOutbound = JsonObject().apply {
            addProperty("tag", "direct")
            addProperty("protocol", "freedom")
            val sockopt = JsonObject().apply {
                addProperty("domainStrategy", "UseIP")
            }
            val streamSettings = JsonObject().apply {
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

        config.add("outbounds", outbounds)

        // 4. Robust DNS configuration matching production
        val dns = JsonObject().apply {
            val servers = JsonArray().apply {
                add("localhost")
                if (routingRegion == "ru") {
                    add("tcp://77.88.8.8:53")
                    add("77.88.8.8")
                }
                add("tcp://1.1.1.1:53")
                add("1.1.1.1")
                add("tcp://8.8.8.8:53")
                add("8.8.8.8")
                add("https://1.1.1.1/dns-query")
                add("https://8.8.8.8/dns-query")
            }
            add("servers", servers)

            val hosts = JsonObject().apply {
                addProperty("cloudflare-dns.com", "1.1.1.1")
                addProperty("dns.google", "8.8.8.8")
            }
            add("hosts", hosts)
            addProperty("queryStrategy", "UseIPv4")
        }
        config.add("dns", dns)

        // 5. Routing rules with valid Xray string types (AsIs routes test domain directly to proxy without blocking on local DNS)
        val routing = JsonObject().apply {
            addProperty("domainStrategy", "AsIs")
            val rules = JsonArray()

            // 5.1 Intercept DNS queries → internal Xray DNS resolver
            val dnsInterceptionRule = JsonObject().apply {
                addProperty("type", "field")
                val inboundArr = JsonArray().apply { add("socks-test") }
                add("inboundTag", inboundArr)
                addProperty("port", "53")
                addProperty("outboundTag", "dns-out")
            }
            rules.add(dnsInterceptionRule)

            // 5.2 Direct DNS servers → direct freedom outbound
            val directDnsRule = JsonObject().apply {
                addProperty("type", "field")
                addProperty("outboundTag", "direct")
                val ipArr = JsonArray().apply {
                    if (routingRegion == "ru") {
                        add("77.88.8.8")
                        add("77.88.8.1")
                    }
                    add("1.1.1.1")
                    add("8.8.8.8")
                }
                add("ip", ipArr)
                addProperty("port", "53")
            }
            rules.add(directDnsRule)

            // The isolated core dials its transport directly, without routing it through
            // this inbound. A server-address bypass could falsely verify a dead candidate
            // when the HTTP test target shares that address.
            // 5.3 Default rule: route incoming probe traffic to the proxy outbound
            val defaultProxyRule = JsonObject().apply {
                addProperty("type", "field")
                addProperty("outboundTag", "proxy")
                addProperty("network", "tcp,udp")
            }
            rules.add(defaultProxyRule)

            add("rules", rules)
        }
        config.add("routing", routing)

        ImportedOutboundPolicy.apply(config, node?.fullConfigJson, enableMux, enableTlsFragment, xhttpModeOverride)
        if (tlsMixedSni) applyMixedSni(config)
        addProxyBootstrapDns(config)
        return gson.toJson(config)
    }

}
