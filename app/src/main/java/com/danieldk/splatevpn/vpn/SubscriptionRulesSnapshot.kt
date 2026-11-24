package com.danieldk.splatevpn.vpn

import com.danieldk.splatevpn.data.ServerNode
import com.danieldk.splatevpn.data.SubscriptionEntity
import com.danieldk.splatevpn.data.SubscriptionRoutingRules
import com.google.gson.JsonObject

data class SubscriptionRulesSnapshot(
    val nodeId: String,
    val rulesJson: String
) {
    fun needsReload(desired: SubscriptionRulesSnapshot?, connectedServerId: String?): Boolean {
        if (connectedServerId == null || connectedServerId != nodeId) return false
        if (desired == null || desired.nodeId != connectedServerId) return false
        return rulesJson != desired.rulesJson
    }

    companion object {
        fun desired(node: ServerNode?, subscriptions: List<SubscriptionEntity>): SubscriptionRulesSnapshot? {
            if (node == null) return null
            val active = SubscriptionRoutingRules.activeFor(node, subscriptions)
            return SubscriptionRulesSnapshot(node.id, active.toString())
        }

        fun applied(nodeId: String, rules: List<JsonObject>): SubscriptionRulesSnapshot {
            return SubscriptionRulesSnapshot(nodeId, rules.toString())
        }
    }
}
