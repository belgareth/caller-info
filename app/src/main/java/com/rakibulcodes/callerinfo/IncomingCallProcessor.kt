package com.rakibulcodes.callerinfo

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.rakibulcodes.callerinfo.data.CallerInfoRepository

object IncomingCallProcessor {

    suspend fun processCall(
        context: Context,
        normalizedNumber: String,
        incomingCallStartMillis: Long,
        verificationState: NumberVerificationState,
        generation: Long,
        isCurrent: (Long, String) -> Boolean
    ) {
        if (!isCurrent(generation, normalizedNumber)) return
        val prefs = context.getSharedPreferences("Settings", Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("enabled", false)
        val lookupKnown = prefs.getBoolean("lookup_known", false)
        
        if (!isEnabled) return

        val numberInContacts = isNumberInContacts(context, normalizedNumber)
        if (!isCurrent(generation, normalizedNumber)) return

        if (lookupKnown || !numberInContacts) {
            val repository = CallerInfoRepository.getInstance(context)
            val lookupResult = repository.getCallerInfoWithSource(normalizedNumber)
            val result = lookupResult.callerInfo
            if (!isCurrent(generation, normalizedNumber)) return
            
            if (result.error == "No internet connection") {
                scheduleOfflineLookup(context, normalizedNumber)
            } else if (isCallStillActive(context)) {
                if (!isCurrent(generation, normalizedNumber)) return
                val overlayIntent = Intent(context, CallerOverlayService::class.java).apply {
                    putExtra("number", result.number)
                    putExtra("name", result.name)
                    putExtra("carrier", result.carrier)
                    putExtra("country", result.country)
                    putExtra("location", result.location)
                    putExtra("email", result.email)
                    putExtra("error", result.error)
                    putExtra("incoming_call_start", incomingCallStartMillis)
                    putExtra("verification_state", verificationState.name)
                    putExtra("lookup_source", lookupResult.source.name)
                    putExtra("call_generation", generation)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startService(overlayIntent)
            } else {
                if (!isCurrent(generation, normalizedNumber)) return
                val message = NotificationHelper.buildNotificationMessage(result)
                NotificationHelper.showNotification(context, result.number, message, result = result)
            }
        }
    }

    private fun isCallStillActive(context: Context): Boolean {
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return false

        return try {
            telephonyManager.callState != TelephonyManager.CALL_STATE_IDLE
        } catch (_: SecurityException) {
            false
        }
    }

    private fun isNumberInContacts(context: Context, phoneNumber: String): Boolean {
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            phoneNumber
        )
        val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
        return try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                cursor.moveToFirst()
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    private fun scheduleOfflineLookup(context: Context, number: String) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
            
        val data = workDataOf("number" to number)

        val workRequest = OneTimeWorkRequestBuilder<OfflineLookupWorker>()
            .setConstraints(constraints)
            .setInputData(data)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "offline_lookup_$number",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }
}
