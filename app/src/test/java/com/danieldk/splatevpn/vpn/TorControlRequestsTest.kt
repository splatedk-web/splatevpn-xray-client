package com.danieldk.splatevpn.vpn

import kotlinx.coroutines.*
import net.freehaven.tor.control.TorControlConnection
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class TorControlRequestsTest {
    @Test fun cancellingStalledAuthenticationRemainsCancellationAndReplacementCanAuthenticate() = runBlocking {
        val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val requests = TorControlRequests(workerScope, timeoutMillis = 10_000)
        try {
            FakeControlServer(stallAuthentication = true).use { server ->
                val socket = server.connect()
                val connection = TorControlConnection(socket)
                val failure = AtomicReference<Throwable?>()
                val job = launch {
                    try { requests.execute(socket) { connection.authenticate(ByteArray(32)) } }
                    catch (error: Throwable) { failure.set(error) }
                }
                assertTrue(withContext(Dispatchers.IO) { server.authenticationRead.await(5, TimeUnit.SECONDS) })
                withTimeout(2_000) { job.cancelAndJoin() }
                assertTrue("The jtorctl Interrupted IOException must not replace cancellation", failure.get() is CancellationException)
                assertTrue("Cancelling authentication must close its socket", socket.isClosed)
                assertTrue(withContext(Dispatchers.IO) { server.peerClosed.await(2, TimeUnit.SECONDS) })
            }
            FakeControlServer().use { replacement ->
                replacement.connect().use { socket ->
                    val connection = TorControlConnection(socket)
                    requests.execute(socket) { connection.authenticate(ByteArray(32)) }
                    assertEquals("0.4.9.14", requests.execute(socket) { connection.getInfo("version") })
                    assertFalse("An old request must not close the replacement connection", socket.isClosed)
                }
            }
        } finally { workerScope.cancel() }
    }

    @Test fun deadlineClosesStalledAuthenticationAndRemainsTimeout() = runBlocking {
        val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            FakeControlServer(stallAuthentication = true).use { server ->
                val socket = server.connect()
                val connection = TorControlConnection(socket)
                val requests = TorControlRequests(workerScope, timeoutMillis = 1_000)
                val error = runCatching { requests.execute(socket) { connection.authenticate(ByteArray(32)) } }.exceptionOrNull()
                assertTrue("The caller must see the deadline, rather than jtorctl's I/O wrapper", error is TimeoutCancellationException)
                assertTrue(socket.isClosed)
                assertTrue(withContext(Dispatchers.IO) { server.peerClosed.await(2, TimeUnit.SECONDS) })
            }
        } finally { workerScope.cancel() }
    }

    @Test fun actualAuthenticationFailureIsStillAnIoFailure() = runBlocking {
        val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            FakeControlServer(rejectAuthentication = true).use { server ->
                server.connect().use { socket ->
                    val connection = TorControlConnection(socket)
                    val error = runCatching {
                        TorControlRequests(workerScope).execute(socket) { connection.authenticate(ByteArray(32)) }
                    }.exceptionOrNull()
                    assertTrue(error is IOException)
                    assertFalse(error is CancellationException)
                }
            }
        } finally { workerScope.cancel() }
    }

    /** Uses the actual jtorctl parser/waiter against a deterministic local control endpoint. */
    private class FakeControlServer(
        private val stallAuthentication: Boolean = false,
        private val rejectAuthentication: Boolean = false
    ) : Closeable {
        private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val accepted = AtomicReference<Socket?>()
        val authenticationRead = CountDownLatch(1)
        val peerClosed = CountDownLatch(1)
        private val thread = Thread {
            runCatching {
                listener.accept().use { socket ->
                    accepted.set(socket)
                    socket.soTimeout = 5_000
                    val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                    val writer = socket.getOutputStream().bufferedWriter(Charsets.US_ASCII)
                    check(reader.readLine().startsWith("AUTHENTICATE "))
                    authenticationRead.countDown()
                    if (stallAuthentication) {
                        if (reader.read() == -1) peerClosed.countDown()
                    } else {
                        writer.write(if (rejectAuthentication) "515 Authentication failed\r\n" else "250 OK\r\n")
                        writer.flush()
                        if (!rejectAuthentication && reader.readLine() == "GETINFO version") {
                            writer.write("250-version=0.4.9.14\r\n250 OK\r\n")
                            writer.flush()
                            while (reader.readLine() != null) { }
                        }
                    }
                }
            }
        }.apply { isDaemon = true; name = "fake-tor-control"; start() }

        fun connect(): Socket = Socket("127.0.0.1", listener.localPort)

        override fun close() {
            runCatching { listener.close() }
            runCatching { accepted.get()?.close() }
            thread.join(2_000)
        }
    }
}
