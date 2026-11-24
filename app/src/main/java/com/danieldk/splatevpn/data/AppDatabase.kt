package com.danieldk.splatevpn.data

import androidx.room.Database
import androidx.room.RoomDatabase

@androidx.room.TypeConverters(AmneziaWgProfileConverter::class)
@Database(entities = [ServerNode::class, SubscriptionEntity::class, SubscriptionUpdateEvent::class, BackupImportCommit::class], version = 11, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
    abstract fun subscriptionDao(): SubscriptionDao
}

@androidx.room.Entity(tableName = "backup_import_commit")
internal data class BackupImportCommit(@androidx.room.PrimaryKey val slot: Int, val token: String)
