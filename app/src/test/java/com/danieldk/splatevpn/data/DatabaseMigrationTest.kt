package com.danieldk.splatevpn.data

import org.junit.Assert.*
import org.junit.Test

class DatabaseMigrationTest {

    @Test
    fun testMigrationsDefinitionsExist() {
        assertNotNull(DatabaseMigrations.MIGRATION_1_2)
        assertEquals(1, DatabaseMigrations.MIGRATION_1_2.startVersion)
        assertEquals(2, DatabaseMigrations.MIGRATION_1_2.endVersion)

        assertNotNull(DatabaseMigrations.MIGRATION_2_3)
        assertEquals(2, DatabaseMigrations.MIGRATION_2_3.startVersion)
        assertEquals(3, DatabaseMigrations.MIGRATION_2_3.endVersion)

        assertNotNull(DatabaseMigrations.MIGRATION_3_4)
        assertEquals(3, DatabaseMigrations.MIGRATION_3_4.startVersion)
        assertEquals(4, DatabaseMigrations.MIGRATION_3_4.endVersion)

        assertNotNull(DatabaseMigrations.MIGRATION_4_5)
        assertEquals(4, DatabaseMigrations.MIGRATION_4_5.startVersion)
        assertEquals(5, DatabaseMigrations.MIGRATION_4_5.endVersion)

        assertNotNull(DatabaseMigrations.MIGRATION_1_5)
        assertEquals(1, DatabaseMigrations.MIGRATION_1_5.startVersion)
        assertEquals(5, DatabaseMigrations.MIGRATION_1_5.endVersion)

        assertNotNull(DatabaseMigrations.MIGRATION_2_5)
        assertEquals(2, DatabaseMigrations.MIGRATION_2_5.startVersion)
        assertEquals(5, DatabaseMigrations.MIGRATION_2_5.endVersion)

        assertNotNull(DatabaseMigrations.MIGRATION_3_5)
        assertEquals(3, DatabaseMigrations.MIGRATION_3_5.startVersion)
        assertEquals(5, DatabaseMigrations.MIGRATION_3_5.endVersion)
    }

    @Test
    fun testServerNodeDefaults() {
        val server = ServerNode(
            id = "test-node",
            name = "Test Server",
            protocol = "vless",
            address = "1.2.3.4",
            port = 443
        )
        assertEquals("test-node", server.id)
        assertEquals("none", server.encryption)
        assertEquals("tcp", server.network)
        assertEquals("none", server.security)
        assertEquals("auto", server.mode)
        assertEquals("100-1000", server.padding)
        assertEquals("", server.subId)
        assertEquals("", server.subName)
    }

    @Test
    fun testSubscriptionEntityTrafficFields() {
        val sub = SubscriptionEntity(
            id = "sub-1",
            name = "Premium Sub",
            url = "https://example.com/sub",
            uploadBytes = 1024L,
            downloadBytes = 2048L,
            totalBytes = 1048576L,
            expireTimestamp = 1700000000L,
            enabled = true
        )
        assertEquals(1024L, sub.uploadBytes)
        assertEquals(2048L, sub.downloadBytes)
        assertEquals(1048576L, sub.totalBytes)
        assertEquals(1700000000L, sub.expireTimestamp)
        assertTrue(sub.enabled)
    }
}
