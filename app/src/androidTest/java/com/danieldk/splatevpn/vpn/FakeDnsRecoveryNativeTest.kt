package com.danieldk.splatevpn.vpn

import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.SplateConstants
import com.danieldk.splatevpn.data.*
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.*
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Loopback-only checks against the shipped Xray, including actual DNS packets and FakeIP traffic. */
class FakeDnsRecoveryNativeTest {
    @Test(timeout = 60000) fun fakeDnsStillNeedsRealDnsAndResolverDiagnosisWorksThroughTheSameTunnel() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val status = VpnStatusManager(context)
        val core = XrayCoreManager(context, AppLogManager(context), status, LiveConnectionTracker(), testHealthAdvisor(context, status))
        core.ensureAssetsExtracted()
        val workers = Executors.newCachedThreadPool()
        val origin = ServerSocket(0, 8, InetAddress.getByName("127.0.0.4"))
        val healthy = AtomicBoolean(false)
        val dnsRequests = AtomicInteger()
        val originRequests = AtomicInteger()
        val listeners = mutableListOf(origin)
        fun resolver(available: () -> Boolean): Int {
            val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.3"))
            listeners += listener
            workers.submit {
                while (!listener.isClosed) {
                    val socket = runCatching { listener.accept() }.getOrNull() ?: break
                    workers.submit { runCatching { socket.use {
                        it.soTimeout = 3000
                        val input = DataInputStream(it.getInputStream())
                        val out = DataOutputStream(it.getOutputStream())
                        while (true) {
                            val query = ByteArray(input.readUnsignedShort()).also(input::readFully)
                            dnsRequests.incrementAndGet()
                            val ok = available()
                            val answer = query.copyOf().apply {
                                this[2] = 0x81.toByte(); this[3] = if (ok) 0x80.toByte() else 0x82.toByte()
                                this[7] = if (ok) 1 else 0
                            } + if (!ok) byteArrayOf() else byteArrayOf(
                                0xc0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 1, 0, 4, 127, 0, 0, 4)
                            out.writeShort(answer.size); out.write(answer); out.flush()
                        }
                    } } }
                }
            }
            return listener.localPort
        }
        val selectedPort = resolver { healthy.get() }
        val alternatePort = resolver { true }
        workers.submit {
            while (!origin.isClosed) {
                val socket = runCatching { origin.accept() }.getOrNull() ?: break
                workers.submit { runCatching { socket.use {
                    it.soTimeout = 5000
                    val payload = ByteArray(4).also(DataInputStream(it.getInputStream())::readFully)
                    check(String(payload) == "ping")
                    originRequests.incrementAndGet()
                    it.getOutputStream().write("ok".toByteArray())
                } } }
            }
        }
        fun freePort() = ServerSocket(0).use { it.localPort }
        val port = freePort()
        val serverPort = freePort()
        val node = ServerNode("fake-dns-test", "Loopback", "socks", "127.0.0.2", serverPort, encryption = "")
        val config = JsonParser.parseString(XrayConfigGenerator().generateConfig(node, mode = "global",
            dnsProvider = "custom", customDohUrl = "tcp://127.0.0.3:$selectedPort", isFakeDns = true,
            adBlocker = false, bypassAi = false, dnsProbePort = SplateConstants.DNS_CHECK_SOCKS_PORT)).asJsonObject
        config.getAsJsonArray("inbounds").first().asJsonObject.addProperty("port", port)
        config.getAsJsonObject("dns").addProperty("disableCache", true)
        config.getAsJsonArray("inbounds").add(JsonObject().apply {
            addProperty("tag", "test-vpn-server"); addProperty("listen", "127.0.0.2")
            addProperty("port", serverPort); addProperty("protocol", "socks")
            add("settings", JsonObject().apply { addProperty("auth", "noauth") })
        })
        val rules = config.getAsJsonObject("routing").getAsJsonArray("rules")
        config.getAsJsonObject("routing").add("rules", JsonArray().apply {
            add(JsonParser.parseString("""{"type":"field","inboundTag":["test-vpn-server"],"outboundTag":"direct"}"""))
            rules.forEach { add(it) }
        })
        val directory = File(context.cacheDir, "fakedns-recovery-${UUID.randomUUID()}").apply { mkdirs() }
        var process: Process? = null
        var access: Long? = null
        val log = File(directory, "core.log")
        try {
            val source = File(directory, "core.json").apply { writeText(config.toString()) }
            val builder = ProcessBuilder(requireNotNull(core.getXrayBinary()).absolutePath, "run", "-c", source.absolutePath)
                .redirectErrorStream(true).redirectOutput(log)
            builder.environment()["XRAY_LOCATION_ASSET"] = context.filesDir.absolutePath
            val child = builder.start().also { process = it }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (true) {
                assertTrue(log.readText(), child.isAlive)
                if (runCatching { Socket("127.0.0.1", port).close() }.isSuccess) break
                check(System.nanoTime() < deadline) { log.readText() }
                Thread.sleep(40)
            }
            access = LocalProxyAccess.publish(null) { child.isAlive }
            fun fakeIp(): String {
                val id = java.security.SecureRandom().nextInt(65536)
                val query = DnsProbeWire.query(id)
                return Socket().use { socket ->
                    socket.soTimeout = 4000
                    LocalProxyAccess.connectSocks(socket, port, null, SplateConstants.TUN_DNS_ADDRESS, 53, 3000, false)
                    DataOutputStream(socket.getOutputStream()).apply { writeShort(query.size); write(query); flush() }
                    val input = DataInputStream(socket.getInputStream())
                    val response = ByteArray(input.readUnsignedShort()).also(input::readFully)
                    check(DnsProbeWire.valid(response, id) && response[6] == 0.toByte() && response[7] == 1.toByte())
                    // The AI relay validator deliberately rejects FakeIPs. This is a single A answer.
                    response.takeLast(4).joinToString(".") { (it.toInt() and 255).toString() }
                }.also { assertTrue("FakeDNS did not return a FakeIP: $it", it.startsWith("198.18.") || it.startsWith("198.19.")) }
            }
            fun exchange(ip: String): String = Socket().use { socket ->
                socket.soTimeout = 4000
                LocalProxyAccess.connectSocks(socket, port, null, ip, origin.localPort, 3000, false)
                socket.getOutputStream().write("ping".toByteArray())
                String(ByteArray(2).also(DataInputStream(socket.getInputStream())::readFully))
            }
            val failingIp = fakeIp()
            assertTrue(runCatching { exchange(failingIp) }.isFailure)
            assertEquals(0, originRequests.get())
            assertTrue("The real resolver must have received a query despite FakeDNS", dnsRequests.get() > 0)
            val selected = DnsProviderEndpoint("selected", "Selected", "tcp://127.0.0.3:$selectedPort")
            val alternate = DnsProviderEndpoint("alternate", "Alternate", "tcp://127.0.0.3:$alternatePort")
            val checker = DnsProviderChecker()
            val diagnosis = confirmDnsFailure(selected, listOf(alternate), { child.isAlive }) { checker.check(it, viaVpn = true) }
            assertEquals("Resolver diagnosis must work even while ordinary traffic fails: ${log.readText()}", alternate, diagnosis?.endpoint)
            healthy.set(true)
            assertEquals("ok", exchange(fakeIp()))
            assertEquals(1, originRequests.get())
        } finally {
            LocalProxyAccess.revoke(access)
            process?.let { it.destroy(); if (!it.waitFor(2, TimeUnit.SECONDS)) it.destroyForcibly() }
            listeners.forEach { it.close() }; workers.shutdownNow(); workers.awaitTermination(4, TimeUnit.SECONDS)
            directory.deleteRecursively()
        }
    }
}
