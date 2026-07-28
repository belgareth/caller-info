package com.rakibulcodes.callerinfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.Calendar
import java.util.SimpleTimeZone

class RecentInteractionTest {
    private val config = NumberNormalizationConfig(
        callingCode = "123",
        localPrefix = "0",
        nationalNumberLength = 9,
        acceptWithoutPrefix = false
    )

    @Test
    fun newerMessageBeatsOlderIncomingCall() {
        assertEquals(
            interaction(RecentInteractionType.MESSAGE, 900),
            selectNewestInteraction(call(RecentCallType.INCOMING, 800), message(900))
        )
    }

    @Test
    fun newerMessageBeatsOlderOutgoingCall() {
        assertEquals(
            interaction(RecentInteractionType.MESSAGE, 900),
            selectNewestInteraction(call(RecentCallType.OUTGOING, 800), message(900))
        )
    }

    @Test
    fun newerMessageBeatsOlderMissedCall() {
        assertEquals(
            interaction(RecentInteractionType.MESSAGE, 900),
            selectNewestInteraction(call(RecentCallType.MISSED, 800), message(900))
        )
    }

    @Test
    fun newerIncomingCallBeatsOlderMessage() {
        assertEquals(
            interaction(RecentInteractionType.CALL_INCOMING, 900),
            selectNewestInteraction(call(RecentCallType.INCOMING, 900), message(800))
        )
    }

    @Test
    fun newerOutgoingCallBeatsOlderMessage() {
        assertEquals(
            interaction(RecentInteractionType.CALL_OUTGOING, 900),
            selectNewestInteraction(call(RecentCallType.OUTGOING, 900), message(800))
        )
    }

    @Test
    fun newerMissedCallBeatsOlderMessage() {
        assertEquals(
            interaction(RecentInteractionType.CALL_MISSED, 900),
            selectNewestInteraction(call(RecentCallType.MISSED, 900), message(800))
        )
    }

    @Test
    fun selectsOnlyMessage() {
        assertEquals(
            interaction(RecentInteractionType.MESSAGE, 900),
            selectNewestInteraction(null, message(900))
        )
    }

    @Test
    fun selectsOnlyCall() {
        assertEquals(
            interaction(RecentInteractionType.CALL_INCOMING, 900),
            selectNewestInteraction(call(RecentCallType.INCOMING, 900), null)
        )
    }

    @Test
    fun emptyInteractionsReturnNoResult() {
        assertNull(selectNewestInteraction(null, null))
    }

    @Test
    fun equalTimestampsPreferCall() {
        assertEquals(
            interaction(RecentInteractionType.CALL_OUTGOING, 900),
            selectNewestInteraction(call(RecentCallType.OUTGOING, 900), message(900))
        )
    }

    @Test
    fun oldMessageRemainsEligible() {
        val oldTimestamp = timestamp(2025, Calendar.JANUARY, 14)
        val cutoff = timestamp(2026, Calendar.MARCH, 8)

        assertEquals(
            message(oldTimestamp),
            finder(listOf(record("+123712345678", RecentMessageType.INBOX, oldTimestamp)))
                .findPreviousMessage("0712345678", cutoff, config)
        )
    }

    @Test
    fun skipsNewestUnrelatedMessage() {
        val records = listOf(
            record("+123798765432", RecentMessageType.INBOX, 900),
            record("+123712345678", RecentMessageType.SENT, 800)
        )

        assertEquals(
            message(800),
            finder(records).findPreviousMessage("0712345678", 1_000, config)
        )
    }

    @Test
    fun matchesDifferentMessageAddressFormats() {
        val records = listOf(
            record("001230712345678", RecentMessageType.INBOX, 800)
        )

        assertEquals(
            message(800),
            finder(records).findPreviousMessage("(0712) 345-678", 1_000, config)
        )
    }

    @Test
    fun matchesPrefixlessMessageAddressWhenEnabled() {
        val enabledConfig = config.copy(acceptWithoutPrefix = true)
        val records = listOf(record("712345678", RecentMessageType.SENT, 800))

        assertEquals(
            message(800),
            finder(records).findPreviousMessage("0712345678", 1_000, enabledConfig)
        )
    }

    @Test
    fun doesNotMatchPrefixlessMessageAddressWhenDisabled() {
        val records = listOf(record("712345678", RecentMessageType.SENT, 800))

        assertNull(finder(records).findPreviousMessage("0712345678", 1_000, config))
    }

    @Test
    fun invalidMessageAddressesNeverMatch() {
        val values = listOf(null, "", "   ", "0712ABC678", "1234")

        values.forEach { value ->
            assertNull(
                value,
                finder(listOf(record(value, RecentMessageType.INBOX, 800)))
                    .findPreviousMessage("0712345678", 1_000, config)
            )
        }
    }

    @Test
    fun hiddenMessageAddressesNeverMatch() {
        val values = listOf(
            "Unknown",
            "Private",
            "Private number",
            "Withheld",
            "Restricted",
            "Anonymous",
            "Unavailable"
        )

        values.forEach { value ->
            assertNull(
                value,
                finder(listOf(record(value, RecentMessageType.INBOX, 800)))
                    .findPreviousMessage("0712345678", 1_000, config)
            )
        }
    }

    @Test
    fun inboxMessageTypeIsSupported() {
        assertSame(RecentMessageType.INBOX, mapRecentMessageType(1, 1, 2))
    }

    @Test
    fun sentMessageTypeIsSupported() {
        assertSame(RecentMessageType.SENT, mapRecentMessageType(2, 1, 2))
    }

    @Test
    fun draftMessageTypeIsSkipped() {
        assertNull(mapRecentMessageType(3, 1, 2))
    }

