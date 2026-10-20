package com.danieldk.splatevpn.data

/**
 * HEV JNI returns [txPackets, txBytes, rxPackets, rxBytes].
 * These byte counters exclude app updates, probes and subscriptions outside the TUN.
 */
internal class NativeTrafficCounter(initial: LongArray?) {
    private val baseline = initial?.takeIf { it.size >= 4 }
    private var rx = baseline?.get(3)?.coerceAtLeast(0) ?: 0
    private var tx = baseline?.get(1)?.coerceAtLeast(0) ?: 0

    fun sample(stats: LongArray?): Pair<Long, Long> {
        if (stats == null || stats.size < 4) return 0L to 0L
        val nextRx = stats[3].coerceAtLeast(0)
        val nextTx = stats[1].coerceAtLeast(0)
        val deltaRx = if (nextRx >= rx) nextRx - rx else nextRx
        val deltaTx = if (nextTx >= tx) nextTx - tx else nextTx
        rx = nextRx
        tx = nextTx
        return deltaRx to deltaTx
    }
}
