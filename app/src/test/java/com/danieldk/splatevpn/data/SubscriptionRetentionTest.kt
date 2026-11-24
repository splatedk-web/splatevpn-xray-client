package com.danieldk.splatevpn.data

import org.junit.Assert.*
import org.junit.Test

class SubscriptionRetentionTest {
    private val now = 100L * 24 * 60 * 60 * 1000
    private fun node(id: String, address: String = "$id.example") =
        ServerNode(id, id, "vless", address, 443, uuid = "key", subId = "sub")
    private fun verified(id: String) = node(id).copy(lastVerifiedAt = now - 1000, ping = 50)

    @Test fun removedHttpVerifiedNodeIsRetainedButTcpLatencyAloneIsNotEvidence() {
        val old = verified("working").copy(isSelected = true)
        val tcpOnly = node("tcp-only").copy(ping = 1)
        val merged = SubscriptionNodeMerger.merge(listOf(old, tcpOnly), listOf(node("new")), true, now)
        assertEquals(listOf("new", "working"), merged.map { it.id })
        assertTrue(merged.last().isRetained)
        assertTrue(merged.last().isSelected)
        assertEquals(now, merged.last().retainedAt)
    }

    @Test fun aLaterFailureRevokesReserveEligibilityUntilANewerSuccess() {
        val failed = verified("old").copy(lastVerificationFailedAt = now - 500)
        assertFalse(SubscriptionNodeMerger.canRetain(failed, now))
        assertTrue(SubscriptionNodeMerger.canRetain(failed.copy(lastVerifiedAt = now - 100), now))
        assertEquals(listOf("new"), SubscriptionNodeMerger.merge(listOf(failed), listOf(node("new")), true, now).map { it.id })
    }

    @Test fun boundsApplyToProofAgeAndFirstDisappearanceIncludingClockRollback() {
        val old = verified("old")
        val invalid = listOf(
            old.copy(lastVerifiedAt = now - SubscriptionNodeMerger.RETENTION_MILLIS - 1),
            old.copy(lastVerifiedAt = now + 1),
            old.copy(isRetained = true, retainedAt = now - SubscriptionNodeMerger.RETENTION_MILLIS - 1),
            old.copy(isRetained = true, retainedAt = 0),
            old.copy(isRetained = true, retainedAt = now + 1)
        )
        invalid.forEach { assertFalse(it.toString(), SubscriptionNodeMerger.canRetain(it, now)) }
        assertTrue(SubscriptionNodeMerger.canRetain(old.copy(lastVerifiedAt = now - SubscriptionNodeMerger.RETENTION_MILLIS), now))
    }

    @Test fun repeatedRefreshDoesNotExtendFirstDisappearanceAndReappearanceClearsReserve() {
        val first = SubscriptionNodeMerger.merge(listOf(verified("old")), listOf(node("current")), true, now)
        val next = SubscriptionNodeMerger.merge(first, listOf(node("current")), true, now + 10_000)
        val retained = next.single { it.id == "old" }
        assertEquals(now, retained.retainedAt)
        val reappeared = SubscriptionNodeMerger.merge(next, listOf(node("fresh-id", retained.address)), true, now + 20_000)
            .single { it.id == "old" }
        assertFalse(reappeared.isRetained)
        assertEquals(0L, reappeared.retainedAt)
        assertEquals(retained.lastVerifiedAt, reappeared.lastVerifiedAt)
    }

    @Test fun reserveHasAFixedLimitPreferringSelectedAndThenNewestVerifiedNodes() {
        val previous = (0..120).map { index -> verified("old-$index").copy(lastVerifiedAt = now - index - 1) }
            .map { if (it.id == "old-120") it.copy(isSelected = true) else it }
        val result = SubscriptionNodeMerger.merge(previous, listOf(node("current")), true, now)
        val reserve = result.filter { it.isRetained }
        assertEquals(100, reserve.size)
        assertEquals("old-120", reserve.first().id)
        assertTrue(reserve.any { it.id == "old-0" })
        assertFalse(reserve.any { it.id == "old-119" })
    }

    @Test fun changedCredentialsDoNotInheritVerificationButOldWorkingProfileCanRemainInReserve() {
        val old = verified("old")
        val incoming = old.copy(id = "new", uuid = "replacement-key", lastVerifiedAt = now, isSelected = true)
        val result = SubscriptionNodeMerger.merge(listOf(old), listOf(incoming), true, now)
        assertEquals(0L, result.first().lastVerifiedAt)
        assertFalse(result.first().isSelected)
        assertTrue(result.last().isRetained)
        assertEquals("key", result.last().uuid)
        assertFalse(SubscriptionNodeMerger.sameConnection(old, incoming))
    }

    @Test fun reserveCanBeDisabledAndDuplicateOldProfilesAreNotAccumulated() {
        val old = verified("old")
        val duplicate = old.copy(id = "duplicate")
        assertEquals(1, SubscriptionNodeMerger.merge(listOf(old), listOf(node("new")), false, now).size)
        val result = SubscriptionNodeMerger.merge(listOf(old, duplicate), listOf(node("new")), true, now)
        assertEquals(1, result.count { it.isRetained })
        val returned = SubscriptionNodeMerger.merge(listOf(old, duplicate), listOf(old.copy(id = "new")), true, now)
        assertEquals(1, returned.size)
        assertFalse(returned.single().isRetained)
    }

    @Test fun metadataAndRenameDoNotChangeConnectionIdentity() {
        val old = verified("old")
        val updated = old.copy(name = "Renamed", lastVerifiedAt = now, isRetained = true, retainedAt = now,
            lastVerificationFailedAt = now, ping = 10, subName = "Renamed provider")
        assertTrue(SubscriptionNodeMerger.sameConnection(old, updated))
    }
}
