package com.rakibulcodes.callerinfo

enum class NumberVerificationState {
    PASSED,
    FAILED,
    UNAVAILABLE
}

fun visibleNumberVerificationState(
    state: NumberVerificationState,
    isIncoming: Boolean
): NumberVerificationState? =
    state.takeIf { isIncoming && it != NumberVerificationState.UNAVAILABLE }

fun mapNumberVerificationState(
    sdkInt: Int,
    minimumSupportedSdk: Int,
    status: Int?,
    passedStatus: Int,
    failedStatus: Int,
    notVerifiedStatus: Int,
    isIncoming: Boolean
): NumberVerificationState {
    if (!isIncoming || sdkInt < minimumSupportedSdk || status == null) {
        return NumberVerificationState.UNAVAILABLE
    }

    return when (status) {
        passedStatus -> NumberVerificationState.PASSED
        failedStatus -> NumberVerificationState.FAILED
        notVerifiedStatus -> NumberVerificationState.UNAVAILABLE
        else -> NumberVerificationState.UNAVAILABLE
    }
}

fun interface ScreeningResponder {
    fun respond()
}

fun interface IncomingCallDispatcher {
    fun dispatch()
}

class ScreeningCoordinator(
    private val responder: ScreeningResponder,
    private val dispatcher: IncomingCallDispatcher
) {
    fun handle(isIncoming: Boolean) {
        responder.respond()
        if (isIncoming) {
            dispatcher.dispatch()
        }
    }
}

data class IncomingNumberInput(
    val presentation: Int,
    val scheme: String?,
    val value: String?
)

data class IncomingPresentationValues(
    val allowed: Int,
    val payphone: Int,
    val restricted: Int,
    val unknown: Int,
    val unavailable: Int,
    val telephoneScheme: String
)

fun normalizePresentedIncomingNumber(
    input: IncomingNumberInput,
    values: IncomingPresentationValues,
    config: NumberNormalizationConfig
): String {
    val usablePresentation =
        input.presentation == values.allowed || input.presentation == values.payphone
    if (!usablePresentation) return ""
    if (input.scheme != values.telephoneScheme) return ""
    return normalizePhoneNumber(input.value, config)
}

class IncomingCallGenerationTracker {
    private var nextGeneration = 0L
    private var activeGeneration: Long? = null
    private var activeNumber: String? = null

    @Synchronized
    fun begin(): Long {
        val generation = ++nextGeneration
        activeGeneration = generation
        activeNumber = null
        return generation
    }

    @Synchronized
    fun attachNumber(generation: Long, normalizedNumber: String): Boolean {
        if (activeGeneration != generation || normalizedNumber.isBlank()) return false
        activeNumber = normalizedNumber
        return true
    }

    @Synchronized
    fun isCurrent(generation: Long, normalizedNumber: String): Boolean =
        activeGeneration == generation &&
            activeNumber == normalizedNumber &&
            normalizedNumber.isNotBlank()

    @Synchronized
    fun invalidate(generation: Long) {
        if (activeGeneration == generation) {
            activeGeneration = null
            activeNumber = null
        }
    }
}

internal val activeIncomingCallGeneration = IncomingCallGenerationTracker()
