package com.rakibulcodes.callerinfo.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "secure_caller_info")
data class SecureCallerInfoEntity(
    @PrimaryKey val lookupKey: String,
    val encryptedPayload: String,
    val timestamp: Long,
    val lastSuccessfullyUpdatedMillis: Long?
)
