package com.danieldk.splatevpn.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerDao {
    @Query("SELECT * FROM servers ORDER BY isSelected DESC, name ASC")
    fun getAllServers(): Flow<List<ServerNode>>

    @Query("SELECT * FROM servers ORDER BY isSelected DESC, name ASC")
    suspend fun getAllServersSync(): List<ServerNode>

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

    @Query("DELETE FROM servers WHERE subId = :subId")
    suspend fun deleteServersBySubId(subId: String)

    @Query("SELECT * FROM servers WHERE subId = :subId ORDER BY isSelected DESC, name ASC")
    fun getServersBySubId(subId: String): Flow<List<ServerNode>>

    @Query("SELECT * FROM servers WHERE subId = :subId ORDER BY isSelected DESC, name ASC")
    suspend fun getServersBySubIdSync(subId: String): List<ServerNode>
}
