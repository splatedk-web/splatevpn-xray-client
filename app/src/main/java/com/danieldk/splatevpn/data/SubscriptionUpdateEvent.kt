package com.danieldk.splatevpn.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "subscription_updates", indices = [Index(value = ["subscriptionId", "attemptedAt"])])
data class SubscriptionUpdateEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val subscriptionId: String,
    val attemptedAt: Long,
    val successful: Boolean,
    val previousNodeCount: Int,
    val nodeCount: Int,
    val error: String,
    val retryable: Boolean
)
