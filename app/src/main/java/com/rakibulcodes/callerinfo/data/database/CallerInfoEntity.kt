package com.rakibulcodes.callerinfo.data.database

/**
 * In-memory/domain representation. Test.15 persists this through
 * SecureCallerInfoEntity so phone numbers and caller details are not stored
 * as plaintext Room columns.
 */
data class CallerInfoEntity(
    val number: String,
    val country: String?,
    val name: String?,
    val carrier: String?,
    val email: String?,
    val location: String?,
    val address1: String?,
    val address2: String?,
    val error: String?,
    val timestamp: Long = System.currentTimeMillis(),
    val lastSuccessfullyUpdatedMillis: Long? = null,
    val userAlias: String? = null,
    val userNote: String? = null,
    val favorite: Boolean = false
)
