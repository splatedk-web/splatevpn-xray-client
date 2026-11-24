package com.danieldk.splatevpn.data

import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.vpn.*
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.TimeUnit

class SmartDnsBootstrapNativeTest {
    @Test fun staleBootstrapIsRefreshedAndRealEncryptedDnsWorksWithAndWithoutFragment() = runBlocking {
        assumeTrue("Explicit external DNS diagnostic", InstrumentationRegistry.getArguments().getString("liveSmartDns") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val key = "dagami"; val provider = SmartDnsProviders.forAi(key)
        for (fragment in listOf(false, true)) {
            val port = ServerSocket(0).use { it.localPort }; val credentials = LocalProxyCredentials.generate()
            val root = JsonParser.parseString(XrayConfigGenerator().generateAiDnsProbeConfig(key, fragment, port, credentials)).asJsonObject
            root.getAsJsonObject("dns").getAsJsonObject("hosts").add(provider.host, JsonArray().apply { add("127.0.0.2") })
            val prepared = PreparedTransports.prepare(app, root.toString())
            val actual = JsonParser.parseString(prepared.json).asJsonObject.getAsJsonObject("dns").getAsJsonObject("hosts")[provider.host].asJsonArray.map { it.asString }
            assertTrue("Physical network DNS refresh did not replace the obsolete bootstrap", actual.isNotEmpty() && "127.0.0.2" !in actual)
            val directory = File(app.cacheDir,"smartdns-runtime-${UUID.randomUUID()}").apply { mkdirs() }
            val file = File(directory,"config.json").apply { writeText(prepared.json) }
            val process = ProcessBuilder(File(app.applicationInfo.nativeLibraryDir,"libxray.so").path,"run","-c",file.path)
                .redirectErrorStream(true).redirectOutput(File(directory,"core.log")).start()
            try {
                val deadline = System.currentTimeMillis() + 5000
                while (System.currentTimeMillis() < deadline && process.isAlive) {
                    if (runCatching { Socket("127.0.0.1",port).use { LocalProxyAccess.probeReadiness(it,credentials.username to credentials.password) } }.getOrDefault(false)) break
                    kotlinx.coroutines.delay(40)
                }
                assertTrue(process.isAlive)
                val client = LocalProxyAccess.client(OkHttpClient.Builder().callTimeout(8,TimeUnit.SECONDS).build(),port,credentials)
                try {
                    val query = AiDnsWire.query("gemini.google.com")
                    val request = Request.Builder().url(provider.dohUrl).post(query.toRequestBody("application/dns-message".toMediaType()))
                        .header("Accept","application/dns-message").build()
                    client.newCall(request).execute().use { reply ->
                        assertEquals(200,reply.code)
                        assertEquals("application/dns-message",reply.header("Content-Type")?.substringBefore(';'))
                        assertTrue(AiDnsWire.addresses(reply.body!!.bytes(),query).isNotEmpty())
                    }
                } finally { client.connectionPool.evictAll();client.dispatcher.executorService.shutdown() }
            } finally {
                process.destroyForcibly(); assertTrue(process.waitFor(3,TimeUnit.SECONDS));prepared.close();directory.deleteRecursively()
            }
        }
    }
}
