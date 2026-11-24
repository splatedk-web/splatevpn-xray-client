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

    @Test fun changedHysteriaTransportDoesNotInheritOldHealthOrSelection() {
        val pin = "ab".repeat(32)
        val base = "hy2://auth@example.com:443,8443/?obfs=salamander&obfs-password=secret&pinSHA256=$pin&hop-interval=5"
        val old = hysteria("old", base).copy(isSelected = true, ping = 42)
        val changes = listOf(
            base.replace("443,8443", "443,9443"),
            base.replace("hop-interval=5", "hop-interval=10"),
            base.replace("obfs-password=secret", "obfs-password=changed"),
            base.replace("obfs=salamander&obfs-password=secret&", ""),
            base.replace(pin, "cd".repeat(32))
        )
        for (link in changes) {
            val result = SubscriptionNodeMerger.merge(listOf(old), listOf(hysteria("new", link))).single()
            assertEquals(link, "new", result.id)
            assertEquals(link, 0L, result.ping)
            assertFalse(link, result.isSelected)
        }
    }

    @Test fun equivalentHysteriaPortListsAndIntervalAliasesKeepHealth() {
        val old = hysteria("old", "hy2://auth@example.com:443/?mport=443,8443-8445&hopInterval=5")
            .copy(isSelected = true, ping = 42)
        val incoming = hysteria("new", "hysteria2://auth@example.com:8445,443,8443-8444/?hop-interval=5s#Renamed")
        val result = SubscriptionNodeMerger.merge(listOf(old), listOf(incoming)).single()
        assertEquals("old", result.id)
        assertEquals(42L, result.ping)
        assertTrue(result.isSelected)
        assertEquals("Renamed", result.name)
        assertEquals(incoming.rawLink, result.rawLink)
    }

    @Test fun equivalentHysteriaCertificatePinAndQueryOrderKeepHealth() {
        val pin = "ab".repeat(32)
        val old = hysteria("old", "hy2://auth@example.com/?pinSHA256=$pin&sni=example.com")
            .copy(isSelected = true, ping = 42)
        val incoming = hysteria("new", "hy2://auth@example.com/?sni=example.com&pinSHA256=${pin.chunked(2).joinToString(":")}")
        val result = SubscriptionNodeMerger.merge(listOf(old), listOf(incoming)).single()
        assertEquals("old", result.id)
        assertEquals(42L, result.ping)
        assertTrue(result.isSelected)
    }

    @Test fun unusedHoppingIntervalOnSinglePortDoesNotInvalidateHealth() {
        val old = hysteria("old", "hy2://auth@example.com:443/?hopInterval=5").copy(ping = 42)
        val incoming = hysteria("new", "hy2://auth@example.com:443/?hop-interval=10")
        val result = SubscriptionNodeMerger.merge(listOf(old), listOf(incoming)).single()
        assertEquals("old", result.id)
        assertEquals(42L, result.ping)
    }

    @Test fun invalidOldHysteriaLinkCanBeReplacedByAValidTransport() {
        val incoming = hysteria("new", "hy2://auth@example.com:443/")
        val old = incoming.copy(id = "old", rawLink = "hy2://auth@example.com:443/?obfs=unsupported", ping = 42, isSelected = true)
        val result = SubscriptionNodeMerger.merge(listOf(old), listOf(incoming)).single()
        assertEquals("new", result.id)
        assertEquals(0L, result.ping)
        assertFalse(result.isSelected)
    }

    private fun hysteria(id: String, link: String) = requireNotNull(Hysteria2Profile.parse(link)).copy(id = id)
}
