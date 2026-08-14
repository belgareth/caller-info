package com.rakibulcodes.callerinfo

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Presents a deliberately compact, non-focusable overlay for a locked incoming call.
 * It never starts an activity or asks Android to replace the phone application's call UI.
 */
object LockedCallerCardController {
    private var activeGeneration: Long? = null

    @Synchronized
    fun isActive(generation: Long): Boolean = activeGeneration == generation

    fun present(
        context: Context,
        generation: Long,
        normalizedNumber: String,
        name: String?,
        carrier: String?,
        country: String?,
        incomingCallStartMillis: Long,
        verificationState: NumberVerificationState,
        lookupStage: IncomingLookupStage,
        error: String? = null
    ) {
        if (!activeIncomingCallGeneration.isCurrent(generation, normalizedNumber)) return
        synchronized(this) {
            activeGeneration = generation
        }

        if (!Settings.canDrawOverlays(context)) {
            showFallback(context, generation, normalizedNumber, lookupStage, error)
            return
        }

        val overlayIntent = Intent(context, CallerOverlayService::class.java).apply {
            putExtra("number", normalizedNumber)
            putExtra("name", name)
            putExtra("carrier", carrier)
            putExtra("country", country)
            putExtra("error", error)
            putExtra("incoming_call_start", incomingCallStartMillis)
            putExtra("verification_state", verificationState.name)
            putExtra("lookup_stage", lookupStage.name)
            putExtra("call_generation", generation)
            putExtra("locked_presentation", true)
        }
        try {
            context.startService(overlayIntent)
            CallerDiagnostics.getInstance(context).record(
                CallerDiagnosticEvent.OVERLAY_PRESENTED,
                generation,
                CallerDiagnosticStatus.LOCKED_CALLER_CARD
            )
        } catch (_: IllegalStateException) {
            showFallback(
                context,
                generation,
                normalizedNumber,
                lookupStage,
                error,
                CallerDiagnosticStatus.START_NOT_ALLOWED
            )
        } catch (_: SecurityException) {
            showFallback(
                context,
                generation,
                normalizedNumber,
                lookupStage,
                error,
                CallerDiagnosticStatus.SECURITY_EXCEPTION
            )
        }
    }

    fun clear(context: Context, generation: Long) {
        synchronized(this) {
            if (activeGeneration != generation) return
            activeGeneration = null
        }
        CallerOverlayService.clearIncomingPresentation(context, generation)
        IncomingOverlayFallbackNotification.cancel(context)
    }

    fun clearCurrent(context: Context) {
        val generation = synchronized(this) { activeGeneration } ?: return
        clear(context, generation)
    }

    /** Clears controller-only state after the overlay or fallback has ended the call presentation. */
    @Synchronized
    fun onPresentationCleared(generation: Long) {
        if (activeGeneration == generation) {
            activeGeneration = null
        }
    }

    fun isDeviceLocked(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true

    private fun showFallback(
        context: Context,
        generation: Long,
        number: String,
        stage: IncomingLookupStage,
        error: String?,
        status: CallerDiagnosticStatus = CallerDiagnosticStatus.OVERLAY_PERMISSION_MISSING
    ) {
        CallerDiagnostics.getInstance(context).record(
            CallerDiagnosticEvent.FALLBACK_NOTIFICATION_REQUESTED,
            generation,
            status
        )
        IncomingOverlayFallbackNotification.show(
            context = context,
            number = number,
            stage = stage,
            error = error,
            generation = generation
        )
    }
}
