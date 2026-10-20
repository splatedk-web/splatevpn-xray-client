package com.danieldk.splatevpn.data

/** Keeps local identity when a provider reorders or renames unchanged configurations. */
internal object SubscriptionNodeMerger {
    fun merge(previous: List<ServerNode>, incoming: List<ServerNode>): List<ServerNode> {
        val remaining = previous.toMutableList()
        return incoming.map { node ->
            val index = remaining.indexOfFirst { identity(it) == identity(node) }
            if (index < 0) {
                node.copy(isSelected = false)
            } else {
                val old = remaining.removeAt(index)
                node.copy(id = old.id, isSelected = old.isSelected, ping = old.ping)
            }
        }
    }

    private fun identity(node: ServerNode) = node.copy(
        id = "", name = "", ping = 0L, rawLink = "", isSelected = false,
        subId = "", subName = ""
    )
}
