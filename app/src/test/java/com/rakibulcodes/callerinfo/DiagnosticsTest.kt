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
            previousCallContextEnabled = false
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
            items.first { it.type == AppStatusType.PREVIOUS_CALL_CONTEXT }.value
        )
    }

    @Test
    fun previousCallContextUsesAppOwnedStateNotPermissionAction() {
        val item = statuses(previousCallContextEnabled = true)
            .first { it.type == AppStatusType.PREVIOUS_CALL_CONTEXT }

        assertEquals(AppStatusValue.ACTIVE, item.value)
        assertEquals(AppStatusAction.NONE, item.action)
        assertTrue(item.optional)
    }

    @Test
    fun batteryAndBackgroundStatesAreDiagnosticNotScaryFailures() {
        val items = statuses(
            batteryOptimizationStatus = BatteryOptimizationStatus.DEFAULT,
            backgroundRestrictionStatus = BackgroundRestrictionStatus.NOT_RESTRICTED
        )

        assertEquals(
            AppStatusValue.DEFAULT,
            items.first { it.type == AppStatusType.BATTERY_OPTIMIZATION }.value
        )
        assertEquals(
            AppStatusAction.OPEN_SETTINGS,
            items.first { it.type == AppStatusType.BATTERY_OPTIMIZATION }.action
        )
        assertEquals(
            AppStatusValue.NOT_RESTRICTED,
            items.first { it.type == AppStatusType.BACKGROUND_RESTRICTION }.value
        )
    }

    @Test
    fun backgroundRestrictionReportsRestrictedWhenAndroidDoes() {
        assertEquals(
            BackgroundRestrictionStatus.RESTRICTED,
            backgroundRestrictionStatus(sdkInt = android.os.Build.VERSION_CODES.P, backgroundRestricted = true)
        )
        assertEquals(
            BackgroundRestrictionStatus.NOT_RESTRICTED,
            backgroundRestrictionStatus(sdkInt = android.os.Build.VERSION_CODES.P, backgroundRestricted = false)
        )
        assertEquals(
            BackgroundRestrictionStatus.UNAVAILABLE,
            backgroundRestrictionStatus(sdkInt = android.os.Build.VERSION_CODES.O, backgroundRestricted = false)
        )
    }

    @Test
    fun batteryOptimizationReportsDefaultExemptOrUnavailable() {
        assertEquals(
            BatteryOptimizationStatus.DEFAULT,
            batteryOptimizationStatus(sdkInt = android.os.Build.VERSION_CODES.M, ignoringBatteryOptimizations = false)
        )
        assertEquals(
            BatteryOptimizationStatus.EXEMPT,
            batteryOptimizationStatus(sdkInt = android.os.Build.VERSION_CODES.M, ignoringBatteryOptimizations = true)
        )
        assertEquals(
            BatteryOptimizationStatus.UNAVAILABLE,
            batteryOptimizationStatus(sdkInt = android.os.Build.VERSION_CODES.LOLLIPOP, ignoringBatteryOptimizations = false)
        )
    }

    @Test
    fun batteryBackgroundSettingsOffersExplicitChoices() {
        val actions = batteryBackgroundSettingsDestinations(android.os.Build.VERSION_CODES.TIRAMISU)

        assertTrue(actions.contains(BatteryBackgroundSettingsDestination.APP_DETAILS))
        assertTrue(actions.contains(BatteryBackgroundSettingsDestination.BATTERY_OPTIMIZATION))
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
        assertEquals("0000000000", preview.number)
        assertEquals(RecentCallType.INCOMING, preview.recentCall.type)
        assertEquals(0L, preview.recentCall.timestampMillis)
        assertEquals(NumberVerificationState.PASSED, preview.verificationState)
        assertEquals(CallerLookupSource.LOCAL, preview.lookupSource)
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

        state.beginRealCall(7)

        assertEquals(OverlayPresentationMode.REAL_CALL, state.mode)
        assertFalse(state.beginPreview())
        assertFalse(state.dismissPreview())
        assertFalse(state.shouldAutoDismissPreview())
    }

    @Test
    fun delayedClearCannotRemoveNewerIncomingPresentation() {
        val state = OverlayPresentationState()
        state.beginRealCall(7)
        state.beginRealCall(8)

        assertFalse(state.canClearIncoming(7))
        assertEquals(OverlayPresentationMode.REAL_CALL, state.mode)
        assertTrue(state.canClearIncoming(8))
        state.clear()
        assertEquals(OverlayPresentationMode.NONE, state.mode)
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
        previousCallContextEnabled: Boolean = false,
        notificationsRelevant: Boolean = true,
        batteryOptimizationStatus: BatteryOptimizationStatus = BatteryOptimizationStatus.EXEMPT,
        backgroundRestrictionStatus: BackgroundRestrictionStatus = BackgroundRestrictionStatus.NOT_RESTRICTED
    ): List<AppStatusItem> = buildAppStatusItems(
        AppStatusSnapshot(
            callerScreeningAvailable = true,
            callerScreeningActive = callerScreeningActive,
            overlayAllowed = overlayAllowed,
            phoneAllowed = true,
            contactsAllowed = contactsAllowed,
            previousCallContextEnabled = previousCallContextEnabled,
            notificationsRelevant = notificationsRelevant,
            notificationsAllowed = true,
            batteryOptimizationStatus = batteryOptimizationStatus,
            backgroundRestrictionStatus = backgroundRestrictionStatus
        )
    )
}
