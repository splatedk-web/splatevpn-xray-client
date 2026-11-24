package com.danieldk.splatevpn.vpn

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import androidx.test.platform.app.InstrumentationRegistry
import com.v2ray.ang.service.TProxyService
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real HEV/lwIP processes IP packets from a datagram socketpair in place of a VPN TUN.
 * SOCKS stays on loopback. Documentation-only IP destinations are never dialled by the fixture.
 */
class HevAppIdentityNativeTest {
    @Test fun udpUsesOriginalFiveTupleOnIpv4AndIpv6AndRejectsUnknownIdentity() {
        assertEquals(1, TProxyService.identityApiVersion())
        val lookups = ConcurrentLinkedQueue<Flow>()
        val resolver = TProxyService.FlowIdentityResolver { protocol, local, localPort, remote, remotePort ->
            lookups += Flow(protocol, local, localPort, remote, remotePort)
            when (localPort) {
                31999 -> null
                31998 -> throw IllegalArgumentException("Deliberate identity lookup failure")
                31997 -> "invalid\nidentity"
                else -> identity(protocol, localPort, remotePort)
            }
        }
        SocksOrigin().use { socks -> withTunnel(socks, resolver) { tun ->
            val first = Flow(17, "172.19.0.2", 31001, "198.51.100.10", 443)
            // Same application port, different destination: cannot share one global UDP association.
            val second = first.copy(remote = "198.51.100.11", remotePort = 8443)
            val ipv6 = Flow(17, "fd00::2", 31002, "2001:db8::10", 443)
            for ((index, flow) in listOf(first, second, ipv6).withIndex()) {
                tun.send(ipPacket(flow, udp(flow, "udp-$index".toByteArray())))
                await("SOCKS association for $flow") { socks.requests.size >= index + 1 }
                assertFlow(flow, lookups.toList()[index])
                assertEquals(identity(flow.protocol, flow.localPort, flow.remotePort), socks.requests.toList()[index].user)
                assertEquals(3, socks.requests.toList()[index].command)
            }
            assertEquals(3, lookups.size)
            assertEquals(3, socks.requests.size)
            val before = socks.accepted.get()
            for (port in listOf(31999, 31998, 31997)) {
                val rejected = first.copy(localPort = port)
                tun.send(ipPacket(rejected, udp(rejected, "must-drop".toByteArray())))
            }
            await("all rejected identity lookups") { lookups.size >= 6 }
            // A rejected flow must not even open the private SOCKS transport.
            assertFalse("Rejected identity connected to SOCKS", waitUntil(250) { socks.accepted.get() != before })
            assertEquals(3, socks.requests.size)
            assertTrue(socks.failures.toString(), socks.failures.isEmpty())
        } }
    }

    @Test fun tcpUsesOriginalTupleAfterIpv4AndIpv6Handshakes() {
        val lookups = ConcurrentLinkedQueue<Flow>()
        val resolver = TProxyService.FlowIdentityResolver { protocol, local, localPort, remote, remotePort ->
            lookups += Flow(protocol, local, localPort, remote, remotePort)
            identity(protocol, localPort, remotePort)
        }
        SocksOrigin().use { socks -> withTunnel(socks, resolver) { tun ->
            val flows = listOf(Flow(6, "172.19.0.2", 32001, "198.51.100.20", 443),
                Flow(6, "fd00::2", 32002, "2001:db8::20", 8443))
            for ((index, flow) in flows.withIndex()) {
                tun.openTcp(flow)
                await("TCP SOCKS request for $flow") { socks.requests.size >= index + 1 }
                assertFlow(flow, lookups.toList()[index])
                val request = socks.requests.toList()[index]
                assertEquals(1, request.command)
                assertEquals(identity(6, flow.localPort, flow.remotePort), request.user)
                assertEquals(InetAddress.getByName(flow.remote), InetAddress.getByName(request.host))
                assertEquals(flow.remotePort, request.port)
            }
            assertEquals(2, lookups.size)
            assertTrue(socks.failures.toString(), socks.failures.isEmpty())
        } }
    }

