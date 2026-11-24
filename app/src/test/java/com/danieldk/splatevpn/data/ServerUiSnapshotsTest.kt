package com.danieldk.splatevpn.data

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class ServerUiSnapshotsTest {
    @Test fun burstsKeepAllLatestResultsAndRemovedNodesStayRemoved() = runBlocking {
        val source = MutableStateFlow((0 until 100).map { ServerNode("$it", "Node $it", "vless", "example.com", 443, ping = 999) })
        val measured = MutableStateFlow<Map<String,Long>>(emptyMap())
        val delivered = Channel<List<ServerNode>>(Channel.UNLIMITED)
        val job = launch { serverUiSnapshots(source, measured).collect { delivered.send(it) } }
        try {
            val initial = withTimeout(3000) { delivered.receive() }
            assertTrue(initial.all { it.ping == 0L })
            repeat(100) { i -> measured.value = measured.value + ("$i" to i.toLong()+1) }
            val result = withTimeout(3000) { var value = delivered.receive(); while (value.last().ping != 100L) value = delivered.receive(); value }
            assertTrue(result.all { it.ping == it.id.toLong()+1 })
            source.value = source.value.drop(1)
            val removed = withTimeout(3000) { var value = delivered.receive(); while (value.size != 99) value = delivered.receive(); value }
            assertFalse(removed.any { it.id == "0" })
            assertTrue(source.value.all { it.ping == 999L })
        } finally { job.cancelAndJoin(); delivered.close() }
    }
    @Test fun unchangedLatencyReusesTheImmutableNode() {
        val node = ServerNode("node", "Node", "vless", "example.com", 443)
        assertSame(node, node.withSessionPing(emptyMap()))
        assertSame(node, node.withSessionPing(mapOf("node" to 0L)))
        assertNotSame(node, node.withSessionPing(mapOf("node" to 12L)))
    }
}
