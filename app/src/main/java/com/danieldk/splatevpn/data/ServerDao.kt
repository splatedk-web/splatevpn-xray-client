package com.danieldk.splatevpn.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerDao {
    // Selection only changes the highlight; keep list keys in the same order.
    // id makes equally named subscription nodes deterministic as well.
    @Query("SELECT * FROM servers ORDER BY name ASC, id ASC")
    fun getAllServers(): Flow<List<ServerNode>>

    @Query("SELECT * FROM servers ORDER BY name ASC, id ASC")
    suspend fun getAllServersSync(): List<ServerNode>

    @Query("""
        SELECT * FROM servers
        WHERE NOT EXISTS (SELECT 1 FROM subscriptions WHERE subscriptions.id = servers.subId AND enabled = 0)
        ORDER BY name ASC, id ASC
    """)
    suspend fun getServersForBulkCheck(): List<ServerNode>

    @Query("SELECT * FROM servers WHERE id = :id LIMIT 1")
    suspend fun getServerById(id: String): ServerNode?

    @Query("SELECT * FROM servers WHERE isRetained = 1")
    suspend fun getRetainedServersSync(): List<ServerNode>

    @Query("SELECT * FROM servers WHERE isSelected = 1 LIMIT 1")
    fun getSelectedServer(): Flow<ServerNode?>

    @Query("SELECT * FROM servers WHERE isSelected = 1 LIMIT 1")
    suspend fun getSelectedServerSync(): ServerNode?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertServer(server: ServerNode)

    @androidx.room.Update
    suspend fun updateServer(server: ServerNode)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertServers(servers: List<ServerNode>)

    @Query("DELETE FROM servers WHERE id = :id")
    suspend fun deleteServer(id: String)

    @Query("DELETE FROM servers WHERE id IN (:ids)")
    suspend fun deleteServers(ids: List<String>)

    @Query("DELETE FROM servers")
    suspend fun clearAll()

    @Query("UPDATE servers SET isSelected = 0")
    suspend fun clearSelection()

    @Query("UPDATE servers SET isSelected = 1 WHERE id = :id")
    suspend fun selectServer(id: String)

    @Transaction
    suspend fun setActiveServer(id: String) {
        clearSelection()
        selectServer(id)
    }

    @Query("UPDATE servers SET ping = :ping WHERE id = :id")
    suspend fun updatePing(id: String, ping: Long)

    @Transaction
    suspend fun updatePingIfUnchanged(expected: ServerNode, ping: Long): Boolean {
        val current = getServerById(expected.id) ?: return false
        if (!SubscriptionNodeMerger.sameConnection(current, expected)) return false
        updatePing(expected.id, ping)
        return true
    }

    @Query("UPDATE servers SET lastVerifiedAt = MAX(lastVerifiedAt, :timestamp) WHERE id = :id")
    suspend fun recordVerificationSuccess(id: String, timestamp: Long)

    @Query("UPDATE servers SET lastVerificationFailedAt = MAX(lastVerificationFailedAt, :timestamp) WHERE id = :id")
    suspend fun recordVerificationFailure(id: String, timestamp: Long)

    /** A probe may finish after this row was edited or replaced; never certify a different transport. */
    @Transaction
    suspend fun recordVerificationIfUnchanged(expected: ServerNode, timestamp: Long, successful: Boolean): Boolean {
        val current = getServerById(expected.id) ?: return false
        if (!SubscriptionNodeMerger.sameConnection(current, expected)) return false
        if (successful) recordVerificationSuccess(expected.id, timestamp)
        else recordVerificationFailure(expected.id, timestamp)
        return true
    }

    @Query("DELETE FROM servers WHERE subId = :subId")
    suspend fun deleteServersBySubId(subId: String)

    @Query("UPDATE servers SET subName = :name WHERE subId = :subId")
    suspend fun updateSubscriptionName(subId: String, name: String)

    @Query("SELECT * FROM servers WHERE subId = :subId ORDER BY isSelected DESC, name ASC")
    fun getServersBySubId(subId: String): Flow<List<ServerNode>>

    @Query("SELECT * FROM servers WHERE subId = :subId ORDER BY isSelected DESC, name ASC")
    suspend fun getServersBySubIdSync(subId: String): List<ServerNode>
}
