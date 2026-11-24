package com.danieldk.splatevpn.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.vpn.*
import com.google.gson.JsonParser
import com.google.gson.JsonObject
import com.google.gson.JsonArray
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.File
import java.net.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Real Android Xray client -> Xray VPN server -> SOCKS recorder.
 * Uses loopback only: no public provider, credentials, VPN service or user settings.
 */
class AiDnsTunnelTest {
    /** Separates REALITY negotiation from DNS: the HTTPS target is a numeric IP. */
    @Test(timeout = 120000) fun liveRealityHandshake() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveDnsChain") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val parsedNode = requireNotNull(LinkParser.parse(File("/data/local/tmp/splate_dns_chain_link.txt").readText().trim()))
        val relayPort = InstrumentationRegistry.getArguments().getString("relayPort")?.toIntOrNull()
        val node = if (relayPort != null) parsedNode.copy(address = "10.0.2.2", port = relayPort) else parsedNode
        val core = XrayCoreManager(context, AppLogManager(context), VpnStatusManager(context), LiveConnectionTracker(), com.danieldk.splatevpn.vpn.testHealthAdvisor(context, VpnStatusManager(context)))
        core.ensureAssetsExtracted()
        var success = false
        for (fingerprint in listOf(node.fingerprint, "hellofirefox_148", "hellochrome_133", "hellochrome_131")) {
            val port = freePort()
            val config = JsonParser.parseString(XrayConfigGenerator().generateConfig(node.copy(fingerprint = fingerprint),
                mode = "global", bypassAi = false, adBlocker = false)).asJsonObject
            config.getAsJsonArray("inbounds").single().asJsonObject.addProperty("port", port)
            withCore(requireNotNull(core.getXrayBinary()), context, config.toString(), port) {
                val client = OkHttpClient.Builder()
                    .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
                    .callTimeout(12, TimeUnit.SECONDS).build()
                try {
                    val outcome = runCatching {
                        client.newCall(Request.Builder().url("https://1.1.1.1/cdn-cgi/trace").build()).execute().use {
                            if (it.isSuccessful) success = true
                            "HTTP=${it.code} ${it.body!!.string().take(1200)}"
                        }
                    }.getOrElse { "FAILED:${it.javaClass.simpleName}" }
                    android.util.Log.i("DnsChainProbe", "handshake $fingerprint $outcome")
                } finally { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
            }
        }
        assertTrue("No successful REALITY tunnel, independent of DNS resolution", success)
    }

