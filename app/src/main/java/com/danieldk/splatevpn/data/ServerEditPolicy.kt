package com.danieldk.splatevpn.data

internal object ServerEditPolicy {
    fun supportsConnectionEditing(server: ServerNode): Boolean = server.fullConfigJson.isNullOrBlank()

    fun prepare(current: ServerNode, edited: ServerNode): ServerNode {
        require(current.id == edited.id) { "Server identity changed" }
        require(edited.name.isNotBlank() && edited.name.none(Char::isISOControl)) { "Enter a server name" }
        if (edited.isAmneziaWg) {
            val p = requireNotNull(edited.amneziaWg).validated(); val endpoint = AmneziaWg.endpoint(p.endpoint)
            require(edited.address == endpoint.first && edited.port == endpoint.second && edited.rawLink.isEmpty() && edited.fullConfigJson.isNullOrEmpty()) { "Inconsistent AWG profile" }
        } else require(edited.amneziaWg == null) { "Unexpected AWG profile" }
        val candidate = edited.copy(
            name = edited.name.trim(),
            isSelected = current.isSelected,
            subId = current.subId, subName = current.subName,
            ping = current.ping,
            lastVerifiedAt = current.lastVerifiedAt,
            lastVerificationFailedAt = current.lastVerificationFailedAt,
            isRetained = current.isRetained, retainedAt = current.retainedAt
        )
        require(supportsConnectionEditing(current) || SubscriptionNodeMerger.sameConnection(current, candidate)) {
            "This server uses a full JSON configuration. Only its name can be changed in this editor."
        }
        return candidate
    }
}
