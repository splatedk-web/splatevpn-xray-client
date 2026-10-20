package com.danieldk.splatevpn.vpn

import com.danieldk.splatevpn.data.selectResponsiveCandidate
import com.danieldk.splatevpn.data.REAL_DELAY_PARALLELISM
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/** A total deadline covers both parallel endpoints, including executor queue time. */
internal object TunnelProbe {
    // Six server probes each race two independent HTTP endpoints.
    private val workers = Executors.newFixedThreadPool(REAL_DELAY_PARALLELISM * 2) { task ->
        Thread(task, "splate-connectivity-probe").apply { isDaemon = true }
    }

    private val healthWorkers = Executors.newFixedThreadPool(4) { task ->
        Thread(task, "splate-health-probe").apply { isDaemon = true }
    }

    suspend fun measure(
        proxy: Proxy, primaryUrl: String, timeoutMs: Int,
        fallbackUrl: String = "http://connectivitycheck.gstatic.com/generate_204", priority: Boolean = false
    ): Long {
        val targets = listOf(primaryUrl, fallbackUrl).distinct()
        return withTimeoutOrNull(timeoutMs.toLong()) {
            selectResponsiveCandidate(targets, settleMs = 0, score = { _, ping -> ping }) { target ->
                request(proxy, target, timeoutMs, priority)
            }?.second ?: -1L
        } ?: -1L
    }

    private suspend fun request(proxy: Proxy, target: String, budgetMs: Int, priority: Boolean): Long =
        suspendCancellableCoroutine { continuation ->
            val connection = AtomicReference<HttpURLConnection?>()
            val future = (if (priority) healthWorkers else workers).submit {
                val started = System.nanoTime()
                var result = -1L
                try {
                    if (!continuation.isActive) return@submit
                    val conn = (URL(target).openConnection(proxy) as HttpURLConnection)
                    connection.set(conn)
                    if (!continuation.isActive) return@submit
                    // The coroutine already enforces the total deadline. Splitting it in half
                    // rejects a slow first response even when it arrives within that deadline.
                    conn.connectTimeout = budgetMs.coerceAtLeast(1)
                    conn.readTimeout = budgetMs.coerceAtLeast(1)
                    conn.instanceFollowRedirects = false
                    conn.setRequestProperty("Connection", "close")
                    conn.setRequestProperty("User-Agent", "Splate/ConnectivityCheck")
                    if (conn.responseCode == HttpURLConnection.HTTP_NO_CONTENT) result = ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(1)
                } catch (_: Exception) {
                    result = -1L
                } finally {
                    try { connection.getAndSet(null)?.disconnect() } catch (_: Exception) { }
                }
                if (continuation.isActive) continuation.resume(result)
            }
            continuation.invokeOnCancellation {
                future.cancel(true)
                try { connection.getAndSet(null)?.disconnect() } catch (_: Exception) { }
            }
        }
}
