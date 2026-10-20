package com.danieldk.splatevpn.data

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeTrafficCounterTest {
    @Test fun uploadUsesTransmittedBytesNotReceivedPacketCount() {
        // JNI layout verified in the bundled HEV libraries: TX packets/bytes, RX packets/bytes.
        val counter = NativeTrafficCounter(longArrayOf(0, 0, 0, 0))
        val download = 3L * 1024 * 1024 * 1024
        val upload = 150L * 1024 * 1024
        assertEquals(download to upload, counter.sample(longArrayOf(80_000, upload, 7_000_000, download)))
    }

    @Test fun uploadOnlyTrafficStillCountsWhenNoPacketsArrive() {
        val counter = NativeTrafficCounter(null)
        assertEquals(0L to 1_048_576L, counter.sample(longArrayOf(800, 1_048_576, 0, 0)))
    }

    @Test fun packetsWithoutAdditionalBytesDoNotChangeTotals() {
        val counter = NativeTrafficCounter(longArrayOf(10, 1000, 20, 2000))
        assertEquals(0L to 0L, counter.sample(longArrayOf(30, 1000, 50, 2000)))
    }

    @Test fun malformedSamplesDoNotReplaceValidBaseline() {
        val counter = NativeTrafficCounter(longArrayOf(10, 1000, 20, 2000))
        assertEquals(0L to 0L, counter.sample(longArrayOf(900, 900_000)))
        assertEquals(200L to 100L, counter.sample(longArrayOf(11, 1100, 22, 2200)))
    }

    @Test fun malformedInitialSampleDoesNotCreatePartialBaseline() {
        val counter = NativeTrafficCounter(longArrayOf(10, 999_999))
        assertEquals(200L to 100L, counter.sample(longArrayOf(1, 100, 2, 200)))
    }
}
