package com.rakibulcodes.callerinfo

enum class AppStatusType {
    CALLER_SCREENING,
    OVERLAY,
    PHONE,
    CONTACTS,
    CALL_HISTORY,
    NOTIFICATIONS,
    TELEGRAM,
    FULL_SCREEN_CALLER_CARD,
    BATTERY_OPTIMIZATION,
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
    val callHistoryAllowed: Boolean,
    val callHistoryEnabled: Boolean,
    val notificationsRelevant: Boolean,
    val notificationsAllowed: Boolean,
    val telegramReady: Boolean = false,
    val fullScreenRelevant: Boolean = false,
    val fullScreenAllowed: Boolean = true,
    val batteryOptimizationIgnored: Boolean = false,
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
            type = AppStatusType.CALL_HISTORY,
            value = when {
                snapshot.callHistoryAllowed -> AppStatusValue.ALLOWED
                snapshot.callHistoryEnabled -> AppStatusValue.NOT_ALLOWED
                else -> AppStatusValue.OPTIONAL
            },
            action = if (snapshot.callHistoryEnabled && !snapshot.callHistoryAllowed) {
                AppStatusAction.ALLOW
            } else AppStatusAction.NONE,
            optional = true
        ),
        AppStatusItem(
            type = AppStatusType.TELEGRAM,
            value = if (snapshot.telegramReady) AppStatusValue.CONNECTED else AppStatusValue.DISCONNECTED,
            action = AppStatusAction.NONE
        ),
        AppStatusItem(
            type = AppStatusType.BATTERY_OPTIMIZATION,
            value = if (snapshot.batteryOptimizationIgnored) AppStatusValue.ALLOWED else AppStatusValue.NOT_ALLOWED,
            action = if (snapshot.batteryOptimizationIgnored) AppStatusAction.NONE else AppStatusAction.OPEN_SETTINGS
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
    if (snapshot.fullScreenRelevant) {
        items += AppStatusItem(
            type = AppStatusType.FULL_SCREEN_CALLER_CARD,
            value = if (snapshot.fullScreenAllowed) AppStatusValue.ALLOWED else AppStatusValue.NOT_ALLOWED,
            action = if (snapshot.fullScreenAllowed) AppStatusAction.NONE else AppStatusAction.OPEN_SETTINGS
        )
    }
    return items
}

private fun permissionStatus(type: AppStatusType, allowed: Boolean): AppStatusItem =
    AppStatusItem(
        type = type,
        value = if (allowed) AppStatusValue.ALLOWED else AppStatusValue.NOT_ALLOWED,
        action = if (allowed) AppStatusAction.NONE else AppStatusAction.ALLOW
    )
