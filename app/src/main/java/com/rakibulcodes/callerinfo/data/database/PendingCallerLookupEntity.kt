package com.rakibulcodes.callerinfo.data.database

/** In-memory representation; persisted through SecurePendingCallerLookupEntity. */
data class PendingCallerLookupEntity(
    val normalizedNumber: String,
    val createdTimestampMillis: Long,
    val nextEligibleRetryTimestampMillis: Long,
    val attemptCount: Int
)

@androidx.room.Entity(tableName = "secure_pending_caller_lookup")
data class SecurePendingCallerLookupEntity(
    @androidx.room.PrimaryKey val lookupKey: String,
    val encryptedNumber: String,
    val createdTimestampMillis: Long,
    val nextEligibleRetryTimestampMillis: Long,
    val attemptCount: Int
)
