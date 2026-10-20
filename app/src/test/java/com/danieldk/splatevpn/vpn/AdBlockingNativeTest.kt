package com.danieldk.splatevpn.vpn

import com.danieldk.splatevpn.data.ServerNode
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import com.danieldk.splatevpn.data.AdFilterCompiler
import java.io.File
import java.util.zip.GZIPInputStream

/** Exercises intercepted DNS over SOCKS against the bundled Android core, without Internet access. */
class AdBlockingNativeTest {
    @Test fun dnsFilterWorksWithFakeDnsImportedProfilesAndBothAddressFamilies() {
        val adb = System.getenv("SPLATE_XRAY_SMOKE_ADB")
        assumeTrue("Requires a staged Android core", !adb.isNullOrBlank())
        fun command(vararg args: String): String {
            val child = ProcessBuilder(listOf(adb!!) + args).redirectErrorStream(true).start()
            check(child.waitFor(30, TimeUnit.SECONDS)) { "adb timeout" }
            val output = child.inputStream.bufferedReader().readText()
            assertEquals(output, 0, child.exitValue())
            return output.trim()
        }
        val generator = XrayConfigGenerator()
        val imported = ServerNode("native-ads", "Native ads", "vless", "example.com", 443,
            fullConfigJson = """{"outbounds":[{"tag":"direct","protocol":"freedom"}]}""")
        val configs = listOf(
            generator.generateConfig(null, bypassAi = false, enableSniffing = false),
            generator.generateConfig(null, bypassAi = false, isFakeDns = true),
            generator.generateConfig(imported),
            generator.generateConfig(null, bypassAi = false, adBlocker = false),
            generator.generateConfig(imported.copy(fullConfigJson = "{}")),
            generator.generateConfig(null, bypassAi = false, adBlockMatcher = "ext:splate-ads-test.dat:ads", adBlockAllowlist = listOf("adsdk.yandex.ru")),
            generator.generateConfig(imported, adBlockMatcher = "ext:splate-ads-test.dat:ads", adBlockAllowlist = listOf("adsdk.yandex.ru"))
        )
        val remote = "/data/local/tmp/splate-core-check"
        val fixture = Files.createTempFile("splate-ad-filter-", ".json").toFile()
        val database = Files.createTempFile("splate-ad-filter-", ".dat").toFile()
        try {
            val bundled = File("src/main/assets/adguard-dns-source.bin").takeIf { it.exists() } ?: File("app/src/main/assets/adguard-dns-source.bin")
            val text = GZIPInputStream(bundled.inputStream()).bufferedReader().use { it.readText() }
            val domains = AdFilterCompiler.parse(text, AdBlockingPolicy.domains, listOf("adsdk.yandex.ru"))
            database.writeBytes(AdFilterCompiler.encode(domains))
            command("push", database.absolutePath, "$remote/splate-ads-test.dat")
            configs.forEachIndexed { index, source ->
                val config = JsonParser.parseString(source).asJsonObject
                config.getAsJsonArray("inbounds")[0].asJsonObject.addProperty("port", 29083)
                val dns = config.getAsJsonObject("dns")
                val hosts = dns.get("hosts")?.asJsonObject ?: JsonObject().also { dns.add("hosts", it) }
                // A positive control and an upstream-free result when the filter is off.
                hosts.addProperty("full:content-control.example", "203.0.113.10")
                if (index == 3 || index >= 5) hosts.addProperty("full:adsdk.yandex.ru", "203.0.113.11")
                fixture.writeText(config.toString())
                command("push", fixture.absolutePath, "$remote/ad-filter.json")
                val forward = command("forward", "tcp:0", "tcp:29083").toInt()
                val pid = command("shell", "sh -c 'XRAY_LOCATION_ASSET=$remote $remote/xray-new run -c $remote/ad-filter.json >$remote/ad-filter.log 2>&1 & echo ${'$'}!'")
                assertTrue("Expected own core PID: $pid", pid.matches(Regex("[0-9]+")))
                try {
                    var ready = false
                    repeat(50) {
                        if (!ready) {
                            ready = runCatching {
                                Socket("127.0.0.1", forward).use { socket ->
                                    socket.soTimeout = 200
                                    socket.getOutputStream().write(byteArrayOf(5, 1, 0))
                                    socket.getInputStream().read() == 5 && socket.getInputStream().read() == 0
                                }
                            }.getOrDefault(false)
                            if (!ready) Thread.sleep(100)
                        }
                    }
                    assertTrue(command("shell", "cat", "$remote/ad-filter.log"), ready)
                    fun query(domain: String, type: Int): ByteArray = Socket("127.0.0.1", forward).use { socket ->
                        socket.soTimeout = 5000
                        val input = DataInputStream(socket.getInputStream())
                        val output = DataOutputStream(socket.getOutputStream())
                        output.write(byteArrayOf(5, 1, 0)); output.flush()
                        assertEquals(5, input.readUnsignedByte()); assertEquals(0, input.readUnsignedByte())
                        output.write(byteArrayOf(5, 1, 0, 1, 1, 1, 1, 1, 0, 53)); output.flush()
                        assertEquals(5, input.readUnsignedByte()); assertEquals(0, input.readUnsignedByte())
                        input.readUnsignedByte()
                        when (input.readUnsignedByte()) {
                            1 -> input.readFully(ByteArray(4))
                            4 -> input.readFully(ByteArray(16))
                            3 -> input.readFully(ByteArray(input.readUnsignedByte()))
                            else -> error("Unexpected SOCKS address")
                        }
                        input.readUnsignedShort()
                        val message = ByteArrayOutputStream().also { bytes ->
                            DataOutputStream(bytes).use { packet ->
                                packet.writeShort(0x1234); packet.writeShort(0x0100)
                                packet.writeShort(1); repeat(3) { packet.writeShort(0) }
                                domain.split('.').forEach { label -> packet.writeByte(label.length); packet.writeBytes(label) }
                                packet.writeByte(0); packet.writeShort(type); packet.writeShort(1)
                            }
                        }.toByteArray()
                        output.writeShort(message.size); output.write(message); output.flush()
                        ByteArray(input.readUnsignedShort()).also { input.readFully(it) }
                    }
                    fun assertAnswer(domain: String, type: Int, address: ByteArray) {
                        val answer = query(domain, type)
                        assertEquals("DNS must not report an error", 0, answer[3].toInt() and 15)
                        val hasAnswers = answer[6].toInt() != 0 || answer[7].toInt() != 0
                        // Generated VPN profiles are IPv4-only: AAAA must be empty, not resolved upstream.
                        if (!hasAnswers && type == 28 && dns.get("queryStrategy")?.asString == "UseIPv4") return
                        assertTrue("Profile $index must answer $domain, type $type", hasAnswers)
                        assertArrayEquals(address, answer.takeLast(address.size).toByteArray())
                    }
                    assertAnswer("content-control.example", 1, byteArrayOf(203.toByte(), 0, 113, 10))
                    if (index == 3 || index >= 5) assertAnswer("adsdk.yandex.ru", 1, byteArrayOf(203.toByte(), 0, 113, 11))
                    if (index != 3) {
                        (listOf("banners.mobile.yandex.net", "r.my.com", "doubleclick.net", "adtech.yahooinc.com", "tracking.rus.miui.com") +
                            if (index < 5) listOf("adsdk.yandex.ru") else emptyList()).forEach {
                            assertAnswer(it, 1, ByteArray(4))
                            assertAnswer(it, 28, ByteArray(16))
                        }
                    }
                } finally {
                    command("shell", "kill", pid)
                    command("forward", "--remove", "tcp:$forward")
                }
            }
        } finally { fixture.delete(); database.delete() }
    }
}
