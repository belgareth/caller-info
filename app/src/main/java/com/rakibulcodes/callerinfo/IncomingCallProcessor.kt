package com.rakibulcodes.callerinfo

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.TelephonyManager
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
            var localPresented = false
            val lookupResult = repository.getCallerInfoWithSource(
                rawNumber = normalizedNumber,
                onLocalResult = { localResult ->
                    localPresented = true
                    presentResult(
                        context,
                        localResult,
                        incomingCallStartMillis,
                        verificationState,
                        generation,
                        normalizedNumber,
                        isCurrent
                    )
                },
                requestStillValid = { isCurrent(generation, normalizedNumber) }
            )
            val result = lookupResult.callerInfo
            if (!isCurrent(generation, normalizedNumber)) return
            if (localPresented && lookupResult.source == CallerLookupSource.LOCAL) return
            presentResult(
                context,
                lookupResult,
                incomingCallStartMillis,
                verificationState,
                generation,
                normalizedNumber,
                isCurrent
            )
        }
    }

    private fun presentResult(
        context: Context,
        lookupResult: CallerLookupResult,
        incomingCallStartMillis: Long,
        verificationState: NumberVerificationState,
        generation: Long,
        normalizedNumber: String,
        isCurrent: (Long, String) -> Boolean
    ) {
        if (!isCurrent(generation, normalizedNumber)) return
        val result = lookupResult.callerInfo
        if (result.error == "No internet connection") return
        if (isCallStillActive(context)) {
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
            val message = NotificationHelper.buildNotificationMessage(result)
            NotificationHelper.showNotification(context, result.number, message, result = result)
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

}
