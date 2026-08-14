package com.rakibulcodes.callerinfo

data class CallerIdRoleDecision(
    val enabled: Boolean,
    val requestRole: Boolean
)

fun callerIdRoleDecision(
    enableRequested: Boolean,
    roleRequired: Boolean,
    roleAvailable: Boolean,
    roleHeld: Boolean
): CallerIdRoleDecision = when {
    !enableRequested -> CallerIdRoleDecision(enabled = false, requestRole = false)
    !roleRequired -> CallerIdRoleDecision(enabled = true, requestRole = false)
    !roleAvailable -> CallerIdRoleDecision(enabled = false, requestRole = false)
    roleHeld -> CallerIdRoleDecision(enabled = true, requestRole = false)
    else -> CallerIdRoleDecision(enabled = false, requestRole = true)
}

fun reconciledCallerIdEnabled(
    persistedEnabled: Boolean,
    roleRequired: Boolean,
    roleAvailable: Boolean,
    roleHeld: Boolean
): Boolean = callerIdRoleDecision(
    enableRequested = persistedEnabled,
    roleRequired = roleRequired,
    roleAvailable = roleAvailable,
    roleHeld = roleHeld
).enabled
