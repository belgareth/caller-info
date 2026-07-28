package com.rakibulcodes.callerinfo

import com.rakibulcodes.callerinfo.data.database.CallerInfoEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallScreeningSafeguardsTest {
    private val config = NumberNormalizationConfig(
        callingCode = "123",
        localPrefix = "0",
        nationalNumberLength = 9,
        acceptWithoutPrefix = false
    )
    private val presentationValues = IncomingPresentationValues(
        allowed = 1,
        payphone = 4,
        restricted = 2,
        unknown = 3,
        unavailable = 5,
        telephoneScheme = "tel"
    )

    @Test
    fun responseOccursExactlyOnceBeforeProcessing() {
        val events = mutableListOf<String>()
        var responses = 0
        ScreeningCoordinator(
            responder = ScreeningResponder {
                responses++
                events += "response"
            },
            dispatcher = IncomingCallDispatcher {
                events += "contact"
                events += "local"
                events += "remote"
                events += "history"
                events += "overlay"
            }
        ).handle(isIncoming = true)

        assertEquals(1, responses)
        assertEquals("response", events.first())
        assertEquals(
            listOf("response", "contact", "local", "remote", "history", "overlay"),
            events
        )
    }

    @Test
    fun laterFailureDoesNotCauseAnotherResponse() {
        var responses = 0
        val coordinator = ScreeningCoordinator(
            responder = ScreeningResponder { responses++ },
            dispatcher = IncomingCallDispatcher { throw IllegalStateException() }
        )

        try {
            coordinator.handle(isIncoming = true)
        } catch (_: IllegalStateException) {
        }

        assertEquals(1, responses)
    }

    @Test
    fun outgoingCallDoesNotEnterIncomingProcessing() {
        var dispatched = false
        ScreeningCoordinator(
            responder = ScreeningResponder {},
            dispatcher = IncomingCallDispatcher { dispatched = true }
        ).handle(isIncoming = false)

        assertFalse(dispatched)
    }

    @Test
    fun verificationStatusesMapWithoutInference() {
        assertEquals(NumberVerificationState.PASSED, verificationState(1))
        assertEquals(NumberVerificationState.FAILED, verificationState(2))
        assertEquals(NumberVerificationState.UNAVAILABLE, verificationState(3))
        assertEquals(NumberVerificationState.UNAVAILABLE, verificationState(99))
        assertEquals(
            NumberVerificationState.UNAVAILABLE,
            verificationState(1, sdkInt = 29)
        )
        assertEquals(
            NumberVerificationState.UNAVAILABLE,
            verificationState(1, isIncoming = false)
        )
    }

    @Test
    fun verificationBadgeIsShownOnlyForMeaningfulIncomingVerdicts() {
        assertEquals(
            NumberVerificationState.PASSED,
            visibleNumberVerificationState(NumberVerificationState.PASSED, true)
        )
        assertEquals(
            NumberVerificationState.FAILED,
            visibleNumberVerificationState(NumberVerificationState.FAILED, true)
        )
        assertNull(
            visibleNumberVerificationState(NumberVerificationState.UNAVAILABLE, true)
        )
        assertNull(
            visibleNumberVerificationState(NumberVerificationState.PASSED, false)
        )
    }

    @Test
    fun restrictedUnknownAndUnavailablePresentationsAreRejected() {
        listOf(
            presentationValues.restricted,
            presentationValues.unknown,
            presentationValues.unavailable
        ).forEach { presentation ->
            assertEquals(
                "",
                normalizePresentedIncomingNumber(
                    IncomingNumberInput(presentation, "tel", "0712345678"),
                    presentationValues,
                    config
                )
            )
        }
    }

    @Test
    fun nullUnsupportedBlankHiddenAndMalformedHandlesAreRejected() {
        val inputs = listOf(
            IncomingNumberInput(presentationValues.allowed, null, "0712345678"),
            IncomingNumberInput(presentationValues.allowed, "other", "0712345678"),
            IncomingNumberInput(presentationValues.allowed, "tel", null),
            IncomingNumberInput(presentationValues.allowed, "tel", ""),
            IncomingNumberInput(presentationValues.allowed, "tel", "   "),
            IncomingNumberInput(presentationValues.allowed, "tel", "Private"),
            IncomingNumberInput(presentationValues.allowed, "tel", "Unknown"),
            IncomingNumberInput(presentationValues.allowed, "tel", "Unavailable"),
            IncomingNumberInput(presentationValues.allowed, "tel", "0712ABC678")
        )

        inputs.forEach {
            assertEquals("", normalizePresentedIncomingNumber(it, presentationValues, config))
        }
    }

    @Test
    fun allowedAndPayphoneNumbersRequireSharedNormalization() {
        assertEquals(
            "+123712345678",
            normalizePresentedIncomingNumber(
                IncomingNumberInput(presentationValues.allowed, "tel", "0712 345 678"),
                presentationValues,
                config
            )
        )
        assertEquals(
            "+123712345678",
            normalizePresentedIncomingNumber(
                IncomingNumberInput(presentationValues.payphone, "tel", "+123712345678"),
                presentationValues,
                config
            )
        )
    }

    @Test
    fun newGenerationInvalidatesOlderResultsAndDifferentNumbers() {
        val tracker = IncomingCallGenerationTracker()
        val first = tracker.begin()
        assertTrue(tracker.attachNumber(first, "+123712345678"))

        val second = tracker.begin()
        assertFalse(tracker.isCurrent(first, "+123712345678"))
        assertTrue(tracker.attachNumber(second, "+123798765432"))
        assertFalse(tracker.isCurrent(second, "+123712345678"))
        assertTrue(tracker.isCurrent(second, "+123798765432"))
    }

    @Test
    fun dismissedGenerationCannotUpdateLater() {
        val tracker = IncomingCallGenerationTracker()
        val generation = tracker.begin()
        tracker.attachNumber(generation, "+123712345678")

        tracker.invalidate(generation)

        assertFalse(tracker.isCurrent(generation, "+123712345678"))
    }

    @Test
    fun lookupSourcesAreVisibleOnlyForCurrentInformationWhenEnabled() {
        CallerLookupSource.values().forEach { source ->
            assertEquals(source, visibleLookupSource(source, true, true))
        }
        assertNull(visibleLookupSource(CallerLookupSource.LOCAL, false, true))
        assertNull(visibleLookupSource(CallerLookupSource.REMOTE, true, false))
        assertNull(visibleLookupSource(null, true, true))
    }

    @Test
    fun callerInformationEligibilityExcludesErrorsAndEmptyResults() {
        assertTrue(hasDisplayableCallerInformation(callerInfo(name = "Sample caller")))
        assertFalse(hasDisplayableCallerInformation(callerInfo()))
        assertFalse(hasDisplayableCallerInformation(callerInfo(name = "Unknown")))
        assertFalse(
            hasDisplayableCallerInformation(
                callerInfo(name = "Sample caller", error = "Unavailable")
            )
        )
    }

    private fun verificationState(
        status: Int,
        sdkInt: Int = 30,
        isIncoming: Boolean = true
    ): NumberVerificationState = mapNumberVerificationState(
        sdkInt = sdkInt,
        minimumSupportedSdk = 30,
        status = status,
        passedStatus = 1,
        failedStatus = 2,
        notVerifiedStatus = 3,
        isIncoming = isIncoming
    )

    private fun callerInfo(
        name: String? = null,
        error: String? = null
    ) = CallerInfoEntity(
        "+123712345678",
        null,
        name,
        null,
        null,
        null,
        null,
        null,
        error
    )
}
