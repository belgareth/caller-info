package com.rakibulcodes.callerinfo

enum class AppStatusType {
    CALLER_SCREENING,
    OVERLAY,
    PHONE,
    CONTACTS,
    PREVIOUS_CALL_CONTEXT,
    NOTIFICATIONS,
    TELEGRAM,
    BATTERY_OPTIMIZATION,
    BACKGROUND_RESTRICTION,
    PENDING_LOOKUPS,
    LAST_REMOTE_LOOKUP
}

enum class AppStatusValue {
    ACTIVE,
    ALLOWED,
    NOT_ALLOWED,
    NOT_SELECTED,
    OPTIONAL,
    UNAVAILABLE,
    DEFAULT,
    EXEMPT,
    RESTRICTED,
    NOT_RESTRICTED,
    CONNECTED,
    DISCONNECTED,
    INFO
}

enum class AppStatusAction {
    NONE,
    SELECT,
    ALLOW,
    OPEN_SETTINGS
}

data class AppStatusItem(
    val type: AppStatusType,
    val value: AppStatusValue,
    val action: AppStatusAction,
    val optional: Boolean = false,
    val detail: String? = null
)

data class AppStatusSnapshot(
    val callerScreeningAvailable: Boolean,
    val callerScreeningActive: Boolean,
    val overlayAllowed: Boolean,
    val phoneAllowed: Boolean,
    val contactsAllowed: Boolean,
    val previousCallContextEnabled: Boolean = false,
    val notificationsRelevant: Boolean,
    val notificationsAllowed: Boolean,
    val telegramReady: Boolean = false,
    val batteryOptimizationStatus: BatteryOptimizationStatus = BatteryOptimizationStatus.UNAVAILABLE,
    val backgroundRestrictionStatus: BackgroundRestrictionStatus = BackgroundRestrictionStatus.UNAVAILABLE,
    val pendingLookupCount: Int = 0,
    val lastRemoteLookupText: String? = null
)

fun buildAppStatusItems(snapshot: AppStatusSnapshot): List<AppStatusItem> {
    val items = mutableListOf(
        AppStatusItem(
            type = AppStatusType.CALLER_SCREENING,
            value = when {
                !snapshot.callerScreeningAvailable -> AppStatusValue.UNAVAILABLE
                snapshot.callerScreeningActive -> AppStatusValue.ACTIVE
                else -> AppStatusValue.NOT_SELECTED
            },
            action = if (snapshot.callerScreeningAvailable && !snapshot.callerScreeningActive) {
                AppStatusAction.SELECT
            } else AppStatusAction.NONE
        ),
        AppStatusItem(
            type = AppStatusType.OVERLAY,
            value = if (snapshot.overlayAllowed) AppStatusValue.ALLOWED else AppStatusValue.NOT_ALLOWED,
            action = if (snapshot.overlayAllowed) AppStatusAction.NONE else AppStatusAction.OPEN_SETTINGS
        ),
        permissionStatus(AppStatusType.PHONE, snapshot.phoneAllowed),
        AppStatusItem(
            type = AppStatusType.CONTACTS,
            value = if (snapshot.contactsAllowed) AppStatusValue.ALLOWED else AppStatusValue.OPTIONAL,
            action = if (snapshot.contactsAllowed) AppStatusAction.NONE else AppStatusAction.ALLOW,
            optional = true
        ),
        AppStatusItem(
            type = AppStatusType.PREVIOUS_CALL_CONTEXT,
            value = if (snapshot.previousCallContextEnabled) AppStatusValue.ACTIVE else AppStatusValue.OPTIONAL,
            action = AppStatusAction.NONE,
            optional = true,
            detail = if (snapshot.previousCallContextEnabled) "App-observed calls" else null
        ),
        AppStatusItem(
            type = AppStatusType.TELEGRAM,
            value = if (snapshot.telegramReady) AppStatusValue.CONNECTED else AppStatusValue.DISCONNECTED,
            action = AppStatusAction.NONE
        ),
        AppStatusItem(
            type = AppStatusType.BATTERY_OPTIMIZATION,
            value = when (snapshot.batteryOptimizationStatus) {
                BatteryOptimizationStatus.DEFAULT -> AppStatusValue.DEFAULT
                BatteryOptimizationStatus.EXEMPT -> AppStatusValue.EXEMPT
                BatteryOptimizationStatus.UNAVAILABLE -> AppStatusValue.UNAVAILABLE
            },
            action = AppStatusAction.OPEN_SETTINGS,
            optional = true
        ),
        AppStatusItem(
            type = AppStatusType.BACKGROUND_RESTRICTION,
            value = when (snapshot.backgroundRestrictionStatus) {
                BackgroundRestrictionStatus.NOT_RESTRICTED -> AppStatusValue.NOT_RESTRICTED
                BackgroundRestrictionStatus.RESTRICTED -> AppStatusValue.RESTRICTED
                BackgroundRestrictionStatus.UNAVAILABLE -> AppStatusValue.UNAVAILABLE
            },
            action = AppStatusAction.OPEN_SETTINGS,
            optional = true
        ),
        AppStatusItem(
            type = AppStatusType.PENDING_LOOKUPS,
            value = AppStatusValue.INFO,
            action = AppStatusAction.NONE,
            detail = snapshot.pendingLookupCount.toString()
        ),
        AppStatusItem(
            type = AppStatusType.LAST_REMOTE_LOOKUP,
            value = AppStatusValue.INFO,
            action = AppStatusAction.NONE,
            detail = snapshot.lastRemoteLookupText ?: "Never"
        )
    )

    if (snapshot.notificationsRelevant) {
        items += permissionStatus(AppStatusType.NOTIFICATIONS, snapshot.notificationsAllowed)
    }
    return items
}

private fun permissionStatus(type: AppStatusType, allowed: Boolean): AppStatusItem =
    AppStatusItem(
        type = type,
        value = if (allowed) AppStatusValue.ALLOWED else AppStatusValue.NOT_ALLOWED,
        action = if (allowed) AppStatusAction.NONE else AppStatusAction.ALLOW
    )
