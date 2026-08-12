package com.rakibulcodes.callerinfo.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface CallerInfoDao {
    @Query("SELECT * FROM secure_caller_info WHERE lookupKey = :lookupKey")
    suspend fun getCallerInfo(lookupKey: String): SecureCallerInfoEntity?

    @Query("SELECT * FROM secure_caller_info ORDER BY timestamp DESC")
    suspend fun getAllCallerInfo(): List<SecureCallerInfoEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCallerInfo(callerInfo: SecureCallerInfoEntity): Long

    @Query("DELETE FROM secure_caller_info")
    suspend fun clearAll(): Int

    @Query("DELETE FROM secure_caller_info WHERE lookupKey NOT IN (SELECT lookupKey FROM secure_caller_info ORDER BY timestamp DESC LIMIT :limit)")
    suspend fun deleteOldEntries(limit: Int): Int

    @Query("DELETE FROM secure_caller_info WHERE lookupKey = :lookupKey")
    suspend fun deleteByNumber(lookupKey: String)

    @Transaction
    suspend fun insertAndTrim(callerInfo: SecureCallerInfoEntity, limit: Int): Int {
        insertCallerInfo(callerInfo)
        return deleteOldEntries(limit)
    }
}
