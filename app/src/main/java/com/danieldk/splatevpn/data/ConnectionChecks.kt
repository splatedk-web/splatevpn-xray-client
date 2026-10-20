package com.danieldk.splatevpn.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.danieldk.splatevpn.SplateConstants
import com.danieldk.splatevpn.vpn.VpnState
import com.danieldk.splatevpn.vpn.VpnStatusManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

enum class CheckOutcome { OK, LIMITED, FAILED, SKIPPED }
data class CheckResult(val name: String, val outcome: CheckOutcome, val detail: String, val elapsedMs: Long? = null)
data class CheckRun(val running: Boolean = false, val results: List<CheckResult> = emptyList(), val completedAt: Long = 0, val cancelled: Boolean = false)
internal fun classifyHttp(code: Int): CheckOutcome = when (code) {
    in 200..399 -> CheckOutcome.OK
    401, 403, 429 -> CheckOutcome.LIMITED
    else -> CheckOutcome.FAILED
}

internal suspend fun checkHttp(client: OkHttpClient, name: String, url: String, connectivity: Boolean = false): CheckResult =
    suspendCancellableCoroutine { continuation ->
        val start = System.nanoTime()
        val call = client.newCall(Request.Builder().url(url).header("User-Agent", "SplateVPN/ConnectivityCheck").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val detail = when (e) {
                    is java.net.UnknownHostException -> "dns"
                    is javax.net.ssl.SSLException -> "tls"
                    is java.io.InterruptedIOException -> "timeout"
                    else -> "network"
                }
                if (continuation.isActive) continuation.resume(CheckResult(name, CheckOutcome.FAILED, detail))
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val outcome = if (connectivity && it.code != 204) CheckOutcome.FAILED else classifyHttp(it.code)
                    val result = CheckResult(name, outcome, "HTTP ${it.code}", (System.nanoTime() - start) / 1_000_000)
                    if (continuation.isActive) continuation.resume(result)
                }
            }
        })
    }

@Singleton
class ConnectionChecks @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vpn: VpnStatusManager
) {
    private val _services = MutableStateFlow(CheckRun())
    val services = _services.asStateFlow()
    private val _diagnostics = MutableStateFlow(CheckRun())
    val diagnostics = _diagnostics.asStateFlow()
    private val client = OkHttpClient.Builder()
        .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(SplateConstants.XRAY_SOCKS_ADDRESS, SplateConstants.XRAY_SOCKS_PORT)))
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()

    companion object {
        val targets = listOf(
            "Google" to "https://www.google.com/",
            "YouTube" to "https://www.youtube.com/",
            "Telegram" to "https://telegram.org/",
            "Discord" to "https://discord.com/",
            "GitHub" to "https://github.com/",
            "Wikipedia" to "https://www.wikipedia.org/",
            "ChatGPT" to "https://chatgpt.com/",
            "Gemini" to "https://gemini.google.com/",
            "Claude" to "https://claude.ai/",
            "Яндекс" to "https://ya.ru/"
        )
    }

    private suspend fun http(name: String, url: String, connectivity: Boolean = false): CheckResult =
        checkHttp(client, name, url, connectivity)

    suspend fun checkServices() {
        if (_services.value.running) return
        _services.value = CheckRun(running = true)
        try {
            if (vpn.state.value != VpnState.CONNECTED) {
                _services.value = CheckRun(results = targets.map { CheckResult(it.first, CheckOutcome.SKIPPED, "vpn_off") })
                return
            }
            // Small batches limit sockets and make completed results visible immediately.
            for (batch in targets.chunked(3)) {
                val results = coroutineScope { batch.map { (name, url) -> async { http(name, url) } }.awaitAll() }
                if (vpn.state.value != VpnState.CONNECTED) {
                    _services.value = _services.value.copy(results = _services.value.results + batch.map { CheckResult(it.first, CheckOutcome.SKIPPED, "vpn_off") })
                    break
                }
                _services.value = _services.value.copy(results = _services.value.results + results)
            }
        } catch (e: CancellationException) {
            _services.value = _services.value.copy(cancelled = true)
            throw e
        } catch (_: Exception) {
            _services.value = _services.value.copy(results = _services.value.results + CheckResult("HTTPS", CheckOutcome.FAILED, "network"))
        } finally {
            _services.value = _services.value.copy(running = false, completedAt = System.currentTimeMillis())
        }
    }

    suspend fun diagnose(node: ServerNode?) {
        if (_diagnostics.value.running) return
        _diagnostics.value = CheckRun(running = true)
        fun add(result: CheckResult) { _diagnostics.value = _diagnostics.value.copy(results = _diagnostics.value.results + result) }
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.allNetworks.firstOrNull {
                val caps = cm.getNetworkCapabilities(it)
                caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
            add(CheckResult("network", if (network != null) CheckOutcome.OK else CheckOutcome.FAILED,
                if (network != null) "network_ok" else "network_missing"))
            if (network != null) {
                val dns = try { BoundedNetworkTask.run(4000) { network.getAllByName("www.google.com").isNotEmpty() } == true }
                    catch (e: CancellationException) { throw e } catch (_: Exception) { false }
                add(CheckResult("dns", if (dns) CheckOutcome.OK else CheckOutcome.FAILED, if (dns) "dns_ok" else "dns_failed"))
            } else add(CheckResult("dns", CheckOutcome.SKIPPED, "network_missing"))
            if (node != null && network != null) {
                val socket = network.socketFactory.createSocket()
                val ping = try {
                    BoundedNetworkTask.run(5000, onCancel = { socket.close() }) {
                        val start = android.os.SystemClock.elapsedRealtime()
                        val address = network.getAllByName(node.address).first()
                        socket.use { it.connect(InetSocketAddress(address, node.port), 4000) }
                        android.os.SystemClock.elapsedRealtime() - start
                    }
                } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                finally { try { socket.close() } catch (_: Exception) {} }
                add(CheckResult("server", if (ping != null) CheckOutcome.OK else CheckOutcome.FAILED,
                    if (ping != null) "port_ok" else "port_failed", ping))
            } else add(CheckResult("server", CheckOutcome.SKIPPED, if (node == null) "no_server" else "network_missing"))
            val connected = vpn.state.value == VpnState.CONNECTED
            add(CheckResult("vpn", if (connected) CheckOutcome.OK else CheckOutcome.SKIPPED, if (connected) "vpn_ok" else "vpn_off"))
            if (connected) {
                add(http("Cloudflare HTTPS", "https://cp.cloudflare.com/generate_204", connectivity = true))
                add(http("Google HTTPS", "https://www.gstatic.com/generate_204", connectivity = true))
            } else add(CheckResult("HTTPS", CheckOutcome.SKIPPED, "vpn_off"))
        } catch (e: CancellationException) {
            _diagnostics.value = _diagnostics.value.copy(cancelled = true)
            throw e
        } catch (_: Exception) {
            add(CheckResult("HTTPS", CheckOutcome.FAILED, "network"))
        } finally {
            _diagnostics.value = _diagnostics.value.copy(running = false, completedAt = System.currentTimeMillis())
        }
    }
}
