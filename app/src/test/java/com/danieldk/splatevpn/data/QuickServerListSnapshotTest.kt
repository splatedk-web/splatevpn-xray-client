package com.danieldk.splatevpn.data

import org.junit.Assert.*
import org.junit.Test

class QuickServerListSnapshotTest {
    @Test fun oldProjectionCannotPinTheOrderAfterSpeedRefresh() {
        val slow = candidate("slow", 20)
        val fast = candidate("fast", 200)
        val ids = setOf("slow", "fast")
        assertNull(QuickServerListSnapshot.publish(null, listOf(slow, fast), ids, 1, 2))
        val refreshed = QuickServerListSnapshot.publish(null, listOf(fast, slow), ids, 2, 2)
        assertEquals(listOf("fast", "slow"), refreshed!!.candidates.map { it.server.id })
    }
    private fun candidate(id: String, delay: Long) = QuickServerCandidate(ServerNode(id, id, "vless", "192.0.2.1", 443), delay, 1000)

    @Test fun newAlternativesFillTheOpenPickerWithoutMovingExistingRows() {
        val original = candidate("old", 100)
        val snapshot = QuickServerListSnapshot.capture(listOf(original))
        val grown = snapshot.append((1..15).map { candidate("new$it", it.toLong()) } + original.copy(delayMs = 1))
        assertEquals(10, grown.candidates.size)
        assertEquals(original, grown.candidates.first())
        assertEquals(10, grown.candidates.map { it.server.id }.toSet().size)
        assertEquals(grown, grown.append(grown.candidates.reversed()))
    }

    @Test fun deletedProfilesFreeSlotsWhileTemporarilyFailedProfilesStayVisible() {
        val original = (1..10).map { candidate("old$it", it.toLong()) }
        val snapshot = QuickServerListSnapshot.capture(original)
        val fresh = listOf(candidate("new", 2))
        val updated = snapshot.append(fresh, setOf("old1", "old2", "new"))
        assertEquals(listOf("old1", "old2", "new"), updated.candidates.map { it.server.id })
        assertEquals(setOf("new"), updated.available(fresh))
    }

    @Test fun backgroundMeasurementsDoNotReplaceDisplayedValuesOrReorderRows() {
        val first = candidate("a", 10)
        val second = candidate("b", 20)
        val snapshot = QuickServerListSnapshot.capture(listOf(first, second))
        val newer = listOf(second.copy(delayMs = 5, checkedAt = 2000), first.copy(delayMs = 30, checkedAt = 2000))
        assertEquals(listOf(first, second), snapshot.visible(setOf("a", "b")))
        assertEquals(setOf("a", "b"), snapshot.available(newer))
        assertEquals(listOf("b", "a"), QuickServerListSnapshot.capture(newer).candidates.map { it.server.id })
    }

    @Test fun failedThenSuccessfulCheckKeepsTheRowButChangesSelectability() {
        val node = candidate("a", 10)
        val snapshot = QuickServerListSnapshot.capture(listOf(node))
        assertEquals(listOf(node), snapshot.visible(setOf("a")))
        assertTrue(snapshot.available(emptyList()).isEmpty())
        assertEquals(listOf(node), snapshot.visible(setOf("a")))
        assertEquals(setOf("a"), snapshot.available(listOf(node.copy(delayMs = 40))))
        assertTrue(snapshot.available(listOf(node.copy(server = node.server.copy(port = 8443)))).isEmpty())
        assertTrue(snapshot.visible(emptySet()).isEmpty())
    }
}
