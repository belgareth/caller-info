package com.rakibulcodes.callerinfo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RecentCallPreferences private constructor(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = preferences.all[KEY_ENABLED] as? Boolean ?: false

    fun setEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun wasPermissionRequested(): Boolean =
        preferences.all[KEY_PERMISSION_REQUESTED] as? Boolean ?: false

    fun markPermissionRequested() {
        preferences.edit().putBoolean(KEY_PERMISSION_REQUESTED, true).apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "Settings"
        private const val KEY_ENABLED = "showPreviousCall"
        private const val KEY_PERMISSION_REQUESTED = "callLogPermissionRequested"

        @Volatile
        private var instance: RecentCallPreferences? = null

        fun getInstance(context: Context): RecentCallPreferences =
            instance ?: synchronized(this) {
                instance ?: RecentCallPreferences(context).also { instance = it }
            }
    }
}

class RecentCallRepository private constructor(context: Context) {
    private val applicationContext = context.applicationContext
    private val preferences = RecentCallPreferences.getInstance(applicationContext)
    private val finder = RecentCallFinder(
        recordSource = DeviceCallRecordSource(applicationContext),
        featureEnabled = preferences::isEnabled,
        permissionGranted = {
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.READ_CALL_LOG
            ) == PackageManager.PERMISSION_GRANTED
        }
    )

    suspend fun findPreviousCall(
        incomingNumber: String?,
        cutoffMillis: Long,
        config: NumberNormalizationConfig
    ): RecentCallInteraction? = withContext(Dispatchers.IO) {
        finder.findPreviousCall(incomingNumber, cutoffMillis, config)
    }

    companion object {
        @Volatile
        private var instance: RecentCallRepository? = null

        fun getInstance(context: Context): RecentCallRepository =
            instance ?: synchronized(this) {
                instance ?: RecentCallRepository(context).also { instance = it }
            }
    }
}

private class DeviceCallRecordSource(context: Context) : RecentCallRecordSource {
    private val contentResolver = context.applicationContext.contentResolver

    override fun findFirstBefore(
        cutoffMillis: Long,
        predicate: (RecentCallRecord) -> Boolean
    ): RecentCallRecord? {
        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE
        )
        val selection = "${CallLog.Calls.DATE} < ?"
        val selectionArgs = arrayOf(cutoffMillis.toString())
        val sortOrder = "${CallLog.Calls.DATE} DESC"

        return contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val numberIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
            val typeIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
            val dateIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)

            while (cursor.moveToNext()) {
                val record = RecentCallRecord(
                    number = cursor.getString(numberIndex),
                    type = mapCallType(cursor.getInt(typeIndex)),
                    timestampMillis = cursor.getLong(dateIndex)
                )
                if (predicate(record)) return@use record
            }
            null
        }
    }

    private fun mapCallType(value: Int): RecentCallType? = mapRecentCallType(
        value = value,
        incomingValue = CallLog.Calls.INCOMING_TYPE,
        outgoingValue = CallLog.Calls.OUTGOING_TYPE,
        missedValue = CallLog.Calls.MISSED_TYPE,
        rejectedValue = CallLog.Calls.REJECTED_TYPE,
        blockedValue = CallLog.Calls.BLOCKED_TYPE,
        voicemailValue = CallLog.Calls.VOICEMAIL_TYPE,
        answeredElsewhereValue = CallLog.Calls.ANSWERED_EXTERNALLY_TYPE
    )
}
