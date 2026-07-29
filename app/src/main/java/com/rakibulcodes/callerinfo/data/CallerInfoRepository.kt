package com.rakibulcodes.callerinfo.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.room.withTransaction
import com.rakibulcodes.callerinfo.CallerCachePolicy
import com.rakibulcodes.callerinfo.CallerLookupResult
import com.rakibulcodes.callerinfo.CallerLookupSource
import com.rakibulcodes.callerinfo.NumberFormattingPreferences
import com.rakibulcodes.callerinfo.OfflineLookupScheduler
import com.rakibulcodes.callerinfo.data.database.AppDatabase
import com.rakibulcodes.callerinfo.data.database.CallerInfoEntity
import com.rakibulcodes.callerinfo.data.database.PendingCallerLookupEntity
import com.rakibulcodes.callerinfo.hasUsefulCallerInformation
import com.rakibulcodes.callerinfo.normalizePhoneNumber
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi

class CallerInfoRepository(private val context: Context) {
    private val db = AppDatabase.getDatabase(context)
    private val telegramManager = TelegramManager.getInstance(context)
    private val numberFormattingPreferences = NumberFormattingPreferences.getInstance(context)
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val singleFlight = SameKeySingleFlight<String, RemoteLookupOutcome>(refreshScope)
    private val storageMutex = Mutex()
    private val cacheEpoch = AtomicLong(0)

    suspend fun getCallerInfo(rawNumber: String): CallerInfoEntity =
        getCallerInfoWithSource(rawNumber).callerInfo

    suspend fun getCallerInfoWithSource(
        rawNumber: String,
        onLocalResult: suspend (CallerLookupResult) -> Unit = {},
        requestStillValid: () -> Boolean = { true }
    ): CallerLookupResult {
        val number = sanitizeNumber(rawNumber)
        if (number.isEmpty()) {
            return CallerLookupResult(
                errorEntity("", "Invalid Number"),
                CallerLookupSource.LOCAL
            )
        }
        val lookupEpoch = cacheEpoch.get()
        val local = safelyReadLocal(number)?.takeIf(::hasUsefulCallerInformation)

        if (local != null) {
            val localResult = CallerLookupResult(local, CallerLookupSource.LOCAL)
            if (CallerCachePolicy.isFresh(local.lastSuccessfullyUpdatedMillis, now())) {
                return localResult
            }

            if (requestStillValid()) onLocalResult(localResult)
            if (!isNetworkAvailable()) {
                if (requestStillValid()) queueConnectivityRetry(number)
                return localResult
            }

            return when (val remote = sharedRemoteLookup(number)) {
                is RemoteLookupOutcome.Useful -> {
                    val saveResult = persistUseful(number, remote.callerInfo, lookupEpoch)
                    if (saveResult == SaveCallerResult.Saved) removePending(number)
                    if (requestStillValid()) {
                        CallerLookupResult(remote.callerInfo, CallerLookupSource.REMOTE)
                    } else {
                        localResult
                    }
                }
                RemoteLookupOutcome.PermanentNotFound -> {
                    removePending(number)
                    localResult
                }
                RemoteLookupOutcome.TemporarilyUnavailable -> {
                    if (requestStillValid()) queueConnectivityRetry(number)
                    localResult
                }
                RemoteLookupOutcome.AuthenticationFailure,
                RemoteLookupOutcome.ParsingFailure,
                RemoteLookupOutcome.Failure -> localResult
            }
        }

        if (!isNetworkAvailable()) {
            if (requestStillValid()) queueConnectivityRetry(number)
            return CallerLookupResult(
                errorEntity(number, "No internet connection"),
                CallerLookupSource.LOCAL
            )
        }

        return when (val remote = sharedRemoteLookup(number)) {
            is RemoteLookupOutcome.Useful -> {
                val saveResult = persistUseful(number, remote.callerInfo, lookupEpoch)
                if (saveResult == SaveCallerResult.Saved) removePending(number)
                if (requestStillValid()) {
                    CallerLookupResult(remote.callerInfo, CallerLookupSource.REMOTE)
                } else {
                    CallerLookupResult(
                        errorEntity(number, "Lookup cancelled"),
                        CallerLookupSource.LOCAL
                    )
                }
            }
            RemoteLookupOutcome.PermanentNotFound -> {
                removePending(number)
                CallerLookupResult(errorEntity(number, "Not found"), CallerLookupSource.REMOTE)
            }
            RemoteLookupOutcome.TemporarilyUnavailable -> {
                if (requestStillValid()) queueConnectivityRetry(number)
                CallerLookupResult(
                    errorEntity(number, "No internet connection"),
                    CallerLookupSource.REMOTE
                )
            }
            RemoteLookupOutcome.AuthenticationFailure ->
                CallerLookupResult(
                    errorEntity(number, "Service not connected"),
                    CallerLookupSource.REMOTE
                )
            RemoteLookupOutcome.ParsingFailure ->
                CallerLookupResult(
                    errorEntity(number, "Unable to read lookup response"),
                    CallerLookupSource.REMOTE
                )
            RemoteLookupOutcome.Failure ->
                CallerLookupResult(errorEntity(number, "Lookup failed"), CallerLookupSource.REMOTE)
        }
    }

