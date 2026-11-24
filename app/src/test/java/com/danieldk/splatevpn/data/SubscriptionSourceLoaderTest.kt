package com.danieldk.splatevpn.data

import com.danieldk.splatevpn.SplateConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.net.ServerSocket
import java.net.URLEncoder
import java.util.concurrent.CopyOnWriteArrayList

class SubscriptionSourceLoaderTest {
    @Test fun deviceBoundSourceIdentifiesSplateAndDoesNotRetryAsAnotherApplication() = runBlocking {
        for (status in listOf(200, 403)) {
            HttpFixture { _, _ -> Reply(status, VALID) }.use { http ->
                val loader = SubscriptionSourceLoader(SubscriptionTransport()::fetch, deviceBound = true)
                val result = runCatching { loader.load(http.url("/private-token"), false, ::parse) }
                assertEquals(status == 200, result.isSuccess)
                assertEquals(1, http.requests.size)
                assertEquals("SplateVPN/${com.danieldk.splatevpn.BuildConfig.VERSION_NAME}", http.requests.single().second)
            }
        }
    }
    @Test fun selectedUserAgentIsSentForBoundAndUnboundSubscriptionsWithoutTryingOtherAgents() = runBlocking {
        for (deviceBound in listOf(true, false)) {
            HttpFixture { _, agent -> if (agent == "Happ") Reply(200, VALID) else Reply(403, "Denied") }.use { http ->
                val loader = SubscriptionSourceLoader(
                    SubscriptionTransport()::fetch, deviceBound = deviceBound, userAgent = SubscriptionUserAgent.HAPP
                )
                assertEquals(1, loader.load(http.url("/private-token"), false, ::parse).nodes.size)
                assertEquals(listOf("/private-token" to "Happ"), http.requests)
            }
            HttpFixture { _, _ -> Reply(403, "Denied") }.use { http ->
                val loader = SubscriptionSourceLoader(
                    SubscriptionTransport()::fetch, deviceBound = deviceBound, userAgent = SubscriptionUserAgent.KARING
                )
                assertTrue(runCatching { loader.load(http.url("/private-token"), false, ::parse) }.isFailure)
                assertEquals(listOf("/private-token" to "Karing"), http.requests)
            }
        }
    }

    @Test fun blackVlessSourceUsesGitlabFallback() {
        val primary = SplateConstants.PRESET_SUB_BLACK_URL
        assertEquals(listOf(primary, SplateConstants.PRESET_SUB_BLACK_FALLBACK_URL), subscriptionSourceCandidates(primary))
        val gitlab = SplateConstants.PRESET_SUB_BLACK_FALLBACK_URL
        assertEquals(listOf(gitlab, SplateConstants.PRESET_SUB_BLACK_URL), subscriptionSourceCandidates(gitlab))
    }

    @Test fun knownYandexCidrUsesTheExactRequestedRawBackupWithoutChangingThePrimary() {
        val primary = SplateConstants.PRESET_SUB_CIDR_URL
        assertEquals(listOf(primary,
            "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/refs/heads/main/WHITE-CIDR-RU-all.txt"),
            subscriptionSourceCandidates(primary))
        val encoded = "https://translate.yandex.com/translate?lang=ru-en&url=" +
            URLEncoder.encode("https://white.igareck.net/WHITE-CIDR-RU-all.txt", "UTF-8")
        assertEquals(SplateConstants.PRESET_SUB_CIDR_FALLBACK_URL, subscriptionSourceCandidates(encoded).last())
        assertEquals(2, subscriptionSourceCandidates("https://white.igareck.net/WHITE-CIDR-RU-all.txt").size)
        assertEquals(listOf(SplateConstants.PRESET_SUB_CIDR_FALLBACK_URL),
            subscriptionSourceCandidates(SplateConstants.PRESET_SUB_CIDR_FALLBACK_URL))
    }

    @Test fun privateEditedAndLookalikeUrlsNeverAcquireAnUnrelatedPublicBackup() {
        listOf(
            "https://translate.yandex.ru/translate?url=https://private.example/sub/token&lang=de-de",
            "https://translate.yandex.ru.evil.example/translate?url=https://white.igareck.net/WHITE-CIDR-RU-all.txt",
            "https://translate.yandex.ru/translate?url=https://white.igareck.net/WHITE-CIDR-RU-all.txt&url=https://private.example/sub",
            "https://white.igareck.net/WHITE-CIDR-RU-all.txt?token=private",
            "https://user:pass@white.igareck.net/WHITE-CIDR-RU-all.txt",
            "https://white.igareck.net:8443/WHITE-CIDR-RU-all.txt",
            "https://white.igareck.net/WHITE-CIDR-RU-checked.txt",
            "https://private.example/custom-subscription"
        ).forEach { assertEquals(it, listOf(it), subscriptionSourceCandidates(it)) }
    }

    @Test fun httpFailureFallsBackAndReturnsOnlyValidatedNodesAndMatchingHeaders() = runBlocking {
        HttpFixture { path, _ -> if (path == "/primary") Reply(503, "Unavailable")
            else Reply(200, VALID, "profile-title: Backup title\r\n") }.use { http ->
            val loader = localLoader(http)
            val result = loader.load(http.url("/primary"), false, ::parse)
            assertTrue(result.usedFallback)
            assertEquals("Backup", result.nodes.single().name)
            assertEquals("Backup title", result.response.header("profile-title"))
            assertEquals(listOf("/primary", "/backup"), http.requests.map { it.first })
        }
    }