    @Test fun legacyCredentialsAndRepeatedNativeStartStopRemainSupported() {
        SocksOrigin().use { socks ->
            repeat(2) { index -> withTunnel(socks, null) { tun ->
                val flow = Flow(17, "172.19.0.2", 33000 + index, "198.51.100.30", 443)
                tun.send(ipPacket(flow, udp(flow, byteArrayOf(1, 2, 3))))
                await("legacy SOCKS request after start $index") { socks.requests.size >= index + 1 }
                assertEquals(LEGACY_USER, socks.requests.toList()[index].user)
                assertTrue(socks.failures.toString(), socks.failures.isEmpty())
            } }
        }
    }

    private fun withTunnel(socks: SocksOrigin, resolver: TProxyService.FlowIdentityResolver?, block: (FakeTun) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(app.cacheDir, "hev-identity-${UUID.randomUUID()}.yaml").apply { writeText("""
            tunnel:
              mtu: 1500
              ipv4: 172.19.0.1
              ipv6: 'fd00::1'
            socks5:
              address: 127.0.0.1
              port: ${socks.port}
              udp: 'udp'
              username: '$LEGACY_USER'
              password: '$PASSWORD'
            misc:
              connect-timeout: 2000
              tcp-read-write-timeout: 6000
              udp-read-write-timeout: 6000
              log-level: warn
        """.trimIndent()) }
        FakeTun().use { tun ->
            val service = TProxyService(tun.nativeEnd)
            try {
                service.start(file.absolutePath, resolver)
                block(tun)
            } finally {
                service.stop()
                file.delete()
            }
        }
    }

    private class FakeTun : Closeable {
        private val endpoint = FileDescriptor()
        val nativeEnd: ParcelFileDescriptor
        init {
            val nativeFd = FileDescriptor()
            Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_DGRAM, 0, nativeFd, endpoint)
            nativeEnd = try { ParcelFileDescriptor.dup(nativeFd) } finally { Os.close(nativeFd) }
        }

        fun send(packet: ByteArray) { assertEquals(packet.size, Os.write(endpoint, packet, 0, packet.size)) }

        fun openTcp(flow: Flow) {
            val sequence = 1000L + flow.localPort
            send(ipPacket(flow, tcp(flow, sequence, 0, 2)))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
            while (System.nanoTime() < deadline) {
                val poll = StructPollfd().apply { fd = endpoint; events = OsConstants.POLLIN.toShort() }
                if (Os.poll(arrayOf(poll), 100) == 0) continue
                val buffer = ByteArray(4096)
                val count = Os.read(endpoint, buffer, 0, buffer.size)
                if (count < 40) continue
                val headerLength = if ((buffer[0].toInt() ushr 4) and 15 == 4) (buffer[0].toInt() and 15) * 4 else 40
                if (count < headerLength + 20 || unsignedShort(buffer, headerLength) != flow.remotePort ||
                    unsignedShort(buffer, headerLength + 2) != flow.localPort) continue
                val flags = buffer[headerLength + 13].toInt() and 255
                if (flags and 0x12 != 0x12) continue
                val response = ByteBuffer.wrap(buffer, headerLength + 4, 8)
                val peerSequence = response.int.toLong() and 0xffffffffL
                val acknowledged = response.int.toLong() and 0xffffffffL
                assertEquals(sequence + 1, acknowledged)
                send(ipPacket(flow, tcp(flow, sequence + 1, peerSequence + 1, 0x10)))
                // Give the accepted TCP connection data, including when HEV delays its SOCKS connect.
                send(ipPacket(flow, tcp(flow, sequence + 1, peerSequence + 1, 0x18,
                    "GET / HTTP/1.0\r\n\r\n".toByteArray(Charsets.US_ASCII))))
                return
            }
            fail("No HEV SYN/ACK for $flow")
        }

