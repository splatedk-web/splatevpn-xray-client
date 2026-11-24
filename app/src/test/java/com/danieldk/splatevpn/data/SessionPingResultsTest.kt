package com.danieldk.splatevpn.data

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SessionPingResultsTest {
    @Test fun previousProcessMeasurementsAreNotDisplayedAsFreshChecks() {
        val historical = ServerNode("node", "Server", "vless", "example.com", 443, ping = -1L)
        val session = SessionPingResults()
        assertEquals(0L, historical.withSessionPing(session.results.value).ping)
        assertEquals(0L, historical.copy(ping = 123L).withSessionPing(session.results.value).ping)
        session.record("node", -1L)
        assertEquals(-1L, historical.withSessionPing(session.results.value).ping)
        session.record("node", 42L)
        assertEquals(42L, historical.withSessionPing(session.results.value).ping)
        assertEquals(0L, historical.withSessionPing(SessionPingResults().results.value).ping)
        assertEquals(-1L, historical.ping)
    }

    @Test fun parallelProbeResultsDoNotOverwriteOtherServers() = runBlocking {
        val session = SessionPingResults()
        coroutineScope {
            repeat(200) { index -> launch(Dispatchers.Default) { session.record("node-$index", index.toLong() + 1) } }
        }
        assertEquals(200, session.results.value.size)
        repeat(200) { index -> assertEquals(index.toLong() + 1, session.results.value["node-$index"]) }
    }
}
