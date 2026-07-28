package com.rakibulcodes.callerinfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTest {
    private val config = NumberNormalizationConfig(
        callingCode = "123",
        localPrefix = "0",
        nationalNumberLength = 9,
        acceptWithoutPrefix = false
    )

    @Test
    fun numberPreviewUsesSharedNormalization() {
        val result = previewNumberFormatting("(0712) 345-678", config)

        assertTrue(result.isValid)
        assertEquals("+123712345678", result.formattedNumber)
    }

    @Test
    fun numberPreviewRejectsInvalidInputSafely() {
        val result = previewNumberFormatting("0712ABC678", config)

        assertFalse(result.isValid)
        assertNull(result.formattedNumber)
    }

    @Test
    fun callerScreeningStatusReflectsActiveRole() {
        val item = statuses(callerScreeningActive = true)
            .first { it.type == AppStatusType.CALLER_SCREENING }

        assertEquals(AppStatusValue.ACTIVE, item.value)
        assertEquals(AppStatusAction.NONE, item.action)
    }

    @Test
    fun callerScreeningStatusOffersSelectionWhenInactive() {
        val item = statuses(callerScreeningActive = false)
            .first { it.type == AppStatusType.CALLER_SCREENING }

        assertEquals(AppStatusValue.NOT_SELECTED, item.value)
        assertEquals(AppStatusAction.SELECT, item.action)
    }

    @Test
    fun overlayAndPermissionStatusesReflectCurrentValues() {
        val items = statuses(
            overlayAllowed = false,
            contactsAllowed = false,
            callHistoryAllowed = false
        )

        assertEquals(
            AppStatusAction.OPEN_SETTINGS,
            items.first { it.type == AppStatusType.OVERLAY }.action
        )
        assertEquals(
            AppStatusAction.ALLOW,
            items.first { it.type == AppStatusType.CONTACTS }.action
        )
        assertEquals(
            AppStatusValue.OPTIONAL,
            items.first { it.type == AppStatusType.CALL_HISTORY }.value
        )
    }

    @Test
    fun notificationStatusIsAbsentWhenNotRelevant() {
        assertTrue(statuses(notificationsRelevant = false).none {
            it.type == AppStatusType.NOTIFICATIONS
        })
    }

    @Test
    fun previewUsesSyntheticData() {
        val preview = createCallerCardPreviewData(3_600_000L)

        assertEquals("Sample caller", preview.name)
        assertEquals("+1234567890", preview.number)
        assertEquals(RecentCallType.INCOMING, preview.recentCall.type)
        assertEquals(0L, preview.recentCall.timestampMillis)
    }

    @Test
    fun repeatedPreviewDoesNotCreateDuplicatePresentation() {
        val state = OverlayPresentationState()

        assertTrue(state.beginPreview())
        assertFalse(state.beginPreview())
        assertEquals(OverlayPresentationMode.PREVIEW, state.mode)
        assertTrue(state.shouldAutoDismissPreview())
    }

    @Test
    fun realCallTakesPriorityOverPreview() {
        val state = OverlayPresentationState()
        state.beginPreview()

        state.beginRealCall()

        assertEquals(OverlayPresentationMode.REAL_CALL, state.mode)
        assertFalse(state.beginPreview())
        assertFalse(state.dismissPreview())
        assertFalse(state.shouldAutoDismissPreview())
    }

    @Test
    fun previewCanBeDismissedAndStartedAgain() {
        val state = OverlayPresentationState()
        state.beginPreview()

        assertTrue(state.dismissPreview())
        assertEquals(OverlayPresentationMode.NONE, state.mode)
        assertTrue(state.beginPreview())
    }

    private fun statuses(
        callerScreeningActive: Boolean = true,
        overlayAllowed: Boolean = true,
        contactsAllowed: Boolean = true,
        callHistoryAllowed: Boolean = true,
        notificationsRelevant: Boolean = true
    ): List<AppStatusItem> = buildAppStatusItems(
        AppStatusSnapshot(
            callerScreeningAvailable = true,
            callerScreeningActive = callerScreeningActive,
            overlayAllowed = overlayAllowed,
            phoneAllowed = true,
            contactsAllowed = contactsAllowed,
            callHistoryAllowed = callHistoryAllowed,
            callHistoryEnabled = false,
            notificationsRelevant = notificationsRelevant,
            notificationsAllowed = true
        )
    )
}
