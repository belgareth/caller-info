package com.rakibulcodes.callerinfo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RecentMessagePreferences private constructor(context: Context) {
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
        private const val KEY_ENABLED = "includeMessages"
        private const val KEY_PERMISSION_REQUESTED = "messagePermissionRequested"

        @Volatile
        private var instance: RecentMessagePreferences? = null

        fun getInstance(context: Context): RecentMessagePreferences =
            instance ?: synchronized(this) {
                instance ?: RecentMessagePreferences(context).also { instance = it }
            }
    }
}

class RecentMessageRepository private constructor(context: Context) {
    private val applicationContext = context.applicationContext
    private val interactionPreferences = RecentCallPreferences.getInstance(applicationContext)
    private val messagePreferences = RecentMessagePreferences.getInstance(applicationContext)
    private val finder = RecentMessageFinder(
        recordSource = DeviceMessageRecordSource(applicationContext),
        featureEnabled = {
            interactionPreferences.isEnabled() && messagePreferences.isEnabled()
        },
        permissionGranted = {
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.READ_SMS
            ) == PackageManager.PERMISSION_GRANTED
        }
    )

    suspend fun findPreviousMessage(
        incomingNumber: String?,
        cutoffMillis: Long,
        config: NumberNormalizationConfig
    ): RecentMessageInteraction? = withContext(Dispatchers.IO) {
        finder.findPreviousMessage(incomingNumber, cutoffMillis, config)
    }

    companion object {
        @Volatile
        private var instance: RecentMessageRepository? = null

        fun getInstance(context: Context): RecentMessageRepository =
            instance ?: synchronized(this) {
                instance ?: RecentMessageRepository(context).also { instance = it }
            }
    }
}

private class DeviceMessageRecordSource(context: Context) : RecentMessageRecordSource {
    private val contentResolver = context.applicationContext.contentResolver

    override fun findFirstBefore(
        cutoffMillis: Long,
        shouldContinue: () -> Boolean,
        predicate: (RecentMessageRecord) -> Boolean
    ): RecentMessageRecord? {
        val projection = arrayOf(
            Telephony.Sms.ADDRESS,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE
        )
        val selection = "${Telephony.Sms.DATE} < ?"
        val selectionArgs = arrayOf(cutoffMillis.toString())
        val sortOrder = "${Telephony.Sms.DATE} DESC"

        return contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val typeIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)

            while (shouldContinue() && cursor.moveToNext()) {
                val record = RecentMessageRecord(
                    address = cursor.getString(addressIndex),
                    type = mapMessageType(cursor.getInt(typeIndex)),
                    timestampMillis = cursor.getLong(dateIndex)
                )
                if (predicate(record)) return@use record
            }
            null
        }
    }

    private fun mapMessageType(value: Int): RecentMessageType? = mapRecentMessageType(
        value = value,
        inboxValue = Telephony.Sms.MESSAGE_TYPE_INBOX,
        sentValue = Telephony.Sms.MESSAGE_TYPE_SENT
    )
}
