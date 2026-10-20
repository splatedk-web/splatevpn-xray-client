package com.danieldk.splatevpn.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SubscriptionDao {
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

    @Query("UPDATE subscriptions SET name = :name, url = :url WHERE id = :id")
    suspend fun updateSubscriptionDetails(id: String, name: String, url: String)

    @Query("DELETE FROM subscriptions WHERE id = :id")
    suspend fun deleteSubscription(id: String)

    @Query("DELETE FROM subscriptions")
    suspend fun clearAll()
}
