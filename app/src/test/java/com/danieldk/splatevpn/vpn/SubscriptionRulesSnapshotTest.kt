package com.danieldk.splatevpn.vpn

import com.danieldk.splatevpn.data.ServerNode
import com.danieldk.splatevpn.data.SubscriptionEntity
import org.junit.Assert.*
import org.junit.Test

class SubscriptionRulesSnapshotTest {
    private val node = ServerNode("connected", "Selected", "vless", "vpn.example", 443, subId = "active-source")
    private val approved = SubscriptionEntity("active-source", "Active source", "https://example.com/sub",
        useSubscriptionRules = true,
        importedRulesJson = """[{"type":"field","domain":["full:example.com"],"outboundTag":"direct"}]""")

    @Test fun revokingConsentDisablingOrDroppingTheActiveSourceRemovesAlreadyAppliedRules() {
        val applied = requireNotNull(SubscriptionRulesSnapshot.desired(node, listOf(approved)))
        val removed = listOf(
            listOf(approved.copy(useSubscriptionRules = false)),
            listOf(approved.copy(enabled = false)),
            listOf(approved.copy(importedRulesJson = "")),
            listOf(approved.copy(importedRulesSkipped = 1)),
            listOf(approved.copy(importedRulesJson = "damaged")),
            emptyList()
        )
        for (sources in removed) {
            val desired = SubscriptionRulesSnapshot.desired(node, sources)
            assertTrue(sources.toString(), applied.needsReload(desired, node.id))
            assertFalse("A successful reload must become the new baseline", requireNotNull(desired).needsReload(desired, node.id))
        }
    }

    @Test fun approvingOrChangingEffectiveRulesReloadsOnlyTheSameConnectedNode() {
        val empty = requireNotNull(SubscriptionRulesSnapshot.desired(node, emptyList()))
        val desired = requireNotNull(SubscriptionRulesSnapshot.desired(node, listOf(approved)))
        assertTrue(empty.needsReload(desired, node.id))
        val changed = SubscriptionRulesSnapshot.desired(node,
            listOf(approved.copy(importedRulesJson = approved.importedRulesJson.replace("example.com", "other.example"))))
        assertTrue(desired.needsReload(changed, node.id))
        assertFalse(desired.needsReload(changed, null))
        assertFalse(desired.needsReload(null, node.id))
        assertFalse(desired.needsReload(SubscriptionRulesSnapshot.desired(node.copy(id = "next-node"), listOf(approved)), node.id))
        assertFalse(desired.needsReload(changed, "next-node"))
    }

    @Test fun metadataAndOtherSubscriptionsNeverTriggerReloadOrReactivateUnapprovedRules() {
        val applied = requireNotNull(SubscriptionRulesSnapshot.desired(node, listOf(approved)))
        val unrelated = approved.copy(id = "unrelated", importedRulesJson = approved.importedRulesJson.replace("direct", "block"))
        val desired = SubscriptionRulesSnapshot.desired(node, listOf(approved.copy(name = "Renamed", nodeCount = 42,
            lastUpdated = 99, lastError = "Update failed"), unrelated))
        assertFalse(applied.needsReload(desired, node.id))
        val unapproved = approved.copy(useSubscriptionRules = false)
        val previous = requireNotNull(SubscriptionRulesSnapshot.desired(node, listOf(unapproved)))
        assertFalse(previous.needsReload(SubscriptionRulesSnapshot.desired(node, listOf(unapproved.copy(
            importedRulesJson = unrelated.importedRulesJson))), node.id))
    }
}