    suspend fun getAllHistory(): List<CallerInfoEntity> =
        db.callerInfoDao().getAllCallerInfo()

    suspend fun clearHistory() {
        db.callerInfoDao().clearAll()
    }

    suspend fun clearSavedCallerInformation(): Boolean =
        try {
            storageMutex.withLock {
                db.withTransaction {
                    db.callerInfoDao().clearAll()
                    db.pendingCallerLookupDao().clearAll()
                }
                cacheEpoch.incrementAndGet()
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }

    suspend fun deleteHistoryItem(number: String) {
        val normalizedNumber = sanitizeNumber(number)
        if (normalizedNumber.isNotEmpty()) {
            db.callerInfoDao().deleteByNumber(normalizedNumber)
        }
    }

    fun sanitizeNumber(input: String?): String =
        normalizePhoneNumber(input, numberFormattingPreferences.getConfig())

    suspend fun processPendingLookups(): PendingLookupRunResult = withContext(Dispatchers.IO) {
        val currentTime = now()
        val pendingDao = db.pendingCallerLookupDao()
        pendingDao.deleteExpiredOrExhausted(
            currentTime - CallerCachePolicy.RETRY_EXPIRY_MILLIS,
            CallerCachePolicy.MAXIMUM_ATTEMPTS
        )

        if (!isNetworkAvailable()) {
            return@withContext pendingRunResult(
                processedCount = 0,
                failure = PendingRunFailure.TRANSIENT,
                nowMillis = currentTime
            )
        }

        val eligible = pendingDao.eligible(
            currentTime,
            CallerCachePolicy.MAXIMUM_ITEMS_PER_RUN
        )
        var processed = 0
        var hadTransientFailure = false

        for (item in eligible) {
            currentCoroutineContext().ensureActive()
            val canonicalNumber = sanitizeNumber(item.normalizedNumber)
            if (canonicalNumber.isEmpty() || canonicalNumber != item.normalizedNumber) {
                pendingDao.delete(item.normalizedNumber)
                continue
            }

            processed++
            val lookupEpoch = cacheEpoch.get()
            when (val outcome = sharedRemoteLookup(canonicalNumber)) {
                is RemoteLookupOutcome.Useful -> {
                    when (
                        persistUseful(
                            canonicalNumber,
                            outcome.callerInfo,
                            lookupEpoch,
                            removePendingAfterSave = true
                        )
                    ) {
                        SaveCallerResult.Saved -> Unit
                        SaveCallerResult.Failed -> {
                            hadTransientFailure = true
                            advancePendingAttempt(item, currentTime)
                        }
                        SaveCallerResult.RejectedAfterUserClear -> {
                            // The clear transaction already removed the row.
                        }
                    }
                }
                RemoteLookupOutcome.PermanentNotFound -> {
                    pendingDao.delete(canonicalNumber)
                }
                RemoteLookupOutcome.TemporarilyUnavailable,
                RemoteLookupOutcome.AuthenticationFailure,
                RemoteLookupOutcome.ParsingFailure,
                RemoteLookupOutcome.Failure -> {
                    hadTransientFailure = true
                    advancePendingAttempt(item, currentTime)
                }
            }
        }

        pendingRunResult(
            processedCount = processed,
            failure = if (hadTransientFailure) {
                PendingRunFailure.TRANSIENT
            } else {
                PendingRunFailure.NONE
            },
            nowMillis = now()
        )
    }

    private suspend fun pendingRunResult(
        processedCount: Int,
        failure: PendingRunFailure,
        nowMillis: Long
    ): PendingLookupRunResult {
        val pendingDao = db.pendingCallerLookupDao()
        return PendingLookupRunResult(
            processedCount = processedCount,
            failure = failure,
            eligibleNowCount = pendingDao.eligibleCount(nowMillis),
            earliestFutureEligibleMillis = pendingDao.earliestFutureEligible(nowMillis)
        )
    }

    private suspend fun advancePendingAttempt(
        item: PendingCallerLookupEntity,
        currentTime: Long
    ) {
        val attemptCount = item.attemptCount + 1
        if (attemptCount >= CallerCachePolicy.MAXIMUM_ATTEMPTS) {
            db.pendingCallerLookupDao().delete(item.normalizedNumber)
        } else {
            db.pendingCallerLookupDao().updateAttempt(
                item.normalizedNumber,
                attemptCount,
                CallerCachePolicy.nextEligibleRetryMillis(currentTime, attemptCount)
            )
        }
    }

    private suspend fun safelyReadLocal(number: String): CallerInfoEntity? =
        try {
            db.callerInfoDao().getCallerInfo(number)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }

    private suspend fun persistUseful(
        number: String,
        callerInfo: CallerInfoEntity,
        expectedCacheEpoch: Long,
        removePendingAfterSave: Boolean = false
    ): SaveCallerResult {
        if (
            !isCallerRecordSafeToPersist(
                expectedCanonicalNumber = number,
                recordCanonicalNumber = callerInfo.number,
                hasUsefulIdentification = hasUsefulCallerInformation(callerInfo)
            )
        ) {
            return SaveCallerResult.Failed
        }
        val savedAt = now()
        val saved = callerInfo.copy(
            number = number,
            error = null,
            timestamp = savedAt,
            lastSuccessfullyUpdatedMillis = savedAt
        )

        return storageMutex.withLock {
            saveCallerRecord(
                expectedCacheEpoch = expectedCacheEpoch,
                currentCacheEpoch = cacheEpoch::get
            ) {
                if (removePendingAfterSave) {
                    db.withTransaction {
                        writeSavedCaller(saved)
                        db.pendingCallerLookupDao().delete(number)
                    }
                } else {
                    writeSavedCaller(saved)
                }
            }
        }
    }

    private suspend fun writeSavedCaller(saved: CallerInfoEntity) {
        val prefs = context.getSharedPreferences("Settings", Context.MODE_PRIVATE)
        val limitValue = prefs.getString("max_history_size", "1000")
        if (limitValue == "Unlimited") {
            db.callerInfoDao().insertCallerInfo(saved)
        } else {
            db.callerInfoDao().insertAndTrim(
                saved,
                limitValue?.toIntOrNull() ?: 1000
            )
        }
    }

    private suspend fun queueConnectivityRetry(number: String) {
        if (number.isBlank()) return
        try {
            val currentTime = now()
            db.pendingCallerLookupDao().insertBounded(
                PendingCallerLookupEntity(
                    normalizedNumber = number,
                    createdTimestampMillis = currentTime,
                    nextEligibleRetryTimestampMillis = currentTime,
                    attemptCount = 0
                ),
                CallerCachePolicy.MAXIMUM_PENDING_LOOKUPS
            )
            OfflineLookupScheduler.enqueue(context)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Caller presentation must remain independent of retry persistence.
        }
    }

    private suspend fun removePending(number: String) {
        try {
            db.pendingCallerLookupDao().delete(number)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A later bounded run can remove a completed permanent outcome.
        }
    }

    private suspend fun sharedRemoteLookup(number: String): RemoteLookupOutcome =
        try {
            singleFlight.run(number) {
                REMOTE_TRANSACTION_COORDINATOR.run {
                    fetchFromTelegram(number)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: LookupTransactionTimeoutException) {
            RemoteLookupOutcome.TemporarilyUnavailable
        }

    private fun isNetworkAvailable(): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private suspend fun fetchFromTelegram(number: String): RemoteLookupOutcome =
        withContext(Dispatchers.IO) {
            if (!telegramManager.isReady()) {
                return@withContext RemoteLookupOutcome.AuthenticationFailure
            }

            try {
                telegramManager.ensureJoined("true_caller", isBot = false)
                val botChat = telegramManager.ensureJoined("TrueCalleRobot", isBot = true)
                val chatId = botChat.id
                coroutineScope {
                    val receivedUpdates = Channel<TdApi.Object>(Channel.UNLIMITED)
                    val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                        telegramManager.updates.collect(receivedUpdates::send)
                    }
                    try {
                        val content = TdApi.InputMessageText(
                            TdApi.FormattedText(number, null),
                            null,
                            true
                        )
                        val sentMessage = telegramManager.sendSuspend(
                            TdApi.SendMessage(chatId, null, null, null, null, content)
                        ) as? TdApi.Message ?: return@coroutineScope RemoteLookupOutcome.Failure
                        val responseMessage = awaitMatchingResponse(
                            timeoutMillis = LookupTimeouts.FINAL_RESPONSE_MILLIS,
                            next = {
                                fullMessageForUpdate(
                                    update = receivedUpdates.receive(),
                                    chatId = chatId,
                                    sentMessageId = sentMessage.id
                                )
                            },
                            matches = { message ->
                                isCorrelatedFinalResponse(
                                    message = message.toEnvelope(),
                                    expectedChatId = chatId,
                                    sentMessageId = sentMessage.id,
                                    isSupportedFinalResponse = ::isFinalResponse
                                )
                            }
                        ) ?: return@coroutineScope RemoteLookupOutcome.TemporarilyUnavailable

                        val formattedText = extractFormattedText(responseMessage.content)
                            ?: return@coroutineScope RemoteLookupOutcome.ParsingFailure
                        classifyParsedResponse(number, formattedText)
                    } finally {
                        collector.cancelAndJoin()
                        receivedUpdates.close()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: TdlibUnavailableException) {
                RemoteLookupOutcome.TemporarilyUnavailable
            } catch (_: TdlibCommandTimeoutException) {
                RemoteLookupOutcome.TemporarilyUnavailable
            } catch (failure: TdlibCommandFailureException) {
                if (failure.errorCode == 401) {
                    RemoteLookupOutcome.AuthenticationFailure
                } else {
                    RemoteLookupOutcome.TemporarilyUnavailable
                }
            } catch (_: IOException) {
                RemoteLookupOutcome.TemporarilyUnavailable
            } catch (_: Exception) {
                RemoteLookupOutcome.Failure
            }
        }

    private suspend fun fullMessageForUpdate(
        update: TdApi.Object,
        chatId: Long,
        sentMessageId: Long
    ): TdApi.Message? = when (update) {
        is TdApi.UpdateNewMessage -> update.message
        is TdApi.UpdateMessageContent -> {
            if (update.chatId != chatId || update.messageId <= sentMessageId) {
                null
            } else {
                telegramManager.sendSuspend(
                    TdApi.GetMessage(update.chatId, update.messageId)
                ) as? TdApi.Message
            }
        }
        else -> null
    }

    private fun TdApi.Message.toEnvelope(): BotMessageEnvelope {
        val reply = replyTo
        return BotMessageEnvelope(
            chatId = chatId,
            messageId = id,
            isOutgoing = isOutgoing,
            replyChatId = (reply as? TdApi.MessageReplyToMessage)?.chatId,
            replyMessageId = (reply as? TdApi.MessageReplyToMessage)?.messageId,
            hasUnsupportedReplyType =
                reply != null && reply !is TdApi.MessageReplyToMessage,
            text = extractFormattedText(content)?.text
        )
    }

    private fun isFinalResponse(text: String): Boolean =
        !text.contains("Searching...", ignoreCase = true) &&
            listOf(
                "Country:",
                "Not Found",
                "exceeded",
                "Says:",
                "Name:",
                "invalid number",
                "Oops"
            ).any { text.contains(it, ignoreCase = true) }

    private fun classifyParsedResponse(
        number: String,
        formattedText: TdApi.FormattedText
    ): RemoteLookupOutcome {
        val responseText = formattedText.toHtml()
        if (responseText.contains("Not Found", ignoreCase = true)) {
            return RemoteLookupOutcome.PermanentNotFound
        }
        if (
            responseText.contains("invalid number", ignoreCase = true) ||
            responseText.contains("Oops", ignoreCase = true)
        ) {
            return RemoteLookupOutcome.ParsingFailure
        }
        if (
            responseText.contains("exceeded your daily search limit", ignoreCase = true) ||
            responseText.contains("Limit exceeded", ignoreCase = true)
        ) {
            return RemoteLookupOutcome.AuthenticationFailure
        }

        val parsed = parseUsefulResponse(number, responseText)
        return if (hasUsefulCallerInformation(parsed)) {
            RemoteLookupOutcome.Useful(parsed)
        } else {
            RemoteLookupOutcome.ParsingFailure
        }
    }

    private fun extractFormattedText(content: TdApi.MessageContent): TdApi.FormattedText? =
        when (content) {
            is TdApi.MessageText -> content.text
            is TdApi.MessagePhoto -> content.caption
            is TdApi.MessageVideo -> content.caption
            is TdApi.MessageAnimation -> content.caption
            is TdApi.MessageDocument -> content.caption
            else -> null
        }

    private fun TdApi.FormattedText.toHtml(): String {
        val insertions = mutableListOf<Pair<Int, String>>()
        entities?.forEach { entity ->
            val tag = when (entity.type) {
                is TdApi.TextEntityTypeBold -> "strong"
                is TdApi.TextEntityTypeCode -> "code"
                else -> null
            }
            if (tag != null) {
                insertions += entity.offset to "<$tag>"
                insertions += entity.offset + entity.length to "</$tag>"
            }
        }
        var resultText = text
        insertions.sortedByDescending { it.first }.forEach { (offset, tag) ->
            if (offset in 0..resultText.length) {
                resultText =
                    resultText.substring(0, offset) + tag + resultText.substring(offset)
            }
        }
        return resultText
    }

    private fun parseUsefulResponse(number: String, responseText: String): CallerInfoEntity {
        val names = linkedSetOf<String>()
        var carrier: String? = null
        var email: String? = null
        var location: String? = null
        var address1: String? = null
        var address2: String? = null
        val country = Regex(
            "Country:\\s*([^<]+)(?:<\\/strong>)?",
            RegexOption.IGNORE_CASE
        ).find(responseText)?.groupValues?.get(1)?.trim()

        val keyValues =
            Regex("<strong>([^<]+?):\\s*<\\/strong>\\s*<code>(.*?)<\\/code>")
                .findAll(responseText)
        keyValues.forEach { match ->
            val key = match.groupValues[1].trim().lowercase().replace(Regex("\\s"), "_")
            val value = match.groupValues[2].replace(Regex("<[^>]*>?"), "").trim()
            if (value.isEmpty() || value.equals("Not Found", ignoreCase = true)) return@forEach
            when (key) {
                "name" -> names += value
                "carrier" -> if (carrier == null) carrier = value
                "email" -> if (email == null) email = value
                "location" -> if (location == null) location = value
                "address1", "address_1" -> if (address1 == null) address1 = value
                "address2", "address_2" -> if (address2 == null) address2 = value
            }
        }

        val updated = now()
        return CallerInfoEntity(
            number = number,
            country = country,
            name = names.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "Unknown",
            carrier = carrier,
            email = email,
            location = location,
            address1 = address1,
            address2 = address2,
            error = null,
            timestamp = updated,
            lastSuccessfullyUpdatedMillis = updated
        )
    }

    private fun errorEntity(number: String, message: String) = CallerInfoEntity(
        number = number,
        country = null,
        name = null,
        carrier = null,
        email = null,
        location = null,
        address1 = null,
        address2 = null,
        error = message
    )

    private fun now(): Long = System.currentTimeMillis()

    companion object {
        private val REMOTE_TRANSACTION_COORDINATOR = RemoteLookupTransactionCoordinator()

        @Volatile
        private var INSTANCE: CallerInfoRepository? = null

        fun getInstance(context: Context): CallerInfoRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: CallerInfoRepository(context.applicationContext).also {
                    INSTANCE = it
                }
            }
    }
}

private sealed interface RemoteLookupOutcome {
    data class Useful(val callerInfo: CallerInfoEntity) : RemoteLookupOutcome
    data object PermanentNotFound : RemoteLookupOutcome
    data object TemporarilyUnavailable : RemoteLookupOutcome
    data object AuthenticationFailure : RemoteLookupOutcome
    data object ParsingFailure : RemoteLookupOutcome
    data object Failure : RemoteLookupOutcome
}
