package com.danieldk.splatevpn.vpn

import java.util.UUID

enum class ConnectionStatus {
    ACCEPTED,
    REJECTED
}

enum class OutboundRuleType {
    PROXY,
    DIRECT,
    BLOCK,
    DNS,
    OTHER;

    fun label(): String = when (this) {
        PROXY -> "Proxy"
        DIRECT -> "Direct"
        BLOCK -> "Block"
        DNS -> "DNS"
        OTHER -> "Default"
    }
}

data class LiveConnection(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val lastSeen: Long = timestamp,
    val source: String,
    val status: ConnectionStatus,
    val network: String,
    val destination: String,
    val port: Int,
    val inboundTag: String,
    val outboundTag: String,
    val ruleType: OutboundRuleType,
    val isDomain: Boolean,
    val hitCount: Int = 1
) {
    val endpointKey: String
        get() = "$network:$destination:$port"
}
