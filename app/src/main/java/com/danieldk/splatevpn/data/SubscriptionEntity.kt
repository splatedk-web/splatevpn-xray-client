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
    val autoUpdate: Boolean = true,
    val uploadBytes: Long = 0L,
    val downloadBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val expireTimestamp: Long = 0L,
    val enabled: Boolean = true,
    @androidx.room.ColumnInfo(defaultValue = "0") val lastAttemptAt: Long = 0L,
    @androidx.room.ColumnInfo(defaultValue = "''") val lastError: String = "",
    @androidx.room.ColumnInfo(defaultValue = "0") val lastFailureRetryable: Boolean = false,
    @androidx.room.ColumnInfo(defaultValue = "0") val previousNodeCount: Int = 0,
    @androidx.room.ColumnInfo(defaultValue = "1") val retainWorkingNodes: Boolean = true,
    @androidx.room.ColumnInfo(defaultValue = "0") val retainedNodeCount: Int = 0,
    @androidx.room.ColumnInfo(defaultValue = "0") val useSubscriptionRules: Boolean = false,
    @androidx.room.ColumnInfo(defaultValue = "''") val importedRulesJson: String = "",
    @androidx.room.ColumnInfo(defaultValue = "0") val importedRulesSkipped: Int = 0,
    @androidx.room.ColumnInfo(defaultValue = "''") val publisherMetadataJson: String = ""
)
