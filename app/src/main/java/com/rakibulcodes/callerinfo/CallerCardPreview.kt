package com.rakibulcodes.callerinfo

enum class OverlayPresentationMode {
    NONE,
    PREVIEW,
    REAL_CALL
}

class OverlayPresentationState {
    var mode: OverlayPresentationMode = OverlayPresentationMode.NONE
        private set

    var activeCallGeneration: Long? = null
        private set

    fun beginPreview(): Boolean {
        if (mode != OverlayPresentationMode.NONE) return false
        mode = OverlayPresentationMode.PREVIEW
        return true
    }

    fun beginRealCall(generation: Long) {
        mode = OverlayPresentationMode.REAL_CALL
        activeCallGeneration = generation
    }

    fun dismissPreview(): Boolean {
        if (mode != OverlayPresentationMode.PREVIEW) return false
        mode = OverlayPresentationMode.NONE
        return true
    }

    fun shouldAutoDismissPreview(): Boolean = mode == OverlayPresentationMode.PREVIEW

    fun canClearIncoming(generation: Long): Boolean =
        generation >= 0 &&
            mode == OverlayPresentationMode.REAL_CALL &&
            activeCallGeneration == generation

    fun clear() {
        mode = OverlayPresentationMode.NONE
        activeCallGeneration = null
    }
}

data class CallerCardPreviewData(
    val name: String,
    val number: String,
    val detail: String,
    val recentCall: RecentCallInteraction,
    val verificationState: NumberVerificationState,
    val lookupSource: CallerLookupSource
)

fun createCallerCardPreviewData(nowMillis: Long): CallerCardPreviewData =
    CallerCardPreviewData(
        name = "Sample caller",
        number = "0000000000",
        detail = "Preview only",
        recentCall = RecentCallInteraction(
            type = RecentCallType.INCOMING,
            timestampMillis = nowMillis - 60 * 60 * 1000L
        ),
        verificationState = NumberVerificationState.PASSED,
        lookupSource = CallerLookupSource.LOCAL
    )
