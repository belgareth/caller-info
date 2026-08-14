package com.rakibulcodes.callerinfo

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.Connection
import android.telecom.PhoneAccount
import android.telecom.TelecomManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CallerScreeningService : CallScreeningService() {
    private val diagnostics by lazy { CallerDiagnostics.getInstance(applicationContext) }

    override fun onScreenCall(callDetails: Call.Details) {
        diagnostics.record(CallerDiagnosticEvent.SCREEN_CALLBACK_RECEIVED)
        val snapshot = captureIncomingCall(callDetails)
        if (snapshot?.isIncoming != true) {
            diagnostics.record(CallerDiagnosticEvent.SCREEN_NOT_INCOMING)
        }
        val coordinator = ScreeningCoordinator(
            responder = ScreeningResponder {
                respondToCall(callDetails, safeAllowResponse())
                diagnostics.record(CallerDiagnosticEvent.SCREEN_RESPONSE_SENT)
            },
            dispatcher = IncomingCallDispatcher {
                snapshot?.let(::dispatchIncomingCall)
            }
        )

        try {
            coordinator.handle(snapshot?.isIncoming == true)
        } catch (_: Exception) {
            // The response is never retried; later processing is optional.
        }
    }

    private fun captureIncomingCall(callDetails: Call.Details): IncomingCallSnapshot? =
        try {
            val isIncoming = callDetails.callDirection == Call.Details.DIRECTION_INCOMING
            val verificationState = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                mapNumberVerificationState(
                    sdkInt = Build.VERSION.SDK_INT,
                    minimumSupportedSdk = Build.VERSION_CODES.R,
                    status = callDetails.callerNumberVerificationStatus,
                    passedStatus = Connection.VERIFICATION_STATUS_PASSED,
                    failedStatus = Connection.VERIFICATION_STATUS_FAILED,
                    notVerifiedStatus = Connection.VERIFICATION_STATUS_NOT_VERIFIED,
                    isIncoming = isIncoming
                )
            } else {
                NumberVerificationState.UNAVAILABLE
            }

            val phoneAccountLabel = runCatching {
                val telecom = getSystemService(TelecomManager::class.java)
                callDetails.accountHandle?.let(telecom::getPhoneAccount)?.label?.toString()
            }.getOrNull()?.takeIf(String::isNotBlank)

            IncomingCallSnapshot(
                isIncoming = isIncoming,
                presentation = callDetails.handlePresentation,
                scheme = callDetails.handle?.scheme,
                value = callDetails.handle?.schemeSpecificPart,
                verificationState = verificationState,
                phoneAccountLabel = phoneAccountLabel,
                cutoffMillis = System.currentTimeMillis()
            )
        } catch (_: Exception) {
            null
        }

    private fun dispatchIncomingCall(snapshot: IncomingCallSnapshot) {
        val previousGeneration = activeIncomingCallGeneration.currentGeneration()
        IncomingOverlayFallbackNotification.cancel(applicationContext)
        LockedCallerCardController.clearCurrent(applicationContext)
        previousGeneration?.let {
            diagnostics.record(CallerDiagnosticEvent.GENERATION_INVALIDATED, generation = it)
            CallerOverlayService.clearIncomingPresentation(applicationContext, it)
        }
        val generation = activeIncomingCallGeneration.begin()
        diagnostics.record(CallerDiagnosticEvent.GENERATION_CREATED, generation = generation)

        IncomingCallLookupRuntime.replace(generation, diagnostics) {
            when {
                snapshot.presentation != TelecomManager.PRESENTATION_ALLOWED &&
                    snapshot.presentation != TelecomManager.PRESENTATION_PAYPHONE ->
                    diagnostics.record(
                        CallerDiagnosticEvent.SCREEN_PRESENTATION_REJECTED,
                        generation
                    )
                snapshot.value.isNullOrBlank() ->
                    diagnostics.record(CallerDiagnosticEvent.SCREEN_HANDLE_MISSING, generation)
                snapshot.scheme != PhoneAccount.SCHEME_TEL ->
                    diagnostics.record(CallerDiagnosticEvent.SCREEN_SCHEME_REJECTED, generation)
            }
            val normalizedNumber = normalizePresentedIncomingNumber(
                input = IncomingNumberInput(
                    presentation = snapshot.presentation,
                    scheme = snapshot.scheme,
                    value = snapshot.value
                ),
                values = IncomingPresentationValues(
                    allowed = TelecomManager.PRESENTATION_ALLOWED,
                    payphone = TelecomManager.PRESENTATION_PAYPHONE,
                    restricted = TelecomManager.PRESENTATION_RESTRICTED,
                    unknown = TelecomManager.PRESENTATION_UNKNOWN,
                    unavailable = TelecomManager.PRESENTATION_UNAVAILABLE,
                    telephoneScheme = PhoneAccount.SCHEME_TEL
                ),
                config = NumberFormattingPreferences.getInstance(applicationContext).getConfig()
            )
            diagnostics.record(
                if (normalizedNumber.isBlank()) {
                    CallerDiagnosticEvent.NORMALIZATION_FAILED
                } else {
                    CallerDiagnosticEvent.NORMALIZATION_SUCCESS
                },
                generation
            )
            if (!activeIncomingCallGeneration.attachNumber(generation, normalizedNumber)) {
                diagnostics.record(CallerDiagnosticEvent.GENERATION_ATTACH_FAILED, generation)
                return@replace
            }
            diagnostics.record(CallerDiagnosticEvent.GENERATION_ATTACHED, generation)
            val observedSaved = RecentCallRepository.getInstance(applicationContext).recordObservedIncomingCall(
                normalizedNumber = normalizedNumber,
                timestampMillis = snapshot.cutoffMillis
            )
            diagnostics.record(
                if (observedSaved) {
                    CallerDiagnosticEvent.OBSERVED_CALL_SAVE_SUCCESS
                } else {
                    CallerDiagnosticEvent.OBSERVED_CALL_SAVE_FAILED
                },
                generation
            )

            IncomingCallProcessor.processCall(
                context = applicationContext,
                normalizedNumber = normalizedNumber,
                incomingCallStartMillis = snapshot.cutoffMillis,
                verificationState = snapshot.verificationState,
                phoneAccountLabel = snapshot.phoneAccountLabel,
                generation = generation,
                isCurrent = activeIncomingCallGeneration::isCurrent
            )
        }
    }

    private fun safeAllowResponse(): CallResponse =
        CallResponse.Builder()
            .setDisallowCall(false)
            .setRejectCall(false)
            .setSilenceCall(false)
            .setSkipCallLog(false)
            .setSkipNotification(false)
            .build()

}

private object IncomingCallLookupRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null

    private var activeGeneration: Long? = null

    @Synchronized
    fun replace(
        generation: Long,
        diagnostics: CallerDiagnosticTrail,
        block: suspend () -> Unit
    ) {
        activeJob?.let {
            diagnostics.record(CallerDiagnosticEvent.JOB_CANCELLED, activeGeneration)
            it.cancel()
        }
        activeGeneration = generation
        activeJob = scope.launch { block() }
    }
}

private data class IncomingCallSnapshot(
    val isIncoming: Boolean,
    val presentation: Int,
    val scheme: String?,
    val value: String?,
    val verificationState: NumberVerificationState,
    val phoneAccountLabel: String?,
    val cutoffMillis: Long
)
