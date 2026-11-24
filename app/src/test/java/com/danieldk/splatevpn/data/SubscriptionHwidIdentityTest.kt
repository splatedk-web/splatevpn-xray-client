package com.danieldk.splatevpn.data

import com.danieldk.splatevpn.SplateConstants
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

class SubscriptionHwidIdentityTest {
    private val source = "https://private.example/sub/token"
    private val id = "a".repeat(64)

    @Test fun installationAndSourceBothContributeToTheStableId() {
        val secret = ByteArray(32) { it.toByte() }
        val first = deriveSubscriptionHwid(secret, source)
        assertTrue(first.matches(Regex("[a-f0-9]{64}")))
        assertEquals(first, deriveSubscriptionHwid(secret, source))
        assertNotEquals(first, deriveSubscriptionHwid(secret, "$source-2"))
        assertNotEquals(first, deriveSubscriptionHwid(ByteArray(32) { 7 }, source))
    }

    @Test fun canonicalSourceHandlesWrappersHostCaseAndFragmentsWithoutMergingAccounts() {
        assertEquals(SubscriptionHwidPolicy.key(source), SubscriptionHwidPolicy.key("https://PRIVATE.example:443/sub/token#title"))
        assertEquals(SubscriptionHwidPolicy.key(source), SubscriptionHwidPolicy.key("happ://add/$source"))
        assertNotEquals(SubscriptionHwidPolicy.key(source), SubscriptionHwidPolicy.key("$source?account=2"))
        assertNotEquals(SubscriptionHwidPolicy.key(source), SubscriptionHwidPolicy.key("https://private.example:8443/sub/token"))
    }

    @Test fun privateSourcesRequireExplicitChoiceExceptForTheExistingSponsor() {
        assertFalse(SubscriptionHwidPolicy.enabled(source, emptyMap()))
        val key = requireNotNull(SubscriptionHwidPolicy.key(source))
        assertTrue(SubscriptionHwidPolicy.enabled(source, mapOf(key to true)))
        assertFalse(SubscriptionHwidPolicy.enabled(source, mapOf(key to false)))
        val sponsor = SplateConstants.PRESET_SUB_SPONSOR_URL
        if (sponsor.isBlank()) {
            assertFalse(SubscriptionHwidPolicy.enabled(sponsor, emptyMap()))
        } else {
            assertTrue(SubscriptionHwidPolicy.enabled(sponsor, emptyMap()))
            assertFalse(SubscriptionHwidPolicy.enabled(
                sponsor, mapOf(requireNotNull(SubscriptionHwidPolicy.key(sponsor)) to false)
            ))
        }
    }

    @Test fun httpPublicFeedsAndUrlsWithCredentialsNeverReceiveIdentity() {
        for (url in listOf("http://private.example/sub", "https://user:password@private.example/sub",
            "https://raw.githubusercontent.com/owner/project/main/sub.txt", "https://github.com/owner/project/raw/sub.txt",
            "https://raw.githack.com/owner/project/main/tor.txt", "https://bridges.torproject.org/bridges",
            SplateConstants.PRESET_SUB_CIDR_URL, SplateConstants.PRESET_SUB_CIDR_FALLBACK_URL)) {
            assertFalse(SubscriptionHwidPolicy.canSend(url))
            val key = SubscriptionHwidPolicy.key(url)
            assertFalse(SubscriptionHwidPolicy.enabled(url, if (key == null) emptyMap() else mapOf(key to true)))
        }
    }

    @Test fun identityStaysOnItsOriginalHttpsOriginAndCannotReturnAfterLeavingIt() {
        val identity = SubscriptionRequestIdentity(source, id)
        assertFalse(identity.toString().contains(id))
        assertFalse(identity.toString().contains("token"))
        assertNull(identity.redirectStateFor("$source-other"))
        val state = requireNotNull(identity.redirectStateFor(source))
        fun request(url: String) = state.apply(
            Request.Builder().url(url)
                .header("X-HWID", "stale")
                .header("X-Device-OS", "stale")
                .header("X-Ver-OS", "stale")
                .header("X-Device-Model", "stale")
                .build()
        )
        // Headers are attached (or absent when Build fields are null in JVM tests) while on the
        // original origin; stale values must never survive in any case.
        val onOrigin = request("https://private.example/resolved")
        assertEquals(id, onOrigin.header("X-HWID"))
        assertEquals("Android", onOrigin.header("X-Device-OS"))
        assertNotEquals("stale", onOrigin.header("X-Ver-OS"))
        assertNotEquals("stale", onOrigin.header("X-Device-Model"))
        // All identity headers are stripped once the request leaves the original origin.
        assertNull(request("https://other.example/sub").header("X-HWID"))
        // After leaving origin the state is permanently revoked.
        assertNull(request(source).header("X-HWID"))
        assertNull(request(source).header("X-Device-OS"))
        assertNull(request(source).header("X-Ver-OS"))
        assertNull(request(source).header("X-Device-Model"))
        for (target in listOf("http://private.example/sub", "https://private.example:8443/sub")) {
            val otherState = requireNotNull(identity.redirectStateFor(source))
            assertNull(otherState.apply(Request.Builder().url(target).build()).header("X-HWID"))
        }
    }

    @Test fun definitiveHwidDenialStopsBeforeParsingAndNeverRetriesAnotherClientOrMirror() = runBlocking {
        for ((header, reason) in listOf("x-hwid-not-supported" to SubscriptionHwidFailure.REQUIRED,
            "x-hwid-max-devices-reached" to SubscriptionHwidFailure.DEVICE_LIMIT)) {
            var requests = 0
            var parses = 0
            val loader = SubscriptionSourceLoader(fetch = { _, _, _ ->
                requests++
                SubscriptionResponse(200, "placeholder config", Headers.Builder().add(header, "true").build())
            }, candidates = { listOf(it, "https://other.example/sub") })
            val error = runCatching { loader.load(source, false) { parses++; listOf("node") } }.exceptionOrNull()
            assertEquals(reason, (error as SubscriptionHwidException).reason)
            assertFalse(isRetryableSubscriptionFailure(error))
            assertEquals(1, requests)
            assertEquals(0, parses)
        }
    }

    @Test fun legacyLimitFlagOnHttp200IsAcceptedButNotOnDeniedResponse() {
        val headers = Headers.Builder().add("x-hwid-limit", "true").build()
        SubscriptionResponse(200, "config", headers).checkHwidAccess()
        val error = assertThrows(SubscriptionHwidException::class.java) {
            SubscriptionResponse(403, "denied", headers).checkHwidAccess()
        }
        assertEquals("HWID device limit reached", subscriptionFailureLabel(error))
        assertEquals("HWID required", subscriptionFailureLabel(SubscriptionHwidException(SubscriptionHwidFailure.REQUIRED, 404)))
    }
}
