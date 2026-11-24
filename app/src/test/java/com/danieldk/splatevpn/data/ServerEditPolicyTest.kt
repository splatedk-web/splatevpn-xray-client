package com.danieldk.splatevpn.data

import org.junit.Assert.*
import org.junit.Test

class ServerEditPolicyTest {
    private val ordinary = ServerNode("node", "Server", "vless", "example.com", 443, uuid = "test-id")
    private val raw = """{"outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"example.com","port":443,"users":[{"id":"test-id"}]}]},"customField":{"keep":true}}]}"""

    @Test fun renamingAnImportedProfilePreservesItsCompleteConfiguration() {
        val current = ordinary.copy(fullConfigJson = raw)
        assertFalse(ServerEditPolicy.supportsConnectionEditing(current))
        val updated = ServerEditPolicy.prepare(current, current.copy(name = "  Renamed  "))
        assertEquals("Renamed", updated.name)
        assertEquals(raw, updated.fullConfigJson)
        assertTrue(SubscriptionNodeMerger.sameConnection(current, updated))
    }

    @Test fun importedConnectionEditsCannotAppearSavedWhileOldJsonStillRuns() {
        val current = ordinary.copy(fullConfigJson = raw)
        for (edited in listOf(current.copy(port = 8443), current.copy(address = "other.example"),
                current.copy(uuid = "new-id"), current.copy(network = "ws"), current.copy(security = "tls"),
                current.copy(fullConfigJson = ""))) {
            assertTrue(runCatching { ServerEditPolicy.prepare(current, edited) }.isFailure)
        }
    }

    @Test fun savingAnOldEditorDoesNotUndoActiveSelectionOrFreshVerification() {
        val current = ordinary.copy(isSelected = false, ping = 42, lastVerifiedAt = 900,
            lastVerificationFailedAt = 800, subId = "sub", subName = "Renamed subscription",
            isRetained = true, retainedAt = 700)
        val stale = ordinary.copy(name = "Edited", isSelected = true, ping = 200, subName = "Old subscription")
        val updated = ServerEditPolicy.prepare(current, stale)
        assertEquals("Edited", updated.name)
        assertFalse(updated.isSelected)
        assertEquals(42L, updated.ping)
        assertEquals(900L, updated.lastVerifiedAt)
        assertEquals(800L, updated.lastVerificationFailedAt)
        assertEquals("sub", updated.subId)
        assertEquals("Renamed subscription", updated.subName)
        assertTrue(updated.isRetained)
        assertEquals(700L, updated.retainedAt)
    }

    @Test fun ordinaryConnectionEditsKeepExtraParametersFromTheirImportLink() {
        val hy2 = ordinary.copy(protocol = "hysteria2", rawLink = "hysteria2://secret@example.com:443?mport=443,8443&obfs=salamander&obfs-password=key")
        val reality = ordinary.copy(rawLink = "vless://test-id@example.com:443?security=reality&pqv=verification-key")
        for (current in listOf(hy2, reality)) {
            assertTrue(ServerEditPolicy.supportsConnectionEditing(current))
            val updated = ServerEditPolicy.prepare(current, current.copy(name = "Edited", port = 9443))
            assertEquals(9443, updated.port)
            assertEquals(current.rawLink, updated.rawLink)
        }
        assertTrue(runCatching { ServerEditPolicy.prepare(ordinary, ordinary.copy(name = " ")) }.isFailure)
    }
}
