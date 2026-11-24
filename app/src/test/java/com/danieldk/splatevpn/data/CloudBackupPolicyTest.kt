package com.danieldk.splatevpn.data

import org.junit.Assert.*
import org.junit.Test

class CloudBackupPolicyTest {
    @Test fun obsoleteFolderAndUnknownDestinationsDisableAutoBackupWithoutLosingPreferences() {
        val settings = CloudBackupSettings(enabled = true, unmeteredOnly = false, intervalHours = 6,
            hasPassword = true, lastSuccessAt = 1234)
        for (target in listOf("folder", "unknown")) {
            val migrated = CloudBackupPolicy.migrateLegacySettings(settings, target)
            assertFalse(migrated.enabled)
            assertTrue(migrated.folderMigrationNotice)
            assertEquals(settings.copy(enabled = false, folderMigrationNotice = true), migrated)
        }
    }
    @Test fun existingWebDavAndModernSettingsKeepTheirSchedule() {
        val settings = CloudBackupSettings(enabled = true, intervalHours = 6, hasPassword = true, lastSuccessAt = 1234)
        assertEquals(settings, CloudBackupPolicy.migrateLegacySettings(settings, "webdav"))
        assertEquals(settings, CloudBackupPolicy.migrateLegacySettings(settings, null))
    }
    @Test fun snapshotUrlsAreContainedInValidatedHttpsFolder() {
        val name = CloudBackupPolicy.fileName("123456abcdef", 1000, "1234abcd")
        assertEquals("https://example.test/backups/$name", WebDavProtocol.snapshotUrl("https://example.test/backups", name))
        listOf("../secret", "splate-settings.v1.json", "https://evil.test/data").forEach {
            assertTrue(runCatching { WebDavProtocol.snapshotUrl("https://example.test/backups", it) }.isFailure)
        }
        assertTrue(runCatching { WebDavProtocol.snapshotUrl("http://example.test/backups", name) }.isFailure)
    }
    @Test fun snapshotNamesCannotInjectPathsOrUnboundedIdentifiers() {
        assertTrue(runCatching { CloudBackupPolicy.fileName("../outside", 1, "1234abcd") }.isFailure)
        assertTrue(runCatching { CloudBackupPolicy.fileName("123456abcdef", 0, "1234abcd") }.isFailure)
        assertTrue(runCatching { CloudBackupPolicy.fileName("123456abcdef", 1, "../../etc") }.isFailure)
    }
    @Test fun acknowledgedMigrationIsNotRepeatedAfterDestinationWasForgotten() {
        val acknowledged = CloudBackupPolicy.migrateLegacySettings(CloudBackupSettings(enabled = true), "folder")
            .copy(folderMigrationNotice = false)
        assertEquals(acknowledged, CloudBackupPolicy.migrateLegacySettings(acknowledged, null))
    }
}
