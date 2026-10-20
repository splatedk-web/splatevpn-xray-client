package com.danieldk.splatevpn.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.danieldk.splatevpn.vpn.XrayConfigGenerator
import com.danieldk.splatevpn.vpn.XrayCoreManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class AiDnsAutoSelector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val core: XrayCoreManager,
    private val generator: XrayConfigGenerator,
    private val logs: AppLogManager
) {
    private val gate = Mutex()
    private val _state = MutableStateFlow(AiDnsSelectionState())
    val state = _state.asStateFlow()
    private val cm get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val dispatcher = Dispatcher(Executors.newFixedThreadPool(3) { runnable ->
        Thread(runnable, "ai-dns-http").apply { isDaemon = true }
    })
    private val baseClient = OkHttpClient.Builder().dispatcher(dispatcher)
        .connectTimeout(4, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()

    companion object {
        val targets = listOf("Gemini" to "https://gemini.google.com/", "ChatGPT" to "https://chatgpt.com/", "Claude" to "https://claude.ai/")
    }

    suspend fun run(applyChoice: (AiDnsChoice) -> Boolean) = withContext(Dispatchers.IO) {
        if (!gate.tryLock()) return@withContext
        val candidates = SmartDnsProviders.all.flatMap { listOf(AiDnsChoice(it.key, false), AiDnsChoice(it.key, true)) }
        _state.value = AiDnsSelectionState(phase = AiDnsSelectionPhase.TESTING, total = candidates.size)
        try {
            val network = cm.activeNetwork ?: throw IOException("network_missing")
            core.ensureAssetsExtracted()
            val binary = core.getXrayBinary()?.takeIf { it.exists() } ?: throw IOException("core_missing")
            for (choice in candidates) {
                currentCoroutineContext().ensureActive()
                requireSameNetwork(network)
                _state.value = _state.value.copy(current = choice)
                val result = probeCandidate(binary, choice)
                _state.value = _state.value.copy(completed = _state.value.completed + 1, results = _state.value.results + result)
            }
            _state.value = _state.value.copy(phase = AiDnsSelectionPhase.VERIFYING)
            val winner = confirmBestAiDns(_state.value.results, requiredService = "Gemini") { choice ->
                requireSameNetwork(network)
                _state.value = _state.value.copy(current = choice)
                probeCandidate(binary, choice, renderGemini = true).also { verified ->
                    _state.value = _state.value.copy(results = _state.value.results.map { if (it.choice == choice) verified else it })
                }
            }
            currentCoroutineContext().ensureActive()
            requireSameNetwork(network)
            if (winner == null) {
                _state.value = _state.value.copy(phase = AiDnsSelectionPhase.NO_MATCH, current = null)
            } else {
                // Serialize the final preference change with manual UI changes and cancellation.
                withContext(Dispatchers.Main.immediate) {
                    ensureActive()
                    requireSameNetwork(network)
                    if (applyChoice(winner.choice)) {
                        _state.value = _state.value.copy(phase = AiDnsSelectionPhase.APPLIED, winner = winner, current = null)
                        logs.i("AiDns", "Selected ${winner.choice.providerKey}, fragment=${winner.choice.tlsFragment}, accessible=${winner.accessible}/${targets.size}")
                    } else _state.value = _state.value.copy(phase = AiDnsSelectionPhase.CANCELLED, current = null)
                }
            }
        } catch (e: CancellationException) {
            if (_state.value.phase != AiDnsSelectionPhase.APPLIED) _state.value = _state.value.copy(phase = AiDnsSelectionPhase.CANCELLED, current = null)
            throw e
        } catch (e: Exception) {
            _state.value = _state.value.copy(phase = AiDnsSelectionPhase.ERROR, current = null,
                error = if (e.message == "network_changed" || e.message == "network_missing") e.message else "probe_failed")
        } finally {
            dispatcher.cancelAll()
            gate.unlock()
        }
    }

    private fun requireSameNetwork(original: Network) {
        if (cm.activeNetwork != original) throw IOException("network_changed")
    }

    internal suspend fun probeCandidate(binary: File, choice: AiDnsChoice, renderGemini: Boolean = false): AiDnsCandidateResult = coroutineScope {
        val port = ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val file = File.createTempFile("ai_dns_", ".json", context.cacheDir)
        var process: Process? = null
        var client: OkHttpClient? = null
        try {
            file.writeText(generator.generateAiDnsProbeConfig(choice.providerKey, choice.tlsFragment, port))
            val builder = ProcessBuilder(binary.absolutePath, "run", "-c", file.absolutePath)
                .directory(context.filesDir).redirectErrorStream(true)
            builder.environment()["XRAY_LOCATION_ASSET"] = context.filesDir.absolutePath
            val child = builder.start()
            process = child
            Thread {
                try { child.inputStream.bufferedReader().use { reader -> while (reader.readLine() != null) { /* Drain only this child's output. */ } } }
                catch (_: IOException) { }
            }.apply { name = "ai-dns-core-$port"; isDaemon = true; start() }
            val ready = withTimeoutOrNull(5_000) {
                while (child.isAlive) {
                    val listening = try {
                        Socket().use { socket ->
                            socket.connect(InetSocketAddress("127.0.0.1", port), 150)
                            socket.soTimeout = 200
                            socket.getOutputStream().write(byteArrayOf(5, 1, 0))
                            socket.getInputStream().read() == 5 && socket.getInputStream().read() == 0
                        }
                    } catch (_: IOException) { false }
                    if (listening && child.isAlive) return@withTimeoutOrNull true
                    delay(50)
                }
                false
            } == true
            if (!ready) throw IOException("probe_core_startup")
            val probeClient = baseClient.newBuilder()
                .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
                .connectionPool(ConnectionPool()).build()
            client = probeClient
            val results = targets.map { (name, url) -> async {
                val first = request(probeClient, choice, name, url)
                if (first.isTransientFailure()) { delay(300); request(probeClient, choice, name, url) } else first
            } }.awaitAll().toMutableList()
            if (renderGemini && results[0].canVerifyInBrowser()) {
                results[0] = verifyGeminiPage(context, port)
            }
            AiDnsCandidateResult(choice, results)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            AiDnsCandidateResult(choice, targets.map { AiDnsTargetResult(it.first, AiDnsOutcome.FAILED, detail = AiDnsDetail.CONNECTION) })
        } finally {
            client?.dispatcher?.cancelAll()
            client?.connectionPool?.evictAll()
            withContext(NonCancellable + Dispatchers.IO) {
                process?.let { child ->
                    child.destroy()
                    if (!child.waitFor(500, TimeUnit.MILLISECONDS)) child.destroyForcibly()
                    runCatching { child.inputStream.close() }
                }
                file.delete()
            }
        }
    }

    private suspend fun request(client: OkHttpClient, choice: AiDnsChoice, name: String, url: String): AiDnsTargetResult {
        val started = System.nanoTime()
        fun result(outcome: AiDnsOutcome, detail: AiDnsDetail, code: Int? = null) = AiDnsTargetResult(name, outcome, (System.nanoTime() - started) / 1_000_000, detail, code)
        var detail = AiDnsDetail.CONNECTION
        try {
            val query = AiDnsWire.query(java.net.URI(url).host)
            detail = AiDnsDetail.DNS_CONNECTION
            val dns = Request.Builder().url(SmartDnsProviders.forAi(choice.providerKey).dohUrl)
                .post(query.toRequestBody("application/dns-message".toMediaType())).header("Accept", "application/dns-message").build()
            val dnsFailure = execute(client, dns) { reply ->
                detail = AiDnsDetail.DNS_HTTP
                if (!reply.isSuccessful) return@execute result(AiDnsOutcome.FAILED, detail, reply.code)
                detail = AiDnsDetail.DNS_CONTENT
                if (reply.body?.contentType()?.let { "${it.type}/${it.subtype}" } != "application/dns-message") return@execute result(AiDnsOutcome.FAILED, detail, reply.code)
                detail = AiDnsDetail.DNS_INVALID
                val bytes = readLimited(reply, 65536)
                AiDnsWire.addresses(bytes, query)
                null
            }
            if (dnsFailure != null) return dnsFailure
            detail = AiDnsDetail.CONNECTION
            return execute(client, Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Android) SplateVPN/AiDnsCheck")
                .header("Cache-Control", "no-cache").build()) { reply ->
                detail = AiDnsDetail.HTTP_REPLY
                val body = String(readLimited(reply, 512 * 1024), Charsets.UTF_8)
                result(classifyAiDnsResponse(reply.code, body, reply.header("Location"), reply.header("cf-mitigated") == "challenge"), detail, reply.code)
            }
        } catch (e: CancellationException) { throw e }
        catch (e: IOException) {
            android.util.Log.w("AiDnsProbe", "${choice.providerKey} fragment=${choice.tlsFragment} $name $detail: ${e.javaClass.simpleName}: ${e.message}")
            val failureDetail = when {
                detail == AiDnsDetail.DNS_CONNECTION && e is java.io.InterruptedIOException -> AiDnsDetail.DNS_TIMEOUT
                detail == AiDnsDetail.CONNECTION && e is java.io.InterruptedIOException -> AiDnsDetail.CONNECTION_TIMEOUT
                detail == AiDnsDetail.CONNECTION && e is javax.net.ssl.SSLException -> AiDnsDetail.TLS_FAILURE
                else -> detail
            }
            return result(AiDnsOutcome.FAILED, failureDetail)
        }
    }

    private fun readLimited(response: Response, limit: Int): ByteArray {
        val buffer = Buffer()
        val source = response.body?.source() ?: throw IOException("empty_body")
        while (buffer.size < limit) {
            if (source.read(buffer, minOf(8192L, limit - buffer.size)) < 0) break
        }
        return buffer.readByteArray()
    }

    private suspend fun <T> execute(client: OkHttpClient, request: Request, consume: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(e))
            }
            override fun onResponse(call: Call, response: Response) {
                // Keep cancellation attached until the bounded body has been consumed.
                val result = runCatching { response.use(consume) }
                if (continuation.isActive) continuation.resumeWith(result)
            }
        })
    }
}
