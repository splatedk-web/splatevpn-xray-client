package com.danieldk.splatevpn.data

import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.vpn.LocalProxyAccess
import com.danieldk.splatevpn.vpn.VpnState
import com.danieldk.splatevpn.vpn.VpnStatusManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class SpeedtestLifecycleTest {
    @Test fun disconnectedTestReturnsAnErrorWithoutNetworkRequests() = runBlocking {
        val status = VpnStatusManager(InstrumentationRegistry.getInstrumentation().targetContext)
        val manager = SpeedtestManager(status)
        manager.startSpeedtest()
        assertEquals(SpeedtestState.Error(SpeedtestError.VPN_REQUIRED), manager.testState.value)
    }

    @Test fun resetOnTheUiThreadStopsReadersAndDoesNotResurrectProgress() = runBlocking {
        Fixture().use { fixture ->
            val work = launch { fixture.run() }
            fixture.awaitPayload()
            InstrumentationRegistry.getInstrumentation().runOnMainSync { fixture.manager.reset() }
            withTimeout(4000) { work.join() }
            delay(350)
            assertEquals(SpeedtestState.Idle, fixture.manager.testState.value)
            fixture.manager.runSpeedtest(emptyList()) { 1 }
            assertEquals(SpeedtestState.Error(SpeedtestError.INVALID_SAMPLE), fixture.manager.testState.value)
        }
    }

    @Test fun cancellationByNetworkPolicyDoesNotLeaveTheTestRunning() = runBlocking {
        Fixture().use { fixture ->
            repeat(12) {
                val work = launch { fixture.run() }
                fixture.awaitPayload()
                withTimeout(4000) { work.cancelAndJoin() }
                assertEquals(SpeedtestState.Idle, fixture.manager.testState.value)
            }
        }
    }

    @Test fun tunnelLossBecomesAnErrorAndMalformedSamplesDoNotCrashTheCaller() = runBlocking {
        Fixture().use { fixture ->
            val work = launch { fixture.run() }
            fixture.awaitPayload()
            fixture.status.setState(VpnState.DISCONNECTED)
            withTimeout(5000) { work.join() }
            assertEquals(SpeedtestState.Error(SpeedtestError.VPN_CHANGED), fixture.manager.testState.value)
        }
        Fixture(textResponse = true).use { fixture ->
            withTimeout(5000) { fixture.run() }
            assertEquals(SpeedtestState.Error(SpeedtestError.INVALID_SAMPLE), fixture.manager.testState.value)
        }
    }

    private class Fixture(private val textResponse: Boolean = false) : AutoCloseable {
        val status = VpnStatusManager(InstrumentationRegistry.getInstrumentation().targetContext).apply {
            setState(VpnState.CONNECTED); connectedServerId = "speed-fixture"
        }
        val manager = SpeedtestManager(status)
        private val server = ServerSocket().apply {
            reuseAddress = true
            bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), 10808), 8)
        }
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        private val executor = Executors.newCachedThreadPool { task -> Thread(task, "speedtest-fixture").apply { isDaemon = true } }
        private val token = LocalProxyAccess.publish(null) { status.state.value == VpnState.CONNECTED }

        init {
            executor.submit {
                while (!server.isClosed) {
                    val socket = try { server.accept() } catch (_: Exception) { break }
                    sockets.add(socket)
                    executor.submit client@ {
                        try {
                            socket.use {
                                it.soTimeout = 3000
                                val reader = it.getInputStream().bufferedReader()
                                var lines = 0
                                while (true) {
                                    val line = reader.readLine() ?: return@client
                                    if (line.isEmpty()) break
                                    check(++lines < 100)
                                }
                                val output = it.getOutputStream()
                                output.write(("HTTP/1.1 200 OK\r\nContent-Type: " +
                                    (if (textResponse) "text/html" else "application/octet-stream") +
                                    "\r\nContent-Length: 10000000\r\nConnection: close\r\n\r\n").toByteArray())
                                output.flush()
                                val bytes = ByteArray(8192) { 1 }
                                repeat(1000) { output.write(bytes); output.flush(); Thread.sleep(10) }
                            }
                        } catch (_: Exception) { }
                        finally { sockets.remove(socket) }
                    }
                }
            }
        }

        suspend fun run() = manager.runSpeedtest(listOf(SpeedtestEndpoint("http://127.0.0.1/sample", 10_000_000))) { 1 }
        suspend fun awaitPayload() = withTimeout(7000) {
            manager.testState.first { it is SpeedtestState.Running && it.downloadedBytes > 0 }
        }

        override fun close() {
            manager.reset()
            LocalProxyAccess.revoke(token)
            server.close()
            sockets.forEach { runCatching { it.close() } }
            executor.shutdownNow()
        }
    }
}
