package com.danieldk.splatevpn.data

import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class InvalidProfileProbeTest {
    @Test fun invalidRealityProfileReturnsFailureAndReleasesProbeResources() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = EntryPointAccessors.fromApplication(
            app, SubscriptionUpdateWorker.WorkerDependencies::class.java
        ).serverRepository()
        val key = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(1952))
        val link = "vless://11111111-2222-3333-4444-555555555555@192.0.2.1:443?security=reality&type=xhttp&sni=example.com&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&pqv="
        val node = requireNotNull(LinkParser.parse(link + key.dropLast(4)))
        val before = app.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("xray_test_") }.map { it.name }.toSet()
        // Repeat so a leaked gate/active ID cannot hide behind the first handled failure.
        repeat(2) {
            assertEquals(-1L, withTimeout(10_000) { repository.testRealDelay(node) })
            assertFalse(repository.activePingIds.value.contains(node.id))
        }
        val after = app.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("xray_test_") }.map { it.name }.toSet()
        assertEquals(before, after)
    }
}
