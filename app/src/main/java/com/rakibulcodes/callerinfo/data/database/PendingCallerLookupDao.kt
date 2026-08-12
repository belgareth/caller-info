package com.rakibulcodes.callerinfo.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface PendingCallerLookupDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(item: SecurePendingCallerLookupEntity): Long

    @Query(
        "SELECT * FROM secure_pending_caller_lookup " +
            "WHERE nextEligibleRetryTimestampMillis <= :nowMillis " +
            "ORDER BY createdTimestampMillis ASC LIMIT :limit"
    )
    suspend fun eligible(nowMillis: Long, limit: Int): List<SecurePendingCallerLookupEntity>

    @Query("DELETE FROM secure_pending_caller_lookup WHERE lookupKey = :lookupKey")
    suspend fun delete(lookupKey: String): Int

    @Query("DELETE FROM secure_pending_caller_lookup")
    suspend fun clearAll(): Int

    @Query(
        "DELETE FROM secure_pending_caller_lookup WHERE createdTimestampMillis <= :cutoffMillis " +
            "OR attemptCount >= :maximumAttempts"
    )
    suspend fun deleteExpiredOrExhausted(cutoffMillis: Long, maximumAttempts: Int): Int

    @Query(
        "UPDATE secure_pending_caller_lookup SET attemptCount = :attemptCount, " +
            "nextEligibleRetryTimestampMillis = :nextEligibleMillis " +
            "WHERE lookupKey = :lookupKey"
    )
    suspend fun updateAttempt(
        lookupKey: String,
        attemptCount: Int,
        nextEligibleMillis: Long
    ): Int

    @Query(
        "DELETE FROM secure_pending_caller_lookup WHERE lookupKey NOT IN " +
            "(SELECT lookupKey FROM secure_pending_caller_lookup " +
            "ORDER BY createdTimestampMillis ASC LIMIT :limit)"
    )
    suspend fun trimToLimit(limit: Int): Int

    @Query("SELECT COUNT(*) FROM secure_pending_caller_lookup")
    suspend fun count(): Int

    @Query(
        "SELECT COUNT(*) FROM secure_pending_caller_lookup " +
            "WHERE nextEligibleRetryTimestampMillis <= :nowMillis"
    )
    suspend fun eligibleCount(nowMillis: Long): Int

    @Query(
        "SELECT MIN(nextEligibleRetryTimestampMillis) FROM secure_pending_caller_lookup " +
            "WHERE nextEligibleRetryTimestampMillis > :nowMillis"
    )
    suspend fun earliestFutureEligible(nowMillis: Long): Long?

    @Transaction
    suspend fun insertBounded(item: SecurePendingCallerLookupEntity, limit: Int): Boolean {
        val inserted = insertIfAbsent(item) != -1L
        trimToLimit(limit)
        return inserted
    }
}
