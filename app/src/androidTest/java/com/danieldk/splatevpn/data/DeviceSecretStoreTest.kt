package com.danieldk.splatevpn.data

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class DeviceSecretStoreTest {
    @Test fun cloudSecretIsEncryptedSurvivesRecreationAndCanBeForgotten() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val alias = "cloud-test-${UUID.randomUUID()}"
        val store = DeviceSecretStore(context, alias)
        val file = File(context.noBackupFilesDir, "$alias.json")
        try {
            assertNull(store.read())
            store.write("unique-backup-password-123456789")
            assertFalse(file.readText().contains("unique-backup-password"))
            assertEquals("unique-backup-password-123456789", DeviceSecretStore(context, alias).read())
            val original = file.readText()
            val document = com.google.gson.JsonParser.parseString(original).asJsonObject
            val ciphertext = java.util.Base64.getDecoder().decode(document["ciphertext"].asString)
            ciphertext[0] = (ciphertext[0].toInt() xor 1).toByte()
            document.addProperty("ciphertext", java.util.Base64.getEncoder().encodeToString(ciphertext))
            file.writeText(document.toString())
            assertTrue(runCatching { store.read() }.isFailure)
            store.delete()
            assertNull(DeviceSecretStore(context, alias).read())
        } finally {
            store.delete()
            java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }
}
