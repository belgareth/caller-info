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
    override fun onScreenCall(callDetails: Call.Details) {
        val snapshot = captureIncomingCall(callDetails)
        val coordinator = ScreeningCoordinator(
            responder = ScreeningResponder {
                respondToCall(callDetails, safeAllowResponse())
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

            IncomingCallSnapshot(
                isIncoming = isIncoming,
                presentation = callDetails.handlePresentation,
                scheme = callDetails.handle?.scheme,
                value = callDetails.handle?.schemeSpecificPart,
                verificationState = verificationState,
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
            CallerOverlayService.clearIncomingPresentation(applicationContext, it)
        }
        val generation = activeIncomingCallGeneration.begin()

        IncomingCallLookupRuntime.replace {
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
            if (!activeIncomingCallGeneration.attachNumber(generation, normalizedNumber)) {
                return@replace
            }

            IncomingCallProcessor.processCall(
                context = applicationContext,
                normalizedNumber = normalizedNumber,
                incomingCallStartMillis = snapshot.cutoffMillis,
                verificationState = snapshot.verificationState,
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

    @Synchronized
    fun replace(block: suspend () -> Unit) {
        activeJob?.cancel()
        activeJob = scope.launch { block() }
    }
}

private data class IncomingCallSnapshot(
    val isIncoming: Boolean,
    val presentation: Int,
    val scheme: String?,
    val value: String?,
    val verificationState: NumberVerificationState,
    val cutoffMillis: Long
)
