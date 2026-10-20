package com.danieldk.splatevpn.data

import org.junit.Assert.*
import org.junit.Test

class SubscriptionNodeMergerTest {
    private fun node(id: String, address: String = "example.com") =
        ServerNode(id, "Server", "vless", address, 443, uuid = "key")

    @Test fun preservesSelectionAndIdentityAfterRenameAndReorder() {
        val selected = node("selected").copy(isSelected = true, ping = 42)
        val other = node("other", "other.example")
        val result = SubscriptionNodeMerger.merge(listOf(selected, other), listOf(
            other.copy(id = "new-other"), selected.copy(id = "new-selected", name = "Renamed", ping = 0)
        ))
        assertEquals(listOf("other", "selected"), result.map { it.id })
        assertTrue(result[1].isSelected)
        assertEquals(42L, result[1].ping)
        assertEquals("Renamed", result[1].name)
    }

    @Test fun changedCredentialsAreANewNode() {
        val old = node("old").copy(isSelected = true)
        val result = SubscriptionNodeMerger.merge(listOf(old), listOf(node("new").copy(uuid = "new-key")))
        assertEquals("new", result.single().id)
        assertFalse(result.single().isSelected)
    }

    @Test fun changedFullConfigIsANewNode() {
        val old = node("old").copy(isSelected = true, fullConfigJson = "{\"outbounds\":[{\"tag\":\"old\"}]}")
        val incoming = old.copy(id = "new", fullConfigJson = "{\"outbounds\":[{\"tag\":\"new\"}]}")
        val result = SubscriptionNodeMerger.merge(listOf(old), listOf(incoming))
        assertEquals("new", result.single().id)
        assertFalse(result.single().isSelected)
    }

    @Test fun duplicatesDoNotReuseTheSamePrimaryKey() {
        val old = node("old").copy(isSelected = true)
        val result = SubscriptionNodeMerger.merge(listOf(old), listOf(node("new-1"), node("new-2")))
        assertEquals(2, result.map { it.id }.distinct().size)
        assertEquals(1, result.count { it.isSelected })
    }

    @Test fun removedSelectionIsNotTransferredToAnUnrelatedNode() {
        val result = SubscriptionNodeMerger.merge(
            listOf(node("old").copy(isSelected = true)), listOf(node("new", "new.example"))
        )
        assertFalse(result.single().isSelected)
    }
}
