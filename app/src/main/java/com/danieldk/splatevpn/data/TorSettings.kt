package com.danieldk.splatevpn.data

/** Only client options are accepted from subscriptions; imported torrc is never executed. */
data class TorSettings(
    val enabled: Boolean = false,
    val useBridges: Boolean = false,
    val bridges: List<String> = emptyList(),
    val entryNodes: String = "",
    val exitNodes: String = "",
    val excludeNodes: String = "",
    val strictNodes: Boolean = false,
    val subscriptionUrl: String = "",
    val autoUpdate: Boolean = false,
    val lastUpdatedAt: Long = 0L,
    val selectedBridge: String = "",
    val bridgeTransport: String = "auto",
    val lastImportSkipped: Int = 0,
    val optimisticData: Boolean = true,
    val autoSelectFastestBridge: Boolean = true,
    val circuitBuildTimeout: Int = 15,
    val maxCircuitDirtiness: Int = 300,
    val customBridgeSni: String = "",
    val bridgeSelectionMetric: String = "latency",
    val bridgeSubscriptions: List<TorBridgeSubscription> = emptyList(),
    val manualBridgeEntries: List<String>? = null,
    val defaultBridgeSubscriptionInitialized: Boolean = false
)

data class TorBridgeSubscription(val url: String, val bridges: List<String>)

internal fun TorSettings.withDefaultBridgeSubscription(): TorSettings {
    if (defaultBridgeSubscriptionInitialized) return this
    val sources = if (bridgeSubscriptions.size < TorConfigParser.MAX_SUBSCRIPTIONS &&
        bridgeSubscriptions.none { it.url == TOR_TOP100_SUBSCRIPTION }
    ) {
        listOf(TorBridgeSubscription(TOR_TOP100_SUBSCRIPTION, emptyList())) + bridgeSubscriptions
    } else bridgeSubscriptions
    return copy(bridgeSubscriptions = sources, defaultBridgeSubscriptionInitialized = true)
}

internal fun TorSettings.manualBridges(): List<String> {
    manualBridgeEntries?.let { return it }
    val subscribed = bridgeSubscriptions.flatMapTo(hashSetOf()) { it.bridges }
    return bridges.filterNot { it in subscribed }
}

internal fun TorSettings.withBridgeSources(
    sources: List<TorBridgeSubscription> = bridgeSubscriptions,
    manual: List<String> = manualBridges()
): TorSettings {
    val combined = (manual + sources.flatMap { it.bridges }).distinct()
    val transport = bridgeTransport.takeIf { type -> type == "auto" || combined.any { torBridgeTransport(it) == type } } ?: "auto"
    return copy(
        bridges = combined,
        bridgeSubscriptions = sources,
        manualBridgeEntries = manual.distinct(),
        useBridges = useBridges && combined.isNotEmpty(),
        selectedBridge = selectedBridge.takeIf { it in combined }.orEmpty(),
        bridgeTransport = transport
    )
}

enum class TorBridgeMetric(val id: String) {
    LATENCY("latency"), SPEED("speed");
    companion object {
        fun fromId(id: String?): TorBridgeMetric = entries.firstOrNull { it.id == id } ?: LATENCY
    }
}

internal data class TorBridgeSpeedContext(
    val network: String, val entry: String, val exit: String, val excluded: String, val strict: Boolean,
    val sni: String, val circuitTimeout: Int, val circuitLifetime: Int, val optimistic: Boolean,
    val transport: String
) {
    companion object {
        fun from(settings: TorSettings, network: String) = TorBridgeSpeedContext(network, settings.entryNodes,
            settings.exitNodes, settings.excludeNodes, settings.strictNodes, settings.customBridgeSni,
            settings.circuitBuildTimeout, settings.maxCircuitDirtiness, settings.optimisticData, settings.bridgeTransport)
    }
}

data class TorImport(
    val bridges: List<String>,
    val entryNodes: String? = null,
    val exitNodes: String? = null,
    val excludeNodes: String? = null,
    val useBridges: Boolean? = null,
    val strictNodes: Boolean? = null,
    val skippedBridges: Int = 0
)

const val TOR_TOP100_SUBSCRIPTION = "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/TOR-BRIDGES/TOR_BRIDGES_TOP100.txt"

fun torBridgeTransport(bridge: String): String = when (bridge.substringBefore(' ').lowercase()) {
    "webtunnel" -> "webtunnel"
    "obfs4" -> "obfs4"
    "snowflake" -> "snowflake"
    else -> "vanilla"
}

/** A blocked transport must not consume the entire first TCP-probe batch. */
internal fun torBridgeProbeOrder(bridges: List<String>, rotation: Int): List<String> {
    val groups = bridges.groupBy(::torBridgeTransport).values.map { group ->
        val offset = Math.floorMod(rotation, group.size)
        group.drop(offset) + group.take(offset)
    }
    return buildList {
        for (row in 0 until (groups.maxOfOrNull { it.size } ?: 0)) groups.forEach { it.getOrNull(row)?.let(::add) }
    }
}

/** Keep the subscription intact while trying one bridge at a time in the live connection. */
fun TorSettings.forConnection(): TorSettings {
    if (!useBridges) return this
    val candidates = bridges.filter { bridgeTransport == "auto" || torBridgeTransport(it) == bridgeTransport }
    val selected = selectedBridge.takeIf { it in candidates }
        ?: candidates.firstOrNull { torBridgeTransport(it) == "vanilla" }
        ?: candidates.firstOrNull()
        ?: throw IllegalStateException("No bridges available for the selected transport")
    return copy(bridges = listOf(selected), selectedBridge = selected)
}
