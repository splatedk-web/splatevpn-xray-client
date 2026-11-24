package com.danieldk.splatevpn.data

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object DatabaseMigrations {
    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("ALTER TABLE servers ADD COLUMN amneziaWg TEXT") }
    }

    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS backup_import_commit (slot INTEGER NOT NULL PRIMARY KEY, token TEXT NOT NULL)")
        }
    }

    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE subscriptions ADD COLUMN publisherMetadataJson TEXT NOT NULL DEFAULT ''")
        }
    }
    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE servers ADD COLUMN lastVerifiedAt INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE servers ADD COLUMN lastVerificationFailedAt INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE servers ADD COLUMN isRetained INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE servers ADD COLUMN retainedAt INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE subscriptions ADD COLUMN retainWorkingNodes INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE subscriptions ADD COLUMN retainedNodeCount INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE subscriptions ADD COLUMN useSubscriptionRules INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE subscriptions ADD COLUMN importedRulesJson TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE subscriptions ADD COLUMN importedRulesSkipped INTEGER NOT NULL DEFAULT 0")
        }
    }
    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE subscriptions ADD COLUMN lastAttemptAt INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE subscriptions ADD COLUMN lastError TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE subscriptions ADD COLUMN lastFailureRetryable INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE subscriptions ADD COLUMN previousNodeCount INTEGER NOT NULL DEFAULT 0")
            db.execSQL("CREATE TABLE IF NOT EXISTS subscription_updates (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, subscriptionId TEXT NOT NULL, attemptedAt INTEGER NOT NULL, successful INTEGER NOT NULL, previousNodeCount INTEGER NOT NULL, nodeCount INTEGER NOT NULL, error TEXT NOT NULL, retryable INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_subscription_updates_subscriptionId_attemptedAt ON subscription_updates (subscriptionId, attemptedAt)")
        }
    }
    private const val TAG = "DatabaseMigrations"

    private fun addColumnIfNotExists(
        db: SupportSQLiteDatabase,
        tableName: String,
        columnName: String,
        columnDefinition: String
    ) {
        var columnExists = false
        try {
            db.query("PRAGMA table_info(`$tableName`)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (nameIndex != -1 && cursor.getString(nameIndex).equals(columnName, ignoreCase = true)) {
                        columnExists = true
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking table_info for $tableName.$columnName: ${e.message}")
        }

        if (!columnExists) {
            try {
                db.execSQL("ALTER TABLE `$tableName` ADD COLUMN `$columnName` $columnDefinition")
                Log.i(TAG, "Added column `$columnName` to `$tableName`")
            } catch (e: Exception) {
                Log.w(TAG, "Could not add column `$columnName` to `$tableName`: ${e.message}")
            }
        }
    }

    private fun ensureSubscriptionsTable(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `subscriptions` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `url` TEXT NOT NULL,
                `lastUpdated` INTEGER NOT NULL,
                `nodeCount` INTEGER NOT NULL,
                `autoUpdate` INTEGER NOT NULL,
                `uploadBytes` INTEGER NOT NULL DEFAULT 0,
                `downloadBytes` INTEGER NOT NULL DEFAULT 0,
                `totalBytes` INTEGER NOT NULL DEFAULT 0,
                `expireTimestamp` INTEGER NOT NULL DEFAULT 0,
                `enabled` INTEGER NOT NULL DEFAULT 1,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
    }

    private fun ensureServersTable(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `servers` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `protocol` TEXT NOT NULL,
                `address` TEXT NOT NULL,
                `port` INTEGER NOT NULL,
                `uuid` TEXT NOT NULL DEFAULT '',
                `flow` TEXT NOT NULL DEFAULT '',
                `encryption` TEXT NOT NULL DEFAULT 'none',
                `network` TEXT NOT NULL DEFAULT 'tcp',
                `security` TEXT NOT NULL DEFAULT 'none',
                `sni` TEXT NOT NULL DEFAULT '',
                `fingerprint` TEXT NOT NULL DEFAULT 'chrome',
                `publicKey` TEXT NOT NULL DEFAULT '',
                `shortId` TEXT NOT NULL DEFAULT '',
                `spiderX` TEXT NOT NULL DEFAULT '',
                `path` TEXT NOT NULL DEFAULT '',
                `host` TEXT NOT NULL DEFAULT '',
                `mode` TEXT NOT NULL DEFAULT 'auto',
                `padding` TEXT NOT NULL DEFAULT '100-1000',
                `ping` INTEGER NOT NULL DEFAULT 0,
                `rawLink` TEXT NOT NULL DEFAULT '',
                `isSelected` INTEGER NOT NULL DEFAULT 0,
                `subId` TEXT NOT NULL DEFAULT '',
                `subName` TEXT NOT NULL DEFAULT '',
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
    }

    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Log.i(TAG, "Executing MIGRATION_1_2: ensuring subscriptions table and subId/subName in servers")
            ensureServersTable(db)
            addColumnIfNotExists(db, "servers", "subId", "TEXT NOT NULL DEFAULT ''")
            addColumnIfNotExists(db, "servers", "subName", "TEXT NOT NULL DEFAULT ''")
            ensureSubscriptionsTable(db)
        }
    }

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Log.i(TAG, "Executing MIGRATION_2_3: adding mode and padding to servers")
            ensureServersTable(db)
            addColumnIfNotExists(db, "servers", "mode", "TEXT NOT NULL DEFAULT 'auto'")
            addColumnIfNotExists(db, "servers", "padding", "TEXT NOT NULL DEFAULT '100-1000'")
            ensureSubscriptionsTable(db)
        }
    }

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Log.i(TAG, "Executing MIGRATION_3_4: adding traffic and expiration fields to subscriptions")
            ensureSubscriptionsTable(db)
            addColumnIfNotExists(db, "subscriptions", "uploadBytes", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfNotExists(db, "subscriptions", "downloadBytes", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfNotExists(db, "subscriptions", "totalBytes", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfNotExists(db, "subscriptions", "expireTimestamp", "INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Log.i(TAG, "Executing MIGRATION_4_5: adding enabled column to subscriptions")
            ensureSubscriptionsTable(db)
            addColumnIfNotExists(db, "subscriptions", "enabled", "INTEGER NOT NULL DEFAULT 1")
        }
    }

    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Log.i(TAG, "Executing MIGRATION_5_6: adding full Xray config storage to servers")
            addColumnIfNotExists(db, "servers", "fullConfigJson", "TEXT")
        }
    }

    val MIGRATION_1_5 = object : Migration(1, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Log.i(TAG, "Executing MIGRATION_1_5: full schema upgrade from v1 to v5")
            ensureServersTable(db)
            addColumnIfNotExists(db, "servers", "subId", "TEXT NOT NULL DEFAULT ''")
            addColumnIfNotExists(db, "servers", "subName", "TEXT NOT NULL DEFAULT ''")
            addColumnIfNotExists(db, "servers", "mode", "TEXT NOT NULL DEFAULT 'auto'")
            addColumnIfNotExists(db, "servers", "padding", "TEXT NOT NULL DEFAULT '100-1000'")
            ensureSubscriptionsTable(db)
            addColumnIfNotExists(db, "subscriptions", "uploadBytes", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfNotExists(db, "subscriptions", "downloadBytes", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfNotExists(db, "subscriptions", "totalBytes", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfNotExists(db, "subscriptions", "expireTimestamp", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfNotExists(db, "subscriptions", "enabled", "INTEGER NOT NULL DEFAULT 1")
        }
    }

    val MIGRATION_2_5 = object : Migration(2, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Log.i(TAG, "Executing MIGRATION_2_5: upgrade from v2 to v5")
            ensureServersTable(db)
            addColumnIfNotExists(db, "servers", "mode", "TEXT NOT NULL DEFAULT 'auto'")
            addColumnIfNotExists(db, "servers", "padding", "TEXT NOT NULL DEFAULT '100-1000'")
            ensureSubscriptionsTable(db)
            addColumnIfNotExists(db, "subscriptions", "uploadBytes", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfNotExists(db, "subscriptions", "downloadBytes", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfNotExists(db, "subscriptions", "totalBytes", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfNotExists(db, "subscriptions", "expireTimestamp", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfNotExists(db, "subscriptions", "enabled", "INTEGER NOT NULL DEFAULT 1")
        }
    }

    val MIGRATION_3_5 = object : Migration(3, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Log.i(TAG, "Executing MIGRATION_3_5: upgrade from v3 to v5")
            ensureSubscriptionsTable(db)
            addColumnIfNotExists(db, "subscriptions", "uploadBytes", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfNotExists(db, "subscriptions", "downloadBytes", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfNotExists(db, "subscriptions", "totalBytes", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfNotExists(db, "subscriptions", "expireTimestamp", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfNotExists(db, "subscriptions", "enabled", "INTEGER NOT NULL DEFAULT 1")
        }
    }
}
