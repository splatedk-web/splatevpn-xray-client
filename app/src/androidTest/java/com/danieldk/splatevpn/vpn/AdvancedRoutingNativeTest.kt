package com.danieldk.splatevpn.vpn

import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.data.AdvancedRouteRule
import com.danieldk.splatevpn.data.AdvancedRoutingRules
import com.danieldk.splatevpn.data.ServerNode
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Exercise generated rules in the shipped core, including routeOnly HTTP-host sniffing.
 * Every endpoint and DNS host is loopback; a missed rule reaches a dead local proxy.
 */
class AdvancedRoutingNativeTest {
    @Test fun andRequiresBothDomainSectionsInBundledXray() = truthTable("and")

    @Test fun orAcceptsEitherDomainSectionInBundledXray() = truthTable("or")

    private fun truthTable(operation: String) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(app.cacheDir, "advanced-routing-${UUID.randomUUID()}").apply { mkdirs() }
        val binary = File(app.applicationInfo.nativeLibraryDir, "libxray.so")
        val log = File(directory, "client.log")
        val auth = LocalProxyCredentials("routing-user", "routing-password")
        // The fake proxy node is 127.0.0.1; using .2 avoids its generated direct exception.
        val origin = ServerSocket(0, 16, InetAddress.getByName(ORIGIN_IP)).apply { soTimeout = 250 }
        val worker = Executors.newSingleThreadExecutor()
        val received = ConcurrentLinkedQueue<String>()
        val processes = mutableListOf<Process>()
        var stage = "building $operation configuration"
        try {
            val reservations = List(2) { ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")) }
            val socksPort = reservations[0].localPort
            val deadProxyPort = reservations[1].localPort
            reservations.forEach { it.close() }
            val rule = AdvancedRouteRule(
                id = "native-$operation", name = "Native $operation", operation = operation,
                outbound = "direct", suffixes = listOf("example.test"), keywords = listOf("api")
            )
            val compiled = AdvancedRoutingRules.compile(listOf(rule))
            val node = ServerNode("native-fixture", "Native fixture", "vless", "127.0.0.1", deadProxyPort,
                uuid = UUID.randomUUID().toString())
            val config = JsonParser.parseString(XrayConfigGenerator().generateConfig(
                node = node, mode = "global", enableSniffing = true, routeOnlySniffing = true,
                bypassAi = false, adBlocker = false, blockIpv6 = false,
                dnsProvider = "custom", diagnosticLogging = true,
                localProxyAccess = auth, advancedRules = listOf(rule)
            )).asJsonObject
            config.getAsJsonArray("inbounds")[0].asJsonObject.addProperty("port", socksPort)
            val generatedRules = config.getAsJsonObject("routing").getAsJsonArray("rules")
            compiled.forEach { expected ->
                assertTrue("Advanced rule was not retained by the generator: $expected",
                    generatedRules.any { it == expected })
            }
            // Global's broad LAN bypass would otherwise conceal a failed advanced rule.
            // Keep the generated advanced rules and proxy fallback; remove only that preset.
            generatedRules.removeAll { element ->
                val candidate = element.asJsonObject
                candidate["outboundTag"]?.asString == "direct" &&
                    candidate.getAsJsonArray("ip")?.any { it.asString == "geoip:private" } == true
            }
            assertTrue(generatedRules.none { it.asJsonObject.getAsJsonArray("ip")?.any { ip ->
                ip.asString.startsWith("geoip:")
            } == true })
            config.add("dns", JsonObject().apply {
                addProperty("queryStrategy", "UseIPv4")
                add("hosts", JsonObject().apply { CASES.forEach { addProperty(it.host, ORIGIN_IP) } })
                // ForceIPv4 below prevents fallback to the platform resolver if a host is missing.
                add("servers", JsonArray())
            })
            config.add("outbounds", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("tag", "proxy"); addProperty("protocol", "socks")
                    add("settings", JsonObject().apply { add("servers", JsonArray().apply { add(JsonObject().apply {
                        addProperty("address", "127.0.0.1"); addProperty("port", deadProxyPort)
                    }) }) })
                })
                add(JsonObject().apply {
                    addProperty("tag", "direct"); addProperty("protocol", "freedom")
                    add("settings", JsonObject().apply {
                        addProperty("domainStrategy", "ForceIPv4")
                        add("finalRules", JsonArray().apply {
                            add(JsonObject().apply {
                                addProperty("action", "allow"); addProperty("network", "tcp")
                                addProperty("port", origin.localPort)
                                add("ip", JsonArray().apply { add("$ORIGIN_IP/32") })
                            })
                            add(JsonObject().apply {
                                addProperty("action", "block"); addProperty("blockDelay", "0")
                            })
                        })
                    })
                })
                add(JsonObject().apply { addProperty("tag", "dns-out"); addProperty("protocol", "dns") })
                add(JsonObject().apply { addProperty("tag", "block"); addProperty("protocol", "blackhole") })
            })
            val source = File(directory, "client.json").apply { writeText(config.toString()) }
            stage = "native configuration validation"
            val validationLog = File(directory, "validation.log")
            val validation = ProcessBuilder(binary.absolutePath, "run", "-test", "-c", source.absolutePath)
                .redirectErrorStream(true).redirectOutput(validationLog).start().also { processes += it }
            assertTrue("Native config validation timed out", validation.waitFor(3, TimeUnit.SECONDS))
            assertEquals(validationLog.readText(), 0, validation.exitValue())
            assertTrue(validationLog.readText(), validationLog.readText().contains("Configuration OK"))

            val responseWorker = worker.submit<Unit> {
                while (!origin.isClosed) {
                    val accepted = try { origin.accept() }
                    catch (_: SocketTimeoutException) { continue }
                    catch (error: IOException) { if (origin.isClosed) break else throw error }
                    accepted.use { socket ->
                        socket.soTimeout = TIMEOUT_MS
                        val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                        val request = reader.readLine() ?: throw EOFException("Origin received no HTTP request")
                        check(request == "GET /routing HTTP/1.1") { "Unexpected origin request: $request" }
                        var host: String? = null
                        var headers = 0
                        while (true) {
                            val header = reader.readLine() ?: throw EOFException("Incomplete origin headers")
                            if (header.isEmpty()) break
                            check(++headers <= 32 && header.length <= 1024) { "Unexpected origin headers" }
                            if (header.startsWith("Host:", ignoreCase = true)) host = header.substringAfter(':').trim()
                        }
                        received += requireNotNull(host)
                        socket.getOutputStream().apply {
                            write(("HTTP/1.1 200 OK\r\nContent-Length: ${BODY.length}\r\n" +
                                "Connection: close\r\n\r\n$BODY").toByteArray(Charsets.US_ASCII))
                            flush()
                        }
                    }
                }
            }
            stage = "starting native core"
            val process = ProcessBuilder(binary.absolutePath, "run", "-c", source.absolutePath)
                .redirectErrorStream(true).redirectOutput(log).start().also { processes += it }
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS.toLong())
            while (true) {
                assertTrue(log.readText(), process.isAlive)
                try { Socket().use { it.connect(InetSocketAddress("127.0.0.1", socksPort), 100) }; break }
                catch (error: IOException) { if (System.nanoTime() >= deadline) throw error; Thread.sleep(25) }
            }
            val expectedHosts = mutableListOf<String>()
            for (numericTarget in listOf(false, true)) for (case in CASES) {
                stage = "$operation ${case.host}, numeric SOCKS target=$numericTarget"
                val expected = if (operation == "and") case.suffix && case.keyword else case.suffix || case.keyword
                val result = runCatching {
                    Socket().use { socket ->
                        socket.soTimeout = TIMEOUT_MS
                        LocalProxyAccess.connectSocks(socket, socksPort, auth,
                            if (numericTarget) ORIGIN_IP else case.host, origin.localPort,
                            TIMEOUT_MS, requireRunningVpn = false)
                        socket.getOutputStream().apply {
                            write(("GET /routing HTTP/1.1\r\nHost: ${case.host}\r\n" +
                                "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                            flush()
                        }
                        readBody(socket)
                    }
                }
                if (expected) {
                    assertEquals("$stage; ${result.exceptionOrNull()}", BODY, result.getOrNull())
                    expectedHosts += case.host
                } else {
                    assertTrue("$stage unexpectedly reached the direct HTTP origin: $result",
                        result.exceptionOrNull() is IOException)
                }
                assertTrue(log.readText(), process.isAlive)
                assertEquals("$stage reached an unexpected origin", expectedHosts, received.toList())
                // Propagate unexpected request/parser failures from the HTTP worker promptly.
                if (responseWorker.isDone) responseWorker.get(1, TimeUnit.SECONDS)
            }
            assertEquals(if (operation == "and") 2 else 6, received.size)
            origin.close()
            responseWorker.get(3, TimeUnit.SECONDS)
        } catch (error: Throwable) {
            throw AssertionError("$stage\n${File(directory, "validation.log").takeIf { it.exists() }?.readText().orEmpty()}\n" +
                log.takeIf { it.exists() }?.readText().orEmpty(), error)
        } finally {
            origin.close()
            worker.shutdownNow()
            processes.forEach { process ->
                process.destroy()
                if (!process.waitFor(2, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(2, TimeUnit.SECONDS) }
            }
            worker.awaitTermination(3, TimeUnit.SECONDS)
            directory.deleteRecursively()
        }
    }

    private fun readBody(socket: Socket): String {
        val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
        val status = reader.readLine() ?: throw EOFException("No HTTP response")
        if (status != "HTTP/1.1 200 OK") throw IOException("Unexpected HTTP response: $status")
        var length: Int? = null
        var headers = 0
        while (true) {
            val header = reader.readLine() ?: throw EOFException("Incomplete HTTP response headers")
            if (header.isEmpty()) break
            if (++headers > 32 || header.length > 1024) throw IOException("Unexpected HTTP response headers")
            if (header.startsWith("Content-Length:", ignoreCase = true)) length = header.substringAfter(':').trim().toInt()
        }
        if (length != BODY.length) throw IOException("Unexpected HTTP response length: $length")
        return CharArray(BODY.length).also { chars ->
            for (index in chars.indices) {
                val next = reader.read()
                if (next < 0) throw EOFException("Incomplete HTTP response body")
                chars[index] = next.toChar()
            }
        }.concatToString()
    }

    private data class Case(val host: String, val suffix: Boolean, val keyword: Boolean)

    companion object {
        private const val ORIGIN_IP = "127.0.0.2"
        private const val TIMEOUT_MS = 3000
        private const val BODY = "advanced-routing-ok"
        private val CASES = listOf(
            Case("api.example.test", suffix = true, keyword = true),
            Case("api.other.test", suffix = false, keyword = true),
            Case("www.example.test", suffix = true, keyword = false),
            Case("unrelated.test", suffix = false, keyword = false)
        )
    }
}
