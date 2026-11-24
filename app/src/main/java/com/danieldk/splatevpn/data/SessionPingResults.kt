package com.danieldk.splatevpn.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** UI evidence for this process only; persisted latency is historical, not a fresh check. */
internal class SessionPingResults {
    private val values = MutableStateFlow<Map<String, Long>>(emptyMap())
    val results = values.asStateFlow()

    fun record(id: String, ping: Long) { values.update { it + (id to ping) } }
}

internal fun ServerNode.withSessionPing(results: Map<String, Long>): ServerNode {
    val current = results[id] ?: 0L
    return if (ping == current) this else copy(ping = current)
}
