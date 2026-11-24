package com.danieldk.splatevpn.data

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SubscriptionTransportTest {
    @Test fun unknownLengthCannotExceedLimit() {
        assertEquals(8, ByteArrayInputStream(ByteArray(8)).readLimited(8).size)
        assertThrows(IllegalArgumentException::class.java) { ByteArrayInputStream(ByteArray(9)).readLimited(8) }
    }

    @Test fun cancellingFetchClosesSocketWhileBodyIsStalled() = runBlocking {
        ServerSocket(0).use { server ->
            val bodyStarted = CountDownLatch(1)
            val socketClosed = CountDownLatch(1)
            val thread = Thread {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray()); flush()
                    }
                    bodyStarted.countDown()
                    if (socket.getInputStream().read() == -1) socketClosed.countDown()
                }
            }.apply { isDaemon = true; start() }
            val job = async(Dispatchers.IO) { SubscriptionTransport().fetch("http://127.0.0.1:${server.localPort}/private", "test") }
            assertTrue(withContext(Dispatchers.IO) { bodyStarted.await(5, TimeUnit.SECONDS) })
            job.cancelAndJoin()
            assertTrue("Cancelling must interrupt the HTTP body read", withContext(Dispatchers.IO) { socketClosed.await(5, TimeUnit.SECONDS) })
            thread.join(1000)
        }
    }

    @Test fun permanentHttpAndCertificateErrorsAreNotRetried() {
        assertFalse(isRetryableSubscriptionFailure(SubscriptionHttpException(403)))
        assertFalse(isRetryableSubscriptionFailure(java.io.IOException("wrapped", javax.net.ssl.SSLHandshakeException("expired"))))
        assertTrue(isRetryableSubscriptionFailure(SubscriptionHttpException(429)))
        assertTrue(isRetryableSubscriptionFailure(SubscriptionHttpException(503)))
        assertTrue(isRetryableSubscriptionFailure(java.net.SocketTimeoutException()))
        assertFalse(isRetryableSubscriptionFailure(IllegalArgumentException("invalid body")))
        assertEquals("Network error", subscriptionFailureLabel(java.io.IOException("https://private/token")))
    }
}
