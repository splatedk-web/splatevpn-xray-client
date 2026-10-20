package com.danieldk.splatevpn.data

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import java.io.Closeable
import java.net.ServerSocket
import java.net.Socket
import java.net.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ConnectionChecksTest {
    private class Endpoint(private val code: Int?, private val location: String? = null) : Closeable {
        private val listener = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        @Volatile private var socket: Socket? = null
        val accepted = CountDownLatch(1)
        val url = "http://127.0.0.1:${listener.localPort}/"
        init {
            Thread {
                try {
                    listener.accept().use { client ->
                        socket = client
                        client.soTimeout = 3000
                        val reader = client.getInputStream().bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) {}
                        accepted.countDown()
                        if (code != null) {
                            val redirect = location?.let { "Location: $it\r\n" } ?: ""
                            client.getOutputStream().write("HTTP/1.1 $code Test\r\n${redirect}Content-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        } else client.getInputStream().read()
                    }
                } catch (_: Exception) {}
            }.apply { isDaemon = true; start() }
        }
        override fun close() { listener.close(); socket?.close() }
    }

    private fun client() = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false)
        .callTimeout(2, TimeUnit.SECONDS).build()

    @Test fun httpAccessRestrictionsAreNotReportedAsNetworkFailures() {
        listOf(401, 403, 429).forEach { assertEquals(CheckOutcome.LIMITED, classifyHttp(it)) }
        listOf(200, 204, 301, 302).forEach { assertEquals(CheckOutcome.OK, classifyHttp(it)) }
        listOf(404, 500, 503).forEach { assertEquals(CheckOutcome.FAILED, classifyHttp(it)) }
    }

    @Test fun realHttpRestrictionKeepsStatusAndTiming() = runBlocking {
        Endpoint(403).use { endpoint ->
            val result = checkHttp(client(), "Service", endpoint.url)
            assertEquals(CheckOutcome.LIMITED, result.outcome)
            assertEquals("HTTP 403", result.detail)
            assertNotNull(result.elapsedMs)
        }
    }

    @Test fun diagnosticRedirectCannotMasqueradeAsHealthyTunnel() = runBlocking {
        Endpoint(302, "http://127.0.0.1:1/login").use { endpoint ->
            val result = checkHttp(client(), "Tunnel", endpoint.url, connectivity = true)
            assertEquals(CheckOutcome.FAILED, result.outcome)
            assertEquals("HTTP 302", result.detail)
        }
    }

    @Test fun coroutineCancellationCancelsUnderlyingHttpCall() = runBlocking {
        Endpoint(null).use { endpoint ->
            val cancelled = AtomicBoolean(false)
            val client = client().newBuilder().eventListener(object : EventListener() {
                override fun canceled(call: Call) { cancelled.set(true) }
            }).build()
            val job = launch { checkHttp(client, "Slow", endpoint.url) }
            withTimeout(3000) { while (endpoint.accepted.count > 0) delay(10) }
            job.cancelAndJoin()
            assertTrue(cancelled.get())
        }
    }

    @Test fun silentEndpointProducesBoundedTimeout() = runBlocking {
        Endpoint(null).use { endpoint ->
            val client = client().newBuilder().callTimeout(200, TimeUnit.MILLISECONDS).build()
            val result = withTimeout(2000) { checkHttp(client, "Slow", endpoint.url) }
            assertEquals(CheckOutcome.FAILED, result.outcome)
            assertEquals("timeout", result.detail)
        }
    }
}
