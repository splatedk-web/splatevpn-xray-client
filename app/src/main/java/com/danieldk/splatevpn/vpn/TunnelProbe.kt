package com.danieldk.splatevpn.vpn

import com.danieldk.splatevpn.data.selectResponsiveCandidate
import com.danieldk.splatevpn.data.REAL_DELAY_PARALLELISM
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Proxy
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/** A total deadline covers both parallel endpoints, including executor queue time. */
internal object TunnelProbe {
    const val PRIMARY_URL = "https://cp.cloudflare.com/generate_204"
    const val FALLBACK_URL = "https://www.gstatic.com/generate_204"
    private val httpClient = okhttp3.OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
    // Six server probes each race two independent HTTP endpoints.
    private val workers = Executors.newFixedThreadPool(REAL_DELAY_PARALLELISM * 2) { task ->
        Thread(task, "splate-connectivity-probe").apply { isDaemon = true }
    }

    private val healthWorkers = Executors.newFixedThreadPool(4) { task ->
        Thread(task, "splate-health-probe").apply { isDaemon = true }
    }

    suspend fun measure(
        proxy: Proxy, primaryUrl: String, timeoutMs: Int,
        fallbackUrl: String = FALLBACK_URL, priority: Boolean = false,
        credentials: LocalProxyCredentials? = null,
        client: OkHttpClient = httpClient
    ): Long {
        // A plaintext response cannot establish that TLS works through this candidate.
        val targets = listOf(primaryUrl, fallbackUrl).distinct().filter {
            it.toHttpUrlOrNull()?.let { url -> url.isHttps && url.username.isEmpty() && url.password.isEmpty() } == true
        }
        if (targets.isEmpty()) return -1L
        return withTimeoutOrNull(timeoutMs.toLong()) {
            selectResponsiveCandidate(targets, settleMs = 0, score = { _, ping -> ping }) { target ->
                request(proxy, target, timeoutMs, priority, credentials, client)
            }?.second ?: -1L
        } ?: -1L
    }

    private suspend fun request(proxy: Proxy, target: String, budgetMs: Int, priority: Boolean, credentials: LocalProxyCredentials?, client: OkHttpClient): Long =
        suspendCancellableCoroutine { continuation ->
            val activeCall = AtomicReference<okhttp3.Call?>()
            val future = (if (priority) healthWorkers else workers).submit {
                val started = System.nanoTime()
                var result = -1L
                try {
                    if (!continuation.isActive) return@submit
                    val proxyPort = (proxy.address() as? java.net.InetSocketAddress)?.port
                    val base = client.newBuilder().connectTimeout(budgetMs.toLong(), TimeUnit.MILLISECONDS)
                        .readTimeout(budgetMs.toLong(), TimeUnit.MILLISECONDS).callTimeout(budgetMs.toLong(), TimeUnit.MILLISECONDS)
                        .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
                        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
                    val routed = if (proxyPort != null && (proxy.type() == Proxy.Type.SOCKS || credentials != null ||
                        LocalProxyAccess.isOwnedPort(proxyPort))) {
                        LocalProxyAccess.socksClient(base, proxyPort, credentials)
                    } else {
                        base.newBuilder().proxy(proxy).build()
                    }
                    val call = routed.newCall(okhttp3.Request.Builder().url(target)
                        .header("Connection", "close").header("User-Agent", "Splate/ConnectivityCheck").build())
                    activeCall.set(call)
                    if (!continuation.isActive) { call.cancel(); return@submit }
                    call.execute().use { if (it.code == 204) result = ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(1) }
                } catch (_: Exception) {
                    result = -1L
                }
                if (continuation.isActive) continuation.resume(result)
            }
            continuation.invokeOnCancellation {
                future.cancel(true)
                activeCall.getAndSet(null)?.cancel()
            }
        }
}
