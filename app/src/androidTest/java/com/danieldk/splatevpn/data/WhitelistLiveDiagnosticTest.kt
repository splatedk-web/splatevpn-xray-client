package com.danieldk.splatevpn.data

import android.content.Context
import android.content.ContextWrapper
import android.app.ActivityManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.vpn.LiveConnectionTracker
import com.danieldk.splatevpn.vpn.SplateVpnService
import com.danieldk.splatevpn.vpn.TunnelProbe
import com.danieldk.splatevpn.vpn.VpnStatusManager
import com.danieldk.splatevpn.vpn.XrayConfigGenerator
import com.danieldk.splatevpn.vpn.XrayCoreManager
import com.danieldk.splatevpn.vpn.testHealthAdvisor
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Explicit network diagnostic. Never runs on a person's device or as part of ordinary CI. */
class WhitelistLiveDiagnosticTest {
    @Test fun sampledBundledSourcesCarryRealHttpsTraffic() = runBlocking {
        assumeTrue("Pass -e run_live_whitelist_checks true to enable public proxy probes",
            InstrumentationRegistry.getArguments().getString("run_live_whitelist_checks") == "true")
        assumeTrue("Use an isolated Android emulator", Build.HARDWARE in setOf("ranchu", "goldfish"))
        assumeFalse("Do not probe while Splate VPN is active", SplateVpnService.isServiceActive)
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val serviceRunning = (app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
            .getRunningServices(Int.MAX_VALUE).any { it.service.className == SplateVpnService::class.java.name }
        assumeFalse("Do not interrupt an in-progress Splate connection", serviceRunning)
        val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        val vpnPresent = connectivity.allNetworks.any {
            connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
        assumeFalse("Do not run this diagnostic through another VPN", vpnPresent)

        val directory = File(app.cacheDir, "whitelist-live-${UUID.randomUUID()}").apply { check(mkdir()) }
        val preferences = ConcurrentHashMap.newKeySet<String>()
        val prefix = directory.name
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(directory, "private").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                preferences += name
                return app.getSharedPreferences("$prefix-$name", mode)
            }
        }
        val report = JsonObject().apply {
            addProperty("checkedAtUtc", Instant.now().toString())
            addProperty("sampleLimitPerSource", 12)
            addProperty("maxConcurrentProbes", 4)
        }
        val sourceReports = JsonArray()
        report.add("sources", sourceReports)
        val destination = File(app.getExternalFilesDir(null) ?: app.filesDir, "whitelist-live-qa.json")
        fun saveReport() = destination.writeText(GsonBuilder().setPrettyPrinting().create().toJson(report))
        try {
            val baseline = TunnelProbe.measure(Proxy.NO_PROXY, PRIMARY_204, 8_000, fallbackUrl = FALLBACK_204)
            report.addProperty("directHttps204DelayMs", baseline)
            saveReport()
            assertTrue("Direct HTTPS 204 baseline failed; public proxy results would be inconclusive. See whitelist-live-qa.json", baseline > 0)

            val status = VpnStatusManager(context)
            val core = XrayCoreManager(context, AppLogManager(context), status, LiveConnectionTracker(),
                testHealthAdvisor(context, status))
            withContext(Dispatchers.IO) { core.ensureAssetsExtracted() }
            val binary = requireNotNull(core.getXrayBinary()) { "Bundled Xray executable unavailable" }
            val gate = Semaphore(4)
            val ports = ConcurrentHashMap.newKeySet<Int>()
            val sources = builtInSubscriptions.filter { it.group == PresetGroup.EXPANDED_WHITELISTS }
            for (source in sources) {
                val sourceReport = JsonObject().apply { addProperty("sourceId", source.id) }
                sourceReports.add(sourceReport)
                val parsed = try {
                    val content = app.assets.open(source.asset).bufferedReader().use { it.readText() }
                    parseUsableSubscription(content, source.id, source.name)
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) {
                    sourceReport.addProperty("parseFailed", true)
                    emptyList()
                }
                val distinct = parsed.distinctBy { it.address.lowercase() to it.port }
                val count = minOf(12, distinct.size)
                val sample = if (count <= 1) distinct.take(count) else (0 until count).map {
                    distinct[(it.toLong() * (distinct.size - 1) / (count - 1)).toInt()]
                }
                sourceReport.addProperty("availableProfiles", parsed.size)
                sourceReport.addProperty("distinctEndpoints", distinct.size)
                sourceReport.addProperty("sampleCount", sample.size)
                val results = coroutineScope {
                    sample.map { node -> async(Dispatchers.IO) {
                        gate.withPermit { measureNode(node, context, binary, ports) }
                    } }.awaitAll()
                }
                sourceReport.addProperty("passed", results.count { it.delayMs > 0 })
                sourceReport.add("delaysMs", JsonArray().apply { results.forEach { add(it.delayMs) } })
                sourceReport.add("outcomes", JsonArray().apply { results.forEach { add(it.outcome) } })
                saveReport()
            }
            saveReport()
            assertTrue("No expanded whitelist sources were bundled", sources.isNotEmpty())
            for (source in sourceReports) {
                val result = source.asJsonObject
                assertTrue("No sampled real HTTPS 204 success for ${result["sourceId"].asString}; see whitelist-live-qa.json",
                    result["passed"].asInt >= 1)
            }
        } finally {
            // Report contains only source IDs, counts, timings and fixed outcome categories.
            try { saveReport() } finally {
                preferences.forEach { app.deleteSharedPreferences("$prefix-$it") }
                directory.deleteRecursively()
            }
        }
    }

