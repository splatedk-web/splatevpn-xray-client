package com.danieldk.splatevpn.data

import org.junit.Assert.*
import org.junit.Test

class DownloadMeasurementTest {
    @Test fun warmupPayloadDoesNotInflateMeasuredSpeed() {
        val meter = DownloadMeasurement(10_000_000_000L)
        meter.record(8_000_000, 10_000_000_000L)
        meter.record(1_000_000, 10_500_000_000L)
        val result = meter.snapshot(11_500_000_000L)
        assertEquals(9_000_000L, result.totalBytes)
        assertEquals(1_000_000L, result.measuredBytes)
        assertEquals(8f, result.mbps, .001f)
    }
    @Test fun throughputUsesDecimalMegabitsAndActualElapsedTime() {
        val meter = DownloadMeasurement(0, 0)
        meter.record(3_000_000, 0)
        assertEquals(12f, meter.snapshot(2_000_000_000).mbps, .001f)
        assertEquals(6f, meter.snapshot(4_000_000_000).mbps, .001f)
    }
    @Test fun zeroOrNegativeElapsedNeverProducesInfinity() {
        val meter = DownloadMeasurement(5_000, 0)
        meter.record(512, 5_000)
        assertEquals(0f, meter.snapshot(4_999).mbps, 0f)
        assertEquals(0f, meter.snapshot(5_000).mbps, 0f)
    }
    @Test fun parallelStreamsAggregateWithoutLosingBytes() {
        val meter = DownloadMeasurement(0, 0)
        val workers = List(2) { Thread { repeat(1000) { meter.record(500, 0) } } }
        workers.forEach { it.start() }
        workers.forEach { it.join() }
        assertEquals(1_000_000L, meter.snapshot(1_000_000_000).measuredBytes)
        assertEquals(8f, meter.snapshot(1_000_000_000).mbps, .001f)
    }
}
