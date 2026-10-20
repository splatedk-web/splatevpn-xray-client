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
}
