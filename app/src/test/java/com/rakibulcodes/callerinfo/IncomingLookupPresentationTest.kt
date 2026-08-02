package com.rakibulcodes.callerinfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingLookupPresentationTest {
    @Test
    fun unresolvedLookupStartsInLookingUpState() {
        assertEquals(
            IncomingLookupStage.LOOKING_UP,
            incomingLookupStage(
                hasUsefulCallerInformation = false,
                retryScheduled = false,
                errorMessage = null
            )
        )
    }

    @Test
    fun usefulCallerInformationWinsOverRetryOrErrorFlags() {
        assertEquals(
            IncomingLookupStage.RESOLVED,
            incomingLookupStage(
                hasUsefulCallerInformation = true,
                retryScheduled = true,
                errorMessage = "Temporary failure"
            )
        )
    }

    @Test
    fun temporaryFailureWithDurableQueueShowsRetryQueued() {
        assertEquals(
            IncomingLookupStage.RETRY_QUEUED,
            incomingLookupStage(
                hasUsefulCallerInformation = false,
                retryScheduled = true,
                errorMessage = "Internet connection unavailable"
            )
        )
    }

    @Test
    fun nonRetryableFailureShowsFailed() {
        assertEquals(
            IncomingLookupStage.FAILED,
            incomingLookupStage(
                hasUsefulCallerInformation = false,
                retryScheduled = false,
                errorMessage = "Caller information was not found"
            )
        )
    }

    @Test
    fun actionsAppearOnlyForResolvedUsefulCallerInformation() {
        assertTrue(
            incomingActionsVisible(
                IncomingLookupStage.RESOLVED,
                hasUsefulCallerInformation = true,
                isPreview = false
            )
        )
        assertFalse(
            incomingActionsVisible(
                IncomingLookupStage.LOOKING_UP,
                hasUsefulCallerInformation = false,
                isPreview = false
            )
        )
        assertFalse(
            incomingActionsVisible(
                IncomingLookupStage.RETRY_QUEUED,
                hasUsefulCallerInformation = false,
                isPreview = false
            )
        )
        assertFalse(
            incomingActionsVisible(
                IncomingLookupStage.RESOLVED,
                hasUsefulCallerInformation = true,
                isPreview = true
            )
        )
    }
    @Test
    fun incomingDeferredRetrySurvivesCallPresentationEnding() {
        assertTrue(
            shouldScheduleDeferredRetry(
                requestStillValid = false,
                retainAfterRequestEnds = true
            )
        )
        assertFalse(
            shouldScheduleDeferredRetry(
                requestStillValid = false,
                retainAfterRequestEnds = false
            )
        )
    }

}
