package com.rakibulcodes.callerinfo.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface PendingCallerLookupDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(item: PendingCallerLookupEntity): Long

    @Query(
        "SELECT * FROM pending_caller_lookup " +
            "WHERE nextEligibleRetryTimestampMillis <= :nowMillis " +
            "ORDER BY createdTimestampMillis ASC LIMIT :limit"
    )
    suspend fun eligible(nowMillis: Long, limit: Int): List<PendingCallerLookupEntity>

    @Query("DELETE FROM pending_caller_lookup WHERE normalizedNumber = :normalizedNumber")
    suspend fun delete(normalizedNumber: String): Int

    @Query("DELETE FROM pending_caller_lookup")
    suspend fun clearAll(): Int

    @Query(
        "DELETE FROM pending_caller_lookup WHERE createdTimestampMillis <= :cutoffMillis " +
            "OR attemptCount >= :maximumAttempts"
    )
    suspend fun deleteExpiredOrExhausted(cutoffMillis: Long, maximumAttempts: Int): Int

    @Query(
        "UPDATE pending_caller_lookup SET attemptCount = :attemptCount, " +
            "nextEligibleRetryTimestampMillis = :nextEligibleMillis " +
            "WHERE normalizedNumber = :normalizedNumber"
    )
    suspend fun updateAttempt(
        normalizedNumber: String,
        attemptCount: Int,
        nextEligibleMillis: Long
    ): Int

    @Query(
        "DELETE FROM pending_caller_lookup WHERE normalizedNumber NOT IN " +
            "(SELECT normalizedNumber FROM pending_caller_lookup " +
            "ORDER BY createdTimestampMillis ASC LIMIT :limit)"
    )
    suspend fun trimToLimit(limit: Int): Int

    @Query("SELECT COUNT(*) FROM pending_caller_lookup")
    suspend fun count(): Int

    @Query(
        "SELECT COUNT(*) FROM pending_caller_lookup " +
            "WHERE nextEligibleRetryTimestampMillis <= :nowMillis"
    )
    suspend fun eligibleCount(nowMillis: Long): Int

    @Query(
        "SELECT MIN(nextEligibleRetryTimestampMillis) FROM pending_caller_lookup " +
            "WHERE nextEligibleRetryTimestampMillis > :nowMillis"
    )
    suspend fun earliestFutureEligible(nowMillis: Long): Long?

    @Transaction
    suspend fun insertBounded(item: PendingCallerLookupEntity, limit: Int): Boolean {
        val inserted = insertIfAbsent(item) != -1L
        trimToLimit(limit)
        return inserted
    }
}
