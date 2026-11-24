package com.danieldk.splatevpn.vpn

import com.danieldk.splatevpn.SplateConstants
import com.danieldk.splatevpn.data.ServerNode
import com.danieldk.splatevpn.data.TorConfigParser
import com.danieldk.splatevpn.data.TorSettings
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RknHardeningSecurityTest {

    @Test
    fun defaultXrayConfigHasNoUnauthenticatedLocalProxiesOrExposedApis() {
        val creds = LocalProxyCredentials.generate()
        val node = ServerNode(
            id = "rkn-test", name = "Test", protocol = "vless",
            address = "vpn.example.com", port = 443,
            uuid = "11111111-2222-3333-4444-555555555555", security = "tls", sni = "vpn.example.com"
        )
        val generator = XrayConfigGenerator()
        val config = JsonParser.parseString(
            generator.generateConfig(
                node = node,
                dnsProbePort = SplateConstants.DNS_CHECK_SOCKS_PORT,
                localProxyAccess = creds
            )
        ).asJsonObject

        assertFalse("Xray API must never be exposed to local scanners", config.has("api"))
        val inbounds = config.getAsJsonArray("inbounds").map { it.asJsonObject }
        assertTrue(inbounds.isNotEmpty())
        inbounds.forEach { inbound ->
            val protocol = inbound["protocol"].asString
            assertNotEquals("API inbound must not exist", "dokodemo-door", protocol)
            val settings = inbound.getAsJsonObject("settings")
            assertEquals("Every loopback inbound must require password auth", "password", settings["auth"].asString)
            val account = settings.getAsJsonArray("accounts")[0].asJsonObject
            assertEquals(creds.username, account["user"].asString)
            assertEquals(creds.password, account["pass"].asString)
        }
    }

    @Test
    fun ephemeralSocksAndDnsPortsAvoidWellKnownPortsAndPublishToLocalProxyAccess() {
        val creds = LocalProxyCredentials.generate()
        val socksPort = 41234
        val dnsPort = 41235
        val config = JsonParser.parseString(
            XrayConfigGenerator().generateConfig(
                node = null,
                socksPort = socksPort,
                dnsProbePort = dnsPort,
                localProxyAccess = creds
            )
        ).asJsonObject
        val inbounds = config.getAsJsonArray("inbounds").map { it.asJsonObject }
        val socksIn = inbounds.first { it["tag"].asString == "socks-in" }
        val dnsIn = inbounds.first { it["tag"].asString == "dns-check-in" }
        assertEquals(socksPort, socksIn["port"].asInt)
        assertEquals(dnsPort, dnsIn["port"].asInt)
        assertNotEquals(SplateConstants.XRAY_SOCKS_PORT, socksIn["port"].asInt)
        assertNotEquals(SplateConstants.DNS_CHECK_SOCKS_PORT, dnsIn["port"].asInt)

        val token = LocalProxyAccess.publish(creds, socksPort = socksPort, dnsProbePort = dnsPort)
        try {
            assertEquals(socksPort, LocalProxyAccess.activeSocksPort)
            assertEquals(dnsPort, LocalProxyAccess.activeDnsProbePort)
            assertTrue(LocalProxyAccess.isOwnedPort(socksPort))
            assertTrue(LocalProxyAccess.isOwnedPort(dnsPort))
        } finally {
            LocalProxyAccess.revoke(token)
        }
    }

    @Test
    fun splitTunnelFlowGuardBlocksVpnNetworkBindingFromExcludedAppsWhilePreservingAllowedAppsAndDns() {
        val ownPkg = "com.danieldk.splatevpn"
        val browser = "com.android.chrome"
        val scanner = "ru.rknhardering.app"

        // 1. Global mode (only ownPackage excluded) does not activate guard
        val globalPlan = PerAppRoutingPlan(excluded = setOf(ownPkg))
        assertFalse(SplitTunnelFlowGuard.shouldActivate(globalPlan, ownPkg))

        // 2. Include mode: only browser allowed
        val includePlan = PerAppRoutingPlan(allowed = setOf(browser))
        assertTrue(SplitTunnelFlowGuard.shouldActivate(includePlan, ownPkg))
        assertTrue("Allowed browser must pass", SplitTunnelFlowGuard.isFlowAllowed(10200, setOf(browser), 443, includePlan))
        assertFalse("Scanner binding to VPN network must be dropped", SplitTunnelFlowGuard.isFlowAllowed(10300, setOf(scanner), 443, includePlan))
        assertFalse("Unknown non-DNS flow must be dropped in include mode", SplitTunnelFlowGuard.isFlowAllowed(-1, emptySet(), 443, includePlan))
        assertTrue("System DNS resolver (port 53) must still work for allowed browser", SplitTunnelFlowGuard.isFlowAllowed(1051, emptySet(), 53, includePlan))
        assertTrue("Unattributed DNS (port 53) must still work", SplitTunnelFlowGuard.isFlowAllowed(-1, emptySet(), 53, includePlan))
        assertFalse("Non-DNS system process traffic must not leak in include mode", SplitTunnelFlowGuard.isFlowAllowed(1000, emptySet(), 443, includePlan))

        // 3. Exclude mode: scanner excluded
        val excludePlan = PerAppRoutingPlan(excluded = setOf(ownPkg, scanner))
        assertTrue(SplitTunnelFlowGuard.shouldActivate(excludePlan, ownPkg))
        assertTrue("Non-excluded app must pass", SplitTunnelFlowGuard.isFlowAllowed(10200, setOf(browser), 443, excludePlan))
        assertFalse("Excluded scanner binding to VPN network must be dropped", SplitTunnelFlowGuard.isFlowAllowed(10300, setOf(scanner), 443, excludePlan))
    }

    @Test
    fun torConfigUsesPrivateUnixSocketInsteadOfLoopbackTcpPort() {
        val torrc = TorConfigParser.buildTorrc(
            TorSettings(useBridges = false),
            emptyMap(),
            socksUnixPath = "/data/user/0/com.danieldk.splatevpn/no_backup/tor/socks.sock"
        )
        assertTrue(torrc.contains("SocksPort unix:/data/user/0/com.danieldk.splatevpn/no_backup/tor/socks.sock IsolateSOCKSAuth"))
        assertFalse(torrc.contains("SocksPort 127.0.0.1"))
    }

    @Test
    fun torAuthRelayBlocksUnauthenticatedSocksWrongPasswordAndHttpProbesOverLiveLoopback() {
        val creds = LocalProxyCredentials.generate()
        val executor = Executors.newCachedThreadPool()
        val mockTorUpstream = ServerSocket(0)
        val upstreamAccepted = java.util.concurrent.atomic.AtomicInteger(0)

        executor.execute {
            while (!mockTorUpstream.isClosed) {
                val up = try { mockTorUpstream.accept() } catch (_: Exception) { break }
                upstreamAccepted.incrementAndGet()
                executor.execute {
                    up.use { socket ->
                        val input = DataInputStream(socket.getInputStream())
                        val out = socket.getOutputStream()
                        // Expect internal SOCKS5 no-auth greeting from relay
                        assertEquals(5, input.readUnsignedByte())
                        val n = input.readUnsignedByte()
                        ByteArray(n).also(input::readFully)
                        out.write(byteArrayOf(5, 0))
                        out.flush()
                        // Echo next 4 bytes to prove authenticated tunnel works
                        val payload = ByteArray(4)
                        input.readFully(payload)
                        out.write(payload)
                        out.flush()
                    }
                }
            }
        }

        val relay = TorSocksAuthRelay(creds) {
            val s = Socket()
            s.connect(InetSocketAddress("127.0.0.1", mockTorUpstream.localPort), 2_000)
            object : TorSocksAuthRelay.UpstreamConnection {
                override val inputStream: InputStream = s.getInputStream()
                override val outputStream: OutputStream = s.getOutputStream()
                override fun close() = s.close()
            }
        }

        try {
            // 1. RKNHardening-style unauthenticated SOCKS5 probe (0x05 0x01 0x00) must be rejected with 0x05 0xFF
            Socket().use { s ->
                s.soTimeout = 2_000
                s.connect(InetSocketAddress("127.0.0.1", relay.port), 2_000)
                s.getOutputStream().write(byteArrayOf(5, 1, 0))
                s.getOutputStream().flush()
                val input = DataInputStream(s.getInputStream())
                assertEquals(5, input.readUnsignedByte())
                assertEquals(0xFF, input.readUnsignedByte())
            }
            assertEquals("Unauthenticated probe must never reach Tor upstream", 0, upstreamAccepted.get())

            // 2. Wrong password must be rejected with RFC 1929 failure (0x01 0x01)
            Socket().use { s ->
                s.soTimeout = 2_000
                s.connect(InetSocketAddress("127.0.0.1", relay.port), 2_000)
                val out = s.getOutputStream()
                val input = DataInputStream(s.getInputStream())
                out.write(byteArrayOf(5, 1, 2))
                out.flush()
                assertEquals(5, input.readUnsignedByte())
                assertEquals(2, input.readUnsignedByte())
                out.write(byteArrayOf(1, 3, 'b'.code.toByte(), 'a'.code.toByte(), 'd'.code.toByte(), 3, 'p'.code.toByte(), 'w'.code.toByte(), 'd'.code.toByte()))
                out.flush()
                assertEquals(1, input.readUnsignedByte())
                assertEquals(1, input.readUnsignedByte())
            }
            assertEquals("Wrong password must never reach Tor upstream", 0, upstreamAccepted.get())

            // 3. Raw HTTP proxy / Clash API probe must get 0 bytes and close immediately
            Socket().use { s ->
                s.soTimeout = 2_000
                s.connect(InetSocketAddress("127.0.0.1", relay.port), 2_000)
                s.getOutputStream().write("GET http://api.ipify.org/ HTTP/1.1\r\nHost: api.ipify.org\r\n\r\n".toByteArray())
                s.getOutputStream().flush()
                assertEquals(-1, s.getInputStream().read())
            }
            assertEquals("HTTP probe must never reach Tor upstream", 0, upstreamAccepted.get())

            // 4. Authorized Xray client with valid credentials succeeds end-to-end
            Socket().use { s ->
                s.soTimeout = 2_000
                s.connect(InetSocketAddress("127.0.0.1", relay.port), 2_000)
                val out = s.getOutputStream()
                val input = DataInputStream(s.getInputStream())
                out.write(byteArrayOf(5, 1, 2))
                out.flush()
                assertEquals(5, input.readUnsignedByte())
                assertEquals(2, input.readUnsignedByte())
                val u = creds.username.toByteArray()
                val p = creds.password.toByteArray()
                out.write(byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(p.size.toByte()) + p)
                out.flush()
                assertEquals(1, input.readUnsignedByte())
                assertEquals(0, input.readUnsignedByte())
                out.write("PING".toByteArray())
                out.flush()
                val echo = ByteArray(4)
                input.readFully(echo)
                assertEquals("PING", String(echo))
            }
            assertEquals(1, upstreamAccepted.get())
        } finally {
            relay.close()
            mockTorUpstream.close()
            executor.shutdownNow()
            executor.awaitTermination(2, TimeUnit.SECONDS)
        }
    }
}
