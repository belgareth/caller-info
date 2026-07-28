package com.rakibulcodes.callerinfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.SimpleTimeZone
import java.util.TimeZone

class RecentCallInteractionTest {
    private val config = NumberNormalizationConfig(
        callingCode = "123",
        localPrefix = "0",
        nationalNumberLength = 9,
        acceptWithoutPrefix = false
    )

    @Test
    fun selectsNewestMatchingCall() {
        val records = listOf(
            record("+123712345678", RecentCallType.OUTGOING, 900),
            record("0712345678", RecentCallType.INCOMING, 500)
        )

        assertEquals(
            RecentCallInteraction(RecentCallType.OUTGOING, 900),
            finder(records).findPreviousCall("0712345678", 1_000, config)
        )
    }

    @Test
    fun selectsOldMatchingCall() {
        val oldTimestamp = timestamp(2025, Calendar.JANUARY, 14, 2, 0)
        val cutoff = timestamp(2026, Calendar.MARCH, 8, 16, 0)

        assertEquals(
            RecentCallInteraction(RecentCallType.INCOMING, oldTimestamp),
            finder(listOf(record("0712345678", RecentCallType.INCOMING, oldTimestamp)))
                .findPreviousCall("+123712345678", cutoff, config)
        )
    }

    @Test
    fun skipsNewestUnrelatedCall() {
        val records = listOf(
            record("+123798765432", RecentCallType.MISSED, 900),
            record("+123712345678", RecentCallType.INCOMING, 800)
        )

        assertEquals(
            RecentCallInteraction(RecentCallType.INCOMING, 800),
            finder(records).findPreviousCall("0712345678", 1_000, config)
        )
    }

    @Test
    fun matchesDifferentNumberFormats() {
        val result = finder(
            listOf(record("001230712345678", RecentCallType.MISSED, 800))
        ).findPreviousCall("(0712) 345-678", 1_000, config)

        assertEquals(RecentCallType.MISSED, result?.type)
    }

    @Test
    fun matchesPrefixlessNumberWhenEnabled() {
        val enabledConfig = config.copy(acceptWithoutPrefix = true)

        val result = finder(
            listOf(record("712345678", RecentCallType.INCOMING, 800))
        ).findPreviousCall("0712345678", 1_000, enabledConfig)

        assertEquals(RecentCallType.INCOMING, result?.type)
    }

    @Test
    fun doesNotMatchPrefixlessNumberWhenDisabled() {
        val result = finder(
            listOf(record("712345678", RecentCallType.INCOMING, 800))
        ).findPreviousCall("0712345678", 1_000, config)

        assertNull(result)
    }

    @Test
    fun blankOrInvalidNumbersDoNotMatch() {
        val inputs = listOf(null, "", "   ", "0712ABC678", "1234")

        inputs.forEach { value ->
            assertNull(
                value,
                finder(listOf(record(value, RecentCallType.INCOMING, 800)))
                    .findPreviousCall("0712345678", 1_000, config)
            )
        }
    }

    @Test
    fun hiddenCallerValuesDoNotMatch() {
        val inputs = listOf(
            "Unknown",
            "Private",
            "Private number",
            "Withheld",
            "Restricted",
            "Anonymous",
            "Unavailable"
        )

        inputs.forEach { value ->
            assertNull(
                value,
                finder(listOf(record(value, RecentCallType.INCOMING, 800)))
                    .findPreviousCall("0712345678", 1_000, config)
            )
        }
    }

    @Test
    fun skipsUnsupportedCallType() {
        val records = listOf(
            record("+123712345678", null, 900),
            record("+123712345678", RecentCallType.OUTGOING, 800)
        )

        assertEquals(
            RecentCallInteraction(RecentCallType.OUTGOING, 800),
            finder(records).findPreviousCall("0712345678", 1_000, config)
        )
    }

    @Test
    fun excludesCurrentAndFutureTimestamps() {
        val records = listOf(
            record("+123712345678", RecentCallType.MISSED, 1_100),
            record("+123712345678", RecentCallType.OUTGOING, 1_000),
            record("+123712345678", RecentCallType.INCOMING, 900)
        )

        assertEquals(
            RecentCallInteraction(RecentCallType.INCOMING, 900),
            finder(records).findPreviousCall("0712345678", 1_000, config)
        )
    }

    @Test
    fun mapsIncomingCallType() {
        assertSame(RecentCallType.INCOMING, mapRecentCallType(1, 1, 2, 3))
    }

