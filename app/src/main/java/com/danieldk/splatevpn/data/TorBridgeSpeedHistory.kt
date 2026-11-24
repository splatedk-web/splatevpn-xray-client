package com.danieldk.splatevpn.data

/** Bounded observations from independent circuits, scoped to one physical network and configuration. */
internal class TorBridgeSpeedHistory(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private data class Sample(val at: Long, val mbps: Double)
    private var context: TorBridgeSpeedContext? = null
    private val samples = mutableMapOf<String, List<Sample>>()
    private val attempts = mutableMapOf<String, Long>()
    private var sequence = 0L

    private fun prepare(settings: TorSettings, network: String) {
        val next = TorBridgeSpeedContext.from(settings, network)
        if (context != next) {
            samples.clear()
            attempts.clear()
            context = next
        }
        samples.keys.retainAll(settings.bridges.toSet())
        attempts.keys.retainAll(settings.bridges.toSet())
        val now = clock()
        samples.replaceAll { _, values -> values.filter { now - it.at in 0..600_000L } }
    }

    @Synchronized fun speeds(settings: TorSettings, network: String): Map<String, Double> {
        if (context != TorBridgeSpeedContext.from(settings, network)) return emptyMap()
        prepare(settings, network)
        return samples.mapNotNull { (bridge, values) ->
            val sorted = values.map { it.mbps }.sorted()
            if (sorted.isEmpty()) null else bridge to sorted[(sorted.size - 1) / 2]
        }.toMap()
    }

    @Synchronized fun record(settings: TorSettings, network: String, results: Map<String, Double>) {
        prepare(settings, network)
        results.filter { it.key in settings.bridges && it.value.isFinite() && it.value > 0 }.forEach { (bridge, speed) ->
            samples[bridge] = (samples[bridge].orEmpty() + Sample(clock(), speed)).takeLast(5)
        }
    }

    @Synchronized fun candidates(settings: TorSettings, network: String, delays: Map<String, Long>): List<String> {
        prepare(settings, network)
        val speeds = speeds(settings, network)
        val allowed = settings.bridges.distinct().filter {
            settings.bridgeTransport == "auto" || torBridgeTransport(it) == settings.bridgeTransport
        }
        val leader = allowed.filter { (delays[it] ?: 1L) > 0 && it in speeds }.maxByOrNull { speeds.getValue(it) }
        val remaining = allowed.filter { it != leader }.sortedWith(
            compareBy<String> { attempts[it] ?: Long.MIN_VALUE }
                .thenBy { delays[it]?.let { delay -> delay <= 0 } == true }
                .thenBy { delays[it]?.takeIf { delay -> delay > 0 } ?: Long.MAX_VALUE })
        // Interleave transports without repeatedly discarding slower, unmeasured bridges.
        val groups = remaining.groupBy(::torBridgeTransport).values.map { it.iterator() }
        val ordered = buildList {
            leader?.let(::add)
            while (groups.any { it.hasNext() }) groups.forEach { if (it.hasNext()) add(it.next()) }
        }
        return ordered.take(3)
    }

    @Synchronized fun started(settings: TorSettings, network: String, bridge: String) {
        prepare(settings, network)
        attempts[bridge] = ++sequence
    }

    @Synchronized fun clear() {
        samples.clear()
        attempts.clear()
        context = null
    }
}
