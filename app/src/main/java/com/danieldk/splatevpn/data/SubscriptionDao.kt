package com.danieldk.splatevpn.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM subscription_updates WHERE subscriptionId = :id ORDER BY id DESC LIMIT 20")
    fun updateHistory(id: String): Flow<List<SubscriptionUpdateEvent>>

    @Insert suspend fun insertUpdateEvent(event: SubscriptionUpdateEvent)

    @Query("DELETE FROM subscription_updates WHERE subscriptionId = :id AND id NOT IN (SELECT id FROM subscription_updates WHERE subscriptionId = :id ORDER BY id DESC LIMIT 20)")
    suspend fun trimUpdateHistory(id: String)

    @Query("DELETE FROM subscription_updates WHERE subscriptionId = :id")
    suspend fun deleteUpdateHistory(id: String)

    @Query("DELETE FROM subscription_updates")
    suspend fun clearUpdateHistory()
    @Query("UPDATE subscriptions SET lastAttemptAt = :time, lastError = 'Update interrupted', lastFailureRetryable = 1 WHERE id = :id")
    suspend fun recordAttempt(id: String, time: Long)

    @Query("UPDATE subscriptions SET lastAttemptAt = :previousAttempt, lastError = :error, lastFailureRetryable = :retryable WHERE id = :id AND lastAttemptAt = :attemptedAt")
    suspend fun restoreUpdateAttempt(id: String, attemptedAt: Long, previousAttempt: Long, error: String, retryable: Boolean)

    @Query("UPDATE subscriptions SET lastError = :error, lastFailureRetryable = :retryable, previousNodeCount = :previousCount WHERE id = :id")
    suspend fun recordUpdateResult(id: String, error: String, retryable: Boolean, previousCount: Int)
    @Query("SELECT * FROM subscriptions ORDER BY name ASC")
    fun getAllSubscriptions(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscriptions ORDER BY name ASC")
    suspend fun getAllSubscriptionsSync(): List<SubscriptionEntity>

    @Query("SELECT * FROM subscriptions WHERE enabled = 1 ORDER BY name ASC")
    fun getEnabledSubscriptions(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscriptions WHERE id = :id LIMIT 1")
    suspend fun getSubscriptionById(id: String): SubscriptionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSubscription(sub: SubscriptionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSubscriptions(subs: List<SubscriptionEntity>)

    @Query("UPDATE subscriptions SET enabled = :enabled WHERE id = :id")
    suspend fun updateSubscriptionEnabled(id: String, enabled: Boolean)

    @Query("UPDATE subscriptions SET autoUpdate = :autoUpdate WHERE id = :id")
    suspend fun updateSubscriptionAutoUpdate(id: String, autoUpdate: Boolean)

    @Query("UPDATE subscriptions SET retainWorkingNodes = :enabled WHERE id = :id")
    suspend fun updateSubscriptionRetention(id: String, enabled: Boolean)

    @Query("UPDATE subscriptions SET useSubscriptionRules = :enabled WHERE id = :id")
    suspend fun updateSubscriptionRulesEnabled(id: String, enabled: Boolean)

    @Query("UPDATE subscriptions SET name = :name, url = :url WHERE id = :id")
    suspend fun updateSubscriptionDetails(id: String, name: String, url: String)

    @Query("DELETE FROM subscriptions WHERE id = :id")
    suspend fun deleteSubscription(id: String)

    @Query("DELETE FROM subscriptions")
    suspend fun clearAll()
}
