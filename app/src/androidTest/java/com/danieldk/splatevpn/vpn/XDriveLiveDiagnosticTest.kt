package com.danieldk.splatevpn.vpn

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.data.LinkParser
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assume.*
import org.junit.Test
import java.io.File
import java.net.*
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Explicit live probe, isolated from the user's tunnel and preferences. Reports contain no credentials. */
class XDriveLiveDiagnosticTest {
    @Test fun publishedProfileThroughBundledCore() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("run_live_xdrive") == "true")
        val subscriptionUrl = arguments.getString("xdrive_subscription_url").orEmpty()
        assumeTrue(subscriptionUrl.isNotBlank())
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        assumeFalse(SplateVpnService.isServiceActive)
        val app = instrumentation.targetContext
        val report = JsonObject()
        val destination = File(app.filesDir, "xdrive-diagnostic.json")
        fun save(stage: String) { report.addProperty("stage", stage); destination.writeText(report.toString()) }
        val directory = File(app.cacheDir, "xdrive-live-${UUID.randomUUID()}").apply { mkdirs() }
        var process: Process? = null
        val configFile = File(directory, "probe.json")
        val logFile = File(directory, "core.log")
        try {
            save("fetching")
            val client = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build()
            val request = Request.Builder().url(subscriptionUrl)
                .header("User-Agent", "v2rayNG/1.8.5").build()
            var body: String? = null
            repeat(3) { if (body == null) runCatching {
                client.newCall(request).execute().use { response ->
                    check(response.isSuccessful); body = requireNotNull(response.body).string()
                }
            } }
            val raw = requireNotNull(body) { "Subscription fetch failed" }
            val node = LinkParser.parseSubscription(raw).first { it.network == "xdrive" }
            val port = ServerSocket(0).use { it.localPort }
            val config = JsonParser.parseString(XrayConfigGenerator().generateTestConfig(node, port)).asJsonObject
            config.getAsJsonObject("log").addProperty("loglevel", "debug")
            val outbound = config.getAsJsonArray("outbounds")[0].asJsonObject
            val published = JsonParser.parseString(node.fullConfigJson).asJsonObject.getAsJsonArray("outbounds")
                .first { it.asJsonObject["protocol"]?.asString == "vless" }.asJsonObject.deepCopy()
            published.addProperty("tag", "proxy")
            published.add("mux", JsonObject().apply { addProperty("enabled", true); addProperty("concurrency", 8) })
            report.addProperty("matchesPublishedWithRequiredMux", published == outbound)
            val storage = outbound.getAsJsonObject("streamSettings").getAsJsonObject("xdriveSettings")
            report.addProperty("storageService", storage["service"].asString)
            report.addProperty("storageSecretsCount", storage.getAsJsonArray("secrets")?.size() ?: 0)
            configFile.writeText(config.toString())
            val binary = File(app.applicationInfo.nativeLibraryDir, "libxray.so")
            val validation = ProcessBuilder(binary.absolutePath, "run", "-test", "-c", configFile.absolutePath)
                .redirectErrorStream(true).redirectOutput(logFile).start()
            check(validation.waitFor(20, TimeUnit.SECONDS)) { "Validation timed out" }
            report.addProperty("configExitCode", validation.exitValue())
            fun collectErrors() {
                val log = if (logFile.exists()) logFile.readText() else ""
                val patterns = listOf("unauthorized", "forbidden", "invalid_client", "invalid_grant", "access denied",
                    "certificate", "no such host", "timeout", "deadline exceeded", "failed", "error", "403", "401", "404", "429")
                val flags = JsonObject()
                patterns.forEach { flags.addProperty(it, log.contains(it, ignoreCase = true)) }
                report.add("errorFlags", flags)
                val strings = mutableListOf<String>()
                fun secrets(value: com.google.gson.JsonElement) {
                    when {
                        value.isJsonObject -> value.asJsonObject.entrySet().forEach { secrets(it.value) }
                        value.isJsonArray -> value.asJsonArray.forEach { secrets(it) }
                        value.isJsonPrimitive && value.asJsonPrimitive.isString && value.asString.length > 5 -> strings += value.asString
                    }
                }
                secrets(JsonParser.parseString(raw))
                val errors = log.lineSequence().filter { line -> line.contains("failed", true) || line.contains("error", true) }
                    .takeLastLines(12).map { line ->
                        var safe = line
                        strings.sortedByDescending { it.length }.forEach { safe = safe.replace(it, "<redacted>") }
                        safe.replace(Regex("https?://[^\\s]+"), "<url>")
                            .replace(Regex("[A-Za-z0-9_+/=-]{24,}"), "<redacted>").take(700)
                    }
                report.add("coreErrors", com.google.gson.JsonArray().apply { errors.forEach { add(it) } })
            }
            if (validation.exitValue() != 0) { collectErrors(); save("invalid_config"); return }
            fun probe(proxy: Proxy, timeout: Int): Int {
                val connection = URL("https://www.gstatic.com/generate_204").openConnection(proxy) as HttpURLConnection
                return try { connection.connectTimeout = timeout; connection.readTimeout = timeout
                    connection.instanceFollowRedirects = false; connection.responseCode
                } finally { connection.disconnect() }
            }
            report.addProperty("directHttpStatus", runCatching { probe(Proxy.NO_PROXY, 12000) }.getOrDefault(-1))
            save("connecting")
            process = ProcessBuilder(binary.absolutePath, "run", "-c", configFile.absolutePath)
                .redirectErrorStream(true).redirectOutput(logFile).start()
            Thread.sleep(1500)
            val started = System.nanoTime()
            try {
                report.addProperty("proxyHttpStatus", probe(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)), 45000))
            } catch (failure: Exception) { report.addProperty("proxyException", failure.javaClass.simpleName) }
            report.addProperty("elapsedMs", (System.nanoTime() - started) / 1_000_000)
            collectErrors()
            save("complete")
        } catch (failure: Exception) {
            report.addProperty("exception", failure.javaClass.simpleName); save("failed")
        } finally {
            process?.destroy(); process?.waitFor(3, TimeUnit.SECONDS); process?.destroyForcibly()
            configFile.delete(); logFile.delete(); directory.delete()
        }
    }

    private fun Sequence<String>.takeLastLines(count: Int): List<String> = toList().takeLast(count)
}
