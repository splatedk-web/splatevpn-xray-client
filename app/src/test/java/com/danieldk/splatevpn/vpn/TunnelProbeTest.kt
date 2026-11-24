package com.danieldk.splatevpn.vpn

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate

class TunnelProbeTest {
    companion object {
        private val certificate = HeldCertificate.Builder().commonName("localhost")
            .addSubjectAlternativeName("127.0.0.1").build()
        private val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        private val client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
    }
    @Test fun plaintext204CannotProveHttpsConnectivity() = runBlocking {
        Endpoint(0, tls = false).use { first -> Endpoint(0, tls = false).use { second ->
            assertEquals(-1L, TunnelProbe.measure(Proxy.NO_PROXY, first.url, 1000, second.url, client = client))
        } }
    }

    @Test fun workingHttpCannotHideAnInvalidHttpsCertificate() = runBlocking {
        val untrusted = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        Endpoint(0, tls = false).use { plain -> Endpoint(0, peer = untrusted).use { tls ->
            assertEquals(-1L, TunnelProbe.measure(Proxy.NO_PROXY, plain.url, 1500, tls.url, client = client))
        } }
    }

    @Test fun httpsRedirectToPlaintextIsRejected() = runBlocking {
        Endpoint(0, tls = false).use { plain -> Endpoint(0, 302, location = plain.url).use { redirect ->
            assertEquals(-1L, TunnelProbe.measure(Proxy.NO_PROXY, redirect.url, 1000, redirect.url, client = client))
        } }
    }
    private class Endpoint(private val delayMs: Long, private val code: Int = 204, tls: Boolean = true,
        peer: HeldCertificate = certificate, private val location: String? = null) : Closeable {
        private val listener = if (tls) HandshakeCertificates.Builder().heldCertificate(peer).build()
            .sslContext().serverSocketFactory.createServerSocket(0, 16, java.net.InetAddress.getLoopbackAddress())
            else ServerSocket(0, 16, java.net.InetAddress.getLoopbackAddress())
        private val clients = CopyOnWriteArrayList<Socket>()
        private val threads = CopyOnWriteArrayList<Thread>()
        val url = "${if (tls) "https" else "http"}://127.0.0.1:${listener.localPort}/check"
        init {
            Thread {
                try {
                    while (!listener.isClosed) {
                        val client = listener.accept()
                        clients.add(client)
                        val thread = Thread {
                            try {
                                client.use {
                                    it.soTimeout = 1000
                                    val reader = it.getInputStream().bufferedReader()
                                    while (!reader.readLine().isNullOrEmpty()) { }
                                    Thread.sleep(delayMs)
                                    val redirect = location?.let { target -> "Location: $target\r\n" }.orEmpty()
                                    it.getOutputStream().write("HTTP/1.1 $code Test\r\n${redirect}Content-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                                }
                            } catch (_: Exception) { }
                        }.apply { isDaemon = true }
                        threads.add(thread)
                        thread.start()
                    }
                } catch (_: Exception) { }
            }.apply { isDaemon = true; start() }
        }
        override fun close() {
            listener.close()
            clients.forEach { try { it.close() } catch (_: Exception) { } }
            threads.forEach { it.interrupt() }
        }
    }

    @Test fun slowPrimaryDoesNotDelayWorkingFallback() = runBlocking {
        Endpoint(5000).use { slow -> Endpoint(10).use { fast ->
            val started = System.nanoTime()
            val result = TunnelProbe.measure(Proxy.NO_PROXY, slow.url, 1500, fast.url, client = client)
            assertTrue(result > 0)
            assertTrue("A slow endpoint must not hold up a working one", (System.nanoTime() - started) / 1_000_000 < 1000)
        } }
    }

    @Test fun bothUnresponsiveEndpointsRespectOverallBudget() = runBlocking {
        Endpoint(5000).use { first -> Endpoint(5000).use { second ->
            val started = System.nanoTime()
            assertEquals(-1L, TunnelProbe.measure(Proxy.NO_PROXY, first.url, 300, second.url, client = client))
            assertTrue((System.nanoTime() - started) / 1_000_000 < 1000)
        } }
    }

    @Test fun responseAfterHalfBudgetIsStillAccepted() = runBlocking {
        Endpoint(1400).use { endpoint ->
            // Regression: readTimeout used to be half the overall budget (1000 ms).
            assertTrue(TunnelProbe.measure(Proxy.NO_PROXY, endpoint.url, 2400, endpoint.url, client = client) > 0)
        }
    }

    @Test fun badHttpResponseIsNotReportedAsHealthy() = runBlocking {
        Endpoint(0, 503).use { first -> Endpoint(0, 500).use { second ->
            assertEquals(-1L, TunnelProbe.measure(Proxy.NO_PROXY, first.url, 1000, second.url, client = client))
        } }
    }
    @Test fun captivePortalAndRedirectAreNotVerifiedAsWorkingTunnel() = runBlocking {
        Endpoint(0, 200).use { portal -> Endpoint(0, 302).use { redirect ->
            assertEquals(-1L, TunnelProbe.measure(Proxy.NO_PROXY, portal.url, 1000, redirect.url, client = client))
        } }
    }

    @Test fun blockedPrimaryStillAcceptsReal204Fallback() = runBlocking {
        Endpoint(0, 200).use { blocked -> Endpoint(15, 204).use { healthy ->
            assertTrue(TunnelProbe.measure(Proxy.NO_PROXY, blocked.url, 1000, healthy.url, client = client) > 0)
        } }
    }
}
