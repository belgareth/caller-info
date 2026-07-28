package com.rakibulcodes.callerinfo

enum class AppStatusType {
    CALLER_SCREENING,
    OVERLAY,
    PHONE,
    CONTACTS,
    CALL_HISTORY,
    NOTIFICATIONS
}

enum class AppStatusValue {
    ACTIVE,
    ALLOWED,
    NOT_ALLOWED,
    NOT_SELECTED,
    OPTIONAL,
    UNAVAILABLE
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
    val optional: Boolean = false
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
    val notificationsAllowed: Boolean
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
            action = if (
                snapshot.callerScreeningAvailable && !snapshot.callerScreeningActive
            ) {
                AppStatusAction.SELECT
            } else {
                AppStatusAction.NONE
            }
        ),
        AppStatusItem(
            type = AppStatusType.OVERLAY,
            value = if (snapshot.overlayAllowed) {
                AppStatusValue.ALLOWED
            } else {
                AppStatusValue.NOT_ALLOWED
            },
            action = if (snapshot.overlayAllowed) {
                AppStatusAction.NONE
            } else {
                AppStatusAction.OPEN_SETTINGS
            }
        ),
        permissionStatus(AppStatusType.PHONE, snapshot.phoneAllowed),
        permissionStatus(AppStatusType.CONTACTS, snapshot.contactsAllowed),
        AppStatusItem(
            type = AppStatusType.CALL_HISTORY,
            value = when {
                snapshot.callHistoryAllowed -> AppStatusValue.ALLOWED
                snapshot.callHistoryEnabled -> AppStatusValue.NOT_ALLOWED
                else -> AppStatusValue.OPTIONAL
            },
            action = if (
                snapshot.callHistoryEnabled && !snapshot.callHistoryAllowed
            ) {
                AppStatusAction.ALLOW
            } else {
                AppStatusAction.NONE
            },
            optional = true
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