    private data class ProbeResult(val delayMs: Long, val outcome: String)

    private suspend fun measureNode(node: ServerNode, context: Context, binary: File,
        reservedPorts: MutableSet<Int>): ProbeResult = withContext(Dispatchers.IO) {
        var port = 0
        var process: Process? = null
        var drain: Thread? = null
        val configFile = File(context.cacheDir, "probe-${UUID.randomUUID()}.json")
        try {
            repeat(32) {
                if (port == 0) {
                    val candidate = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
                    if (reservedPorts.add(candidate)) port = candidate
                }
            }
            check(port > 0) { "Ephemeral port unavailable" }
            val config = JsonParser.parseString(XrayConfigGenerator().generateTestConfig(
                node, socksPort = port, routingRegion = "international")).asJsonObject
            val routing = config.getAsJsonObject("routing")
            val rules = routing.getAsJsonArray("rules")
            check(!routing.has("balancers") && rules.none { it.asJsonObject.has("balancerTag") }) {
                "A diagnostic must measure one target without a balancer"
            }
            // A CDN-fronted node can share an IP with the connectivity endpoint. Override the
            // generated direct-node rule for this inbound, so a direct 204 cannot pass the test.
            val forcedProxy = JsonObject().apply {
                addProperty("type", "field")
                add("inboundTag", JsonArray().apply { add("socks-test") })
                addProperty("network", "tcp")
                addProperty("outboundTag", "proxy")
            }
            routing.add("rules", JsonArray().apply { add(forcedProxy); rules.forEach { add(it) } })
            configFile.writeText(config.toString())
            val builder = ProcessBuilder(binary.absolutePath, "run", "-c", configFile.absolutePath)
                .directory(context.filesDir).redirectErrorStream(true)
            builder.environment()["XRAY_LOCATION_ASSET"] = context.filesDir.absolutePath
            val child = builder.start().also { process = it }
            drain = Thread {
                // Retain no endpoint, credentials or arbitrary native log text.
                runCatching { child.inputStream.bufferedReader().use { reader -> while (reader.readLine() != null) { } } }
            }.apply { isDaemon = true; name = "whitelist-probe-drain"; start() }
            val ready = withTimeoutOrNull(2_500) {
                while (child.isAlive) {
                    val listening = runCatching {
                        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 100) }
                    }.isSuccess
                    if (listening) return@withTimeoutOrNull true
                    delay(40)
                }
                false
            } == true
            if (!ready || !child.isAlive) return@withContext ProbeResult(-1, "core_not_ready")
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
            val elapsed = TunnelProbe.measure(proxy, PRIMARY_204, 8_000, fallbackUrl = FALLBACK_204)
            ProbeResult(elapsed, if (elapsed > 0) "https_204" else "no_https_204")
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { ProbeResult(-1, "probe_error")
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                process?.let { child ->
                    runCatching {
                        child.destroy()
                        if (!child.waitFor(250, TimeUnit.MILLISECONDS)) {
                            child.destroyForcibly()
                            child.waitFor(1, TimeUnit.SECONDS)
                        }
                    }
                    runCatching { child.inputStream.close() }
                    runCatching { child.errorStream.close() }
                    runCatching { child.outputStream.close() }
                }
                drain?.join(1_000)
                configFile.delete()
                if (port > 0) reservedPorts.remove(port)
            }
        }
    }

    private companion object {
        const val PRIMARY_204 = "https://cp.cloudflare.com/generate_204"
        const val FALLBACK_204 = "https://www.gstatic.com/generate_204"
    }
}
