package com.rakibulcodes.callerinfo

enum class IncomingLookupStage {
    LOOKING_UP,
    RESOLVED,
    RETRY_QUEUED,
    FAILED
}

fun incomingLookupStage(
    hasUsefulCallerInformation: Boolean,
    retryScheduled: Boolean,
    errorMessage: String?
): IncomingLookupStage = when {
    hasUsefulCallerInformation -> IncomingLookupStage.RESOLVED
    retryScheduled -> IncomingLookupStage.RETRY_QUEUED
    errorMessage != null -> IncomingLookupStage.FAILED
    else -> IncomingLookupStage.LOOKING_UP
}

fun incomingActionsVisible(
    stage: IncomingLookupStage,
    hasUsefulCallerInformation: Boolean,
    isPreview: Boolean
): Boolean = !isPreview && stage == IncomingLookupStage.RESOLVED && hasUsefulCallerInformation

fun shouldScheduleDeferredRetry(
    requestStillValid: Boolean,
    retainAfterRequestEnds: Boolean
): Boolean = requestStillValid || retainAfterRequestEnds
