package com.rakibulcodes.callerinfo

import org.junit.Assert.assertEquals
import org.junit.Test

class BottomNavigationClearanceTest {
    @Test
    fun clearanceUsesMeasuredObscuredAreaAndStableBasePadding() {
        assertEquals(116, clearance(rootHeight = 1000, navigationTop = 900))
        assertEquals(166, clearance(rootHeight = 1000, navigationTop = 850))
        assertEquals(116, clearance(rootHeight = 700, navigationTop = 600))
    }

    @Test
    fun systemBottomAreaAlreadyRepresentedByGeometryIsNotAddedAgain() {
        assertEquals(
            140,
            BottomNavigationClearance.requiredBottomPadding(
                rootHeight = 1000,
                bottomNavigationTop = 884,
                stableContentBottomPadding = 8,
                readableSpacing = 16
            )
        )
    }

    @Test
    fun repeatedCalculationsDoNotAccumulateCalculatedPadding() {
        val stableBasePadding = 6
        val first = BottomNavigationClearance.requiredBottomPadding(1000, 900, stableBasePadding, 16)
        val repeated = List(20) {
            BottomNavigationClearance.requiredBottomPadding(1000, 900, stableBasePadding, 16)
        }

        assertEquals(122, first)
        repeated.forEach { assertEquals(first, it) }
    }

    @Test
    fun unavailableGeometryKeepsStableOriginalPadding() {
        assertEquals(7, BottomNavigationClearance.requiredBottomPadding(0, 0, 7, 16))
        assertEquals(7, BottomNavigationClearance.requiredBottomPadding(1000, 0, 7, 16))
        assertEquals(7, BottomNavigationClearance.requiredBottomPadding(1000, 1000, 7, 16))
    }

    @Test
    fun minimalMeasuredObstructionStillIncludesReadableSpacing() {
        assertEquals(17, clearance(rootHeight = 1000, navigationTop = 999))
    }

    private fun clearance(rootHeight: Int, navigationTop: Int): Int =
        BottomNavigationClearance.requiredBottomPadding(
            rootHeight = rootHeight,
            bottomNavigationTop = navigationTop,
            stableContentBottomPadding = 0,
            readableSpacing = 16
        )
}
