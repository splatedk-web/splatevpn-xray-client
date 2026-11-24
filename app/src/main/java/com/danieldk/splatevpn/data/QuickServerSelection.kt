package com.danieldk.splatevpn.data

enum class QuickServerSource { ALL, SUBSCRIPTIONS, PERSONAL }
data class VerifiedServerDelay(val profile: ServerNode, val delayMs: Long, val checkedAt: Long, val network: Long)
data class QuickServerCandidate(val server: ServerNode, val delayMs: Long, val checkedAt: Long)
data class QuickServerCatalog(val nodes: List<ServerNode>, val subscriptions: List<SubscriptionEntity>)

internal object QuickServerSelection {
    const val FRESH_FOR_MS = 10 * 60_000L
    fun isPersonal(node: ServerNode) = node.subId.isBlank() || node.subId in setOf("custom", "builtin")
    fun profile(node: ServerNode) = node.copy(name = "", ping = 0, isSelected = false, subName = "",
        lastVerifiedAt = 0, lastVerificationFailedAt = 0, isRetained = false, retainedAt = 0)

    fun candidates(nodes: List<ServerNode>, proofs: Map<String, VerifiedServerDelay>, source: QuickServerSource,
        now: Long, network: Long?, limit: Int = 10, enabled: (ServerNode) -> Boolean): List<QuickServerCandidate> {
        if (network == null) return emptyList()
        return nodes.mapNotNull { node ->
            val proof = proofs[node.id] ?: return@mapNotNull null
            if (!enabled(node) || proof.network != network || proof.delayMs <= 0 ||
                now - proof.checkedAt !in 0..FRESH_FOR_MS || profile(node) != proof.profile ||
                source == QuickServerSource.SUBSCRIPTIONS && isPersonal(node) ||
                source == QuickServerSource.PERSONAL && !isPersonal(node)) return@mapNotNull null
            QuickServerCandidate(node, proof.delayMs, proof.checkedAt)
        }.sortedWith(compareBy<QuickServerCandidate> { it.delayMs }.thenBy { it.server.id }).take(limit)
    }
}
