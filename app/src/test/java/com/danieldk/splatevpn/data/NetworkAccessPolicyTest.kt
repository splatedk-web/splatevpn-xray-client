package com.danieldk.splatevpn.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NetworkAccessPolicyTest {
    private val wifi = NetworkRouteState(internet = true, wifi = true)
    private val mobile = NetworkRouteState(internet = true, wifi = false, cellular = true)

    @Test fun onlyDefaultWifiAuthorizesTraffic() {
        assertTrue(wifiRouteAllowed(wifi))
        assertFalse(wifiRouteAllowed(mobile))
        assertFalse(wifiRouteAllowed(null))
        assertFalse(wifiRouteAllowed(wifi.copy(internet = false)))
        assertFalse(wifiRouteAllowed(wifi.copy(suspended = true)))
        // Metering is independent: paid Wi-Fi is Wi-Fi, free cellular is still cellular.
        assertTrue(wifiRouteAllowed(wifi.copy(unmetered = false)))
        assertFalse(wifiRouteAllowed(mobile.copy(unmetered = true)))
    }

    @Test fun vpnMustNotHideCellularOrAnUnknownUpstream() {
        assertTrue(wifiRouteAllowed(wifi.copy(vpn = true), wifi))
        assertTrue(wifiRouteAllowed(wifi, wifi))
        assertFalse(wifiRouteAllowed(wifi.copy(vpn = true, cellular = true)))
        assertFalse(wifiRouteAllowed(NetworkRouteState(internet = true, wifi = false, vpn = true)))
        assertFalse(wifiRouteAllowed(wifi, mobile))
        assertFalse(wifiRouteAllowed(mobile, wifi))
    }

    @Test fun deniedOperationDoesNotStartOrCommitHealth() = runBlocking {
        var contacted = false
        var committed = false
        try {
            val result = runWithNetworkPermission({ false }) { contacted = true; -1L }
            committed = result < 0
            fail("Wi-Fi denial must be distinct from an unhealthy server")
        } catch (_: WifiRequiredException) { }
        assertFalse(contacted)
        assertFalse(committed)
    }

    @Test fun losingWifiCancelsInFlightProbeWithoutReturningDeadResult() = runBlocking {
        val allowed = AtomicBoolean(true)
        val started = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        val pendingResponse = CompletableDeferred<Long>()
        var healthWritten = false
        val result = async {
            try {
                val ping = runWithNetworkPermission(allowed::get) {
                    started.complete(Unit)
                    try { pendingResponse.await() } finally { closed.complete(Unit) }
                }
                healthWritten = ping < 0
                false
            } catch (_: WifiRequiredException) { true }
        }
        started.await()
        allowed.set(false)
        assertTrue(result.await())
        assertTrue(closed.isCompleted)
        assertFalse(healthWritten)
    }

    @Test fun wifiLossClosesAStalledHttpBodyInsteadOfContinuingDownload() = runBlocking {
        ServerSocket(0).use { server ->
            val headersSent = CountDownLatch(1)
            val closed = CountDownLatch(1)
            val peer = Thread {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray())
                        flush()
                    }
                    headersSent.countDown()
                    if (socket.getInputStream().read() == -1) closed.countDown()
                }
            }.apply { isDaemon = true; start() }
            val permitted = AtomicBoolean(true)
            val client = OkHttpClient()
            val result = async(Dispatchers.IO) {
                try {
                    runWithNetworkPermission(permitted::get) {
                        client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/").build())
                            .awaitConsumedResponse { response -> response.body!!.byteStream().readLimited(1024) }
                    }
                    false
                } catch (_: WifiRequiredException) { true }
            }
            assertTrue(withContext(Dispatchers.IO) { headersSent.await(5, TimeUnit.SECONDS) })
            permitted.set(false)
            assertTrue(withTimeout(3000) { result.await() })
            assertTrue("Wi-Fi loss must cancel the body read", withContext(Dispatchers.IO) { closed.await(3, TimeUnit.SECONDS) })
            peer.join(1000)
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
