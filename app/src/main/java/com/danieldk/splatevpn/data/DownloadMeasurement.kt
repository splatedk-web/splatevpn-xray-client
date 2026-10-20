package com.danieldk.splatevpn.data

/** Monotonic payload throughput; warm-up bytes cannot inflate the measured sample. */
internal class DownloadMeasurement(private val startNs: Long, private val warmupNs: Long = 500_000_000L) {
    private var totalBytes = 0L
    private var measuredBytes = 0L

    @Synchronized fun record(bytes: Int, readStartedNs: Long) {
        require(bytes >= 0)
        totalBytes += bytes
        if (readStartedNs >= startNs + warmupNs) measuredBytes += bytes
    }

    @Synchronized fun snapshot(nowNs: Long): Sample {
        val seconds = (nowNs - startNs - warmupNs).coerceAtLeast(0) / 1_000_000_000.0
        return Sample(totalBytes, measuredBytes, seconds,
            if (seconds > 0) (measuredBytes * 8.0 / seconds / 1_000_000.0).toFloat() else 0f)
    }

    data class Sample(val totalBytes: Long, val measuredBytes: Long, val seconds: Double, val mbps: Float)
}
