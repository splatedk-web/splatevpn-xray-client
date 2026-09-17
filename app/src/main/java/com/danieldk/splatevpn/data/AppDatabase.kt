package com.danieldk.splatevpn.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [ServerNode::class, SubscriptionEntity::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
    abstract fun subscriptionDao(): SubscriptionDao
}
