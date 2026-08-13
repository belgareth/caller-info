package com.rakibulcodes.callerinfo

import android.os.Build
import android.provider.Settings

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

fun batteryBackgroundSettingsActions(sdkInt: Int): List<String> =
    if (sdkInt >= Build.VERSION_CODES.M) {
        listOf(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
        )
    } else {
        listOf(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
    }
