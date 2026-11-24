package com.danieldk.splatevpn.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class DatabaseMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    @get:Rule val helper = MigrationTestHelper(instrumentation, AppDatabase::class.java.canonicalName!!, FrameworkSQLiteOpenHelperFactory())
    private val migrations = arrayOf(DatabaseMigrations.MIGRATION_1_2, DatabaseMigrations.MIGRATION_2_3,
        DatabaseMigrations.MIGRATION_3_4, DatabaseMigrations.MIGRATION_4_5, DatabaseMigrations.MIGRATION_5_6,
        DatabaseMigrations.MIGRATION_1_5, DatabaseMigrations.MIGRATION_2_5, DatabaseMigrations.MIGRATION_3_5,
        DatabaseMigrations.MIGRATION_6_7, DatabaseMigrations.MIGRATION_7_8, DatabaseMigrations.MIGRATION_8_9, DatabaseMigrations.MIGRATION_9_10, DatabaseMigrations.MIGRATION_10_11)

    @Test fun tenToElevenAddsNullableEncryptedAwgWithoutChangingExistingRows() {
        val name = "awg-migration-${UUID.randomUUID()}"
        try {
            helper.createDatabase(name, 10).close()
            helper.runMigrationsAndValidate(name, 11, true, DatabaseMigrations.MIGRATION_10_11).use { db ->
                db.query("SELECT amneziaWg FROM servers").use { assertFalse(it.moveToFirst()) }
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun eightToNinePreservesSubscriptionsAndAddsEmptyPublisherMetadata() = runBlocking<Unit> {
        val name = "metadata-migration-${UUID.randomUUID()}"
        try {
            helper.createDatabase(name, 8).apply {
                execSQL("INSERT INTO subscriptions (id,name,url,lastUpdated,nodeCount,autoUpdate,uploadBytes,downloadBytes,totalBytes,expireTimestamp,enabled) VALUES ('source','Custom title','https://example.com/sub',123,0,1,0,0,0,0,1)")
                close()
            }
            helper.runMigrationsAndValidate(name, 9, true, DatabaseMigrations.MIGRATION_8_9).close()
            val db = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(*migrations).build()
            try {
                val source = requireNotNull(db.subscriptionDao().getSubscriptionById("source"))
                assertEquals("Custom title", source.name)
                assertEquals(123L, source.lastUpdated)
                assertEquals("", source.publisherMetadataJson)
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun sevenToEightAddsSafeReserveAndRulesDefaultsWithoutLosingData() = runBlocking<Unit> {
        val name = "migration-reserve-${UUID.randomUUID()}"
        try {
            helper.createDatabase(name, 7).use { db ->
                db.execSQL("INSERT INTO subscriptions (id,name,url,lastUpdated,nodeCount,autoUpdate,uploadBytes,downloadBytes,totalBytes,expireTimestamp,enabled) VALUES ('sub','Private','https://example.com/private',123,1,1,2,3,4,5,1)")
                val columns = instrumentation.context.assets.open("com.danieldk.splatevpn.data.AppDatabase/7.json").bufferedReader().use {
                    JsonParser.parseReader(it).asJsonObject["database"].asJsonObject["entities"].asJsonArray
                        .first { table -> table.asJsonObject["tableName"].asString == "servers" }.asJsonObject["fields"].asJsonArray
                }
                val values = columns.associate { field ->
                    val f = field.asJsonObject; val key = f["columnName"].asString
                    key to when (key) {
                        "id" -> "'node'"; "name" -> "'Private'"; "protocol" -> "'trojan'"; "address" -> "'example.com'"
                        "uuid" -> "'secret-password'"; "subId" -> "'sub'"; "port" -> "443"; "isSelected" -> "1"
                        else -> if (f["affinity"].asString == "INTEGER") "0" else "''"
                    }
                }
                db.execSQL("INSERT INTO servers (${values.keys.joinToString()}) VALUES (${values.values.joinToString()})")
            }
            helper.runMigrationsAndValidate(name, 8, true, DatabaseMigrations.MIGRATION_7_8).close()
            val db = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(*migrations).build()
            try {
                val node = requireNotNull(db.serverDao().getSelectedServerSync())
                assertEquals("secret-password", node.uuid)
                assertEquals(0L, node.lastVerifiedAt)
                assertFalse(node.isRetained)
                val sub = requireNotNull(db.subscriptionDao().getSubscriptionById("sub"))
                assertTrue(sub.retainWorkingNodes)
                assertEquals(0, sub.retainedNodeCount)
                assertFalse(sub.useSubscriptionRules)
                assertEquals("", sub.importedRulesJson)
                assertEquals(0, sub.importedRulesSkipped)
                assertEquals(123L, sub.lastUpdated)
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun sixToSevenPreservesCredentialsSelectionAndSubscription() = runBlocking<Unit> {
        val name = "migration-${UUID.randomUUID()}"
        try {
            helper.createDatabase(name, 6).use { db ->
                db.execSQL("INSERT INTO subscriptions (id,name,url,lastUpdated,nodeCount,autoUpdate,uploadBytes,downloadBytes,totalBytes,expireTimestamp,enabled) VALUES ('sub','Private','https://example.com/private',123,1,1,2,3,4,5,1)")
                val columns = instrumentation.context.assets.open("com.danieldk.splatevpn.data.AppDatabase/6.json").bufferedReader().use {
                    JsonParser.parseReader(it).asJsonObject["database"].asJsonObject["entities"].asJsonArray
                        .first { table -> table.asJsonObject["tableName"].asString == "servers" }.asJsonObject["fields"].asJsonArray
                }
                val values = columns.associate { field ->
                    val f = field.asJsonObject; val key = f["columnName"].asString
                    key to when (key) {
                        "id" -> "'node'"; "name" -> "'Private'"; "protocol" -> "'trojan'"; "address" -> "'example.com'"
                        "uuid" -> "'secret-password'"; "subId" -> "'sub'"; "port" -> "443"; "isSelected" -> "1"
                        else -> if (f["affinity"].asString == "INTEGER") "0" else "''"
                    }
                }
                db.execSQL("INSERT INTO servers (${values.keys.joinToString()}) VALUES (${values.values.joinToString()})")
            }
            helper.runMigrationsAndValidate(name, 7, true, DatabaseMigrations.MIGRATION_6_7).close()
            Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(*migrations).build().let { db ->
                try {
                assertEquals("secret-password", db.serverDao().getSelectedServerSync()!!.uuid)
                val subscription = db.subscriptionDao().getSubscriptionById("sub")!!
                assertEquals(123L, subscription.lastUpdated)
                assertEquals("", subscription.lastError)
                assertEquals(0L, subscription.lastAttemptAt)
                db.subscriptionDao().recordAttempt("sub", 456)
                db.subscriptionDao().recordUpdateResult("sub", "HTTP 503", true, 1)
                assertTrue(db.subscriptionDao().getSubscriptionById("sub")!!.lastFailureRetryable)
                } finally { db.close() }
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun everyLegacyUpgradePathPreservesExistingServer() = runBlocking<Unit> {
        val schema = instrumentation.context.assets.open("com.danieldk.splatevpn.data.AppDatabase/6.json").bufferedReader().use {
            JsonParser.parseReader(it).asJsonObject["database"].asJsonObject["entities"].asJsonArray
        }
        for (version in 1..5) {
            val name = "migration-v$version-${UUID.randomUUID()}"
            try {
                val sqlite = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
                    .name(name).callback(object : SupportSQLiteOpenHelper.Callback(version) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            schema.forEach { raw ->
                                val table = raw.asJsonObject
                                val tableName = table["tableName"].asString
                                if (version == 1 && tableName == "subscriptions") return@forEach
                                val fields = table["fields"].asJsonArray.map { it.asJsonObject }.filter {
                                    val key = it["columnName"].asString
                                    when (tableName) {
                                        "servers" -> key != "fullConfigJson" && (version >= 2 || key !in setOf("subId", "subName")) &&
                                            (version >= 3 || key !in setOf("mode", "padding"))
                                        else -> (version >= 4 || key !in setOf("uploadBytes", "downloadBytes", "totalBytes", "expireTimestamp")) && (version >= 5 || key != "enabled")
                                    }
                                }
                                val definitions = fields.map { "`${it["columnName"].asString}` ${it["affinity"].asString}" + if (it["notNull"].asBoolean) " NOT NULL" else "" }
                                db.execSQL("CREATE TABLE `$tableName` (${definitions.joinToString()}, PRIMARY KEY(`id`))")
                                if (tableName == "servers") {
                                    val values = fields.map {
                                        when (it["columnName"].asString) {
                                            "id" -> "'legacy'"; "uuid" -> "'preserved-secret'"; "port" -> "443"
                                            else -> if (it["affinity"].asString == "INTEGER") "0" else "'legacy'"
                                        }
                                    }
                                    db.execSQL("INSERT INTO servers VALUES (${values.joinToString()})")
                                }
                            }
                        }
                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    }).build())
                sqlite.writableDatabase
                sqlite.close()
                Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(*migrations).build().let { db ->
                try {
                    assertEquals("v$version", "preserved-secret", db.serverDao().getAllServersSync().single().uuid)
                    } finally { db.close() }
                }
            } finally { context.deleteDatabase(name) }
        }
    }
}