    @Test fun htmlAndEmptySuccessBodiesDoNotPreventTheMirrorFromBeingTried() = runBlocking {
        for (invalidBody in listOf("<html><body>Access denied</body></html>", "")) {
            HttpFixture { path, _ -> Reply(200, if (path == "/primary") invalidBody else VALID) }.use { http ->
                val result = localLoader(http).load(http.url("/primary"), false, ::parse)
                assertTrue(result.usedFallback)
                assertEquals(1, result.nodes.size)
                assertEquals(listOf("/primary", "/primary", "/backup"), http.requests.map { it.first })
                assertEquals(listOf("v2rayNG/1.8.5", "ClashMeta/1.18.0", "v2rayNG/1.8.5"), http.requests.map { it.second })
            }
        }
    }

    @Test fun aValidPrimaryDoesNotContactTheBackupAndPrivateSourcesKeepClashCompatibility() = runBlocking {
        HttpFixture { _, agent -> if (agent.startsWith("v2rayNG")) Reply(403, "Denied") else Reply(200, VALID) }.use { http ->
            val transport = SubscriptionTransport()
            val result = SubscriptionSourceLoader(transport::fetch).load(http.url("/private-token"), false, ::parse)
            assertFalse(result.usedFallback)
            assertEquals(1, result.nodes.size)
            assertEquals(listOf("/private-token", "/private-token"), http.requests.map { it.first })
        }
        HttpFixture { _, _ -> Reply(200, VALID) }.use { http ->
            val result = localLoader(http).load(http.url("/primary"), false, ::parse)
            assertFalse(result.usedFallback)
            assertEquals(listOf("/primary"), http.requests.map { it.first })
        }
    }

    @Test fun aNetworkFailureUsesTheKnownMirrorAndKeepsTheRouteOnEveryAttempt() = runBlocking {
        HttpFixture { _, _ -> Reply(200, VALID) }.use { http ->
            val attempts = mutableListOf<Pair<String, Boolean>>()
            val transport = SubscriptionTransport()
            val loader = SubscriptionSourceLoader(fetch = { url, userAgent, viaVpn ->
                attempts += url to viaVpn
                if (url == SplateConstants.PRESET_SUB_CIDR_URL) throw IOException("Simulated unreachable source")
                // The transport itself stays local; assert the production resolver and route below.
                transport.fetch(http.url("/backup"), userAgent, false)
            })
            assertTrue(loader.load(SplateConstants.PRESET_SUB_CIDR_URL, true, ::parse).usedFallback)
            assertEquals(listOf(SplateConstants.PRESET_SUB_CIDR_URL to true,
                SplateConstants.PRESET_SUB_CIDR_FALLBACK_URL to true), attempts)
        }
    }

    @Test fun cancellationStopsTheWholeSequenceAndInvalidSourcesNeverYieldAnAcceptedResult() = runBlocking {
        var attempts = 0
        val cancellation = CancellationException("Cancelled by caller")
        val cancelled = SubscriptionSourceLoader(fetch = { _, _, _ -> attempts++; throw cancellation })
        assertSame(cancellation, runCatching { cancelled.load(SplateConstants.PRESET_SUB_CIDR_URL, true, ::parse) }.exceptionOrNull())
        assertEquals(1, attempts)
        HttpFixture { _, _ -> Reply(200, "<html>No configuration</html>") }.use { http ->
            val error = runCatching { localLoader(http).load(http.url("/primary"), false, ::parse) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertEquals("Invalid subscription data", subscriptionFailureLabel(requireNotNull(error)))
            assertEquals(4, http.requests.size)
        }
    }

    private fun parse(response: SubscriptionResponse) = LinkParser.parseSubscription(response.content, "sub", "Custom name")

    private fun localLoader(http: HttpFixture): SubscriptionSourceLoader {
        val transport = SubscriptionTransport()
        return SubscriptionSourceLoader(transport::fetch) { listOf(it, http.url("/backup")) }
    }

    private data class Reply(val code: Int, val body: String, val headers: String = "")

    private class HttpFixture(handler: (String, String) -> Reply) : Closeable {
        private val server = ServerSocket(0)
        val requests = CopyOnWriteArrayList<Pair<String, String>>()
        private val thread = Thread {
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val reader = socket.getInputStream().bufferedReader()
                        val path = reader.readLine().split(' ')[1]
                        var userAgent = ""
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("User-Agent:", true)) userAgent = line.substringAfter(':').trim()
                        }
                        requests += path to userAgent
                        val reply = handler(path, userAgent)
                        val body = reply.body.toByteArray(Charsets.UTF_8)
                        socket.getOutputStream().apply {
                            write(("HTTP/1.1 ${reply.code} Test\r\n${reply.headers}" +
                                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
                            write(body)
                            flush()
                        }
                    }
                } catch (error: IOException) { if (!server.isClosed) throw error }
            }
        }.apply { isDaemon = true; name = "subscription-source-test"; start() }

        fun url(path: String) = "http://127.0.0.1:${server.localPort}$path"
        override fun close() { server.close(); thread.join(1_000) }
    }

    private companion object {
        const val VALID = "vless://11111111-2222-3333-4444-555555555555@example.com:443?security=tls#Backup"
    }
}
