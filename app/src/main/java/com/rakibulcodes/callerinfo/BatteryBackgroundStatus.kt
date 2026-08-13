package com.rakibulcodes.callerinfo

import android.os.Build

enum class BatteryOptimizationStatus {
    DEFAULT,
    EXEMPT,
    UNAVAILABLE
}

enum class BackgroundRestrictionStatus {
    NOT_RESTRICTED,
    RESTRICTED,
    UNAVAILABLE
}

fun batteryOptimizationStatus(
    sdkInt: Int,
    ignoringBatteryOptimizations: Boolean?
): BatteryOptimizationStatus =
    if (sdkInt < Build.VERSION_CODES.M || ignoringBatteryOptimizations == null) {
        BatteryOptimizationStatus.UNAVAILABLE
    } else if (ignoringBatteryOptimizations) {
        BatteryOptimizationStatus.EXEMPT
    } else {
        BatteryOptimizationStatus.DEFAULT
    }

fun backgroundRestrictionStatus(
    sdkInt: Int,
    backgroundRestricted: Boolean?
): BackgroundRestrictionStatus =
    if (sdkInt < Build.VERSION_CODES.P || backgroundRestricted == null) {
        BackgroundRestrictionStatus.UNAVAILABLE
    } else if (backgroundRestricted) {
        BackgroundRestrictionStatus.RESTRICTED
    } else {
        BackgroundRestrictionStatus.NOT_RESTRICTED
    }

enum class BatteryBackgroundSettingsDestination {
    APP_DETAILS,
    BATTERY_OPTIMIZATION
}

fun batteryBackgroundSettingsDestinations(sdkInt: Int): List<BatteryBackgroundSettingsDestination> =
    if (sdkInt >= Build.VERSION_CODES.M) {
        listOf(
            BatteryBackgroundSettingsDestination.APP_DETAILS,
            BatteryBackgroundSettingsDestination.BATTERY_OPTIMIZATION
        )
    } else {
        listOf(BatteryBackgroundSettingsDestination.APP_DETAILS)
    }
