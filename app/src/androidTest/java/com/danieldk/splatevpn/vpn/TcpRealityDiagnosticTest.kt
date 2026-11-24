package com.danieldk.splatevpn.vpn

import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.data.LinkParser
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.TimeUnit

/** A failed loopback peer must expose its transport error in the shipped core's log. */
class TcpRealityDiagnosticTest {
    @Test fun importedTcpRealityReportsTheRefusedPeerWithoutDebugLogging() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(app.cacheDir, "tcp-reality-diagnostic-${UUID.randomUUID()}").apply { mkdirs() }
        val binary = File(app.applicationInfo.nativeLibraryDir, "libxray.so")
        val peer = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val socksPort = ServerSocket(0).use { it.localPort }
        val raw = """[{"remarks":"Local diagnostic","outbounds":[{"tag":"remote","protocol":"vless","settings":{"vnext":[{"address":"127.0.0.1","port":${peer.localPort},"users":[{"id":"11111111-2222-3333-4444-555555555555","encryption":"none"}]}]},"streamSettings":{"network":"tcp","security":"reality","realitySettings":{"serverName":"example.com","publicKey":"DVHAAhW_6ZLKfg8uqNnTARfon5WLU__89hPod6ydOwo","shortId":"abcd","fingerprint":"chrome"}}}]}]"""
        val node = LinkParser.parseSubscription(raw).single()
        val config = JsonParser.parseString(XrayConfigGenerator().generateConfig(
            node, mode = "global", bypassAi = false, adBlocker = false, blockIpv6 = false
        )).asJsonObject
        assertEquals("info", config.getAsJsonObject("log")["loglevel"].asString)
        config.getAsJsonArray("inbounds")[0].asJsonObject.addProperty("port", socksPort)
        // Force the test target through the local proxy, without loading routing databases.
        config.getAsJsonObject("routing").getAsJsonArray("rules").removeAll { rule ->
            rule.asJsonObject.getAsJsonArray("ip")?.any { it.asString.startsWith("geoip:") } == true
        }
        val log = File(directory, "client.log")
        val file = File(directory, "client.json").apply { writeText(config.toString()) }
        val process = ProcessBuilder(binary.absolutePath, "run", "-c", file.absolutePath)
            .redirectErrorStream(true).redirectOutput(log).start()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var ready = false
            while (!ready && System.nanoTime() < deadline) {
                check(process.isAlive) { log.readText() }
                ready = runCatching {
                    Socket().use { it.connect(InetSocketAddress("127.0.0.1", socksPort), 100) }
                    true
                }.getOrDefault(false)
                if (!ready) Thread.sleep(50)
            }
            assertTrue("SOCKS listener did not open: ${log.readText()}", ready)
            peer.close()
            // Xray may acknowledge SOCKS before it dials the peer. Reading forces the dial.
            runCatching {
                Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))).use { socket ->
                    socket.soTimeout = 3_000
                    socket.connect(InetSocketAddress("192.0.2.1", 80), 3_000)
                    socket.getOutputStream().write("GET / HTTP/1.0\r\n\r\n".toByteArray())
                    socket.getInputStream().read()
                }
            }
            val logDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!log.readText().contains("connection refused") && System.nanoTime() < logDeadline) Thread.sleep(50)
            val policy = XrayLogPolicy()
            assertTrue("Transport failure was hidden: ${log.readText()}",
                log.readLines().mapNotNull(policy::select).any {
                    it.contains("connection refused") && it.contains("failed", ignoreCase = true)
                })
        } finally {
            peer.close()
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
            directory.deleteRecursively()
        }
    }
}
