package com.danieldk.splatevpn.data

/** Keeps local identity when a provider reorders or renames unchanged configurations. */
internal object SubscriptionNodeMerger {
    const val MAX_RETAINED_NODES = 100
    const val RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000

    fun merge(previous: List<ServerNode>, incoming: List<ServerNode>, retainWorkingNodes: Boolean = false,
              now: Long = System.currentTimeMillis()): List<ServerNode> {
        // A queue per identity keeps a provider's duplicate entries distinct without quadratic scans.
        val remaining = linkedMapOf<ServerNode, ArrayDeque<ServerNode>>()
        previous.forEach { remaining.getOrPut(identity(it)) { ArrayDeque() }.addLast(it) }
        val refreshed = incoming.map { node ->
            val key = identity(node)
            val old = remaining[key]?.removeFirstOrNull()
            if (old == null) {
                node.copy(isSelected = false, lastVerifiedAt = 0, lastVerificationFailedAt = 0,
                    isRetained = false, retainedAt = 0)
            } else {
                node.copy(id = old.id, isSelected = old.isSelected, ping = old.ping,
                    lastVerifiedAt = old.lastVerifiedAt, lastVerificationFailedAt = old.lastVerificationFailedAt,
                    isRetained = false, retainedAt = 0)
            }
        }
        if (!retainWorkingNodes) return refreshed
        val presentIdentities = incoming.mapTo(hashSetOf()) { identity(it) }
        val retained = remaining.values.asSequence().flatten()
            .filter { it.subId.isNotEmpty() && identity(it) !in presentIdentities && canRetain(it, now) }
            .distinctBy(::identity)
            .sortedWith(compareByDescending<ServerNode> { it.isSelected }.thenByDescending { it.lastVerifiedAt }.thenBy { it.id })
            .take(MAX_RETAINED_NODES)
            .map { it.copy(isRetained = true, retainedAt = it.retainedAt.takeIf { value -> value > 0 } ?: now) }
            .toList()
        return refreshed + retained
    }

    fun canRetain(node: ServerNode, now: Long): Boolean = node.lastVerifiedAt > 0 &&
        node.lastVerifiedAt > node.lastVerificationFailedAt && node.lastVerifiedAt <= now &&
        now - node.lastVerifiedAt <= RETENTION_MILLIS &&
        (!node.isRetained || node.retainedAt > 0 && node.retainedAt <= now && now - node.retainedAt <= RETENTION_MILLIS)

    fun sameConnection(first: ServerNode, second: ServerNode): Boolean = identity(first) == identity(second)

    private fun identity(node: ServerNode) = node.copy(
        id = "", name = "", ping = 0L,
        // HY2 keeps additional connection parameters in its share link. Compare the generated
        // transport so aliases and equivalent port lists do not invalidate measured quality.
        // An invalid old link must not prevent the provider from replacing it with a valid one.
        rawLink = if (node.isHysteria2) runCatching { Hysteria2Profile.outbound(node).toString() }
            .getOrElse { node.rawLink } else "",
        isSelected = false,
        subId = "", subName = "", lastVerifiedAt = 0, lastVerificationFailedAt = 0,
        isRetained = false, retainedAt = 0
    )
}
