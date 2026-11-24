package com.danieldk.splatevpn.vpn

import com.danieldk.splatevpn.data.TorSettings
import com.danieldk.splatevpn.data.TorBridgeMetric
import com.danieldk.splatevpn.data.torBridgeTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class TorBridgeCandidate(val index: Int, val bridge: String?) {
    val transport: String get() = bridge?.let(::torBridgeTransport) ?: "direct"
    fun settings(base: TorSettings): TorSettings = if (bridge == null) base else
        base.copy(bridges = listOf(bridge), selectedBridge = bridge)
}

internal data class TorBridgePlan(val candidates: List<TorBridgeCandidate>, val automatic: Boolean)

internal object TorBridgeSelection {
    const val MAX_ATTEMPTS = 6
    fun fingerprint(bridge: String) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(bridge.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun plan(
        settings: TorSettings,
        lastSuccessful: String? = null,
        rotation: Int = 0,
        delays: Map<String, Long> = emptyMap(),
        recentlyFailed: String? = null,
        speeds: Map<String, Double> = emptyMap()
    ): TorBridgePlan {
        if (!settings.useBridges) return TorBridgePlan(listOf(TorBridgeCandidate(-1, null)), false)
        val candidates = settings.bridges.mapIndexed { index, bridge -> TorBridgeCandidate(index, bridge) }
            .filter { settings.bridgeTransport == "auto" || it.transport == settings.bridgeTransport }
        require(candidates.isNotEmpty()) { "No bridges available for the selected transport" }
        candidates.firstOrNull { it.bridge == settings.selectedBridge }?.let {
            return TorBridgePlan(listOf(it), false)
        }
        fun delayOf(candidate: TorBridgeCandidate) = delays[candidate.bridge]?.takeIf { it > 0 } ?: Long.MAX_VALUE
        val bySpeed = TorBridgeMetric.fromId(settings.bridgeSelectionMetric) == TorBridgeMetric.SPEED
        fun speedOf(candidate: TorBridgeCandidate) = speeds[candidate.bridge]?.takeIf { it.isFinite() && it > 0 } ?: 0.0
        val preferred = (if (bySpeed) candidates.filter { it.bridge != recentlyFailed && speedOf(it) > 0 }
            .maxByOrNull(::speedOf) else candidates.filter { it.bridge != recentlyFailed && delayOf(it) < Long.MAX_VALUE }.minByOrNull(::delayOf))
            ?: candidates.firstOrNull { it.bridge == lastSuccessful && it.bridge != recentlyFailed }
        val failed = candidates.firstOrNull { it.bridge == recentlyFailed }
        val groups = listOf("vanilla", "webtunnel", "obfs4", "snowflake").map { transport ->
            val group = candidates.filter { it.transport == transport && it != preferred && it != failed }
            if (group.isEmpty()) group else {
                val offset = Math.floorMod(rotation, group.size)
                (group.drop(offset) + group.take(offset)).sortedWith(
                    compareByDescending<TorBridgeCandidate> { if (bySpeed) speedOf(it) else 0.0 }.thenBy(::delayOf))
            }
        }.filter { it.isNotEmpty() }.sortedWith(compareBy<List<TorBridgeCandidate>> { it.first().transport == preferred?.transport }.thenBy { delayOf(it.first()) })

        val ordered = buildList {
            preferred?.let(::add)
            for (row in 0 until (groups.maxOfOrNull { it.size } ?: 0)) {
                groups.forEach { group -> group.getOrNull(row)?.let(::add) }
            }
            failed?.let(::add)
        }
        return TorBridgePlan(ordered.take(MAX_ATTEMPTS), ordered.size > 1)
    }
}

internal data class TorBridgeTimings(
    val autoAttemptMillis: Long = 90_000,
    val manualAttemptMillis: Long = 120_000,
    val earlyStallMillis: Long = 12_000
) {
    fun adaptiveStallMillis(
        transport: String = "vanilla",
        isPreviousSuccess: Boolean = false
    ): Long {
        val base = when (transport.lowercase()) {
            "snowflake" -> 30_000L
            "webtunnel" -> maxOf(earlyStallMillis, 18_000L)
            "obfs4" -> earlyStallMillis
            else -> earlyStallMillis
        }
        return if (isPreviousSuccess) base + 10_000L else base
    }

    fun shouldLeaveEarly(
        progress: Int,
        stalledSeconds: Long,
        warning: Boolean = false,
        reason: TorBootstrapReason? = null,
        transport: String = "vanilla",
        isPreviousSuccess: Boolean = false
    ): Boolean {
        if (progress >= 20) return false
        val thresholdMs = adaptiveStallMillis(transport, isPreviousSuccess)
        if (stalledSeconds * 1_000L >= thresholdMs) return true

        val fatalWarning = warning && reason in setOf(
            TorBootstrapReason.REFUSED,
            TorBootstrapReason.NO_ROUTE,
            TorBootstrapReason.IDENTITY,
            TorBootstrapReason.TRANSPORT_MISSING
        )
        val warningStallLimit = if (transport.lowercase() in setOf("snowflake", "webtunnel")) 10L else 6L
        return fatalWarning && stalledSeconds >= warningStallLimit
    }
}

/** A timeout belonging to an attempt is retryable; cancellation of the caller never is. */
internal suspend fun <T> tryTorBridgeCandidates(
    plan: TorBridgePlan,
    retryable: (Throwable) -> Boolean,
    connect: suspend (TorBridgeCandidate, number: Int, hasNext: Boolean) -> T
): T {
    plan.candidates.forEachIndexed { index, candidate ->
        currentCoroutineContext().ensureActive()
        try { return connect(candidate, index + 1, index < plan.candidates.lastIndex) }
        catch (error: Throwable) {
            currentCoroutineContext().ensureActive()
            if (error is CancellationException && error !is TimeoutCancellationException) throw error
            if (index == plan.candidates.lastIndex || !retryable(error)) throw error
        }
    }
    error("No bridge candidates")
}
