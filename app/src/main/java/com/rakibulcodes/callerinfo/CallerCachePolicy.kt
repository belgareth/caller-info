package com.rakibulcodes.callerinfo

import com.rakibulcodes.callerinfo.data.database.PendingCallerLookupEntity

object CallerCachePolicy {
    const val FRESHNESS_MILLIS = 30L * 24 * 60 * 60 * 1000
    const val RETRY_EXPIRY_MILLIS = 7L * 24 * 60 * 60 * 1000
    const val MAXIMUM_PENDING_LOOKUPS = 100
    const val MAXIMUM_ATTEMPTS = 3
    const val MAXIMUM_ITEMS_PER_RUN = 5
    private const val BASE_BACKOFF_MILLIS = 15L * 60 * 1000
    private const val MAXIMUM_BACKOFF_MILLIS = 6L * 60 * 60 * 1000

    fun isFresh(lastSuccessfullyUpdatedMillis: Long?, nowMillis: Long): Boolean =
        lastSuccessfullyUpdatedMillis != null &&
            nowMillis - lastSuccessfullyUpdatedMillis < FRESHNESS_MILLIS

    fun isExpired(createdTimestampMillis: Long, nowMillis: Long): Boolean =
        nowMillis - createdTimestampMillis >= RETRY_EXPIRY_MILLIS

    fun nextEligibleRetryMillis(nowMillis: Long, attemptCount: Int): Long {
        val exponent = (attemptCount - 1).coerceIn(0, 10)
        val delay = (BASE_BACKOFF_MILLIS * (1L shl exponent))
            .coerceAtMost(MAXIMUM_BACKOFF_MILLIS)
        return nowMillis + delay
    }

    fun canRetry(item: PendingCallerLookupEntity, nowMillis: Long): Boolean =
        item.attemptCount < MAXIMUM_ATTEMPTS &&
            !isExpired(item.createdTimestampMillis, nowMillis) &&
            item.nextEligibleRetryTimestampMillis <= nowMillis
}