    @Test
    fun outboxMessageTypeIsSkipped() {
        assertNull(mapRecentMessageType(4, 1, 2))
    }

    @Test
    fun failedMessageTypeIsSkipped() {
        assertNull(mapRecentMessageType(5, 1, 2))
    }

    @Test
    fun queuedMessageTypeIsSkipped() {
        assertNull(mapRecentMessageType(6, 1, 2))
    }

    @Test
    fun unknownMessageTypeIsSkipped() {
        assertNull(mapRecentMessageType(99, 1, 2))
    }

    @Test
    fun unsupportedNewestMessageContinuesToSupportedMatch() {
        val records = listOf(
            record("+123712345678", null, 900),
            record("+123712345678", RecentMessageType.INBOX, 800)
        )

        assertEquals(
            message(800),
            finder(records).findPreviousMessage("0712345678", 1_000, config)
        )
    }

    @Test
    fun currentAndFutureMessagesAreExcluded() {
        val records = listOf(
            record("+123712345678", RecentMessageType.INBOX, 1_100),
            record("+123712345678", RecentMessageType.SENT, 1_000),
            record("+123712345678", RecentMessageType.INBOX, 900)
        )

        assertEquals(
            message(900),
            finder(records).findPreviousMessage("0712345678", 1_000, config)
        )
    }

    @Test
    fun deniedMessagePermissionPerformsNoQuery() {
        var queryCount = 0
        val source = RecentMessageRecordSource { _, _, _ ->
            queryCount++
            null
        }

        val result = RecentMessageFinder(source, { true }, { false })
            .findPreviousMessage("0712345678", 1_000, config)

        assertNull(result)
        assertEquals(0, queryCount)
    }

    @Test
    fun unavailableMessagePermissionPreservesCallResult() {
        val call = call(RecentCallType.MISSED, 800)
        val message = RecentMessageFinder(
            recordSource = RecentMessageRecordSource { _, _, _ -> null },
            featureEnabled = { true },
            permissionGranted = { false }
        ).findPreviousMessage("0712345678", 1_000, config)

        assertEquals(
            interaction(RecentInteractionType.CALL_MISSED, 800),
            selectNewestInteraction(call, message)
        )
    }

    @Test
    fun disabledMessageHistoryPerformsNoQuery() {
        var queryCount = 0
        val source = RecentMessageRecordSource { _, _, _ ->
            queryCount++
            null
        }

        val result = RecentMessageFinder(source, { false }, { true })
            .findPreviousMessage("0712345678", 1_000, config)

        assertNull(result)
        assertEquals(0, queryCount)
    }

    @Test
    fun messageProviderFailureReturnsNoResult() {
        val source = RecentMessageRecordSource { _, _, _ -> throw IllegalStateException() }

        assertNull(
            RecentMessageFinder(source, { true }, { true })
                .findPreviousMessage("0712345678", 1_000, config)
        )
    }

    @Test
    fun messageSecurityFailureReturnsNoResult() {
        val source = RecentMessageRecordSource { _, _, _ -> throw SecurityException() }

        assertNull(
            RecentMessageFinder(source, { true }, { true })
                .findPreviousMessage("0712345678", 1_000, config)
        )
    }

    @Test
    fun emptyMessageHistoryReturnsNoResult() {
        assertNull(finder(emptyList()).findPreviousMessage("0712345678", 1_000, config))
    }

    @Test
    fun messageMapsToMessageInteractionType() {
        assertSame(
            RecentInteractionType.MESSAGE,
            selectNewestInteraction(null, message(800))?.type
        )
    }

    @Test
    fun incomingCallMapsToIncomingInteractionType() {
        assertSame(
            RecentInteractionType.CALL_INCOMING,
            selectNewestInteraction(call(RecentCallType.INCOMING, 800), null)?.type
        )
    }

    @Test
    fun outgoingCallMapsToOutgoingInteractionType() {
        assertSame(
            RecentInteractionType.CALL_OUTGOING,
            selectNewestInteraction(call(RecentCallType.OUTGOING, 800), null)?.type
        )
    }

    @Test
    fun missedCallMapsToMissedInteractionType() {
        assertSame(
            RecentInteractionType.CALL_MISSED,
            selectNewestInteraction(call(RecentCallType.MISSED, 800), null)?.type
        )
    }

    @Test
    fun selectionReturnsExactlyOneInteraction() {
        val selected = listOfNotNull(
            selectNewestInteraction(call(RecentCallType.INCOMING, 800), message(900))
        )

        assertEquals(1, selected.size)
        assertSame(RecentInteractionType.MESSAGE, selected.single().type)
    }

    private fun finder(records: List<RecentMessageRecord>): RecentMessageFinder =
        RecentMessageFinder(
            recordSource = RecentMessageRecordSource { _, shouldContinue, predicate ->
                records.firstOrNull { shouldContinue() && predicate(it) }
            },
            featureEnabled = { true },
            permissionGranted = { true }
        )

    private fun record(
        address: String?,
        type: RecentMessageType?,
        timestampMillis: Long
    ) = RecentMessageRecord(address, type, timestampMillis)

    private fun call(type: RecentCallType, timestampMillis: Long) =
        RecentCallInteraction(type, timestampMillis)

    private fun message(timestampMillis: Long) = RecentMessageInteraction(timestampMillis)

    private fun interaction(type: RecentInteractionType, timestampMillis: Long) =
        RecentInteraction(type, timestampMillis)

    private fun timestamp(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance(SimpleTimeZone(0, "test")).apply {
            clear()
            set(year, month, day)
        }.timeInMillis
}
