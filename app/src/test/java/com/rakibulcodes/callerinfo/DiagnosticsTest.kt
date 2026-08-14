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
    fun roleAlreadyHeldEnablesWithoutRequest() {
        assertEquals(
            CallerIdRoleDecision(enabled = true, requestRole = false),
            callerIdRoleDecision(true, roleRequired = true, roleAvailable = true, roleHeld = true)
        )
    }

    @Test
    fun missingRoleNeverPersistsPrematureEnabledState() {
        assertEquals(
            CallerIdRoleDecision(enabled = false, requestRole = true),
            callerIdRoleDecision(true, roleRequired = true, roleAvailable = true, roleHeld = false)
        )
    }

    @Test
    fun deniedOrRevokedRoleReconcilesDisabled() {
        assertFalse(reconciledCallerIdEnabled(true, true, true, false))
        assertFalse(callerIdRoleDecision(false, true, true, true).enabled)
    }

    @Test
    fun grantedRoleEnablesOnlyAfterAuthoritativeRequery() {
        assertFalse(callerIdRoleDecision(true, true, true, false).enabled)
        assertTrue(callerIdRoleDecision(true, true, true, true).enabled)
    }

    @Test
    fun roleUiUsesAuthoritativeRequeryAndGuardsProgrammaticSwitchChanges() {
        val activity = listOf(
            java.io.File("src/main/java/com/rakibulcodes/callerinfo/MainActivity.kt"),
            java.io.File("app/src/main/java/com/rakibulcodes/callerinfo/MainActivity.kt")
        ).first { it.exists() }.readText()
        val roleCallback = activity.substringAfter("private val roleRequestLauncher")
            .substringBefore("private val exportCallerDataLauncher")

        assertTrue(roleCallback.contains("isCallerScreeningRoleHeld()"))
        assertFalse(roleCallback.contains("result.resultCode"))
        assertTrue(activity.contains("if (updatingCallerIdSwitch) return@setOnCheckedChangeListener"))
        assertTrue(activity.contains("updatingCallerIdSwitch = true"))
        assertTrue(activity.contains("reconcileCallerIdRoleState()"))
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
    fun batteryBackgroundDialogUsesButtonsNotHiddenMessageAndItemsCombination() {
        val activity = listOf(
            java.io.File("src/main/java/com/rakibulcodes/callerinfo/MainActivity.kt"),
            java.io.File("app/src/main/java/com/rakibulcodes/callerinfo/MainActivity.kt")
        ).first { it.exists() }.readText()

        assertTrue(activity.contains("setPositiveButton(R.string.battery_background_app_settings"))
        assertTrue(activity.contains("setNeutralButton(R.string.battery_background_optimization_settings"))
        assertFalse(activity.contains(".setItems(labels)"))
        assertFalse(activity.contains("Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"))
    }

    @Test
    fun callerDiagnosticsAreBoundedPersistentClearableAndEnumOnly() {
        val storage = InMemoryDiagnosticStorage()
        var now = 100L
        val trail = CallerDiagnosticTrail(storage, maxEvents = 3) { now++ }

        trail.record(CallerDiagnosticEvent.SCREEN_CALLBACK_RECEIVED)
        trail.record(CallerDiagnosticEvent.GENERATION_CREATED, generation = 7)
        trail.record(CallerDiagnosticEvent.NETWORK_AVAILABLE, status = CallerDiagnosticStatus.ACTIVE_NETWORK_VALIDATED)
        trail.record(CallerDiagnosticEvent.REMOTE_LOOKUP_STARTED)

        val restored = CallerDiagnosticTrail(storage, maxEvents = 3) { now++ }
        assertEquals(3, restored.entries().size)
        assertEquals(CallerDiagnosticEvent.GENERATION_CREATED, restored.entries().first().event)
        assertTrue(restored.summary().contains("REMOTE_LOOKUP_STARTED"))
        assertFalse(restored.summary().contains("phone"))
        assertFalse(restored.summary().contains("name"))

        restored.clear()
        assertTrue(restored.entries().isEmpty())
    }

    @Test
    fun refreshFailuresExposeNetworkAndAuthenticationAsDifferentReasons() {
        assertEquals(
            "Internet unavailable",
            com.rakibulcodes.callerinfo.data.remoteLookupFailureMessage(
                com.rakibulcodes.callerinfo.data.RemoteLookupFailure.CONNECTIVITY_UNAVAILABLE
            )
        )
        assertEquals(
            "Remote lookup is not ready. Open the app and check sign-in.",
            com.rakibulcodes.callerinfo.data.remoteLookupFailureMessage(
                com.rakibulcodes.callerinfo.data.RemoteLookupFailure.AUTHENTICATION_NOT_READY
            )
        )
    }

    @Test
    fun diagnosticPersistenceSchemaHasNoFreeFormPrivateFields() {
        val diagnosticSource = listOf(
            java.io.File("src/main/java/com/rakibulcodes/callerinfo/CallerDiagnosticTrail.kt"),
            java.io.File("app/src/main/java/com/rakibulcodes/callerinfo/CallerDiagnosticTrail.kt")
        ).first { it.exists() }.readText()

        assertFalse(diagnosticSource.contains("rawNumber"))
        assertFalse(diagnosticSource.contains("phoneNumber"))
        assertFalse(diagnosticSource.contains("callerName"))
        assertFalse(diagnosticSource.contains("chatId"))
        assertFalse(diagnosticSource.contains("responseText"))
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

    private class InMemoryDiagnosticStorage : CallerDiagnosticStorage {
        private var value: String? = null
        override fun read(): String? = value
        override fun write(value: String) { this.value = value }
        override fun clear() { value = null }
    }
}
