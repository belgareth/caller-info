package com.rakibulcodes.callerinfo

import com.rakibulcodes.callerinfo.data.database.PendingCallerLookupEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallerCachePolicyTest {
    private val now = 4_000_000_000L

    @Test
    fun recordBelowThirtyDaysIsFresh() {
        assertTrue(CallerCachePolicy.isFresh(now - CallerCachePolicy.FRESHNESS_MILLIS + 1, now))
    }

    @Test
    fun recordAtExactlyThirtyDaysIsStale() {
        assertFalse(CallerCachePolicy.isFresh(now - CallerCachePolicy.FRESHNESS_MILLIS, now))
    }

    @Test
    fun olderAndUntimestampedRecordsAreStale() {
        assertFalse(CallerCachePolicy.isFresh(now - CallerCachePolicy.FRESHNESS_MILLIS - 1, now))
        assertFalse(CallerCachePolicy.isFresh(null, now))
    }

    @Test
    fun pendingLookupExpiresAtExactlySevenDays() {
        assertFalse(
            CallerCachePolicy.isExpired(now - CallerCachePolicy.RETRY_EXPIRY_MILLIS + 1, now)
        )
        assertTrue(
            CallerCachePolicy.isExpired(now - CallerCachePolicy.RETRY_EXPIRY_MILLIS, now)
        )
    }

    @Test
    fun boundedBackoffIncreasesWithoutExceedingCap() {
        val first = CallerCachePolicy.nextEligibleRetryMillis(now, 1) - now
        val second = CallerCachePolicy.nextEligibleRetryMillis(now, 2) - now
        val later = CallerCachePolicy.nextEligibleRetryMillis(now, 20) - now

        assertTrue(second > first)
        assertTrue(later >= second)
        assertTrue(later <= 6L * 60 * 60 * 1000)
    }

    @Test
    fun thirdAttemptCannotRunAgain() {
        assertTrue(CallerCachePolicy.canRetry(item(attemptCount = 2), now))
        assertFalse(CallerCachePolicy.canRetry(item(attemptCount = 3), now))
    }

    @Test
    fun futureAndExpiredItemsAreNotEligible() {
        assertFalse(
            CallerCachePolicy.canRetry(
                item(nextEligibleMillis = now + 1),
                now
            )
        )
        assertFalse(
            CallerCachePolicy.canRetry(
                item(createdMillis = now - CallerCachePolicy.RETRY_EXPIRY_MILLIS),
                now
            )
        )
    }

    @Test
    fun queueAndBatchLimitsRemainBounded() {
        assertEquals(100, CallerCachePolicy.MAXIMUM_PENDING_LOOKUPS)
        assertEquals(3, CallerCachePolicy.MAXIMUM_ATTEMPTS)
        assertEquals(5, CallerCachePolicy.MAXIMUM_ITEMS_PER_RUN)
    }

    private fun item(
        createdMillis: Long = now,
        nextEligibleMillis: Long = now,
        attemptCount: Int = 0
    ) = PendingCallerLookupEntity(
        normalizedNumber = "+12345678901",
        createdTimestampMillis = createdMillis,
        nextEligibleRetryTimestampMillis = nextEligibleMillis,
        attemptCount = attemptCount
    )
}
