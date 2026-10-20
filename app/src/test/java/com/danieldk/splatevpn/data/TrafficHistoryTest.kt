package com.danieldk.splatevpn.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class TrafficHistoryTest {
    private val zone = ZoneId.of("Asia/Irkutsk")

    @Test fun midnightSplitsBytesAndConnectionTimeWithoutLoss() {
        val midnight = LocalDate.of(2026, 10, 9).atStartOfDay(zone).toInstant().toEpochMilli()
        val rows = splitTrafficSample(midnight + 500, 1000, 101, 9, zone)
        assertEquals(listOf(LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 9)), rows.map { it.date })
        assertEquals(listOf(50L, 51L), rows.map { it.rx })
        assertEquals(9L, rows.sumOf { it.tx })
        assertEquals(1000L, rows.sumOf { it.connectedMs })
    }

    @Test fun monthBoundaryUsesSeparateDays() {
        val boundary = LocalDate.of(2026, 11, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val rows = splitTrafficSample(boundary + 1000, 2000, 500, 100, zone)
        assertEquals(10, rows.first().date.monthValue)
        assertEquals(11, rows.last().date.monthValue)
        assertEquals(500L, rows.sumOf { it.rx })
    }

    @Test fun daylightSavingUsesActualMidnightAndPreservesTotals() {
        val berlin = ZoneId.of("Europe/Berlin")
        val end = LocalDate.of(2026, 3, 30).atStartOfDay(berlin).toInstant().toEpochMilli()
        val rows = splitTrafficSample(end, 23 * 3_600_000L, 77, 11, berlin)
        assertEquals(1, rows.size)
        assertEquals(LocalDate.of(2026, 3, 29), rows.single().date)
        assertEquals(23 * 3_600_000L, rows.single().connectedMs)
    }

    @Test fun idleSampleStillRecordsTimeAndClampsNegativeTraffic() {
        val rows = splitTrafficSample(10000, 1000, -1, -4, zone)
        assertEquals(0L, rows.single().rx)
        assertEquals(0L, rows.single().tx)
        assertEquals(1000L, rows.single().connectedMs)
    }

    @Test fun restartingMonitorDoesNotReplayNativeTraffic() {
        val counter = NativeTrafficCounter(longArrayOf(1, 400, 2, 900))
        assertEquals(100L to 50L, counter.sample(longArrayOf(5, 450, 6, 1000)))
        assertEquals(0L to 0L, counter.sample(longArrayOf(5, 450, 6, 1000)))
    }

    @Test fun missingNativeSamplesRecoverWithoutUidFallbackDoubleCounting() {
        val counter = NativeTrafficCounter(longArrayOf(0, 100, 0, 200))
        assertEquals(0L to 0L, counter.sample(null))
        assertEquals(300L to 200L, counter.sample(longArrayOf(0, 300, 0, 500)))
        assertEquals(0L to 0L, counter.sample(longArrayOf(0, 300, 0, 500)))
    }

    @Test fun nativeRestartCountsOnlyNewCounterValues() {
        val counter = NativeTrafficCounter(longArrayOf(0, 100, 0, 200))
        assertEquals(12L to 5L, counter.sample(longArrayOf(0, 5, 0, 12)))
        assertEquals(3L to 2L, counter.sample(longArrayOf(0, 7, 0, 15)))
    }
}
