package com.danieldk.splatevpn.data

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class BackupValidationTest {
    private val gson = Gson()
    private fun document(settings: Map<String, Any> = emptyMap()) = gson.toJson(BackupManager.BackupData(settings = settings))
    private fun rejects(json: String) {
        try { BackupValidation.decode(json); fail("Invalid document was accepted") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun legacyBackupAndEmptyListsRemainSupported() {
        val json = document(mapOf("block_quic" to true)).replace("2.2.0", "2.1.0")
        val result = BackupValidation.decode(json)
        assertTrue(result.perAppPackages.isEmpty())
        assertTrue(result.customRoutes.isEmpty())
        assertEquals(true, result.settings["block_quic"])
    }
    @Test fun subscriptionsFromOlderBackupsUseTheStandardUpdateDefaults() {
        val backup = com.google.gson.JsonParser.parseString(document()).asJsonObject
        backup.getAsJsonArray("subscriptions").add(com.google.gson.JsonParser.parseString(
            """{"id":"old","name":"Legacy","url":"https://example.com/subscription"}"""
        ))
        val source = BackupValidation.decode(backup.toString()).subscriptions.single()
        assertTrue(source.enabled)
        assertTrue(source.autoUpdate)
        assertEquals("", source.lastError)
        assertTrue(SubscriptionUpdatePolicy.isDue(source, "12h", 1720000000000))
    }

    @Test fun unrelatedJsonAndNullCollectionsAreRejected() {
        rejects("{}")
        rejects(document().replace("\"servers\":[]", "\"servers\":null"))
        rejects(document().replace("\"perAppPackages\":[]", "\"perAppPackages\":[null]"))
    }
    @Test fun invalidSettingsAreRejectedBeforeAnyWrites() {
        rejects(document(mapOf("ad_blocker" to "maybe")))
        rejects(document(mapOf("glass_transparency" to 2.0)))
        rejects(document(mapOf("sub_auto_update_interval" to "every-second")))
    }
    @Test fun malformedRoutesAndDuplicateServersAreRejected() {
        rejects(gson.toJson(BackupManager.BackupData(customRoutes = listOf(listOf("example.com", "unexpected")))))
        val node = ServerNode("same", "Test", "vless", "example.com", 443)
        rejects(gson.toJson(BackupManager.BackupData(servers = listOf(node, node))))
    }
    @Test fun newlySavedSettingsKeepTheirValues() {
        val result = BackupValidation.decode(document(mapOf("app_theme" to "winx", "app_language" to "ru",
            "sub_auto_update_interval" to "12h", "tls_fragment_length" to "11-22")))
        assertEquals("winx", result.settings["app_theme"])
        assertEquals("12h", result.settings["sub_auto_update_interval"])
        assertEquals("11-22", result.settings["tls_fragment_length"])
    }

    @Test fun hwidBackupContainsOnlyValidatedSourceConsentChoices() {
        val key = "a".repeat(64)
        val encoded = "{\"$key\":true}"
        assertEquals(encoded, BackupValidation.decode(document(mapOf("subscription_hwid_v1" to encoded))).settings["subscription_hwid_v1"])
        for (invalid in listOf("[]", "{\"raw-private-url\":true}", "{\"$key\":\"true\"}", "{\"$key\":1}", "{\"$key\":null}")) {
            rejects(document(mapOf("subscription_hwid_v1" to invalid)))
        }
    }

    @Test fun hourlyUpdateIntervalsRemainValidInTransferredBackupDocuments() {
        for (interval in listOf("1h", "3h")) {
            val restored = BackupValidation.decode(document(mapOf("sub_auto_update_interval" to interval)))
            assertEquals(interval, restored.settings["sub_auto_update_interval"])
        }
    }

    @Test fun portableUpdateSettingsAcceptSupportedLocalesAndRejectMalformedValues() {
        val key = "a".repeat(64)
        val values = mapOf(
            "app_language" to "zh-Hans",
            "ui_mode" to "pro",
            "last_sub_update_time" to "1720000000000",
            "subscription_ua_overrides_v1" to """{"$key":"happ"}"""
        )
        assertEquals(values, BackupValidation.decode(document(values)).settings)
        rejects(document(values + ("ui_mode" to "unsupported")))
        rejects(document(values + ("last_sub_update_time" to "-1")))
        rejects(document(values + ("subscription_ua_overrides_v1" to """{"$key":"bad-agent"}""")))
    }

    @Test fun performancePreferencesRemainBooleanInBackupDocuments() {
        val settings = mapOf("ui_animations_enabled" to false, "ui_decorative_background_enabled" to false,
            "ui_glass_highlights_enabled" to true)
        assertEquals(settings, BackupValidation.decode(document(settings)).settings)
        rejects(document(mapOf("ui_animations_enabled" to "sometimes")))
    }

    @Test fun wifiOnlyNetworkPreferencesRoundTripAndRejectInvalidValues() {
        val settings = mapOf("updates_wifi_only" to true, "checks_wifi_only" to false)
        assertEquals(settings, BackupValidation.decode(document(settings)).settings)
        rejects(document(mapOf("updates_wifi_only" to "sometimes")))
        rejects(document(mapOf("checks_wifi_only" to 42)))
    }

    @Test fun legacySubscriptionBackupsEnableBoundedReserveWithoutInventingVerification() {
        val node = ServerNode("node", "Test", "vless", "example.com", 443, subId = "sub")
        val root = com.google.gson.JsonParser.parseString(gson.toJson(BackupManager.BackupData(
            servers = listOf(node), subscriptions = listOf(SubscriptionEntity("sub", "Test", "https://example.com"))))).asJsonObject
        val sub = root["subscriptions"].asJsonArray[0].asJsonObject
        for (key in listOf("retainWorkingNodes", "retainedNodeCount", "useSubscriptionRules", "importedRulesJson", "importedRulesSkipped")) sub.remove(key)
        val rawNode = root["servers"].asJsonArray[0].asJsonObject
        for (key in listOf("lastVerifiedAt", "lastVerificationFailedAt", "isRetained", "retainedAt")) rawNode.remove(key)
        val restored = BackupValidation.decode(root.toString())
        assertTrue(restored.subscriptions.single().retainWorkingNodes)
        assertEquals("", restored.subscriptions.single().importedRulesJson)
        assertFalse(restored.subscriptions.single().useSubscriptionRules)
        assertEquals(0L, restored.servers.single().lastVerifiedAt)
        assertFalse(restored.servers.single().isRetained)
    }

    @Test fun reserveMetadataRoundTripsAndCountsAreRecomputedFromActualRows() {
        val retained = ServerNode("old", "Test", "vless", "example.com", 443, subId = "sub",
            lastVerifiedAt = 1000, retainedAt = 2000, isRetained = true)
        val backup = BackupManager.BackupData(servers = listOf(retained), subscriptions = listOf(
            SubscriptionEntity("sub", "Test", "https://example.com", retainedNodeCount = 99)))
        val restored = BackupValidation.decode(gson.toJson(backup))
        assertEquals(retained, restored.servers.single())
        assertEquals(1, restored.subscriptions.single().retainedNodeCount)
        rejects(gson.toJson(backup.copy(servers = listOf(retained.copy(retainedAt = 0)))))
        rejects(gson.toJson(backup.copy(servers = listOf(retained.copy(lastVerifiedAt = -1)))))
    }

    @Test fun backupCannotEnablePartiallyImportedOrInvalidRoutingRules() {
        val rules = """[{"type":"field","domain":["full:example.com"],"outboundTag":"direct"}]"""
        val sub = SubscriptionEntity("sub", "Test", "https://example.com", useSubscriptionRules = true, importedRulesJson = rules)
        val backup = BackupManager.BackupData(subscriptions = listOf(sub))
        assertTrue(BackupValidation.decode(gson.toJson(backup)).subscriptions.single().useSubscriptionRules)
        rejects(gson.toJson(backup.copy(subscriptions = listOf(sub.copy(importedRulesSkipped = 1)))))
        rejects(gson.toJson(backup.copy(subscriptions = listOf(sub.copy(importedRulesJson = "[]")))))
        val invalid = sub.copy(importedRulesJson = rules.replace("direct", "arbitrary"))
        rejects(gson.toJson(backup.copy(subscriptions = listOf(invalid))))
    }
}