    @Test
    fun mapsOutgoingCallType() {
        assertSame(RecentCallType.OUTGOING, mapRecentCallType(2, 1, 2, 3))
    }

    @Test
    fun mapsMissedCallType() {
        assertSame(RecentCallType.MISSED, mapRecentCallType(3, 1, 2, 3))
    }

    @Test
    fun leavesUnsupportedCallTypeUnmapped() {
        assertNull(mapRecentCallType(4, 1, 2, 3))
    }

    @Test
    fun emptyHistoryReturnsNoResult() {
        assertNull(finder(emptyList()).findPreviousCall("0712345678", 1_000, config))
    }

    @Test
    fun permissionDeniedReturnsNoResultWithoutQuery() {
        var queryCount = 0
        val source = RecentCallRecordSource { _, _ ->
            queryCount++
            null
        }

        val result = RecentCallFinder(source, { true }, { false })
            .findPreviousCall("0712345678", 1_000, config)

        assertNull(result)
        assertEquals(0, queryCount)
    }

    @Test
    fun providerFailureReturnsNoResult() {
        val source = RecentCallRecordSource { _, _ -> throw IllegalStateException() }

        assertNull(
            RecentCallFinder(source, { true }, { true })
                .findPreviousCall("0712345678", 1_000, config)
        )
    }

    @Test
    fun securityFailureReturnsNoResult() {
        val source = RecentCallRecordSource { _, _ -> throw SecurityException() }

        assertNull(
            RecentCallFinder(source, { true }, { true })
                .findPreviousCall("0712345678", 1_000, config)
        )
    }

    @Test
    fun disabledFeaturePerformsNoQuery() {
        var queryCount = 0
        val source = RecentCallRecordSource { _, _ ->
            queryCount++
            null
        }

        val result = RecentCallFinder(source, { false }, { true })
            .findPreviousCall("0712345678", 1_000, config)

        assertNull(result)
        assertEquals(0, queryCount)
    }

    @Test
    fun formatsTodayTime() {
        val now = timestamp(2026, Calendar.MARCH, 8, 16, 0)
        val event = timestamp(2026, Calendar.MARCH, 8, 14, 9)

        assertEquals("14:09", format(event, now))
    }

    @Test
    fun formatsYesterdayTime() {
        val now = timestamp(2026, Calendar.MARCH, 8, 16, 0)
        val event = timestamp(2026, Calendar.MARCH, 7, 6, 30)

        assertEquals("Yesterday, 06:30", format(event, now))
    }

    @Test
    fun formatsCurrentYearDateAndTime() {
        val now = timestamp(2026, Calendar.MARCH, 8, 16, 0)
        val event = timestamp(2026, Calendar.JANUARY, 14, 2, 0)

        assertEquals("01-14 02:00", format(event, now))
    }

    @Test
    fun formatsPreviousYearDate() {
        val now = timestamp(2026, Calendar.MARCH, 8, 16, 0)
        val event = timestamp(2025, Calendar.JANUARY, 14, 2, 0)

        assertEquals("01-14-2025", format(event, now))
    }

    private fun finder(records: List<RecentCallRecord>): RecentCallFinder =
        RecentCallFinder(
            recordSource = RecentCallRecordSource { _, predicate ->
                records.firstOrNull(predicate)
            },
            featureEnabled = { true },
            permissionGranted = { true }
        )

    private fun record(
        number: String?,
        type: RecentCallType?,
        timestampMillis: Long
    ) = RecentCallRecord(number, type, timestampMillis)

    private val testTimeZone: TimeZone = SimpleTimeZone(0, "test")

    private fun timestamp(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int
    ): Long = Calendar.getInstance(testTimeZone).apply {
        clear()
        set(year, month, day, hour, minute)
    }.timeInMillis

    private fun format(timestampMillis: Long, nowMillis: Long): String =
        RecentCallDateFormatter.format(
            timestampMillis = timestampMillis,
            nowMillis = nowMillis,
            locale = Locale.ROOT,
            timeZone = testTimeZone,
            yesterdayLabel = "Yesterday"
        ) { style ->
            when (style) {
                RecentCallDateStyle.TODAY,
                RecentCallDateStyle.YESTERDAY -> "HH:mm"
                RecentCallDateStyle.CURRENT_YEAR -> "MM-dd HH:mm"
                RecentCallDateStyle.PREVIOUS_YEAR -> "MM-dd-yyyy"
            }
        }
}
