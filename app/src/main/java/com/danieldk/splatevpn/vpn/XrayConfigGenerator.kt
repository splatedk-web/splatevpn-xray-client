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

    /** Isolated SmartDNS probe: no VPN, selected server, LAN listener or main-core port. */
    fun generateAiDnsProbeConfig(providerKey: String, fragment: Boolean, socksPort: Int): String {
        require(socksPort in 1..65535)
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
                    add("settings", JsonObject().apply { addProperty("auth", "noauth"); addProperty("udp", false) })
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
                        addProperty("type", "field"); addProperty("port", "443"); addProperty("outboundTag", "dns-probe")
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

    private fun buildImportedFullConfig(
        rawConfig: String,
        inboundPort: Int,
        listenAddress: String,
        udp: Boolean,
        silent: Boolean = false,
        adBlocker: Boolean = false,
        adBlockMatcher: String? = null,
        adBlockAllowlist: List<String> = emptyList()
    ): String? = try {
        val config = com.google.gson.JsonParser.parseString(rawConfig).asJsonObject
        val inbounds = config.get("inbounds")
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?: JsonArray()
        val socksInbound = inbounds.firstOrNull { inbound ->
            inbound.isJsonObject && inbound.asJsonObject.get("protocol")?.asString.equals("socks", ignoreCase = true)
        }?.asJsonObject

        val managedInbound = socksInbound ?: JsonObject().apply {
            addProperty("tag", "socks-in")
            addProperty("protocol", "socks")
        }.also { inbounds.add(it) }
        managedInbound.addProperty("port", inboundPort)
        managedInbound.addProperty("listen", listenAddress)
        val settings = managedInbound.get("settings")
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?: JsonObject().also { managedInbound.add("settings", it) }
        settings.addProperty("auth", "noauth")
        settings.addProperty("udp", udp)

        config.add("inbounds", inbounds)
        if (adBlocker) AdBlockingPolicy.applyToImported(config, managedInbound, adBlockMatcher, adBlockAllowlist)
        if (silent) {
            val log = JsonObject().apply { addProperty("loglevel", "none") }
            config.add("log", log)
        }
        gson.toJson(config)
    } catch (_: Exception) {
        null
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
        adBlockAllowlist: List<String> = emptyList()
    ): String {
        node?.fullConfigJson?.takeIf { it.isNotBlank() }?.let { rawConfig ->
            buildImportedFullConfig(
                rawConfig = rawConfig,
                inboundPort = SplateConstants.XRAY_SOCKS_PORT,
                listenAddress = if (shareLan) "0.0.0.0" else SplateConstants.XRAY_SOCKS_ADDRESS,
                udp = true,
                adBlocker = adBlocker,
                adBlockMatcher = adBlockMatcher,
                adBlockAllowlist = adBlockAllowlist
            )?.let { return it }
        }

        val config = JsonObject()

        // 1. Log: stream access logs to stdout for live connection tracking in app
        val log = JsonObject().apply {
            addProperty("loglevel", "warning")
            addProperty("access", "")
        }
        config.add("log", log)

        // Remote DNS endpoint (tunneled via proxy)
        // Standard DNS (UDP/TCP) is more reliable over Xray proxy than DoH
        val selectedSmartDns = SmartDnsProviders.forAi(aiDnsProvider)
        val generalSmartDns = SmartDnsProviders.find(dnsProvider)
        val remoteDnsUrl = generalSmartDns?.dohUrl ?: when (dnsProvider.lowercase()) {
            "google"  -> "8.8.8.8"
            "quad9"   -> "9.9.9.9"
            "adguard" -> "94.140.14.14"
            "yandex"  -> "tcp://77.88.8.8:53"
            "custom"  -> if (customDohUrl.isNotBlank()) customDohUrl.trim() else "1.1.1.1"
            else      -> "1.1.1.1" // Cloudflare default
        }

        val useSmartDns = bypassAi && aiRoutingMode != "vpn"
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

                // Direct DNS for RU domains — Yandex DNS bypasses Xray tunnel
                // Using tcp://77.88.8.8:53 prevents UDP transaction ID loss/duplication ("cannot find the pending request")
                val ruDns = JsonObject().apply {
                    addProperty("address", "tcp://77.88.8.8:53")
                    addProperty("port", 53)
                    val domains = JsonArray().apply {
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
                        // Resolve server address directly to avoid proxy loop
                        if (!node?.address.isNullOrBlank() && !isIpAddress(node!!.address)) {
                            add("domain:${node.address}")
                        }
                    }
                    add("domains", domains)
                }
                add(ruDns)

                // A user proxy override must not inherit a Smart DNS relay address.
                val proxyDnsDomains = customRoutes.filter { it.second == "proxy" }.map { it.first } +
                    if (bypassAi && !useSmartDns) aiProxyDomainList + aiDirectDomainList else emptyList()
                if (proxyDnsDomains.isNotEmpty()) {
                    add(JsonObject().apply {
                        addProperty("address", if (generalSmartDns != null) "1.1.1.1" else remoteDnsUrl)
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
                // Reliable TCP fallbacks
                add("tcp://8.8.8.8:53")
                add("tcp://1.1.1.1:53")
            }
            add("servers", servers)

            val protocol = node?.protocol?.lowercase() ?: "vless"
            val rawSec = node?.security?.lowercase()?.trim() ?: "none"
            val security: String = when {
                rawSec == "reality"              -> "reality"
                rawSec == "tls"                  -> "tls"
                rawSec == "none"                 -> "none"
                !node?.publicKey.isNullOrEmpty() -> "reality"
                node?.port == 443                -> "tls"
                else                             -> "none"
            }

            // Xray 26+ strictly prohibits VLESS with security="none" if address is a public IP or public domain.
            // Xray requires the address to be a private IP or private domain (.local, .internal, localhost).
            // We provide "server.local" mapped to the real server IP/address in dns.hosts so Xray validation passes.
            val vlessProxyDomain: String? = if (protocol == "vless" && security == "none" && !node?.address.isNullOrBlank()) {
                "server.local"
            } else null


            val hasTls = security == "tls" || security == "reality"
            val effectiveTlsFragment = enableTlsFragment && hasTls

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

                if (vlessProxyDomain != null && !node?.address.isNullOrBlank()) {
                    addProperty(vlessProxyDomain, node.address.trim())
                }
            }
            add("hosts", hosts)
            addProperty("queryStrategy", "UseIPv4")
            // Never replace a matched Smart DNS answer with a regular public IP.
            if (useSmartDns) addProperty("disableFallbackIfMatch", true)
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
            addProperty("port", SplateConstants.XRAY_SOCKS_PORT)
            addProperty("listen", if (shareLan) "0.0.0.0" else SplateConstants.XRAY_SOCKS_ADDRESS)
            addProperty("protocol", "socks")
            val settings = JsonObject().apply {
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

        // When LAN/Hotspot sharing is enabled, provide an HTTP proxy on 0.0.0.0
        // for seamless configuration on Windows, macOS, Linux, Android and iOS
        if (shareLan) {
            val httpLanInbound = JsonObject().apply {
                addProperty("tag", "http-in")
                addProperty("port", lanHttpPort)
                addProperty("listen", "0.0.0.0")
                addProperty("protocol", "http")
                val settings = JsonObject().apply {
                    addProperty("timeout", 300)
                    addProperty("allowTransparent", false)
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
        config.add("inbounds", inbounds)

        // 4. Outbounds
        val outbounds = JsonArray()
        val protocol = node?.protocol?.lowercase() ?: "vless"
        val rawSec = node?.security?.lowercase()?.trim() ?: "none"
        val security: String = when {
            rawSec == "reality"              -> "reality"
            rawSec == "tls"                  -> "tls"
            rawSec == "none"                 -> "none"
            !node?.publicKey.isNullOrEmpty() -> "reality"
            node?.port == 443                -> "tls"
            else                             -> "none"
        }
        val vlessProxyDomain: String? = if (protocol == "vless" && security == "none" && !node?.address.isNullOrBlank()) {
            "server.local"
        } else null
        val hasTls = security == "tls" || security == "reality"
        val effectiveTlsFragment = enableTlsFragment && hasTls

        val proxyOutbound = buildProxyOutbound(
            node = node,
            xhttpModeOverride = xhttpModeOverride,
            enableTlsFragment = effectiveTlsFragment,
            vlessProxyDomain = vlessProxyDomain,
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
                        addProperty("packets", tlsFragmentPackets.ifBlank { "tlshello" })
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
                    if (aiTlsFragment) add("fragment", JsonObject().apply {
                        addProperty("packets", "tlshello")
                        addProperty("length", "10-20")
                        addProperty("interval", "1-2")
                    })
                })
                add("streamSettings", JsonObject().apply {
                    // Preserve the relay selection: never fall back to OS DNS after a SmartDNS failure.
                    add("sockopt", JsonObject().apply { addProperty("domainStrategy", "ForceIPv4") })
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
            addProperty("domainStrategy", "IPIfNonMatch")
            val rules = JsonArray()

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
                    if (vlessProxyDomain != null) {
                        val domainArr = (get("domain") as? JsonArray) ?: JsonArray().also { add("domain", it) }
                        domainArr.add("domain:$vlessProxyDomain")
                    }
                }
                rules.add(nodeAddrRule)
            }

            // Filter before DNS/IP/direct exceptions; never allow ads through a RU bypass.
            if (adBlocker) AdBlockingPolicy.rule("block", adBlockMatcher, adBlockAllowlist)
                .takeIf { it.getAsJsonArray("domain").size() > 0 }?.let { rules.add(it) }

            // 5.2 Intercept DNS queries from TUN → internal Xray DNS resolver
            val dnsInterceptionRule = JsonObject().apply {
                addProperty("type", "field")
                val inboundArr = JsonArray().apply { add("socks-in") }
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

            // 5.3 Yandex DNS & Xbox DNS (RU direct) → direct
            val directYandexDnsRule = JsonObject().apply {
                addProperty("type", "field")
                addProperty("outboundTag", "direct")
                val ipArr = JsonArray().apply {
                    add("77.88.8.8")
                    add("77.88.8.1")
                    add("172.65.9.208")
                }
                add("ip", ipArr)
                addProperty("port", "53")
            }
            rules.add(directYandexDnsRule)

            // Route DoH by hostname alone: Xray combines domain and IP fields with AND.
            if (smartDnsInUse.isNotEmpty()) {
                val aiDnsRule = JsonObject().apply {
                    addProperty("type", "field")
                    addProperty("outboundTag", "direct")
                    addProperty("port", "443")
                    addProperty("network", "tcp")
                    val domainArr = JsonArray().apply {
                        smartDnsInUse.forEach { add("full:${it.host}") }
                    }
                    add("domain", domainArr)
                }
                rules.add(aiDnsRule)
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
                            addProperty("outboundTag", "proxy")
                            val domainArr = JsonArray().apply { add("domain:$customDnsHost") }
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
            rules.add(ntpRule)

            // 5.11 User custom domain routes (highest priority after DNS rules)
            val customDirect = customRoutes.filter { it.second == "direct" }.map { it.first }
            val customProxy = customRoutes.filter { it.second == "proxy" }.map { it.first }
            val customBlock = customRoutes.filter { it.second == "block" }.map { it.first }

            if (customDirect.isNotEmpty()) {
                val rule = JsonObject().apply {
                    addProperty("type", "field")
                    addProperty("outboundTag", "direct")
                    val arr = JsonArray().apply { customDirect.forEach { add(it) } }
                    add("domain", arr)
                }
                rules.add(rule)
            }
            if (customBlock.isNotEmpty()) {
                val rule = JsonObject().apply {
                    addProperty("type", "field")
                    addProperty("outboundTag", "block")
                    val arr = JsonArray().apply { customBlock.forEach { add(it) } }
                    add("domain", arr)
                }
                rules.add(rule)
            }
            if (customProxy.isNotEmpty()) {
                val rule = JsonObject().apply {
                    addProperty("type", "field")
                    addProperty("outboundTag", "proxy")
                    val arr = JsonArray().apply { customProxy.forEach { add(it) } }
                    add("domain", arr)
                }
                rules.add(rule)
            }

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

            val isLegacyStreamingTransport = node?.network?.lowercase() in listOf("ws", "grpc", "h2", "http")

            if (mode == "smart_ru") {
                // IP checkers & leak test services → proxy for accurate external IP display
                val proxyCheckerDomains = JsonObject().apply {
                    addProperty("type", "field")
                    addProperty("outboundTag", "proxy")
                    val domainArr = JsonArray().apply {
                        add("domain:2ip.io")
                        add("domain:2ip.ru")
                        add("domain:whoer.net")
                        add("domain:ipinfo.io")
                        add("domain:ipwho.is")
                        add("domain:api.ip.sb")
                        add("domain:ifconfig.me")
                        add("domain:checkip.amazonaws.com")
                        add("domain:browserleaks.com")
                        add("domain:dnsleaktest.com")
                        add("domain:ipleak.net")
                    }
                    add("domain", domainArr)
                }
                rules.add(proxyCheckerDomains)

                // RU domains → direct (no VPN overhead for local services)
                val directRuDomains = JsonObject().apply {
                    addProperty("type", "field")
                    addProperty("outboundTag", "direct")
                    val domainArr = JsonArray().apply {
                        add("geosite:category-ru")
                        add("geosite:tld-ru")
                        add("geosite:yandex")
                        add("geosite:vk")
                        add("geosite:mailru")
                        add("domain:heytapmobile.com")
                        add("domain:heytapdl.com")
                        add("domain:coloros.com")
                        add("domain:oppo.com")
                        add("domain:oneplus.com")
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

                // RU IPs & private LAN → direct
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

            // For legacy streaming transports (WS, gRPC, H2) that don't support UDP:
            // route remaining UDP directly to prevent UDP black hole
            if (isLegacyStreamingTransport) {
                val directRemainingUdp = JsonObject().apply {
                    addProperty("type", "field")
                    addProperty("network", "udp")
                    addProperty("outboundTag", "direct")
                }
                rules.add(directRemainingUdp)
            }

            // Default fallback → proxy
            val defaultProxyRule = JsonObject().apply {
                addProperty("type", "field")
                addProperty("network", if (isLegacyStreamingTransport) "tcp" else "tcp,udp")
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

        return gson.toJson(config)
    }

    private fun isIpAddress(address: String): Boolean {
        val trimmed = address.trim().removeSurrounding("[", "]")
        return trimmed.matches(Regex("""^(\d{1,3}\.){3}\d{1,3}$""")) || trimmed.contains(':')
    }

    private fun buildProxyOutbound(
        node: ServerNode?,
        xhttpModeOverride: String,
        enableTlsFragment: Boolean = false,
        vlessProxyDomain: String? = null,
        enableMux: Boolean = false,
        muxConcurrency: Int = 8
    ): JsonObject {
        val proxy = JsonObject()
        proxy.addProperty("tag", "proxy")

        val protocol = node?.protocol?.lowercase() ?: "vless"
        proxy.addProperty("protocol", protocol)

        val rawNet = node?.network?.lowercase()?.trim() ?: "tcp"
        val network: String = when (rawNet) {
            "splithttp", "xhttp" -> "xhttp"
            "websocket", "ws"    -> "ws"
            "h2", "http"         -> "h2"
            "grpc"               -> "grpc"
            else                 -> "tcp"
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
                    val targetAddress = vlessProxyDomain ?: (node?.address ?: "127.0.0.1")
                    addProperty("address", targetAddress)
                    addProperty("port", node?.port ?: 443)
                    val users = JsonArray()
                    val user = JsonObject().apply {
                        addProperty("id", node?.uuid ?: "00000000-0000-0000-0000-000000000000")
                        addProperty("encryption", if (node?.encryption.isNullOrEmpty()) "none" else node?.encryption)
                        // XTLS Vision flow only valid for TCP+Reality/TLS
                        if (!node?.flow.isNullOrEmpty()) {
                            if (network == "tcp" && (security == "reality" || security == "tls")) {
                                addProperty("flow", node?.flow)
                            }
                        } else if (security == "reality" && network == "tcp") {
                            addProperty("flow", "xtls-rprx-vision")
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
                if (network == "xhttp" || network == "h2") {
                    val alpnArr = JsonArray().apply {
                        add("h2")
                        add("http/1.1")
                    }
                    add("alpn", alpnArr)
                }
                // verifyPeerCertByName: when using camouflage SNI (e.g. dl.google.com),
                // verify TLS certificate against the real server host, not SNI.
                val realHost = if (!node?.host.isNullOrEmpty()) node.host else (node?.address ?: "")
                if (!node?.sni.isNullOrEmpty() && realHost.isNotBlank() && node?.sni != realHost) {
                    addProperty("verifyPeerCertByName", realHost)
                }
            }
            streamSettings.add("tlsSettings", tls)
        }

        // Socket-level optimizations for Xray 26.x
        val sockopt = JsonObject().apply {
            addProperty("domainStrategy", "AsIs")
            addProperty("tcpNoDelay", true)
            addProperty("tcpKeepAliveInterval", 15)
            if (enableTlsFragment) {
                addProperty("dialerProxy", "fragment")
            }
        }
        streamSettings.add("sockopt", sockopt)

        when (network) {
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
                    addProperty("idle_timeout", 60)
                    addProperty("health_check_timeout", 20)
                }
                streamSettings.add("grpcSettings", grpc)
            }
            "h2" -> {
                val http = JsonObject().apply {
                    val rawPath = if (node?.path.isNullOrEmpty()) "/" else node?.path!!
                    val normalizedPath = if (rawPath.startsWith("/")) rawPath else "/$rawPath"
                    val pathArr = JsonArray().apply { add(normalizedPath) }
                    add("path", pathArr)
                    val hostVal = when {
                        !node?.host.isNullOrEmpty() -> node?.host
                        !node?.sni.isNullOrEmpty()  -> node?.sni
                        else                         -> node?.address
                    }
                    if (!hostVal.isNullOrEmpty()) {
                        val hostArr = JsonArray().apply { add(hostVal) }
                        add("host", hostArr)
                    }
                }
                streamSettings.add("httpSettings", http)
            }
            "tcp" -> {
                // TCP with HTTP header camouflage (used in some VMess configs)
                if (!node?.path.isNullOrEmpty() || !node?.host.isNullOrEmpty()) {
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

        if (enableTlsFragment && (security == "tls" || security == "reality")) {
            val sockopt = streamSettings.getAsJsonObject("sockopt") ?: JsonObject().also {
                streamSettings.add("sockopt", it)
            }
            sockopt.addProperty("dialerProxy", "fragment")
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
    fun generateTestConfig(
        node: ServerNode,
        socksPort: Int,
        xhttpModeOverride: String = "auto",
        enableTlsFragment: Boolean = false,
        tlsFragmentLength: String = "100-200",
        tlsFragmentInterval: String = "10-20",
        tlsFragmentPackets: String = "tlshello",
        enableMux: Boolean = false,
        muxConcurrency: Int = 8
    ): String {
        node.fullConfigJson?.takeIf { it.isNotBlank() }?.let { rawConfig ->
            buildImportedFullConfig(
                rawConfig = rawConfig,
                inboundPort = socksPort,
                listenAddress = "127.0.0.1",
                udp = false,
                silent = true
            )?.let { return it }
        }

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
            val inSettings = JsonObject().apply {
                addProperty("auth", "noauth")
                addProperty("udp", false)
            }
            add("settings", inSettings)
        }
        inbounds.add(socksInbound)
        config.add("inbounds", inbounds)

        // 3. Outbounds: Target node proxy outbound + fragment + direct + dns-out
        val outbounds = JsonArray()
        val protocol = node.protocol.lowercase().trim().ifBlank { "vless" }
        val rawSec = node.security.lowercase().trim()
        val security: String = when {
            rawSec == "reality"           -> "reality"
            rawSec == "tls"               -> "tls"
            rawSec == "none"              -> "none"
            node.publicKey.isNotEmpty()   -> "reality"
            node.port == 443              -> "tls"
            else                          -> "none"
        }

        // Xray 26+ compatibility: map unencrypted VLESS to private virtual domain
        val vlessProxyDomain: String? = if (protocol == "vless" && security == "none" && node.address.isNotBlank()) {
            "server.local"
        } else null

        val effectiveTlsFragment = enableTlsFragment && (security == "tls" || security == "reality")
        val proxyOutbound = buildProxyOutbound(
            node = node,
            xhttpModeOverride = xhttpModeOverride,
            enableTlsFragment = effectiveTlsFragment,
            vlessProxyDomain = vlessProxyDomain,
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
                        addProperty("packets", tlsFragmentPackets.ifBlank { "tlshello" })
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
                add("https://1.1.1.1/dns-query")
                add("https://8.8.8.8/dns-query")
                add("tcp://77.88.8.8:53")
                add("77.88.8.8")
                add("1.1.1.1")
                add("8.8.8.8")
            }
            add("servers", servers)

            val hosts = JsonObject().apply {
                addProperty("cloudflare-dns.com", "1.1.1.1")
                addProperty("dns.google", "8.8.8.8")
                if (vlessProxyDomain != null && node.address.isNotBlank()) {
                    addProperty(vlessProxyDomain, node.address.trim())
                }
            }
            add("hosts", hosts)
            addProperty("queryStrategy", "UseIPv4")
        }
        config.add("dns", dns)

        // 5. Routing rules with valid Xray string types
        val routing = JsonObject().apply {
            addProperty("domainStrategy", "IPIfNonMatch")
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
                    add("77.88.8.8")
                    add("77.88.8.1")
                    add("1.1.1.1")
                    add("8.8.8.8")
                }
                add("ip", ipArr)
                addProperty("port", "53")
            }
            rules.add(directDnsRule)

            // 5.3 Target node address → direct to prevent routing loops
            if (node.address.isNotBlank()) {
                val directNodeRule = JsonObject().apply {
                    addProperty("type", "field")
                    addProperty("outboundTag", "direct")
                    if (isIpAddress(node.address)) {
                        val ipArr = JsonArray().apply { add(node.address) }
                        add("ip", ipArr)
                    } else {
                        val domainArr = JsonArray().apply { add("domain:${node.address}") }
                        add("domain", domainArr)
                    }
                    if (vlessProxyDomain != null) {
                        val domainArr = (get("domain") as? JsonArray) ?: JsonArray().also { add("domain", it) }
                        domainArr.add("domain:$vlessProxyDomain")
                    }
                }
                rules.add(directNodeRule)
            }

            // 5.4 Default rule: route incoming probe traffic to the proxy outbound
            val defaultProxyRule = JsonObject().apply {
                addProperty("type", "field")
                addProperty("outboundTag", "proxy")
                addProperty("network", "tcp,udp")
            }
            rules.add(defaultProxyRule)

            add("rules", rules)
        }
        config.add("routing", routing)

        return gson.toJson(config)
    }
}
