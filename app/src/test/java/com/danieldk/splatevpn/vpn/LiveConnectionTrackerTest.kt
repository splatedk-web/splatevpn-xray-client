package com.danieldk.splatevpn.vpn

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class LiveConnectionTrackerTest {
    private lateinit var tracker: LiveConnectionTracker

    @Before
    fun setUp() {
        tracker = LiveConnectionTracker()
        // Disable the timer so these tests control publication without sleeps.
        val scopeField = LiveConnectionTracker::class.java.getDeclaredField("scope")
        scopeField.isAccessible = true
        (scopeField.get(tracker) as CoroutineScope).cancel()
    }

    private fun publish() {
        val method = LiveConnectionTracker::class.java.getDeclaredMethod("publishSnapshot")
        method.isAccessible = true
        method.invoke(tracker)
    }

    private fun record(host: String, tag: String = "proxy") {
        assertTrue(tracker.recordLine("127.0.0.1:12345 accepted tcp:$host:443 [socks-in -> $tag]"))
    }

    @Test
    fun repeatedEndpointMovesToFrontAndUpdatesRoute() {
        record("one.example")
        record("two.example")
        record("one.example", "direct")
        publish()
        assertEquals(listOf("one.example", "two.example"), tracker.connections.value.map { it.destination })
        assertEquals(2, tracker.connections.value.first().hitCount)
        assertEquals(3, tracker.totalHits.value)
        assertEquals(1, tracker.directCount.value)
        assertEquals(1, tracker.proxyCount.value)
    }

    @Test
    fun capacityEvictsLeastRecentlyUsedEndpoint() {
        repeat(150) { record("host$it.example") }
        record("host0.example")
        record("new.example")
        publish()
        assertEquals(150, tracker.totalCount.value)
        assertTrue(tracker.connections.value.any { it.destination == "host0.example" })
        assertFalse(tracker.connections.value.any { it.destination == "host1.example" })
        assertEquals("new.example", tracker.connections.value.first().destination)
        assertEquals(152, tracker.totalHits.value)
    }

    @Test
    fun pauseFreezesRowsAndCountersUntilResume() {
        record("one.example")
        publish()
        val frozen = tracker.connections.value
        tracker.setPaused(true)
        record("two.example")
        publish()
        assertEquals(frozen, tracker.connections.value)
        assertEquals(1, tracker.totalHits.value)
        tracker.setPaused(false)
        publish()
        assertEquals(2, tracker.totalHits.value)
        assertEquals(frozen, tracker.connections.value)
    }

    @Test
    fun clearResetsRowsAndEveryCounterWhilePaused() {
        record("one.example", "direct")
        record("two.example", "block")
        record("three.example", "dns")
        publish()
        tracker.setPaused(true)
        tracker.clear()
        publish()
        assertTrue(tracker.connections.value.isEmpty())
        assertEquals(0, tracker.totalCount.value)
        assertEquals(0, tracker.totalHits.value)
        assertEquals(0, tracker.proxyCount.value)
        assertEquals(0, tracker.directCount.value)
        assertEquals(0, tracker.blockCount.value)
        assertEquals(0, tracker.dnsCount.value)
    }

    @Test
    fun ipv6AndRejectedTrafficAreParsedAndLoopbackIsExcluded() {
        assertFalse(tracker.recordLine("unrelated diagnostic line"))
        assertTrue(tracker.recordLine("127.0.0.1:123 accepted tcp:[2001:db8::1]:443 [proxy]"))
        assertTrue(tracker.recordLine("127.0.0.1:123 rejected udp:ads.example:53 [block]"))
        record("127.0.0.1")
        publish()
        assertEquals(2, tracker.totalCount.value)
        assertEquals(2, tracker.totalHits.value)
        assertEquals(1, tracker.blockCount.value)
        val ipv6 = tracker.connections.value.first { it.destination == "2001:db8::1" }
        assertFalse(ipv6.isDomain)
        assertEquals(443, ipv6.port)
    }
}
