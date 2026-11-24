package com.danieldk.splatevpn.data

import okhttp3.Headers
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class SubscriptionMetadataTest {
    private fun response(vararg headers: Pair<String, String>) = SubscriptionResponse(200, "", Headers.Builder().apply {
        headers.forEach { (key, value) -> add(key, value) }
    }.build())
    private fun encoded(text: String) = "base64:" + Base64.getEncoder().encodeToString(text.toByteArray())

    @Test fun decodesPanelTitleAnnouncementLinksAndRecommendedInterval() {
        val metadata = SubscriptionMetadata.from(response("Profile-Title" to encoded("Название провайдера"),
            "Announce" to encoded("Описание\nВторая строка"), "Profile-Web-Page-Url" to "https://example.com/portal",
            "Support-Url" to "https://t.me/example", "Profile-Update-Interval" to "12"))
        assertEquals("Название провайдера", metadata.title)
        assertEquals("Описание\nВторая строка", metadata.announcement)
        assertEquals("https://example.com/portal", metadata.website)
        assertEquals("https://t.me/example", metadata.support)
        assertEquals(12, metadata.updateHours)
        assertEquals(metadata, SubscriptionMetadata.decode(metadata.encode()))
    }

    @Test fun rejectsExecutableLinksAndInvalidIntervalsWithoutRejectingTheSubscription() {
        val metadata = SubscriptionMetadata.from(response("profile-title" to "base64:not-base64!",
            "support-url" to "javascript:alert(1)", "profile-web-page-url" to "file:///private/data",
            "profile-update-interval" to "-1"))
        assertEquals(SubscriptionMetadata(), metadata)
    }

    @Test fun remoteTitleReplacesAddressAndTracksPreviouslyAutomaticName() {
        val fresh = SubscriptionMetadata(title = "New title")
        val previous = SubscriptionMetadata(title = "Old title")
        val sub = SubscriptionEntity("source", "https://example.com/sub", "https://example.com/sub")
        assertEquals("New title", fresh.displayName(sub))
        assertEquals("New title", fresh.displayName(sub.copy(name = "example.com")))
        assertEquals("New title", fresh.displayName(sub.copy(name = "Old title", publisherMetadataJson = previous.encode())))
    }

    @Test fun updatesPreserveUserDefinedNames() {
        val sub = SubscriptionEntity("source", "My custom name", "https://example.com/sub",
            publisherMetadataJson = SubscriptionMetadata(title = "Provider title").encode())
        assertEquals("My custom name", SubscriptionMetadata(title = "Renamed by provider").displayName(sub))
    }

    @Test fun oldBackupsAndMalformedMetadataAreSafe() {
        assertEquals(SubscriptionMetadata(), SubscriptionMetadata.decode(null))
        assertEquals(SubscriptionMetadata(), SubscriptionMetadata.decode("{"))
        assertEquals(SubscriptionMetadata(), SubscriptionMetadata.decode("{}"))
        assertEquals("", SubscriptionMetadata.decode("{\"website\":\"data:text/html,test\"}").website)
    }
}
