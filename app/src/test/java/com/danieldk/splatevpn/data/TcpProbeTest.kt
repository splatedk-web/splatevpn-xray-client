package com.danieldk.splatevpn.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

class TcpProbeTest {
    @Test fun reachablePortDoesNotRequireTlsOrApplicationResponse() = runBlocking {
        // A listener that accepts TCP but never sends TLS/application bytes must pass TCP ping.
        ServerSocket(0, 16, InetAddress.getLoopbackAddress()).use { listener ->
            assertTrue(TcpProbe.measure("127.0.0.1", listener.localPort, 1500) > 0)
        }
    }

    @Test fun refusedConnectionIsNotReportedAsReachable() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        assertEquals(-1L, TcpProbe.measure("127.0.0.1", port, 1500))
    }
}
