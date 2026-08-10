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
import com.rakibulcodes.callerinfo.shouldScheduleDeferredRetry
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    private val pendingQueueEpoch = AtomicLong(0)

    suspend fun getCallerInfo(rawNumber: String): CallerInfoEntity =
        getCallerInfoWithSource(rawNumber).callerInfo

    suspend fun getCallerInfoWithSource(
        rawNumber: String,
        onLocalResult: suspend (CallerLookupResult) -> Unit = {},
        requestStillValid: () -> Boolean = { true },
        retainDeferredRetryAfterRequestEnds: Boolean = false
    ): CallerLookupResult {
        val number = sanitizeNumber(rawNumber)
        if (number.isEmpty()) {
            return CallerLookupResult(
                errorEntity("", "Invalid Number"),
                CallerLookupSource.LOCAL
            )
        }
        val lookupEpoch = cacheEpoch.get()
        val lookupPendingQueueEpoch = pendingQueueEpoch.get()
        val local = safelyReadLocal(number)?.takeIf(::hasUsefulCallerInformation)

        if (local != null) {
            val localResult = CallerLookupResult(local, CallerLookupSource.LOCAL)
            if (CallerCachePolicy.isFresh(local.lastSuccessfullyUpdatedMillis, now())) {
                return localResult
            }

            if (requestStillValid()) onLocalResult(localResult)
            if (!isNetworkAvailable()) {
                if (shouldScheduleDeferredRetry(requestStillValid(), retainDeferredRetryAfterRequestEnds)) {
                    queueRetry(number, RemoteLookupFailure.CONNECTIVITY_UNAVAILABLE, lookupPendingQueueEpoch)
                }
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
                is RemoteLookupOutcome.Failure -> {
                    if (
                        shouldScheduleDeferredRetry(
                            requestStillValid(),
                            retainDeferredRetryAfterRequestEnds
                        ) && retryPolicyFor(remote.reason) != null
                    ) {
                        queueRetry(number, remote.reason, lookupPendingQueueEpoch)
                    }
                    if (remote.reason == RemoteLookupFailure.PERMANENT_NOT_FOUND) {
                        removePending(number)
                    }
                    localResult
                }
            }
        }

        if (!isNetworkAvailable()) {
            val retryScheduled =
                shouldScheduleDeferredRetry(requestStillValid(), retainDeferredRetryAfterRequestEnds) &&
                    queueRetry(number, RemoteLookupFailure.CONNECTIVITY_UNAVAILABLE, lookupPendingQueueEpoch)
            return CallerLookupResult(
                errorEntity(
                    number,
                    remoteLookupFailureMessage(RemoteLookupFailure.CONNECTIVITY_UNAVAILABLE)
                ),
                CallerLookupSource.LOCAL,
                retryScheduled = retryScheduled
            )
        }

        return when (val remote = sharedRemoteLookup(number)) {
            is RemoteLookupOutcome.Useful -> {
                val saveResult = persistUseful(number, remote.callerInfo, lookupEpoch)
                if (saveResult == SaveCallerResult.Saved) removePending(number)
                val persistenceRetryScheduled =
                    saveResult == SaveCallerResult.Failed &&
                        shouldScheduleDeferredRetry(requestStillValid(), retainDeferredRetryAfterRequestEnds) &&
                        queueRetry(number, RemoteLookupFailure.PERSISTENCE_FAILURE, lookupPendingQueueEpoch)
                if (requestStillValid()) {
                    val displayed = if (saveResult == SaveCallerResult.Failed) {
                        remote.callerInfo.copy(
                            error = remoteLookupFailureMessage(
                                RemoteLookupFailure.PERSISTENCE_FAILURE
                            )
                        )
                    } else {
                        remote.callerInfo
                    }
                    CallerLookupResult(
                        displayed,
                        CallerLookupSource.REMOTE,
                        retryScheduled = persistenceRetryScheduled
                    )
                } else {
                    CallerLookupResult(
                        errorEntity(number, "Lookup cancelled"),
                        CallerLookupSource.LOCAL
                    )
                }
            }
            is RemoteLookupOutcome.Failure -> {
                val retryScheduled = when {
                    remote.reason == RemoteLookupFailure.PERMANENT_NOT_FOUND -> {
                        removePending(number)
                        false
                    }
                    shouldScheduleDeferredRetry(requestStillValid(), retainDeferredRetryAfterRequestEnds) &&
                        retryPolicyFor(remote.reason) != null ->
                        queueRetry(number, remote.reason, lookupPendingQueueEpoch)
                    else -> false
                }
                CallerLookupResult(
                    errorEntity(number, remoteLookupFailureMessage(remote.reason)),
                    CallerLookupSource.REMOTE,
                    retryScheduled = retryScheduled
                )
            }
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

    suspend fun pendingLookupCount(): Int =
        try {
            db.pendingCallerLookupDao().count()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            0
        }

    suspend fun clearPendingLookups(): Boolean =
        try {
            storageMutex.withLock {
                db.pendingCallerLookupDao().clearAll()
                pendingQueueEpoch.incrementAndGet()
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }

    fun isInternetAvailable(): Boolean = isNetworkAvailable()

    suspend fun retryPendingLookupsNow(): Boolean {
        if (pendingLookupCount() == 0) return false
        OfflineLookupScheduler.enqueueUserRequested(context)
        return true
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
                            advancePending(
                                item,
                                currentTime,
                                retryPolicyFor(RemoteLookupFailure.PERSISTENCE_FAILURE)!!
                            )
                        }
                        SaveCallerResult.RejectedAfterUserClear -> {
                            // The clear transaction already removed the row.
                        }
                    }
                }
                is RemoteLookupOutcome.Failure -> {
                    val policy = retryPolicyFor(outcome.reason)
                    if (policy == null) {
                        pendingDao.delete(canonicalNumber)
                    } else {
                        if (
                            policy.incrementAttempt ||
                            outcome.reason == RemoteLookupFailure.PERSISTENCE_FAILURE
                        ) {
                            hadTransientFailure = true
                        }
                        advancePending(item, currentTime, policy)
                    }
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

    private suspend fun advancePending(
        item: PendingCallerLookupEntity,
        currentTime: Long,
        policy: RetryFailurePolicy
    ) {
        val attemptCount = item.attemptCount + if (policy.incrementAttempt) 1 else 0
        if (attemptCount >= CallerCachePolicy.MAXIMUM_ATTEMPTS) {
            db.pendingCallerLookupDao().delete(item.normalizedNumber)
        } else {
            val nextEligible = if (policy.incrementAttempt) {
                CallerCachePolicy.nextEligibleRetryMillis(currentTime, attemptCount)
            } else {
                currentTime + policy.delayMillis
            }
            db.pendingCallerLookupDao().updateAttempt(
                item.normalizedNumber,
                attemptCount,
                nextEligible
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

    private suspend fun queueRetry(
        number: String,
        failure: RemoteLookupFailure,
        expectedPendingQueueEpoch: Long
    ): Boolean {
        if (number.isBlank()) return false
        val policy = retryPolicyFor(failure) ?: return false
        return try {
            if (pendingQueueEpoch.get() != expectedPendingQueueEpoch) return false
            val currentTime = now()
            db.pendingCallerLookupDao().insertBounded(
                PendingCallerLookupEntity(
                    normalizedNumber = number,
                    createdTimestampMillis = currentTime,
                    nextEligibleRetryTimestampMillis = currentTime + policy.delayMillis,
                    attemptCount = 0
                ),
                CallerCachePolicy.MAXIMUM_PENDING_LOOKUPS
            )
            OfflineLookupScheduler.enqueue(context)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Caller presentation must remain independent of retry persistence.
            false
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
                RemoteBotTransactionCoordinator.run {
                    fetchFromTelegram(number)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: LookupTransactionTimeoutException) {
            RemoteLookupOutcome.Failure(RemoteLookupFailure.RESPONSE_TIMEOUT)
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
                return@withContext RemoteLookupOutcome.Failure(
                    RemoteLookupFailure.AUTHENTICATION_NOT_READY
                )
            }

            try {
                val botChat = telegramManager.prepareLookupChat()
                val chatId = botChat.id
                coroutineScope {
                    var requestIdentity: RemoteRequestIdentity? = null
                    try {
                        val content = TdApi.InputMessageText(
                            TdApi.FormattedText(number, null),
                            null,
                            true
                        )
                        val sentMessage = telegramManager.sendSuspend(
                            TdApi.SendMessage(chatId, null, null, null, null, content)
                        ) as? TdApi.Message ?: return@coroutineScope RemoteLookupOutcome.Failure(
                            RemoteLookupFailure.TEMPORARY_TRANSPORT_FAILURE
                        )
                        requestIdentity = RemoteRequestIdentity(chatId, sentMessage.id)
                        var correlatedSentMessageId = sentMessage.id
                        val response = awaitCorrelatedResponse(
                            timeoutMillis = LookupTimeouts.FINAL_RESPONSE_MILLIS,
                            next = {
                                fullMessageForUpdate(
                                    update = telegramManager.nextLookupUpdate(),
                                    chatId = chatId,
                                    sentMessageId = correlatedSentMessageId,
                                    onSendSucceeded = { update ->
                                        val currentIdentity = requestIdentity
                                            ?: return@fullMessageForUpdate
                                        val remapped = remapRemoteRequestIdentityAfterSendSuccess(
                                            current = currentIdentity,
                                            updateChatId = update.message.chatId,
                                            oldMessageId = update.oldMessageId,
                                            newMessageId = update.message.id
                                        ) ?: return@fullMessageForUpdate
                                        requestIdentity = remapped
                                        correlatedSentMessageId = remapped.messageId
                                    }
                                )
                            },
                            correlation = { message ->
                                correlateFinalResponse(
                                    message = message.toEnvelope(),
                                    expectedChatId = chatId,
                                    sentMessageId = correlatedSentMessageId,
                                    expectedCanonicalNumber = number,
                                    isQuarantinedReply = LATE_RESPONSE_QUARANTINE::contains,
                                    isSupportedFinalResponse = ::isFinalResponse
                                )
                            },
                            countsAsUncorrelated = { message ->
                                extractFormattedText(message.content)?.text
                                    ?.let(::isFinalResponse) == true
                            }
                        )
                        val responseMessage = when (response) {
                            is CorrelatedResponseResult.Accepted -> response.value
                            CorrelatedResponseResult.Timeout ->
                                return@coroutineScope RemoteLookupOutcome.Failure(
                                    RemoteLookupFailure.RESPONSE_TIMEOUT
                                )
                            CorrelatedResponseResult.Uncorrelated ->
                                return@coroutineScope RemoteLookupOutcome.Failure(
                                    RemoteLookupFailure.UNCORRELATED_RESPONSE
                                )
                        }

                        val formattedText = extractFormattedText(responseMessage.content)
                            ?: return@coroutineScope RemoteLookupOutcome.Failure(
                                RemoteLookupFailure.PARSING_OR_PROTOCOL_FAILURE
                            )
                        classifyParsedResponse(number, formattedText)
                    } finally {
                        requestIdentity?.let(LATE_RESPONSE_QUARANTINE::add)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: AuthenticationNotReadyException) {
                RemoteLookupOutcome.Failure(RemoteLookupFailure.AUTHENTICATION_NOT_READY)
            } catch (_: TdlibUnavailableException) {
                temporaryTransportOutcome()
            } catch (_: TdlibCommandTimeoutException) {
                temporaryTransportOutcome()
            } catch (failure: TdlibCommandFailureException) {
                when (failure.errorCode) {
                    401 -> RemoteLookupOutcome.Failure(
                        RemoteLookupFailure.AUTHENTICATION_NOT_READY
                    )
                    429 -> RemoteLookupOutcome.Failure(RemoteLookupFailure.RATE_LIMITED)
                    else -> temporaryTransportOutcome()
                }
            } catch (_: IOException) {
                temporaryTransportOutcome()
            } catch (_: Exception) {
                RemoteLookupOutcome.Failure(RemoteLookupFailure.PARSING_OR_PROTOCOL_FAILURE)
            }
        }

    private fun temporaryTransportOutcome(): RemoteLookupOutcome.Failure =
        RemoteLookupOutcome.Failure(
            if (isNetworkAvailable()) {
                RemoteLookupFailure.TEMPORARY_TRANSPORT_FAILURE
            } else {
                RemoteLookupFailure.CONNECTIVITY_UNAVAILABLE
            }
        )

    private suspend fun fullMessageForUpdate(
        update: TdApi.Object,
        chatId: Long,
        sentMessageId: Long,
        onSendSucceeded: (TdApi.UpdateMessageSendSucceeded) -> Unit
    ): TdApi.Message? = when (update) {
        is TdApi.UpdateMessageSendSucceeded -> {
            if (
                update.message.chatId == chatId &&
                update.oldMessageId == sentMessageId
            ) {
                onSendSucceeded(update)
            }
            null
        }
        is TdApi.UpdateMessageSendFailed -> {
            if (
                update.message.chatId == chatId &&
                update.oldMessageId == sentMessageId
            ) {
                throw TdlibCommandFailureException(update.error.code)
            }
            null
        }
        is TdApi.UpdateNewMessage -> update.message.takeIf { message ->
                message.chatId == chatId &&
                    !message.isOutgoing &&
                    message.id > sentMessageId
        }
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
        val text = extractFormattedText(content)?.text
        return BotMessageEnvelope(
            chatId = chatId,
            messageId = id,
            isOutgoing = isOutgoing,
            replyChatId = (reply as? TdApi.MessageReplyToMessage)?.chatId,
            replyMessageId = (reply as? TdApi.MessageReplyToMessage)?.messageId,
            hasUnsupportedReplyType =
                reply != null && reply !is TdApi.MessageReplyToMessage,
            dedicatedCanonicalNumber = text?.let(::extractDedicatedCanonicalNumber),
            text = text
        )
    }

    private fun extractDedicatedCanonicalNumber(text: String): String? {
        return com.rakibulcodes.callerinfo.data.extractDedicatedCanonicalNumber(
            responseText = text,
            normalize = ::sanitizeNumber
        )
    }

    private fun isFinalResponse(text: String): Boolean =
        LegacyWorkingResponseParser.isFinalResponse(text)


    private fun classifyParsedResponse(
        number: String,
        formattedText: TdApi.FormattedText
    ): RemoteLookupOutcome {
        // Deliberately reuse the last known live-working test.7 response parser.
        // Newer strict correlation has already accepted this message before we get here.
        val parsed = LegacyWorkingResponseParser.parse(number, formattedText, now())

        if (parsed.error?.startsWith("Daily Limit Exceeded", ignoreCase = true) == true ||
            parsed.error.equals("Limit Exceeded", ignoreCase = true)
        ) {
            return RemoteLookupOutcome.Failure(RemoteLookupFailure.RATE_LIMITED)
        }

        if (parsed.error.equals("Invalid Number", ignoreCase = true)) {
            return RemoteLookupOutcome.Failure(
                RemoteLookupFailure.PARSING_OR_PROTOCOL_FAILURE
            )
        }

        // Keep the post-test.7 safety improvement: a parser miss must never become
        // a persisted empty/Unknown identity. Optional Not Found fields are harmless
        // when another useful identity field was successfully parsed.
        if (hasUsefulCallerInformation(parsed)) {
            return RemoteLookupOutcome.Useful(parsed.copy(error = null))
        }

        if (formattedText.text.contains("Not Found", ignoreCase = true)) {
            return RemoteLookupOutcome.Failure(RemoteLookupFailure.PERMANENT_NOT_FOUND)
        }

        return RemoteLookupOutcome.Failure(RemoteLookupFailure.PARSING_OR_PROTOCOL_FAILURE)
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
        private val LATE_RESPONSE_QUARANTINE = LateResponseQuarantine(
            nowMillis = System::currentTimeMillis
        )
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
    data class Failure(val reason: RemoteLookupFailure) : RemoteLookupOutcome
}
