package com.danieldk.splatevpn.data

import org.junit.Assert.*
import org.junit.Test

class QuickServerSelectionTest {
    private val now = 1_000_000L
    private fun node(id: String, personal: Boolean = false) = ServerNode(id, id, "vless", "192.0.2.1", 443,
        subId = if (personal) "" else "subscription")
    private fun proof(node: ServerNode, delay: Long = 50) = VerifiedServerDelay(QuickServerSelection.profile(node), delay, now, 1)
    @Test fun allSourcesIncludePersonalAndSubscriptionsAndReturnTenFastest() {
        val nodes = (1..20).map { node(it.toString(), it % 2 == 0) }
        val proofs = nodes.associate { it.id to proof(it, it.id.toLong()) }
        val result = QuickServerSelection.candidates(nodes.reversed(), proofs, QuickServerSource.ALL, now, 1) { true }
        assertEquals((1..10).map(Int::toString), result.map { it.server.id })
        assertTrue(result.any { it.server.subId.isBlank() })
        for (source in listOf(QuickServerSource.PERSONAL, QuickServerSource.SUBSCRIPTIONS)) {
            assertTrue(QuickServerSelection.candidates(nodes, proofs, source, now, 1) { true }
                .all { it.server.subId.isBlank() == (source == QuickServerSource.PERSONAL) })
        }
    }
    @Test fun rejectsWrongNetworkExpiredFutureAndDisabledResults() {
        val n = node("one")
        for (p in listOf(proof(n).copy(network = 2), proof(n).copy(checkedAt = now - 600001),
                proof(n).copy(checkedAt = now + 1), proof(n).copy(delayMs = -1))) {
            assertTrue(QuickServerSelection.candidates(listOf(n), mapOf(n.id to p), QuickServerSource.ALL, now, 1) { true }.isEmpty())
        }
        assertTrue(QuickServerSelection.candidates(listOf(n), mapOf(n.id to proof(n)), QuickServerSource.ALL, now, null) { true }.isEmpty())
        assertTrue(QuickServerSelection.candidates(listOf(n), mapOf(n.id to proof(n)), QuickServerSource.ALL, now, 1) { false }.isEmpty())
    }
    @Test fun tcpPingAndHistoricalDatabaseVerificationAreNotEnough() {
        val n = node("one").copy(ping = 5, lastVerifiedAt = now)
        assertTrue(QuickServerSelection.candidates(listOf(n), emptyMap(), QuickServerSource.ALL, now, 1) { true }.isEmpty())
    }
    @Test fun legacyPersonalFoldersAreNotMistakenForSubscriptions() {
        val nodes = listOf(node("one").copy(subId = "custom"), node("two").copy(subId = "builtin"))
        val proofs = nodes.associate { it.id to proof(it) }
        assertEquals(2, QuickServerSelection.candidates(nodes, proofs, QuickServerSource.PERSONAL, now, 1) { true }.size)
        assertTrue(QuickServerSelection.candidates(nodes, proofs, QuickServerSource.SUBSCRIPTIONS, now, 1) { true }.isEmpty())
    }
    @Test fun changedConnectionInvalidatesButRenameAndSelectionDoNot() {
        val n = node("one")
        val proofs = mapOf(n.id to proof(n))
        assertTrue(QuickServerSelection.candidates(listOf(n.copy(port = 8443)), proofs, QuickServerSource.ALL, now, 1) { true }.isEmpty())
        assertEquals(1, QuickServerSelection.candidates(listOf(n.copy(name = "Renamed", isSelected = true, ping = 1)),
            proofs, QuickServerSource.ALL, now, 1) { true }.size)
    }
}
