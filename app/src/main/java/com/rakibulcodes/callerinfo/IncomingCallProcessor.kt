package com.rakibulcodes.callerinfo

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.TelephonyManager
import com.rakibulcodes.callerinfo.data.CallerInfoRepository
import com.rakibulcodes.callerinfo.data.IncomingPresentationRoute
import com.rakibulcodes.callerinfo.data.database.CallerInfoEntity
import com.rakibulcodes.callerinfo.data.selectIncomingPresentationRoute

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

        presentInitial(
            context = context,
            incomingCallStartMillis = incomingCallStartMillis,
            verificationState = verificationState,
            generation = generation,
            normalizedNumber = normalizedNumber,
            isCurrent = isCurrent
        )

        val contactName = findContactName(context, normalizedNumber)
        if (!isCurrent(generation, normalizedNumber)) return
        if (!lookupKnown && contactName != null) {
            presentResult(
                context = context,
                lookupResult = CallerLookupResult(
                    callerInfo = CallerInfoEntity(
                        number = normalizedNumber,
                        country = null,
                        name = contactName,
                        carrier = null,
                        email = null,
                        location = null,
                        address1 = null,
                        address2 = null,
                        error = null
                    ),
                    source = CallerLookupSource.LOCAL
                ),
                incomingCallStartMillis = incomingCallStartMillis,
                verificationState = verificationState,
                generation = generation,
                normalizedNumber = normalizedNumber,
                isCurrent = isCurrent
            )
            return
        }

        val repository = CallerInfoRepository.getInstance(context)
        var localPresented = false
        val lookupResult = repository.getCallerInfoWithSource(
            rawNumber = normalizedNumber,
            onLocalResult = { localResult ->
                localPresented = true
                presentResult(
                    context = context,
                    lookupResult = localResult,
                    incomingCallStartMillis = incomingCallStartMillis,
                    verificationState = verificationState,
                    generation = generation,
                    normalizedNumber = normalizedNumber,
                    isCurrent = isCurrent
                )
            },
            requestStillValid = { isCurrent(generation, normalizedNumber) },
            retainDeferredRetryAfterRequestEnds = false
        )
        if (!isCurrent(generation, normalizedNumber)) return
        if (localPresented && lookupResult.source == CallerLookupSource.LOCAL) return

        presentResult(
            context = context,
            lookupResult = lookupResult,
            incomingCallStartMillis = incomingCallStartMillis,
            verificationState = verificationState,
            generation = generation,
            normalizedNumber = normalizedNumber,
            isCurrent = isCurrent
        )
    }

    private fun presentInitial(
        context: Context,
        incomingCallStartMillis: Long,
        verificationState: NumberVerificationState,
        generation: Long,
        normalizedNumber: String,
        isCurrent: (Long, String) -> Boolean
    ) {
        if (!isCurrent(generation, normalizedNumber)) return
        presentIncoming(
            context = context,
            callerInfo = CallerInfoEntity(
                number = normalizedNumber,
                country = null,
                name = null,
                carrier = null,
                email = null,
                location = null,
                address1 = null,
                address2 = null,
                error = null
            ),
            lookupSource = null,
            lookupStage = IncomingLookupStage.LOOKING_UP,
            retryScheduled = false,
            incomingCallStartMillis = incomingCallStartMillis,
            verificationState = verificationState,
            generation = generation,
            normalizedNumber = normalizedNumber,
            isCurrent = isCurrent
        )
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
        val result = lookupResult.callerInfo
        val stage = incomingLookupStage(
            hasUsefulCallerInformation = hasUsefulCallerInformation(result),
            retryScheduled = lookupResult.retryScheduled,
            errorMessage = result.error
        )
        presentIncoming(
            context = context,
            callerInfo = result,
            lookupSource = lookupResult.source,
            lookupStage = stage,
            retryScheduled = lookupResult.retryScheduled,
            incomingCallStartMillis = incomingCallStartMillis,
            verificationState = verificationState,
            generation = generation,
            normalizedNumber = normalizedNumber,
            isCurrent = isCurrent
        )
    }

    private fun presentIncoming(
        context: Context,
        callerInfo: CallerInfoEntity,
        lookupSource: CallerLookupSource?,
        lookupStage: IncomingLookupStage,
        retryScheduled: Boolean,
        incomingCallStartMillis: Long,
        verificationState: NumberVerificationState,
        generation: Long,
        normalizedNumber: String,
        isCurrent: (Long, String) -> Boolean
    ) {
        if (!isCurrent(generation, normalizedNumber)) return
        if (
            lookupStage != IncomingLookupStage.LOOKING_UP &&
            !isCallStillActive(context)
        ) {
            CallerOverlayService.clearIncomingPresentation(context, generation)
            LockedCallerCardController.clear(context, generation)
            IncomingOverlayFallbackNotification.cancel(context)
            activeIncomingCallGeneration.invalidate(generation)
            if (hasUsefulCallerInformation(callerInfo)) {
                val message = NotificationHelper.buildNotificationMessage(callerInfo)
                NotificationHelper.showNotification(
                    context,
                    callerInfo.number,
                    message,
                    result = callerInfo
                )
            }
            return
        }

        val deviceLocked = LockedCallerCardController.isDeviceLocked(context)
        when (
            selectIncomingPresentationRoute(
                deviceLocked = deviceLocked,
                lockedPresentationAlreadyActive = LockedCallerCardController.isActive(generation)
            )
        ) {
            IncomingPresentationRoute.LOCKED_CALLER_CARD -> {
                LockedCallerCardController.present(
                    context = context,
                    generation = generation,
                    normalizedNumber = normalizedNumber,
                    name = callerInfo.name?.takeIf { callerInfo.error == null },
                    verificationState = verificationState,
                    lookupStage = lookupStage,
                    error = callerInfo.error
                )
            }
            IncomingPresentationRoute.UNLOCKED_OVERLAY -> {
                presentUnlockedOverlay(
                    context = context,
                    callerInfo = callerInfo,
                    lookupSource = lookupSource,
                    lookupStage = lookupStage,
                    retryScheduled = retryScheduled,
                    incomingCallStartMillis = incomingCallStartMillis,
                    verificationState = verificationState,
                    generation = generation,
                    normalizedNumber = normalizedNumber
                )
            }
        }
    }

    private fun presentUnlockedOverlay(
        context: Context,
        callerInfo: CallerInfoEntity,
        lookupSource: CallerLookupSource?,
        lookupStage: IncomingLookupStage,
        retryScheduled: Boolean,
        incomingCallStartMillis: Long,
        verificationState: NumberVerificationState,
        generation: Long,
        normalizedNumber: String
    ) {
        if (!Settings.canDrawOverlays(context)) {
            IncomingOverlayFallbackNotification.show(
                context = context,
                number = normalizedNumber,
                stage = lookupStage,
                error = callerInfo.error,
                generation = generation
            )
            return
        }

        val overlayIntent = Intent(context, CallerOverlayService::class.java).apply {
            putExtra("number", normalizedNumber)
            putExtra("name", callerInfo.name)
            putExtra("carrier", callerInfo.carrier)
            putExtra("country", callerInfo.country)
            putExtra("location", callerInfo.location)
            putExtra("email", callerInfo.email)
            putExtra("error", callerInfo.error)
            putExtra("incoming_call_start", incomingCallStartMillis)
            putExtra("verification_state", verificationState.name)
            putExtra("lookup_source", lookupSource?.name)
            putExtra("lookup_stage", lookupStage.name)
            putExtra("retry_scheduled", retryScheduled)
            putExtra("call_generation", generation)
        }
        try {
            context.startService(overlayIntent)
        } catch (_: IllegalStateException) {
            IncomingOverlayFallbackNotification.show(
                context = context,
                number = normalizedNumber,
                stage = lookupStage,
                error = callerInfo.error,
                generation = generation
            )
        } catch (_: SecurityException) {
            IncomingOverlayFallbackNotification.show(
                context = context,
                number = normalizedNumber,
                stage = lookupStage,
                error = callerInfo.error,
                generation = generation
            )
        }
    }

    private fun isCallStillActive(context: Context): Boolean {
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return true

        return try {
            telephonyManager.callState != TelephonyManager.CALL_STATE_IDLE
        } catch (_: SecurityException) {
            // The current screening generation is a safer fallback than hiding the caller card.
            true
        }
    }

    private fun findContactName(context: Context, phoneNumber: String): String? {
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            phoneNumber
        )
        val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
        return try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                cursor.getString(
                    cursor.getColumnIndexOrThrow(ContactsContract.PhoneLookup.DISPLAY_NAME)
                )?.takeIf(String::isNotBlank)
            }
        } catch (_: Exception) {
            null
        }
    }
}
