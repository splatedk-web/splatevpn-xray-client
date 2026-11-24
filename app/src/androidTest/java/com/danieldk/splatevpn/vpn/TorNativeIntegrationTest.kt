package com.danieldk.splatevpn.vpn

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.data.AppLogManager
import com.danieldk.splatevpn.data.ServerNode
import com.danieldk.splatevpn.data.TorConfigParser
import com.danieldk.splatevpn.data.TorSettings
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.Socket
import java.net.InetSocketAddress
import java.util.UUID

/** Offline native smoke tests: these do not contact a Tor relay or a subscription. */
class TorNativeIntegrationTest {
    @Test fun runtimeAndConcurrentBridgeBenchmarksShareOneNativeController() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val active = TorTransportPool.acquire(context, setOf("obfs4"))
        try {
            repeat(3) {
                TorTransportPool.acquire(context, setOf("obfs4", "webtunnel")).use { benchmark ->
                    assertEquals(active.ports.getValue("obfs4"), benchmark.ports.getValue("obfs4"))
                    Socket().use { socket -> socket.connect(InetSocketAddress("127.0.0.1", benchmark.ports.getValue("webtunnel")), 1000) }
                }
                Socket().use { socket -> socket.connect(InetSocketAddress("127.0.0.1", active.ports.getValue("obfs4")), 1000) }
            }
        } finally { active.close(); active.close() }
        TorTransportPool.acquire(context, setOf("obfs4")).use { restarted ->
            Socket().use { socket -> socket.connect(InetSocketAddress("127.0.0.1", restarted.ports.getValue("obfs4")), 1000) }
        }
    }
    @Test fun bundledTorExecutableValidatesSafeClientConfiguration() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "tor-verify-${UUID.randomUUID()}").apply { mkdirs() }
        val torrc = File(directory, "torrc")
        val executable = File(context.applicationInfo.nativeLibraryDir, "libtor.so")
        var process: Process? = null
        try {
            torrc.writeText(TorConfigParser.buildTorrc(TorSettings(), emptyMap()) + "DisableNetwork 1\n")
            val output = File(directory, "output")
            process = ProcessBuilder(executable.absolutePath, "--verify-config", "-f", torrc.absolutePath,
                "--DataDirectory", directory.absolutePath, "--RunAsDaemon", "0")
                .redirectErrorStream(true).redirectOutput(output).start()
            assertTrue(process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(output.readText(), 0, process.exitValue())
            process = ProcessBuilder(executable.absolutePath, "--version")
                .redirectErrorStream(true).redirectOutput(output).start()
            assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(0, process.exitValue())
            assertTrue(output.readText().startsWith("Tor version 0.4.9.14"))
        } finally {
            process?.takeIf { it.isAlive }?.destroyForcibly()
            directory.deleteRecursively()
        }
    }

    @Test fun bundledBridgeTransportsOpenLocalSocksListeners() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "tor-pt-${UUID.randomUUID()}").apply { mkdirs() }
        val lease = TorTransportPool.acquire(context, setOf("obfs4", "webtunnel"))
        try {
            for (transport in listOf("obfs4", "webtunnel")) {
                run {
                    val port = lease.ports.getValue(transport)
                    assertTrue(port in 1..65535)
                    java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", port), 1000) }
                }
            }
        } finally { lease.close(); directory.deleteRecursively() }
    }

    @Test fun realXrayAcceptsTorDnsAndUdpRouting() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(app.cacheDir, "tor-xray-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = directory
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences("${directory.name}-$name", mode)
        }
        val status = VpnStatusManager(context)
        val manager = XrayCoreManager(context, AppLogManager(context), status, LiveConnectionTracker(), testHealthAdvisor(context, status))
        try {
            val node = ServerNode("splate-tor", "Tor", "socks", "127.0.0.1", 9050, encryption = "")
            val generated = XrayConfigGenerator().generateConfig(node, mode = "global", isFakeDns = true, bypassAi = false)
            assertTrue("Tor routing must be accepted by the shipped native Xray", manager.startCore(TorRoutingPolicy.apply(generated)))
        } finally {
            manager.stopCore()
            directory.deleteRecursively()
            app.deleteSharedPreferences("${directory.name}-xray_assets_prefs")
        }
    }
}
