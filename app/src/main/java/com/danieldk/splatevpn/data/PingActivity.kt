package com.danieldk.splatevpn.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class PingActivity {
    private val counts = mutableMapOf<String, Int>()
    private val mutableActiveIds = MutableStateFlow<Set<String>>(emptySet())
    val activeIds = mutableActiveIds.asStateFlow()

    suspend fun <T> track(id: String, block: suspend () -> T): T {
        synchronized(counts) {
            counts[id] = (counts[id] ?: 0) + 1
            mutableActiveIds.value = counts.keys.toSet()
        }
        try { return block() }
        finally {
            synchronized(counts) {
                val remaining = (counts[id] ?: 1) - 1
                if (remaining == 0) counts.remove(id) else counts[id] = remaining
                mutableActiveIds.value = counts.keys.toSet()
            }
        }
    }
}
