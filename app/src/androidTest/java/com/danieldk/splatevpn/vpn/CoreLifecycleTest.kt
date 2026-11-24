package com.danieldk.splatevpn.vpn

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.data.AppLogManager
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class CoreLifecycleTest {
    @Test fun bundledNativeVersionMatchesTheReviewedSecurityRelease() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val status = VpnStatusManager(context)
        val manager = XrayCoreManager(context, AppLogManager(context), status, LiveConnectionTracker(), testHealthAdvisor(context, status))
        manager.ensureAssetsExtracted()
        val binary = requireNotNull(manager.getXrayBinary())
        val process = ProcessBuilder(binary.absolutePath, "version").redirectErrorStream(true).start()
        try {
            assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS))
            val version = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.exitValue())
            assertTrue(version, version.startsWith("Xray ${com.danieldk.splatevpn.SplateConstants.XRAY_VERSION} "))
        } finally { process.destroyForcibly() }
    }
    @Test fun realityVerificationFormatsStartRealCoreWithoutDisablingVerification() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(app.cacheDir, "core-pq-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = directory
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences("${directory.name}-$name", mode)
        }
        val status = VpnStatusManager(context)
        val manager = XrayCoreManager(context, AppLogManager(context), status, LiveConnectionTracker(), testHealthAdvisor(context, status))
        val bytes = ByteArray(1952) { (it % 251).toByte() }
        val canonical = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val standard = java.util.Base64.getEncoder().encodeToString(bytes)
        val link = "vless://11111111-2222-3333-4444-555555555555@192.0.2.1:443?security=reality&type=xhttp&sni=example.com&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&pqv="
        val generator = XrayConfigGenerator()
        try {
            for (key in listOf(canonical, "$canonical=", standard, "\u200E$canonical\u200B")) {
                val node = requireNotNull(com.danieldk.splatevpn.data.LinkParser.parse(link + key))
                val config = generator.generateConfig(node)
                val proxy = com.google.gson.JsonParser.parseString(config).asJsonObject.getAsJsonArray("outbounds")
                    .first { it.asJsonObject["tag"].asString == "proxy" }.asJsonObject
                assertEquals(canonical, proxy.getAsJsonObject("streamSettings").getAsJsonObject("realitySettings")["mldsa65Verify"].asString)
                assertTrue("Normalized verification key must allow the real native core to start", manager.startCore(config))
                assertTrue(manager.isRunning())
                manager.stopCore()
            }
            val malformed = requireNotNull(com.danieldk.splatevpn.data.LinkParser.parse(link + canonical.dropLast(1)))
            assertThrows(IllegalArgumentException::class.java) { generator.generateConfig(malformed) }
            assertFalse(manager.isRunning())
        } finally {
            manager.stopCore()
            directory.deleteRecursively()
            app.deleteSharedPreferences("${directory.name}-xray_assets_prefs")
        }
    }

    @Test fun crashedNativeCoreRestartsAndStopCancelsFurtherRecovery() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(app.cacheDir, "core-crash-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = directory
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences("${directory.name}-$name", mode)
        }
        val status = VpnStatusManager(context)
        val manager = XrayCoreManager(context, AppLogManager(context), status, LiveConnectionTracker(), com.danieldk.splatevpn.vpn.testHealthAdvisor(context, status))
        val config = """{"log":{"loglevel":"warning"},"inbounds":[{"listen":"127.0.0.1","port":10808,"protocol":"socks","settings":{"auth":"noauth"}}],"outbounds":[{"protocol":"blackhole"}]}"""
        fun corePid(): Int? = File("/proc").listFiles().orEmpty().firstOrNull { entry ->
            entry.name.toIntOrNull() != null && runCatching {
                val args = File(entry, "cmdline").readText().split('\u0000')
                File(directory, "xray_config.json").absolutePath in args
            }.getOrDefault(false)
        }?.name?.toInt()
        try {
            assertTrue(manager.startCore(config))
            status.setState(VpnState.CONNECTED)
            status.connectedServerId = "selected-test-node"
            val oldPid = requireNotNull(corePid())
            android.system.Os.kill(oldPid, android.system.OsConstants.SIGKILL)
            withTimeout(15_000) {
                while (corePid() == null || corePid() == oldPid || !manager.isRunning()) delay(100)
            }
            withTimeout(8_000) { while (status.state.value != VpnState.CONNECTED) delay(100) }
            assertEquals("selected-test-node", status.connectedServerId)
            android.system.Os.kill(requireNotNull(corePid()), android.system.OsConstants.SIGKILL)
            manager.stopCore()
            delay(4_000)
            assertFalse(manager.isRunning())
            assertNull(corePid())
        } finally {
            manager.stopCore()
            directory.deleteRecursively()
            app.deleteSharedPreferences("${directory.name}-xray_assets_prefs")
        }
    }

    @Test fun delayedShutdownOfOldGenerationCannotKillReplacementCore() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(app.cacheDir, "core-lifecycle-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = directory
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences("${directory.name}-$name", mode)
        }
        val manager = XrayCoreManager(context, AppLogManager(context), VpnStatusManager(context), LiveConnectionTracker(), com.danieldk.splatevpn.vpn.testHealthAdvisor(context, VpnStatusManager(context)))
        val config = """{"log":{"loglevel":"warning"},"inbounds":[{"tag":"local","listen":"127.0.0.1","port":10808,"protocol":"socks","settings":{"auth":"noauth"}}],"outbounds":[{"protocol":"freedom"}]}"""
        try {
            assertTrue(manager.startCore(config))
            val oldGeneration = manager.generationToken()
            assertTrue(manager.startCore(config))
            manager.stopCore(expectedGeneration = oldGeneration)
            assertTrue("A delayed stop from a destroyed service must not terminate the new core", manager.isRunning())
            manager.stopCore()
            assertFalse(manager.isRunning())
            // A replacement after stop must not be restarted by an obsolete watchdog.
            assertTrue(manager.startCore(config))
            manager.stopCore()
            delay(2500)
            assertFalse(manager.isRunning())
        } finally {
            manager.stopCore()
            directory.deleteRecursively()
            app.deleteSharedPreferences("${directory.name}-xray_assets_prefs")
        }
    }

    @Test fun twentyRapidStartStopCyclesNeverBindAddressAlreadyInUse() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(app.cacheDir, "core-20cycles-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = directory
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences("${directory.name}-$name", mode)
        }
        val status = VpnStatusManager(context)
        val manager = XrayCoreManager(context, AppLogManager(context), status, LiveConnectionTracker(), com.danieldk.splatevpn.vpn.testHealthAdvisor(context, status))
        val config = """{"log":{"loglevel":"warning"},"inbounds":[{"tag":"socks-test","listen":"127.0.0.1","port":10808,"protocol":"socks","settings":{"auth":"noauth"}}],"outbounds":[{"protocol":"freedom"}]}"""

        fun isPortOpen(): Boolean = try {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress("127.0.0.1", 10808), 300)
                true
            }
        } catch (_: Exception) {
            false
        }

        try {
            for (cycle in 1..25) {
                val startSuccess = manager.startCore(config)
                assertTrue("Cycle $cycle: Core must start successfully", startSuccess)
                assertTrue("Cycle $cycle: Core must report running", manager.isRunning())
                assertTrue("Cycle $cycle: Port 10808 must accept connections", isPortOpen())

                manager.stopCore()
                assertFalse("Cycle $cycle: Core must report stopped", manager.isRunning())
                assertFalse("Cycle $cycle: Port 10808 must be released after stop", isPortOpen())
            }
        } finally {
            manager.stopCore()
            directory.deleteRecursively()
            app.deleteSharedPreferences("${directory.name}-xray_assets_prefs")
        }
    }

    @Test fun rapidServerSwitchingUnderLoadNeverLeakingProcessesOrPort() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(app.cacheDir, "core-switching-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = directory
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences("${directory.name}-$name", mode)
        }
        val status = VpnStatusManager(context)
        val manager = XrayCoreManager(context, AppLogManager(context), status, LiveConnectionTracker(), com.danieldk.splatevpn.vpn.testHealthAdvisor(context, status))

        fun countOwnedXrayProcesses(): Int {
            return File("/proc").listFiles().orEmpty().count { entry ->
                entry.name.toIntOrNull() != null && runCatching {
                    val cmdline = File(entry, "cmdline").readText()
                    (cmdline.contains("libxray.so") || cmdline.contains("/xray")) && cmdline.contains(directory.absolutePath)
                }.getOrDefault(false)
            }
        }

        try {
            for (i in 1..20) {
                val config = """{"log":{"loglevel":"warning"},"inbounds":[{"tag":"server-$i","listen":"127.0.0.1","port":10808,"protocol":"socks","settings":{"auth":"noauth"}}],"outbounds":[{"tag":"out-$i","protocol":"freedom"}]}"""
                val started = manager.startCore(config)
                assertTrue("Switch $i: Core must successfully switch to new configuration", started)
                assertTrue("Switch $i: Core must be running", manager.isRunning())

                val activeCount = countOwnedXrayProcesses()
                assertTrue("Switch $i: At most 1 Xray process should remain active, but found $activeCount", activeCount <= 1)
            }

            manager.stopCore()
            assertFalse(manager.isRunning())
            delay(1000)
            assertEquals("All Xray processes must be cleaned up after stop", 0, countOwnedXrayProcesses())
        } finally {
            manager.stopCore()
            directory.deleteRecursively()
            app.deleteSharedPreferences("${directory.name}-xray_assets_prefs")
        }
    }

    @Test fun realTrafficPassesThroughNativeCoreSocksProxy() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(app.cacheDir, "core-traffic-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = directory
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences("${directory.name}-$name", mode)
        }
        val status = VpnStatusManager(context)
        val manager = XrayCoreManager(context, AppLogManager(context), status, LiveConnectionTracker(), com.danieldk.splatevpn.vpn.testHealthAdvisor(context, status))
        val config = """{"log":{"loglevel":"warning"},"inbounds":[{"tag":"socks-traffic","listen":"127.0.0.1","port":10808,"protocol":"socks","settings":{"auth":"noauth"}}],"outbounds":[{"tag":"direct","protocol":"freedom"}]}"""
        try {
            assertTrue(manager.startCore(config))
            assertTrue(manager.isRunning())

            val proxy = java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", 10808))
            val url = java.net.URL("http://connectivitycheck.gstatic.com/generate_204")
            val conn = (url.openConnection(proxy) as java.net.HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                instanceFollowRedirects = false
            }
            val responseCode = withContext(Dispatchers.IO) { conn.responseCode }
            assertEquals("Real HTTP traffic through Xray SOCKS proxy must return 204", 204, responseCode)
        } finally {
            manager.stopCore()
            directory.deleteRecursively()
            app.deleteSharedPreferences("${directory.name}-xray_assets_prefs")
        }
    }
}