    /** Opt-in live diagnostic. Credentials are supplied in an external temporary file. */
    @Test(timeout = 240000) fun liveDnsChain() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveDnsChain") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val inputFile = File("/data/local/tmp/splate_dns_chain_link.txt")
        val parsedNode = requireNotNull(LinkParser.parse(inputFile.readText().trim()))
        val relayPort = InstrumentationRegistry.getArguments().getString("relayPort")?.toIntOrNull()
        val node = if (relayPort != null) parsedNode.copy(address = "10.0.2.2", port = relayPort) else parsedNode
        val core = XrayCoreManager(context, AppLogManager(context), VpnStatusManager(context), LiveConnectionTracker(), com.danieldk.splatevpn.vpn.testHealthAdvisor(context, VpnStatusManager(context)))
        core.ensureAssetsExtracted()
        var workingTunnel = false
        val variants = InstrumentationRegistry.getArguments().getString("variants")?.split(',')
            ?: listOf("baseline", "local-target", "doh-local-target")
        for (variant in variants) {
            val port = freePort()
            val config = JsonParser.parseString(XrayConfigGenerator().generateConfig(node,
                mode = "global", dnsProvider = "cloudflare", bypassAi = false, adBlocker = false)).asJsonObject
            if (InstrumentationRegistry.getArguments().getString("debugDnsChain") == "true")
                config.getAsJsonObject("log").addProperty("loglevel", "debug")
            config.getAsJsonArray("inbounds").single().asJsonObject.addProperty("port", port)
            config.getAsJsonArray("outbounds")
                .first { it.asJsonObject["tag"].asString == "proxy" }.asJsonObject
                .addProperty("targetStrategy", if (variant == "baseline") "AsIs" else "ForceIP")
            if (variant.startsWith("doh")) {
                val servers = config.getAsJsonObject("dns").getAsJsonArray("servers")
                val mapped = JsonArray()
                var added = false
                servers.forEach { server ->
                    if (server.isJsonPrimitive) {
                        if (!added) { mapped.add("https://1.1.1.1/dns-query"); added = true }
                    } else mapped.add(server)
                }
                config.getAsJsonObject("dns").add("servers", mapped)
            }
            withCore(requireNotNull(core.getXrayBinary()), context, config.toString(), port) {
                val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
                val dnsResult = runCatching {
                    val query = AiDnsWire.query("whoami.akamai.net")
                    Socket(proxy).use { socket ->
                        socket.soTimeout = 20000
                        socket.connect(InetSocketAddress("172.19.0.2", 53), 10000)
                        val output = java.io.DataOutputStream(socket.getOutputStream())
                        output.writeShort(query.size); output.write(query); output.flush()
                        val input = DataInputStream(socket.getInputStream())
                        AiDnsWire.addresses(ByteArray(input.readUnsignedShort()).also(input::readFully), query).joinToString()
                    }
                }.getOrElse { "FAILED:${it.javaClass.simpleName}" }
                android.util.Log.i("DnsChainProbe", "$variant DNS-wire=$dnsResult")
                val client = OkHttpClient.Builder().proxy(proxy).callTimeout(25, TimeUnit.SECONDS).build()
                try {
                    if (InstrumentationRegistry.getArguments().getString("leakSite") == "true") {
                        val identifiers = (1..6).map { java.util.UUID.randomUUID().toString() }
                        fun sitePost(path: String, key: String): String {
                            val body = JsonObject().apply { add(key, JsonArray().apply { identifiers.forEach { add(it) } }) }
                            return client.newCall(Request.Builder().url("https://www.dnsleaktest.com/api/v1/$path")
                                .post(body.toString().toRequestBody("application/json".toMediaType())).build()).execute().use {
                                check(it.isSuccessful) { "DNS leak API HTTP ${it.code}" }
                                it.body!!.string()
                            }
                        }
                        sitePost("identifiers", "identifiers")
                        for (id in identifiers) runCatching {
                            val scheme = if (variant.endsWith("-http")) "http" else "https"
                            val call = client.newCall(Request.Builder().url("$scheme://$id.test.dnsleaktest.com/").build())
                            call.timeout().timeout(5, TimeUnit.SECONDS)
                            call.execute().close()
                        }
                        android.util.Log.i("DnsChainProbe", "$variant dnsleaktest.com " + sitePost("servers-for-result", "queries"))
                    }
                    for (url in listOf("http://edns.ip-api.com/json", "https://edns.ip-api.com/json", "https://www.cloudflare.com/cdn-cgi/trace")) {
                        val result = runCatching {
                            client.newCall(Request.Builder().url(url).build()).execute().use {
                                if (it.isSuccessful) workingTunnel = true
                                "HTTP=${it.code} ${it.body!!.string().take(1800)}"
                            }
                        }.getOrElse { "FAILED:${it.javaClass.simpleName}" }
                        android.util.Log.i("DnsChainProbe", "$variant ${URI(url).host} $result")
                    }
                } finally { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
            }
        }
        assertTrue("No working VPN HTTP request; DNS comparisons cannot verify this chain", workingTunnel)
    }

