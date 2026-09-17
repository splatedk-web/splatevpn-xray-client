package com.danieldk.splatevpn.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "subscriptions")
data class SubscriptionEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val url: String,
    val lastUpdated: Long = System.currentTimeMillis(),
    val nodeCount: Int = 0,
    val autoUpdate: Boolean = true
)
