package com.danieldk.splatevpn.vpn

import com.danieldk.splatevpn.SplateConstants
import com.danieldk.splatevpn.data.ServerNode
import com.danieldk.splatevpn.data.SmartDnsProviders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Optional integration check against a staged matching-ABI core on Android.
 * Set SPLATE_XRAY_SMOKE_ADB to adb's path; no app installation or VPN connection occurs.
 */
class XrayNativeCompatibilityTest {
    @Test
    fun generatedConfigsPassNativeCoreValidation() {
        val adb = System.getenv("SPLATE_XRAY_SMOKE_ADB")
        assumeTrue("Requires an Android device or emulator and a staged core", !adb.isNullOrBlank())
        val deviceDir = "/data/local/tmp/splate-core-check"

        fun command(vararg args: String): String {
            val process = ProcessBuilder(listOf(adb!!) + args).redirectErrorStream(true).start()
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw AssertionError("Native validation timed out: ${args.joinToString(" ")}")
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals(output, 0, process.exitValue())
            return output
        }

        val version = command("shell", "$deviceDir/xray-new", "version")
        assertTrue(version, version.contains("Xray ${SplateConstants.XRAY_VERSION} "))

        val base = ServerNode(
            id = "native-smoke", name = "Native smoke test", protocol = "vless",
            address = "vpn.example.com", port = 443,
            uuid = "11111111-2222-3333-4444-555555555555", security = "tls", sni = "example.com"
        )
        val nodes = linkedMapOf(
            "vless-tls" to base,
            "vless-reality" to base.copy(
                security = "reality", flow = "xtls-rprx-vision",
                publicKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", shortId = "abcd"
            ),
            "vless-xhttp" to base.copy(network = "xhttp", path = "/xhttp"),
            "vless-ws" to base.copy(network = "ws", path = "/ws"),
            "vless-grpc" to base.copy(network = "grpc", path = "smoke"),
            "vmess" to base.copy(protocol = "vmess", encryption = "auto"),
            "trojan" to base.copy(protocol = "trojan", uuid = "test-password"),
            "shadowsocks" to base.copy(protocol = "shadowsocks", security = "none", uuid = "test-password", encryption = "aes-128-gcm")
        )
        val generator = XrayConfigGenerator()
        val configs = nodes.mapValues { (_, node) -> generator.generateConfig(node) }.toMutableMap()
        configs["private-dns-checker"] = generator.generateConfig(base, dnsProbePort = SplateConstants.DNS_CHECK_SOCKS_PORT)
        val verificationKey = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(1952) { (it % 251).toByte() })
        configs["vless-reality-pq"] = generator.generateConfig(nodes.getValue("vless-reality").copy(
            rawLink = "vless://test@vpn.example.com:443?pqv=$verificationKey"
        ))
        val standardKey = java.util.Base64.getEncoder().encodeToString(java.util.Base64.getUrlDecoder().decode(verificationKey))
        configs["vless-reality-pq-padded"] = generator.generateConfig(nodes.getValue("vless-reality").copy(
            rawLink = "vless://test@vpn.example.com:443?pqv=$verificationKey="
        ))
        configs["vless-reality-pq-standard"] = generator.generateConfig(nodes.getValue("vless-reality").copy(
            rawLink = "vless://test@vpn.example.com:443?pqv=$standardKey"
        ))
        val importedPq = com.google.gson.JsonParser.parseString(configs.getValue("vless-reality-pq")).asJsonObject
        importedPq.getAsJsonArray("outbounds").first { it.asJsonObject["tag"].asString == "proxy" }
            .asJsonObject.getAsJsonObject("streamSettings").getAsJsonObject("realitySettings").addProperty("mldsa65Verify", standardKey)
        configs["imported-reality-pq-standard"] = generator.generateConfig(nodes.getValue("vless-reality").copy(fullConfigJson = importedPq.toString()))
        nodes.forEach { (name, node) ->
            configs["ai-tunnel-$name"] = generator.generateConfig(node, aiRoutingMode = "dns_vpn", aiTlsFragment = true)
        }
        configs["vless-fragment-lan"] = generator.generateConfig(base, enableTlsFragment = true, shareLan = true, lanAccess = LanAccess("192.168.43.1", "splate", "test-password"))
        for (name in listOf("vless-tls", "vless-reality", "vless-xhttp")) {
            val node = nodes.getValue(name)
            configs["fragment-standard-$name"] = generator.generateTestConfig(node, 25001,
                enableTlsFragment = true, tlsFragmentLength = "100-200", tlsFragmentInterval = "10-20", tlsFragmentPackets = "tlshello")
            configs["fragment-aggressive-$name"] = generator.generateTestConfig(node, 25001,
                enableTlsFragment = true, tlsFragmentLength = "50-100", tlsFragmentInterval = "20-40", tlsFragmentPackets = "1-3")
        }
        configs["lan-no-password"] = generator.generateConfig(base, shareLan = true,
            lanAccess = LanAccess("192.168.43.1", "", "", authentication = false))
        configs["mobile-xhttp-max"] = generator.generateConfig(base.copy(address = "192.0.2.25", network = "xhttp", sni = "max.ru", path = "/mobile", mode = "packet-up"), mode = "global", bypassAi = false)
        configs["vless-fakedns-mux"] = generator.generateConfig(base, isFakeDns = true, enableMux = true)
        configs["imported-managed"] = generator.generateConfig(base.copy(fullConfigJson = generator.generateConfig(base)), mode = "global", bypassAi = false)
        configs["imported-test"] = generator.generateTestConfig(base.copy(fullConfigJson = generator.generateConfig(base)), 25001)
        configs["vless-global"] = generator.generateConfig(base, mode = "global")
        listOf("cloudflare", "google", "quad9", "adguard", "yandex", "custom").forEach { provider ->
            configs["selected-dns-$provider"] = generator.generateConfig(base, dnsProvider = provider,
                customDohUrl = "https://resolver.example/dns-query", bypassAi = false)
        }
        configs["ai-vpn-dns-ai-provider"] = generator.generateConfig(base, aiRoutingMode = "vpn", dnsProvider = "dns_ai")
        configs["ai-quic-disabled-globally"] = generator.generateConfig(base, blockQuic = false)
        configs["ai-optional-fragment"] = generator.generateConfig(base, aiDnsProvider = "malw_gateway", aiTlsFragment = true)
        configs["fake-dns-sniffing-conflict"] = generator.generateConfig(base, isFakeDns = true, enableSniffing = false, routeOnlySniffing = true)
        SmartDnsProviders.all.forEach { provider ->
            configs["ai-tunnel-${provider.key}"] = generator.generateConfig(base, aiRoutingMode = "dns_vpn", aiDnsProvider = provider.key)
            listOf(false, true).forEach { fragment ->
                configs["auto-dns-${provider.key}-$fragment"] = generator.generateAiDnsProbeConfig(provider.key, fragment, 29081)
            }
            configs["ai-dns-${provider.key}"] = generator.generateConfig(base, aiDnsProvider = provider.key)
            configs["general-dns-${provider.key}"] = generator.generateConfig(base, dnsProvider = provider.key, bypassAi = false)
            configs["ai-vpn-${provider.key}"] = generator.generateConfig(base, dnsProvider = provider.key, aiRoutingMode = "vpn")
        }

        com.danieldk.splatevpn.data.RoutingRegion.entries.forEach { region ->
            configs["smart-${region.id}"] = generator.generateConfig(base, mode = "smart", routingRegion = region.id, bypassAi = false)
            configs["global-${region.id}"] = generator.generateConfig(base.copy(network = "ws"), mode = "global", routingRegion = region.id, bypassAi = false)
        }

        val configFile = Files.createTempFile("splate-xray-smoke-", ".json").toFile()
        try {
            for ((name, config) in configs) {
                configFile.writeText(config)
                command("push", configFile.absolutePath, "$deviceDir/smoke.json")
                val output = command(
                    "shell", "env", "XRAY_LOCATION_ASSET=$deviceDir", "$deviceDir/xray-new",
                    "run", "-test", "-c", "$deviceDir/smoke.json"
                )
                assertTrue("$name: $output", output.contains("Configuration OK"))
            }
        } finally {
            configFile.delete()
        }
    }
}