    /** Native resolver selection test. Only resolver transport endpoints are replaced
     * with loopback stubs; generated domain scopes and fallback flags stay intact. */
    @Test(timeout = 120000) fun selectedDnsStaysIsolatedWhenResolversFail() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val core = XrayCoreManager(context, AppLogManager(context), VpnStatusManager(context), LiveConnectionTracker(), com.danieldk.splatevpn.vpn.testHealthAdvisor(context, VpnStatusManager(context)))
        core.ensureAssetsExtracted()
        data class Case(val host: String, val failing: Boolean, val expectedProvider: String,
            val aiMode: String? = null, val generalProvider: String = "cloudflare")
        val cases = mutableListOf(
            Case("probe.splate-dns-test.invalid", false, "cloudflare"),
            Case("probe.splate-dns-test.invalid", true, "cloudflare"),
            Case("gosuslugi.ru", true, "yandex")
        )
        for (mode in listOf("dns_ai", "dns_vpn")) {
            cases += Case("probe.splate-dns-test.invalid", false, "cloudflare", mode)
            cases += Case("probe.splate-dns-test.invalid", true, "cloudflare", mode)
            cases += Case("gemini.google.com", false, "ai-smartdns", mode)
            cases += Case("gemini.google.com", true, "ai-smartdns", mode)
            cases += Case("gemini.google.com", true, "ai-smartdns", mode, "geohide")
        }
        for ((host, failing, expectedProvider, aiMode, generalProvider) in cases) {
            val seen = java.util.Collections.synchronizedList(mutableListOf<String>())
            val listeners = mutableListOf<ServerSocket>()
            try {
                val node = ServerNode("dns-test", "dns-test", "vless", "127.0.0.1", 9,
                    uuid = "11111111-2222-3333-4444-555555555555")
                val config = JsonParser.parseString(XrayConfigGenerator().generateConfig(node,
                    dnsProvider = generalProvider, bypassAi = aiMode != null,
                    aiRoutingMode = aiMode ?: "vpn", aiDnsProvider = "dns_ai", adBlocker = false)).asJsonObject
                val dns = config.getAsJsonObject("dns")
                val mapped = JsonArray()
                for (entry in dns.getAsJsonArray("servers")) {
                    val address = if (entry.isJsonObject) entry.asJsonObject["address"].asString else entry.asString
                    val provider = when {
                        address == SmartDnsProviders.forAi("dns_ai").dohUrl -> "ai-smartdns"
                        address.contains("77.88.") -> "yandex"
                        address.contains("1.1.1.1") -> "cloudflare"
                        else -> "other:$address"
                    }
                    val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
                    listeners += listener
                    thread(isDaemon = true) {
                        while (!listener.isClosed) {
                            val socket = runCatching { listener.accept() }.getOrNull() ?: break
                            thread(isDaemon = true) {
                                runCatching { socket.use {
                                    it.soTimeout = 10000
                                    val input = DataInputStream(it.getInputStream())
                                    val output = java.io.DataOutputStream(it.getOutputStream())
                                    while (true) {
                                        val query = ByteArray(input.readUnsignedShort()).also(input::readFully)
                                        seen += provider
                                        // Only the intended resolver fails. Every other resolver
                                        // would return a valid answer, exposing an accidental fallback.
                                        val shouldFail = failing && provider == expectedProvider
                                        val response = query.copyOf().apply {
                                            this[2] = 0x81.toByte()
                                            this[3] = if (shouldFail) 0x82.toByte() else 0x80.toByte()
                                            this[7] = if (shouldFail) 0 else 1
                                        } + if (shouldFail) byteArrayOf() else byteArrayOf(
                                            0xc0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 30, 0, 4, 203.toByte(), 0, 113, 42)
                                        output.writeShort(response.size); output.write(response); output.flush()
                                    }
                                } }
                            }
                        }
                    }
                    val replacement = if (entry.isJsonObject) entry.asJsonObject.deepCopy() else JsonObject()
                    replacement.addProperty("address", "tcp://127.0.0.1:${listener.localPort}")
                    replacement.remove("port")
                    mapped.add(replacement)
                }
                dns.add("servers", mapped)
                val port = freePort()
                config.getAsJsonArray("inbounds").single().asJsonObject.addProperty("port", port)
                val rules = JsonArray().apply {
                    add(JsonParser.parseString("""{"type":"field","ip":["127.0.0.1"],"outboundTag":"direct"}"""))
                    config.getAsJsonObject("routing").getAsJsonArray("rules").forEach { add(it) }
                }
                config.getAsJsonObject("routing").add("rules", rules)
                withCore(requireNotNull(core.getXrayBinary()), context, config.toString(), port) {
                    val query = AiDnsWire.query(host)
                    val result = runCatching {
                        Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))).use { socket ->
                            socket.soTimeout = 15000
                            socket.connect(InetSocketAddress("172.19.0.2", 53), 5000)
                            val output = java.io.DataOutputStream(socket.getOutputStream())
                            output.writeShort(query.size); output.write(query); output.flush()
                            val input = DataInputStream(socket.getInputStream())
                            AiDnsWire.addresses(ByteArray(input.readUnsignedShort()).also(input::readFully), query)
                        }
                    }
                    if (failing) assertTrue("Failed resolver must not produce another provider's answer", result.isFailure)
                    else assertEquals(listOf("203.0.113.42"), result.getOrThrow())
                    assertTrue("No query received for $host", seen.isNotEmpty())
                    assertEquals("Unexpected provider for $host/$aiMode/$generalProvider: $seen", setOf(expectedProvider), seen.toSet())
                }
            } finally { listeners.forEach { it.close() } }
        }
    }

    /** Explicit public-network diagnostic; reads the current server, never applies settings. */
    @Test fun liveSelectedVpn() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("liveAiTunnel") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = EntryPointAccessors.fromApplication(context, SubscriptionUpdateWorker.WorkerDependencies::class.java).serverRepository()
        val node = repository.allServers.first().firstOrNull { it.isSelected }
        assumeTrue("Requires a selected ordinary server", node != null && node.fullConfigJson.isNullOrBlank())
        val settings = SettingsManager(context)
        val core = XrayCoreManager(context, AppLogManager(context), VpnStatusManager(context), LiveConnectionTracker(), com.danieldk.splatevpn.vpn.testHealthAdvisor(context, VpnStatusManager(context)))
        core.ensureAssetsExtracted()
        val providers = args.getString("providers")?.split(',') ?: listOf("geohide", "dns_ai", "zapret_dns")
        for (key in providers) {
            val port = freePort()
            val config = JsonParser.parseString(XrayConfigGenerator().generateConfig(node,
                mode = "global", aiRoutingMode = "dns_vpn", aiDnsProvider = key, adBlocker = false,
                xhttpModeOverride = settings.getXhttpMode(), enableTlsFragment = settings.isTlsFragmentEnabled(),
                tlsFragmentLength = settings.getTlsFragmentLength(), tlsFragmentInterval = settings.getTlsFragmentInterval(),
                tlsFragmentPackets = settings.getTlsFragmentPackets())).asJsonObject
            config.getAsJsonArray("inbounds").single().asJsonObject.addProperty("port", port)
            withCore(requireNotNull(core.getXrayBinary()), context, config.toString(), port) {
                val client = OkHttpClient.Builder()
                    .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
                    .connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
                    .callTimeout(15, TimeUnit.SECONDS).followRedirects(false).build()
                try {
                    for ((service, url) in AiDnsAutoSelector.targets) {
                        val outcome = runCatching {
                            val query = AiDnsWire.query(URI(url).host)
                            client.newCall(Request.Builder().url(SmartDnsProviders.forAi(key).dohUrl)
                                .post(query.toRequestBody("application/dns-message".toMediaType()))
                                .header("Accept", "application/dns-message").build()).execute().use { response ->
                                check(response.isSuccessful)
                                AiDnsWire.addresses(response.body!!.bytes(), query)
                            }
                            client.newCall(Request.Builder().url(url)
                                .header("User-Agent", "Mozilla/5.0 (Android) SplateVPN/AiDnsCheck").build()).execute().use { response ->
                                "${classifyAiDnsResponse(response.code, response.peekBody(512 * 1024).string(), response.header("Location"), response.header("cf-mitigated") == "challenge")} HTTP=${response.code}"
                            }
                        }.getOrElse { "FAILED ${it.javaClass.simpleName}" }
                        android.util.Log.i("AiTunnelProbe", "$key ${node!!.network} $service $outcome")
                        if (service == "Gemini" && args.getString("renderGemini") == "true") {
                            val rendered = runBlocking { verifyGeminiPage(context, port) }
                            android.util.Log.i("AiTunnelProbe", "$key ${node.network} Gemini-JS ${rendered.outcome} ${rendered.detail}")
                        }
                    }
                } finally { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
            }
        }
    }

    @Test fun modeSurvivesSettingsReload() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val context = object : ContextWrapper(app) {
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("ai_tunnel_test_$name", mode)
        }
        try {
            val settings = SettingsManager(context)
            assertEquals("dns_ai", settings.getAiRoutingMode())
            for (mode in listOf("dns_vpn", "vpn", "dns_ai")) {
                settings.setAiRoutingMode(mode)
                assertEquals(mode, settings.aiRoutingModeFlow.value)
                assertEquals(mode, SettingsManager(context).getAiRoutingMode())
            }
            settings.setAiRoutingMode("unknown")
            assertEquals("dns_ai", settings.getAiRoutingMode())
        } finally { app.deleteSharedPreferences("ai_tunnel_test_splate_prefs") }
    }

    @Test(timeout = 60000) fun dohAndResolvedRelayTravelThroughTcpAndXhttp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val core = XrayCoreManager(context, AppLogManager(context), VpnStatusManager(context), LiveConnectionTracker(), com.danieldk.splatevpn.vpn.testHealthAdvisor(context, VpnStatusManager(context)))
        core.ensureAssetsExtracted()
        val binary = requireNotNull(core.getXrayBinary())
        val uuid = "11111111-2222-3333-4444-555555555555"
        val provider = SmartDnsProviders.forAi("geohide")
        val marker = "splate-ai-tunnel-ok\n".toByteArray()

        for (transport in listOf("tcp", "xhttp")) {
            val destinations = LinkedBlockingQueue<String>()
            ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { recorder ->
                recorder.soTimeout = 20000
                val worker = thread(isDaemon = true, name = "ai-tunnel-recorder") {
                    try {
                        repeat(3) {
                            recorder.accept().use { socket ->
                                socket.soTimeout = 10000
                                val input = DataInputStream(socket.getInputStream())
                                val output = socket.getOutputStream()
                                check(input.readUnsignedByte() == 5)
                                val methods = ByteArray(input.readUnsignedByte()); input.readFully(methods)
                                output.write(byteArrayOf(5, 0)); output.flush()
                                check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 1)
                                input.readUnsignedByte()
                                val host = when (input.readUnsignedByte()) {
                                    1 -> ByteArray(4).also(input::readFully).joinToString(".") { (it.toInt() and 255).toString() }
                                    3 -> String(ByteArray(input.readUnsignedByte()).also(input::readFully), Charsets.UTF_8)
                                    else -> error("Unexpected SOCKS address type")
                                }
                                val port = input.readUnsignedShort()
                                destinations.offer("$host:$port")
                                output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 0)); output.flush()
                                val data = ByteArray(marker.size); input.readFully(data)
                                check(marker.contentEquals(data))
                                output.write(data); output.flush()
                            }
                        }
                    } catch (e: Exception) { destinations.offer("ERROR:${e.javaClass.simpleName}") }
                }
                val serverPort = freePort()
                val clientPort = freePort()
                val stream = if (transport == "xhttp")
                    """"network":"xhttp","xhttpSettings":{"path":"/ai-test","mode":"auto"}""" else """"network":"tcp""""
                val serverConfig = """{
                    "log":{"loglevel":"debug"},
                    "inbounds":[{"listen":"127.0.0.1","port":$serverPort,"protocol":"vless",
                        "settings":{"clients":[{"id":"$uuid"}],"decryption":"none"},
                        "streamSettings":{$stream}}],
                    "outbounds":[{"protocol":"socks","settings":{"servers":[{"address":"127.0.0.1","port":${recorder.localPort}}]}}]
                }"""
                val node = ServerNode("test", "test", "vless", "127.0.0.1", serverPort,
                    uuid = uuid, network = transport, path = if (transport == "xhttp") "/ai-test" else "")
                val config = JsonParser.parseString(XrayConfigGenerator().generateConfig(node,
                    aiRoutingMode = "dns_vpn", aiDnsProvider = provider.key, adBlocker = false)).asJsonObject
                config.getAsJsonObject("log").addProperty("loglevel", "debug")
                // The plaintext VLESS fixture uses a numeric loopback listener,
                // without the app's server.local alias for public plaintext nodes.
                config.getAsJsonArray("outbounds").first { it.asJsonObject["tag"].asString == "proxy" }
                    .asJsonObject.getAsJsonObject("settings").getAsJsonArray("vnext")[0]
                    .asJsonObject.addProperty("address", "127.0.0.1")
                config.getAsJsonArray("inbounds").single().asJsonObject.addProperty("port", clientPort)
                // A synthetic SmartDNS answer, to prove the VPN receives the relay IP,
                // not the AI hostname which would be resolved again at the VPN server.
                config.getAsJsonObject("dns").getAsJsonObject("hosts").addProperty("gemini.google.com", "203.0.113.9")
                config.getAsJsonObject("dns").getAsJsonObject("hosts").addProperty("ordinary.splate-dns.invalid", "203.0.113.10")
                withCore(binary, context, serverConfig, serverPort) {
                    withCore(binary, context, config.toString(), clientPort) {
                        for ((host, expected) in listOf("gemini.google.com" to "203.0.113.9:443",
                            "ordinary.splate-dns.invalid" to "203.0.113.10:443", provider.host to "${provider.bootstrapIpv4.first()}:443")) {
                            android.util.Log.i("AiTunnelTest", "Testing $transport $host")
                            Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", clientPort))).use { socket ->
                                socket.soTimeout = 10000
                                socket.connect(InetSocketAddress.createUnresolved(host, 443), 10000)
                                socket.getOutputStream().write(marker)
                                val destination = destinations.poll(10, TimeUnit.SECONDS)
                                if (host == provider.host) assertTrue("$transport: $destination",
                                    provider.bootstrapIpv4.any { "$it:443" == destination })
                                else assertEquals(transport, expected, destination)
                                val echo = ByteArray(marker.size)
                                DataInputStream(socket.getInputStream()).readFully(echo)
                                assertArrayEquals(transport, marker, echo)
                            }
                        }
                    }
                }
                worker.join(1000)
            }
        }
    }

    private fun freePort() = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    private fun withCore(binary: File, context: Context, config: String, port: Int, block: () -> Unit) {
        val file = File.createTempFile("ai_tunnel_test_", ".json", context.cacheDir)
        var process: Process? = null
        try {
            file.writeText(config)
            val builder = ProcessBuilder(binary.absolutePath, "run", "-c", file.absolutePath).redirectErrorStream(true)
            builder.environment()["XRAY_LOCATION_ASSET"] = context.filesDir.absolutePath
            val child = builder.start()
            process = child
            thread(isDaemon = true) { runCatching { child.inputStream.bufferedReader().use { reader ->
                reader.forEachLine { android.util.Log.d("AiTunnelCore", it) }
            } } }
            // A freshly booted emulator can take longer to load the geodata files.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            var ready = false
            while (child.isAlive && System.nanoTime() < deadline) {
                ready = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 100) }; true }.getOrDefault(false)
                if (ready) break
                Thread.sleep(50)
            }
            assertTrue("Xray listener did not start", ready && child.isAlive)
            block()
        } finally {
            process?.let { it.destroy(); if (!it.waitFor(500, TimeUnit.MILLISECONDS)) it.destroyForcibly() }
            file.delete()
        }
    }
}
