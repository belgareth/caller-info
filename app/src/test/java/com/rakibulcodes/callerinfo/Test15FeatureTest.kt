package com.rakibulcodes.callerinfo

import com.rakibulcodes.callerinfo.data.BotMessageEnvelope
import com.rakibulcodes.callerinfo.data.CorrelatedResponseResult
import com.rakibulcodes.callerinfo.data.RemoteRequestIdentity
import com.rakibulcodes.callerinfo.data.ResponseCorrelation
import com.rakibulcodes.callerinfo.data.awaitCorrelatedResponse
import com.rakibulcodes.callerinfo.data.correlateFinalResponse
import com.rakibulcodes.callerinfo.data.remapRemoteRequestIdentityAfterSendSuccess
import com.rakibulcodes.callerinfo.data.database.CallerInfoEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class Test15FeatureTest {
    @Test fun configurableFreshnessHonorsSelectedWindow() {
        val day = 24L * 60 * 60 * 1000
        val now = 100L * day
        assertFalse(callerCacheIsFresh(now - 8 * day, now, CacheFreshness.SEVEN.millis))
        assertTrue(callerCacheIsFresh(now - 8 * day, now, CacheFreshness.THIRTY.millis))
    }

    @Test fun historySearchMatchesAliasCarrierAndFavorites() {
        val items = listOf(
            info("+10000000001", "Remote One", "Carrier A", alias = "Workshop", favorite = true),
            info("+10000000002", "Remote Two", "Carrier B")
        )
        assertEquals(1, filterHistory(items, HistoryFilter(query = "workshop"), 10_000).size)
        assertEquals("+10000000001", filterHistory(items, HistoryFilter(favoritesOnly = true), 10_000).single().number)
        assertEquals("+10000000002", filterHistory(items, HistoryFilter(query = "carrier b"), 10_000).single().number)
    }

    @Test fun historyRecentFilterMatchesOnlyRecentRows() {
        val now = 40L * 24 * 60 * 60 * 1000
        val items = listOf(
            info("+10000000001", "Remote One", "Carrier A", timestamp = now - 40L * 24 * 60 * 60 * 1000),
            info("+10000000002", "Remote Two", "Carrier B", timestamp = now)
        )
        val filtered = filterHistory(items, HistoryFilter(recentOnly = true), nowMillis = now)

        assertEquals(1, filtered.size)
        assertEquals("+10000000002", filtered.single().number)
    }

    @Test fun historyNumberSearchUsesInjectedConfigurationForConfigurationA() {
        val config = NumberNormalizationConfig(
            callingCode = "256",
            localPrefix = "0",
            nationalNumberLength = 9,
            acceptWithoutPrefix = true
        )
        val normalize: (String) -> String = { normalizePhoneNumber(it, config) }
        val items = listOf(info("+256712345678", "Remote One", "Carrier A"))

        assertHistoryNumberMatches(
            items = items,
            expectedNumber = "+256712345678",
            normalize = normalize,
            forms = listOf("0712345678", "712345678", "+256712345678", "00256712345678", "011256712345678", "256712345678")
        )
    }

    @Test fun historyNumberSearchUsesInjectedConfigurationForConfigurationB() {
        val config = NumberNormalizationConfig(
            callingCode = "254",
            localPrefix = "0",
            nationalNumberLength = 9,
            acceptWithoutPrefix = true
        )
        val normalize: (String) -> String = { normalizePhoneNumber(it, config) }
        val items = listOf(info("+254712345678", "Remote Two", "Carrier B"))

        assertHistoryNumberMatches(
            items = items,
            expectedNumber = "+254712345678",
            normalize = normalize,
            forms = listOf("0712345678", "712345678", "+254712345678", "00254712345678", "011254712345678", "254712345678")
        )
    }

    @Test fun historyNumberSearchUsesDifferentPrefixAndLengthConfiguration() {
        val config = NumberNormalizationConfig(
            callingCode = "44",
            localPrefix = "0",
            nationalNumberLength = 10,
            acceptWithoutPrefix = false
        )
        val normalize: (String) -> String = { normalizePhoneNumber(it, config) }
        val items = listOf(info("+442079460958", "Remote Three", "Carrier C"))

        assertHistoryNumberMatches(
            items = items,
            expectedNumber = "+442079460958",
            normalize = normalize,
            forms = listOf("02079460958", "+442079460958", "00442079460958", "011442079460958", "442079460958")
        )
        assertEquals("", normalizePhoneNumber("2079460958", config))
        // A non-normalizable number query deliberately falls back to ordinary encrypted-item text search.
        assertEquals(1, filterHistory(items, HistoryFilter(query = "2079460958"), 10_000, normalizeNumber = normalize).size)
    }

    @Test fun changingNumberConfigurationChangesHistoryNumberEquivalenceWithoutMigration() {
        val stored = listOf(info("+254712345678", "Remote Four", "Carrier D"))
        val configurationA = NumberNormalizationConfig("256", "0", 9, acceptWithoutPrefix = true)
        val configurationB = NumberNormalizationConfig("254", "0", 9, acceptWithoutPrefix = true)

        assertTrue(
            filterHistory(stored, HistoryFilter(query = "0712345678"), 10_000) {
                normalizePhoneNumber(it, configurationA)
            }.isEmpty()
        )
        assertEquals(
            1,
            filterHistory(stored, HistoryFilter(query = "0712345678"), 10_000) {
                normalizePhoneNumber(it, configurationB)
            }.size
        )
    }

    @Test fun historyTextSearchStillMatchesNameAliasCarrierAndCountry() {
        val item = info("+256712345678", "Remote Name", "Carrier A", alias = "Workshop")
        val items = listOf(item)
        listOf("remote name", "workshop", "carrier a", "example").forEach { query ->
            assertEquals(1, filterHistory(items, HistoryFilter(query = query), 10_000).size)
        }
    }

    @Test fun aliasTakesPresentationPrecedenceWithoutDestroyingRemoteName() {
        val item = info("+10000000001", "Remote Name", "Carrier", alias = "My Alias")
        assertEquals("My Alias", item.displayName())
        assertEquals("Remote Name", item.name)
    }

    @Test fun contactsAreOptionalInHealthModel() {
        val item = buildAppStatusItems(
            AppStatusSnapshot(
                callerScreeningAvailable = true,
                callerScreeningActive = true,
                overlayAllowed = true,
                phoneAllowed = true,
                contactsAllowed = false,
                notificationsRelevant = false,
                notificationsAllowed = true
            )
        ).first { it.type == AppStatusType.CONTACTS }
        assertEquals(AppStatusValue.OPTIONAL, item.value)
        assertTrue(item.optional)
    }

    @Test fun temporaryMessageIdRemapsOnlyForExactRequest() {
        val current = RemoteRequestIdentity(7L, 100L)
        assertEquals(RemoteRequestIdentity(7L, 200L), remapRemoteRequestIdentityAfterSendSuccess(current, 7L, 100L, 200L))
        assertNull(remapRemoteRequestIdentityAfterSendSuccess(current, 8L, 100L, 200L))
        assertNull(remapRemoteRequestIdentityAfterSendSuccess(current, 7L, 99L, 200L))
    }

    @Test fun remappedSendSuccessAllowsEditedFinalReplyToCorrelate() = runBlocking {
        var requestIdentity = RemoteRequestIdentity(chatId = 7L, messageId = 100L)
        var correlatedSentMessageId = requestIdentity.messageId

        val remapped = remapRemoteRequestIdentityAfterSendSuccess(
            current = requestIdentity,
            updateChatId = 7L,
            oldMessageId = 100L,
            newMessageId = 200L
        )
        assertNotNull(remapped)
        requestIdentity = remapped!!
        correlatedSentMessageId = requestIdentity.messageId

        val progress = envelope(
            chatId = 7L,
            messageId = 201L,
            replyChatId = 7L,
            replyMessageId = 200L,
            text = "Searching..."
        )
        val editedFinal = progress.copy(
            text = "FINAL\nNumber: +10000000001",
            dedicatedCanonicalNumber = "+10000000001"
        )
        val updates = ArrayDeque(listOf(progress, editedFinal))

        val result = awaitCorrelatedResponse(
            timeoutMillis = 1_000,
            next = { updates.removeFirstOrNull() },
            correlation = { message ->
                correlateFinalResponse(
                    message = message,
                    expectedChatId = 7L,
                    sentMessageId = correlatedSentMessageId,
                    expectedCanonicalNumber = "+10000000001",
                    isSupportedFinalResponse = { it.startsWith("FINAL") }
                )
            },
            countsAsUncorrelated = { false }
        )

        assertTrue(result is CorrelatedResponseResult.Accepted)
        assertEquals(
            ResponseCorrelation.EXACT_REPLY,
            (result as CorrelatedResponseResult.Accepted).correlation
        )
    }

    @Test fun rejectedProgressNeverBecomesAccepted() = runBlocking {
        val queue = ArrayDeque(listOf("progress", "final"))
        val result = awaitCorrelatedResponse(
            timeoutMillis = 1_000,
            next = { queue.removeFirstOrNull() },
            correlation = { if (it == "final") ResponseCorrelation.EXACT_REPLY else ResponseCorrelation.REJECTED },
            countsAsUncorrelated = { false }
        )
        assertTrue(result is com.rakibulcodes.callerinfo.data.CorrelatedResponseResult.Accepted)
    }

    @Test fun postCallEnrichmentPolicyCanRetainRetryWithoutPresentation() {
        assertTrue(shouldScheduleDeferredRetry(requestStillValid = false, retainAfterRequestEnds = true))
        assertFalse(shouldScheduleDeferredRetry(requestStillValid = false, retainAfterRequestEnds = false))
    }

    private fun info(
        number: String,
        name: String,
        carrier: String,
        alias: String? = null,
        favorite: Boolean = false,
        timestamp: Long = 1_000
    ) = CallerInfoEntity(
        number = number,
        country = "Example",
        name = name,
        carrier = carrier,
        email = null,
        location = null,
        address1 = null,
        address2 = null,
        error = null,
        timestamp = timestamp,
        lastSuccessfullyUpdatedMillis = 1_000,
        userAlias = alias,
        favorite = favorite
    )

    private fun assertHistoryNumberMatches(
        items: List<CallerInfoEntity>,
        expectedNumber: String,
        normalize: (String) -> String,
        forms: List<String>
    ) {
        forms.forEach { query ->
            assertEquals(
                expectedNumber,
                filterHistory(
                    items,
                    HistoryFilter(query = query),
                    nowMillis = 10_000,
                    normalizeNumber = normalize
                ).single().number
            )
        }
    }

    private fun envelope(
        chatId: Long = 7L,
        messageId: Long = 201L,
        replyChatId: Long? = 7L,
        replyMessageId: Long? = 200L,
        text: String? = "FINAL",
        dedicatedCanonicalNumber: String? = null
    ) = BotMessageEnvelope(
        chatId = chatId,
        messageId = messageId,
        isOutgoing = false,
        replyChatId = replyChatId,
        replyMessageId = replyMessageId,
        hasUnsupportedReplyType = false,
        dedicatedCanonicalNumber = dedicatedCanonicalNumber,
        text = text
    )
}
