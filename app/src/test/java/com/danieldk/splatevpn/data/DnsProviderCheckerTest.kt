package com.danieldk.splatevpn.data

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DnsProviderCheckerTest {
    private fun answer(query: ByteArray): ByteArray = query.copyOf().apply {
        this[2] = 0x81.toByte(); this[3] = 0x80.toByte(); this[7] = 1
    } + byteArrayOf(0xc0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 192.toByte(), 0, 2, 1)

    @Test fun acceptsAResponseAndRejectsWrongIdsErrorsAndTruncation() {
        val query = DnsProbeWire.query(1234)
        val good = answer(query)
        assertTrue(DnsProbeWire.valid(good, 1234))
        assertFalse(DnsProbeWire.valid(good, 1235))
        assertFalse(DnsProbeWire.valid(query, 1234))
        assertFalse(DnsProbeWire.valid(good.copyOf(good.size - 1), 1234))
        assertFalse(DnsProbeWire.valid(good.copyOf().apply { this[3] = 0x83.toByte() }, 1234))
        assertFalse(DnsProbeWire.valid(good.copyOf().apply { this[2] = 0x83.toByte() }, 1234))
    }
    @Test fun rejectsWrongQuestionsAndCyclicCompressionPointers() {
        val good = answer(DnsProbeWire.query(42))
        assertFalse(DnsProbeWire.valid(good.copyOf().apply { this[13] = 'z'.code.toByte() }, 42))
        assertFalse(DnsProbeWire.valid(good.copyOf().apply { this[12] = 0xc0.toByte(); this[13] = 12 }, 42))
        assertFalse(DnsProbeWire.valid(ByteArray(65536), 42))
    }
    @Test fun endpointParsingDoesNotTreatUrlsOrCredentialsAsPlainDnsAddresses() {
        val checker = DnsProviderChecker()
        assertEquals("1.1.1.1" to 53, checker.tcpAddress("1.1.1.1"))
        assertEquals("127.0.0.1" to 5353, checker.tcpAddress("tcp://127.0.0.1:5353"))
        listOf("999.1.1.1", "https://resolver.example/dns-query", "tcp://user:pass@resolver.example", "tcp://resolver.example:99999", "tcp://resolver.example/path", "").forEach {
            assertNull(it, checker.tcpAddress(it))
        }
    }
    @Test fun tcpCheckRequiresAValidDnsReplyNotJustAnOpenPort() = runBlocking {
        ServerSocket(0).use { server ->
            val responder = async(Dispatchers.IO) {
                repeat(2) { attempt -> server.accept().use { socket ->
                    val input = DataInputStream(socket.getInputStream())
                    val query = ByteArray(input.readUnsignedShort()).also(input::readFully)
                    val payload = if (attempt == 0) answer(query) else "not a DNS response".toByteArray()
                    DataOutputStream(socket.getOutputStream()).apply { writeShort(payload.size); write(payload); flush() }
                } }
            }
            val endpoint = DnsProviderEndpoint("fixture", "Test", "tcp://127.0.0.1:${server.localPort}")
            val checker = DnsProviderChecker()
            val healthy = checker.check(endpoint, false)
            assertEquals(DnsProviderCheckStatus.AVAILABLE, healthy.status)
            assertTrue(requireNotNull(healthy.latencyMs) > 0)
            assertEquals(DnsProviderCheckStatus.FAILED, checker.check(endpoint, false).status)
            responder.await()
        }
    }
    @Test fun cancellationClosesTheDnsSocket() = runBlocking {
        ServerSocket(0).use { server ->
            val closed = CountDownLatch(1)
            val queried = CountDownLatch(1)
            val responder = async(Dispatchers.IO) {
                server.accept().use { socket ->
                    socket.soTimeout = 2000
                    val input = DataInputStream(socket.getInputStream())
                    ByteArray(input.readUnsignedShort()).also(input::readFully)
                    queried.countDown()
                    if (input.read() == -1) closed.countDown()
                }
            }
            val checker = DnsProviderChecker()
            val request = launch { checker.check(DnsProviderEndpoint("fixture", "", "tcp://127.0.0.1:${server.localPort}"), false) }
            withTimeout(2000) { while (queried.count > 0) delay(10) }
            request.cancelAndJoin()
            assertTrue(closed.await(2, TimeUnit.SECONDS))
            responder.await()
        }
    }
}
