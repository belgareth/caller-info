package com.rakibulcodes.callerinfo.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_caller_lookup")
data class PendingCallerLookupEntity(
    @PrimaryKey val normalizedNumber: String,
    val createdTimestampMillis: Long,
    val nextEligibleRetryTimestampMillis: Long,
    val attemptCount: Int
)
