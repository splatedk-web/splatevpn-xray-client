package com.danieldk.splatevpn.data

import android.content.Context
import com.danieldk.splatevpn.vpn.LocalProxyAccess
import com.danieldk.splatevpn.vpn.LocalProxyCredentials
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import com.danieldk.splatevpn.vpn.YouTubeMediaProbe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Socket
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CandidatePerformanceRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val probes: ServerProbeRepository,
    private val settings: SettingsManager
) {
    private val _measurements = MutableStateFlow<Map<String, CandidateMeasurement>>(emptyMap())
    val measurements = _measurements.asStateFlow()
    private val budgetPrefs = context.getSharedPreferences("balancer_probe_budget", Context.MODE_PRIVATE)
    private val budget = ProbeTrafficBudget()
    // Concurrent downloads compete for the same access link and distort the comparison.
    private val speedGate = Semaphore(1)

    fun configuration(): List<Any> = listOf(settings.getXhttpMode(), settings.isTlsFragmentEnabled(),
        settings.getTlsFragmentLength(), settings.getTlsFragmentInterval(), settings.getTlsFragmentPackets(), settings.isTlsMixedSniEnabled(),
        settings.isMuxEnabled(), settings.getMuxConcurrency(), settings.getRoutingRegion())

    fun fresh(server: ServerNode, prefs: BalancerPreferences = settings.getBalancerPreferences()): CandidateMeasurement? =
        measurements.value[server.id]?.takeIf {
            it.matches(server, NetworkAccessPolicy.physicalNetworkKey(context), configuration(), prefs.goal) &&
                (prefs.metric != BalancerMetric.DOWNLOAD || it.freshDownload() != null)
        }

    /** One consistent context for UI metadata; selection still revalidates its own tunnel proof. */
    internal fun freshSnapshot(nodes: List<ServerNode>): Map<String, CandidateMeasurement> {
        val prefs = settings.getBalancerPreferences()
        val network = NetworkAccessPolicy.physicalNetworkKey(context)
        val configuration = configuration()
        val now = System.currentTimeMillis()
        val measured = measurements.value
        return nodes.mapNotNull { node ->
            measured[node.id]?.takeIf { it.matches(node, network, configuration, prefs.goal, now) }
                ?.let { it.copy(downloadMbps = it.freshDownload(now)) }
                ?.let { node.id to it }
        }.toMap()
    }

    internal fun speedBudgetResumesAt(): Long? {
        val now = System.currentTimeMillis()
        val started = budgetPrefs.getLong("started", 0)
        return if (budget.reserve(now, started, budgetPrefs.getLong("used", 0),
                settings.getBalancerPreferences().speedBudgetMiB) == null) started + ProbeTrafficBudget.WINDOW_MS else null
    }

    @Synchronized private fun reserveSpeedSample(limitMiB: Int, purpose: SpeedProbePurpose): Boolean {
        val reservation = budget.reserve(System.currentTimeMillis(), budgetPrefs.getLong("started", 0),
            budgetPrefs.getLong("used", 0), limitMiB, purpose) ?: return false
        // Commit before any I/O so a process restart cannot reset the allowance.
        return budgetPrefs.edit().putLong("started", reservation.first).putLong("used", reservation.second).commit()
    }

    internal suspend fun qualify(server: ServerNode, prefs: BalancerPreferences, purpose: SpeedProbePurpose = SpeedProbePurpose.CANDIDATE, priority: Boolean = false): CandidateMeasurement? =
        if (prefs.metric == BalancerMetric.DOWNLOAD) speedGate.withPermit { qualifyInternal(server, prefs, purpose, priority) }
        else qualifyInternal(server, prefs, purpose, priority)

    private suspend fun qualifyInternal(server: ServerNode, prefs: BalancerPreferences, purpose: SpeedProbePurpose, priority: Boolean): CandidateMeasurement? {
        fresh(server, prefs)?.let { return it }
        val network = NetworkAccessPolicy.physicalNetworkKey(context) ?: return null
        val configuration = configuration()
        val result = try {
            probes.withServerTunnel(server, 22_000L, useBalancerGate = priority) { port, access ->
                val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
                val currentCall = AtomicReference<Call?>()
                val currentSocket = AtomicReference<Socket?>()
                BoundedNetworkTask.run(22_000, onCancel = {
                    cancelled.set(true)
                    currentCall.getAndSet(null)?.cancel()
                    runCatching { currentSocket.getAndSet(null)?.close() }
                }) {
                    val client = LocalProxyAccess.socksClient(OkHttpClient.Builder()
                        .connectTimeout(4, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
                        .callTimeout(7, TimeUnit.SECONDS).retryOnConnectionFailure(false)
                        .followRedirects(true).followSslRedirects(false).build(), port, access)
                    try {
                        fun checkAllowed() {
                            if (cancelled.get() || Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Probe cancelled")
                            settings.requireChecksNetwork()
                            if (network != NetworkAccessPolicy.physicalNetworkKey(context)) throw java.io.InterruptedIOException("Network changed")
                        }
                        fun get(url: String, limit: Int, domain: String? = null): ByteArray? =
                            read(client, currentCall, url, limit, ::checkAllowed, domain)
                        val country = if (prefs.goal == BalancerGoal.YOUTUBE_RU) get("https://www.cloudflare.com/cdn-cgi/trace", 4096)
                            ?.toString(Charsets.UTF_8)?.lineSequence()?.firstOrNull { it.startsWith("loc=") }
                            ?.substringAfter("=")?.takeIf { it.matches(Regex("[A-Z]{2}")) } else null
                        val qualified = try { when (prefs.goal) {
                            BalancerGoal.GENERAL -> true // Real tunnel check is performed before qualification.
                            BalancerGoal.TELEGRAM -> Socket().use { socket ->
                                checkAllowed()
                                currentSocket.set(socket)
                                try {
                                    checkAllowed()
                                    TelegramAvailabilityProbe.measure(port, access, socket)
                                }
                                finally { currentSocket.set(null) }
                            }
                            BalancerGoal.YOUTUBE, BalancerGoal.YOUTUBE_RU ->
                                (prefs.goal != BalancerGoal.YOUTUBE_RU || country == "RU") && runBlocking {
                                    YouTubeMediaProbe.check(client, { currentCall.set(it) }, ::checkAllowed)
                                }
                            BalancerGoal.TIKTOK -> tiktokAccessible(get("https://www.tiktok.com/@tiktok", 1024 * 1024))
                            BalancerGoal.INSTAGRAM, BalancerGoal.WHATSAPP, BalancerGoal.TWITCH ->
                                ServiceWebProbe.check(client, prefs.goal, { currentCall.set(it) }, ::checkAllowed)
                            BalancerGoal.DISCORD -> {
                                val response = get("https://discord.com/api/v10/gateway", 4096, "discord.com")
                                response?.let { bytes ->
                                    runCatching {
                                        val url = JsonParser.parseString(bytes.toString(Charsets.UTF_8))
                                            .asJsonObject.get("url").asString
                                        val gateway = URI(url)
                                        gateway.scheme == "wss" && gateway.host == "gateway.discord.gg"
                                    }.getOrDefault(false)
                                } == true
                            }
                        } } catch (wifi: WifiRequiredException) { throw wifi
                        } catch (_: java.io.IOException) { checkAllowed(); false }
                        checkAllowed()
                        val mbps = if (qualified && prefs.metric == BalancerMetric.DOWNLOAD && reserveSpeedSample(prefs.speedBudgetMiB, purpose))
                            downloadSample(client, currentCall, ::checkAllowed) else null
                        checkAllowed()
                        CandidateMeasurement(QuickServerSelection.profile(server), network, configuration,
                            System.currentTimeMillis(), prefs.goal, qualified, mbps, country)
                    } finally {
                        cancelled.set(true)
                        currentCall.getAndSet(null)?.cancel()
                        client.connectionPool.evictAll()
                        client.dispatcher.executorService.shutdown()
                    }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (wifi: WifiRequiredException) { throw wifi
        } catch (_: Exception) { null }
        settings.requireChecksNetwork()
        if (network != NetworkAccessPolicy.physicalNetworkKey(context) || configuration != configuration()) return null
        if (result != null) _measurements.update { existing ->
            existing.filterValues { System.currentTimeMillis() - it.checkedAt in 0..QuickServerSelection.FRESH_FOR_MS } +
                (server.id to result.retainDownload(existing[server.id]))
        }
        return if (result == null) null else fresh(server, prefs)
    }

    private fun read(client: OkHttpClient, active: AtomicReference<Call?>, url: String, limit: Int, checkAllowed: () -> Unit, domain: String? = null): ByteArray? {
        checkAllowed()
        val request = Request.Builder().url(url).header("Accept-Encoding", "identity")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36")
            .header("Cache-Control", "no-cache").build()
        val call = client.newCall(request)
        active.set(call)
        return try {
            checkAllowed()
            call.execute().use { response ->
                if (!response.isSuccessful || !response.request.url.isHttps) return null
                val host = response.request.url.host
                if (domain != null && host != domain && !host.endsWith(".$domain")) return null
                response.body?.byteStream()?.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (output.size() < limit) {
                        checkAllowed()
                        val count = input.read(buffer, 0, minOf(buffer.size, limit - output.size()))
                        if (count <= 0) break
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            }
        } catch (wifi: WifiRequiredException) { throw wifi
        } catch (_: java.io.InterruptedIOException) { checkAllowed(); null
        } catch (_: java.io.IOException) { null }
        finally { active.compareAndSet(call, null) }
    }

    private fun downloadSample(client: OkHttpClient, active: AtomicReference<Call?>, checkAllowed: () -> Unit): Double? {
        checkAllowed()
        val call = client.newCall(Request.Builder()
            .url("https://speed.cloudflare.com/__down?bytes=${ProbeTrafficBudget.SAMPLE_BYTES}")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36")
            .header("Accept-Encoding", "identity").header("Cache-Control", "no-store").build())
        active.set(call)
        return try {
            checkAllowed()
            call.execute().use { response ->
                if (response.code != 200 || response.header("Content-Type").orEmpty()
                        .substringBefore(';').trim() != "application/octet-stream") return null
                val body = response.body ?: return null
                if (body.contentLength() != ProbeTrafficBudget.SAMPLE_BYTES.toLong()) return null
                val started = System.nanoTime()
                var bytes = 0L
                body.byteStream().use { input ->
                    val buffer = ByteArray(32 * 1024)
                    while (bytes < ProbeTrafficBudget.SAMPLE_BYTES && (System.nanoTime() - started) < 5_000_000_000L) {
                        checkAllowed()
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), ProbeTrafficBudget.SAMPLE_BYTES - bytes).toInt())
                        if (count <= 0) break
                        bytes += count
                    }
                }
                if (bytes < 256 * 1024) null
                else bytes * 8_000.0 / (System.nanoTime() - started).coerceAtLeast(1)
            }
        } catch (wifi: WifiRequiredException) { throw wifi
        } catch (_: java.io.InterruptedIOException) { checkAllowed(); null
        } catch (_: java.io.IOException) { null }
        finally { active.compareAndSet(call, null) }
    }

    companion object {
        internal fun tiktokAccessible(bytes: ByteArray?): Boolean {
            val html = bytes?.toString(Charsets.UTF_8) ?: return false
            val challenge = Regex("""<(?:div|form|iframe)\b[^>]*(?:id|class)\s*=\s*["'][^"']*(?:verify-center|captcha)[^"']*["']""",
                RegexOption.IGNORE_CASE)
            if (challenge.containsMatchIn(html)) return false
            val script = Regex("""<script\b[^>]*\bid\s*=\s*["']__UNIVERSAL_DATA_FOR_REHYDRATION__["'][^>]*>(.*?)</script\s*>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)?.groupValues?.get(1) ?: return false
            return runCatching {
                val data = JsonParser.parseString(script).asJsonObject.getAsJsonObject("__DEFAULT_SCOPE__")
                    ?.getAsJsonObject("webapp.user-detail") ?: return false
                data.get("statusCode")?.asInt == 0 &&
                    data.getAsJsonObject("userInfo")?.getAsJsonObject("user")?.get("id")?.asString?.isNotBlank() == true
            }.getOrDefault(false)
        }
        internal fun extractPlayer(html: String): JsonObject? = runCatching {
            // Mobile pages can assign null before supplying the actual player object.
            val marker = Regex("(?:var\\s+)?ytInitialPlayerResponse\\s*=\\s*(?=\\{)").find(html) ?: return null
            val start = marker.range.last + 1
            if (start >= html.length || html[start] != '{') return null
            var depth = 0; var quoted = false; var escaped = false
            for (i in start until html.length) {
                val char = html[i]
                if (quoted) {
                    if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
                } else when (char) {
                    '"' -> quoted = true
                    '{', '[' -> { depth++; if (depth > 64) return null }
                    '}', ']' -> { depth--; if (depth == 0) return JsonParser.parseString(html.substring(start, i + 1)).asJsonObject }
                }
            }
            null
        }.getOrNull()
    }
}
