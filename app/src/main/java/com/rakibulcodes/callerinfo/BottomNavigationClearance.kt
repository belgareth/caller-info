package com.rakibulcodes.callerinfo

internal object BottomNavigationClearance {
    fun requiredBottomPadding(
        rootHeight: Int,
        bottomNavigationTop: Int,
        stableContentBottomPadding: Int,
        readableSpacing: Int
    ): Int {
        val basePadding = stableContentBottomPadding.coerceAtLeast(0)
        if (rootHeight <= 0 || bottomNavigationTop <= 0 || bottomNavigationTop >= rootHeight) {
            return basePadding
        }

        val obscuredBottomArea = rootHeight - bottomNavigationTop
        return basePadding + obscuredBottomArea + readableSpacing.coerceAtLeast(0)
    }
}
