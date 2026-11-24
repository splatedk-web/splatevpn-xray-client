package com.danieldk.splatevpn.data

import android.content.Context
import android.net.ConnectivityManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Proxy
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Failed control requests never prove an outage: a restricted network may block every control. */
internal enum class PhysicalEvidence { REACHABLE, UNCONFIRMED, NO_NETWORK }

internal suspend fun <T> anyReachable(targets: List<T>, probe: suspend (T) -> Boolean): Boolean = coroutineScope {
    val results = Channel<Boolean>(targets.size)
    val jobs = targets.map { target -> launch { results.send(probe(target)) } }
    try {
        repeat(targets.size) { if (results.receive()) return@coroutineScope true }
        false
    } finally {
        jobs.forEach { it.cancel() }
        results.cancel()
    }
}

internal class FailureEvidenceGate(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val mutex = Mutex()
    private var cachedNetwork: Long? = null
    private var cachedUntil = Long.MIN_VALUE
    private var cached = PhysicalEvidence.UNCONFIRMED

    suspend fun assess(network: Long?, current: () -> Long?, probe: suspend () -> Boolean): PhysicalEvidence = mutex.withLock {
        if (network == null || network == 1L || current() != network) return@withLock PhysicalEvidence.NO_NETWORK
        if (cachedNetwork == network && clock() < cachedUntil) return@withLock cached
        val reachable = probe()
        if (current() != network) return@withLock PhysicalEvidence.NO_NETWORK
        cachedNetwork = network
        cached = if (reachable) PhysicalEvidence.REACHABLE else PhysicalEvidence.UNCONFIRMED
        // Share a failure wave, but do not retain a successful old-network diagnosis for seconds.
        cachedUntil = clock() + if (reachable) 500L else 2_000L
        cached
    }
}

internal fun physicalControlUrls(region: RoutingRegion): List<String> {
    val regional = mapOf(
        RoutingRegion.RUSSIA to listOf("https://ya.ru/", "https://vk.ru/", "https://max.ru/"),
        RoutingRegion.CHINA to listOf("https://www.baidu.com/", "https://www.qq.com/"),
        RoutingRegion.IRAN to listOf("https://www.aparat.com/", "https://www.digikala.com/")
    )
    return (regional[region].orEmpty() + listOf("https://www.microsoft.com/", "https://www.wikipedia.org/") +
        regional.values.flatten()).distinct()
}

@Singleton
class PhysicalFailureEvidence @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsManager
) {
    private val gate = FailureEvidenceGate()
    private val base = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
        .connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()

    internal suspend fun assess(expectedNetwork: Long?): PhysicalEvidence = NetworkAccessPolicy.withChecks(context) {
        gate.assess(expectedNetwork, { NetworkAccessPolicy.physicalNetworkKey(context) }) probe@{
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val network = cm?.allNetworks?.firstOrNull { it.networkHandle == expectedNetwork }
                ?: return@probe false
            val client = base.newBuilder().socketFactory(network.socketFactory)
                .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.SECONDS))
                .dns(object : okhttp3.Dns {
                    override fun lookup(hostname: String) = network.getAllByName(hostname).toList()
                }).build()
            withTimeoutOrNull(3500L) {
                anyReachable(physicalControlUrls(RoutingRegion.fromId(settings.getRoutingRegion()))) { url ->
                    try {
                        // Certificate-verified HTTPS headers, including 403/405, prove an actual exchange.
                        client.newCall(Request.Builder().url(url).head().build()).awaitConsumedResponse { true }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: java.io.IOException) { false }
                }
            } ?: false
        }
    }
}