        override fun close() { nativeEnd.close(); Os.close(endpoint) }
    }

    private data class SocksRequest(val user: String, val command: Int, val host: String, val port: Int)

    private class SocksOrigin : Closeable {
        private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 100 }
        private val executor = Executors.newCachedThreadPool()
        private val sockets = ConcurrentLinkedQueue<Socket>()
        private val udpSockets = ConcurrentLinkedQueue<DatagramSocket>()
        val accepted = AtomicInteger()
        val requests = ConcurrentLinkedQueue<SocksRequest>()
        val failures = ConcurrentLinkedQueue<Throwable>()
        val port get() = listener.localPort
        init { executor.submit {
            while (!listener.isClosed) {
                val client = try { listener.accept() } catch (_: SocketTimeoutException) { continue }
                    catch (error: IOException) { if (listener.isClosed) break else throw error }
                sockets += client; accepted.incrementAndGet()
                executor.submit { handle(client) }
            }
        } }

        private fun handle(client: Socket) {
            try {
                client.soTimeout = 6000
                val input = DataInputStream(client.getInputStream())
                val output = client.getOutputStream()
                assertEquals(5, input.readUnsignedByte())
                val methods = ByteArray(input.readUnsignedByte()).also(input::readFully)
                assertTrue("HEV must advertise password authentication", 2.toByte() in methods)
                output.write(byteArrayOf(5, 2)); output.flush()
                assertEquals(1, input.readUnsignedByte())
                val username = ByteArray(input.readUnsignedByte()).also(input::readFully).toString(Charsets.US_ASCII)
                val password = ByteArray(input.readUnsignedByte()).also(input::readFully).toString(Charsets.US_ASCII)
                assertEquals(PASSWORD, password)
                output.write(byteArrayOf(1, 0)); output.flush()
                assertEquals(5, input.readUnsignedByte())
                val command = input.readUnsignedByte()
                assertEquals(0, input.readUnsignedByte())
                val host = when (input.readUnsignedByte()) {
                    1 -> InetAddress.getByAddress(ByteArray(4).also(input::readFully)).hostAddress!!
                    4 -> InetAddress.getByAddress(ByteArray(16).also(input::readFully)).hostAddress!!
                    3 -> ByteArray(input.readUnsignedByte()).also(input::readFully).toString(Charsets.US_ASCII)
                    else -> throw IOException("Invalid HEV SOCKS target")
                }
                val targetPort = input.readUnsignedShort()
                requests += SocksRequest(username, command, host, targetPort)
                val relay = if (command == 3) DatagramSocket(InetSocketAddress("127.0.0.1", 0)).also { udpSockets += it } else null
                val relayPort = relay?.localPort ?: 0
                output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, (relayPort ushr 8).toByte(), relayPort.toByte()))
                output.flush()
                try { while (input.read() != -1) { } } catch (_: IOException) { }
            } catch (error: Throwable) {
                if (!listener.isClosed) failures += error
            } finally { client.close() }
        }

        override fun close() {
            listener.close(); sockets.forEach { it.close() }; udpSockets.forEach { it.close() }
            executor.shutdownNow(); executor.awaitTermination(3, TimeUnit.SECONDS)
        }
    }

    private data class Flow(val protocol: Int, val local: String, val localPort: Int, val remote: String, val remotePort: Int)

    companion object {
        private const val LEGACY_USER = "legacy-bridge-user"
        private const val PASSWORD = "private-native-bridge-password"
        private fun identity(protocol: Int, localPort: Int, remotePort: Int) = "flow-$protocol-$localPort-$remotePort"

        private fun await(message: String, condition: () -> Boolean) { assertTrue(message, waitUntil(4000, condition)) }
        private fun waitUntil(millis: Long, condition: () -> Boolean): Boolean {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis)
            do { if (condition()) return true; Thread.sleep(10) } while (System.nanoTime() < deadline)
            return condition()
        }
        private fun assertFlow(expected: Flow, actual: Flow) {
            assertEquals(expected.protocol, actual.protocol)
            assertEquals(InetAddress.getByName(expected.local), InetAddress.getByName(actual.local))
            assertEquals(expected.localPort, actual.localPort)
            assertEquals(InetAddress.getByName(expected.remote), InetAddress.getByName(actual.remote))
            assertEquals(expected.remotePort, actual.remotePort)
        }

        private fun ipPacket(flow: Flow, transport: ByteArray): ByteArray {
            val source = InetAddress.getByName(flow.local).address
            val destination = InetAddress.getByName(flow.remote).address
            val ipv6 = source.size == 16
            val packet = ByteBuffer.allocate((if (ipv6) 40 else 20) + transport.size)
            if (ipv6) {
                packet.putInt(0x60000000); packet.putShort(transport.size.toShort())
                packet.put(flow.protocol.toByte()); packet.put(64); packet.put(source); packet.put(destination)
            } else {
                packet.put(0x45); packet.put(0); packet.putShort(packet.capacity().toShort())
                packet.putShort(0); packet.putShort(0x4000.toShort()); packet.put(64); packet.put(flow.protocol.toByte())
                packet.putShort(0); packet.put(source); packet.put(destination)
                packet.putShort(10, checksum(packet.array(), 0, 20).toShort())
            }
            packet.put(transport)
            return packet.array()
        }

        private fun udp(flow: Flow, body: ByteArray): ByteArray {
            val result = ByteBuffer.allocate(8 + body.size).apply {
                putShort(flow.localPort.toShort()); putShort(flow.remotePort.toShort())
                putShort(capacity().toShort()); putShort(0); put(body)
            }.array()
            ByteBuffer.wrap(result).putShort(6, transportChecksum(flow, result).toShort())
            return result
        }

        private fun tcp(flow: Flow, sequence: Long, ack: Long, flags: Int, body: ByteArray = ByteArray(0)): ByteArray {
            val result = ByteBuffer.allocate(20 + body.size).apply {
                putShort(flow.localPort.toShort()); putShort(flow.remotePort.toShort())
                putInt(sequence.toInt()); putInt(ack.toInt()); put(0x50); put(flags.toByte())
                putShort(65535.toShort()); putShort(0); putShort(0); put(body)
            }.array()
            ByteBuffer.wrap(result).putShort(16, transportChecksum(flow, result).toShort())
            return result
        }

        private fun transportChecksum(flow: Flow, payload: ByteArray): Int {
            val source = InetAddress.getByName(flow.local).address
            val destination = InetAddress.getByName(flow.remote).address
            val pseudo = ByteBuffer.allocate(source.size * 2 + (if (source.size == 16) 8 else 4) + payload.size)
                .put(source).put(destination)
            if (source.size == 16) {
                pseudo.putInt(payload.size); pseudo.putInt(flow.protocol)
            } else {
                pseudo.put(0); pseudo.put(flow.protocol.toByte()); pseudo.putShort(payload.size.toShort())
            }
            pseudo.put(payload)
            return checksum(pseudo.array(), 0, pseudo.capacity()).let { if (it == 0) 65535 else it }
        }

        private fun checksum(bytes: ByteArray, start: Int, length: Int): Int {
            var sum = 0L
            var index = start
            while (index + 1 < start + length) {
                sum += unsignedShort(bytes, index).toLong(); index += 2
            }
            if (index < start + length) sum += (bytes[index].toInt() and 255).toLong() shl 8
            while (sum > 65535) sum = (sum and 65535) + (sum ushr 16)
            return sum.toInt().inv() and 65535
        }
        private fun unsignedShort(bytes: ByteArray, offset: Int) =
            ((bytes[offset].toInt() and 255) shl 8) or (bytes[offset + 1].toInt() and 255)
    }
}
