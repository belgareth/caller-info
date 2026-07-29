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
import com.rakibulcodes.callerinfo.hasUsefulCallerInformation
import com.rakibulcodes.callerinfo.normalizePhoneNumber
import com.rakibulcodes.callerinfo.data.database.AppDatabase
import com.rakibulcodes.callerinfo.data.database.CallerInfoEntity
import com.rakibulcodes.callerinfo.data.database.PendingCallerLookupEntity
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi

class CallerInfoRepository(private val context: Context) {
    private val db = AppDatabase.getDatabase(context)
    private val telegramManager = TelegramManager.getInstance(context)
    private val numberFormattingPreferences = NumberFormattingPreferences.getInstance(context)
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlightRefreshes = ConcurrentHashMap<String, Deferred<RemoteLookupOutcome>>()
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

            onLocalResult(localResult)
            if (!isNetworkAvailable()) {
                if (requestStillValid()) {
                    queueConnectivityRetry(number)
                }
                return localResult
            }

            return when (val remote = sharedRemoteLookup(number)) {
                is RemoteLookupOutcome.Useful -> {
                    if (canAcceptResult(number, remote.callerInfo, requestStillValid)) {
                        safelySaveUseful(number, remote.callerInfo, lookupEpoch)
                        removePending(number)
                        CallerLookupResult(remote.callerInfo, CallerLookupSource.REMOTE)
                    } else {
                        localResult
                    }
                }
                RemoteLookupOutcome.TemporarilyUnavailable -> {
                    if (requestStillValid()) {
                        queueConnectivityRetry(number)
                    }
                    localResult
                }
                RemoteLookupOutcome.PermanentNotFound,
                RemoteLookupOutcome.AuthenticationFailure,
                RemoteLookupOutcome.ParsingFailure,
                RemoteLookupOutcome.Failure -> {
                    removePending(number)
                    localResult
                }
                RemoteLookupOutcome.Cancelled -> localResult
            }
        }

        if (!isNetworkAvailable()) {
            if (requestStillValid()) {
                queueConnectivityRetry(number)
            }
            return CallerLookupResult(
                errorEntity(number, "No internet connection"),
                CallerLookupSource.LOCAL
            )
        }

        return when (val remote = sharedRemoteLookup(number)) {
            is RemoteLookupOutcome.Useful -> {
                if (canAcceptResult(number, remote.callerInfo, requestStillValid)) {
                    safelySaveUseful(number, remote.callerInfo, lookupEpoch)
                    removePending(number)
                    CallerLookupResult(remote.callerInfo, CallerLookupSource.REMOTE)
                } else {
                    CallerLookupResult(errorEntity(number, "Lookup cancelled"), CallerLookupSource.LOCAL)
                }
            }
            is RemoteLookupOutcome.PermanentNotFound -> {
                removePending(number)
                CallerLookupResult(errorEntity(number, "Not found"), CallerLookupSource.REMOTE)
            }
            RemoteLookupOutcome.TemporarilyUnavailable -> {
                if (requestStillValid()) {
                    queueConnectivityRetry(number)
                }
                CallerLookupResult(
                    errorEntity(number, "No internet connection"),
                    CallerLookupSource.REMOTE
                )
            }
            RemoteLookupOutcome.AuthenticationFailure -> {
                removePending(number)
                CallerLookupResult(errorEntity(number, "Service not connected"), CallerLookupSource.REMOTE)
            }
            RemoteLookupOutcome.ParsingFailure -> {
                removePending(number)
                CallerLookupResult(errorEntity(number, "Unable to read lookup response"), CallerLookupSource.REMOTE)
            }
            RemoteLookupOutcome.Cancelled ->
                CallerLookupResult(errorEntity(number, "Lookup cancelled"), CallerLookupSource.LOCAL)
            RemoteLookupOutcome.Failure -> {
                removePending(number)
                CallerLookupResult(errorEntity(number, "Lookup failed"), CallerLookupSource.REMOTE)
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
                cacheEpoch.incrementAndGet()
                db.withTransaction {
                    db.callerInfoDao().clearAll()
                    db.pendingCallerLookupDao().clearAll()
                }
            }
            true
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

    suspend fun processPendingLookups(): Int = withContext(Dispatchers.IO) {
        val currentTime = now()
        val pendingDao = db.pendingCallerLookupDao()
        pendingDao.deleteExpiredOrExhausted(
            currentTime - CallerCachePolicy.RETRY_EXPIRY_MILLIS,
            CallerCachePolicy.MAXIMUM_ATTEMPTS
        )
        val eligible = pendingDao.eligible(
            currentTime,
            CallerCachePolicy.MAXIMUM_ITEMS_PER_RUN
        )
        var processed = 0
        for (item in eligible) {
            if (!currentCoroutineContext().isActive || !isNetworkAvailable()) break
            val canonicalNumber = sanitizeNumber(item.normalizedNumber)
            if (canonicalNumber.isEmpty() || canonicalNumber != item.normalizedNumber) {
                pendingDao.delete(item.normalizedNumber)
                continue
            }
            processed++
            val lookupEpoch = cacheEpoch.get()
            when (val outcome = sharedRemoteLookup(canonicalNumber)) {
                is RemoteLookupOutcome.Useful -> {
                    safelySaveUseful(canonicalNumber, outcome.callerInfo, lookupEpoch)
                    pendingDao.delete(canonicalNumber)
                }
                is RemoteLookupOutcome.PermanentNotFound,
                RemoteLookupOutcome.AuthenticationFailure,
                RemoteLookupOutcome.ParsingFailure,
                RemoteLookupOutcome.Cancelled,
                RemoteLookupOutcome.Failure -> pendingDao.delete(item.normalizedNumber)
                RemoteLookupOutcome.TemporarilyUnavailable -> {
                    val attemptCount = item.attemptCount + 1
                    if (attemptCount >= CallerCachePolicy.MAXIMUM_ATTEMPTS) {
                        pendingDao.delete(item.normalizedNumber)
                    } else {
                        pendingDao.updateAttempt(
                            item.normalizedNumber,
                            attemptCount,
                            CallerCachePolicy.nextEligibleRetryMillis(currentTime, attemptCount)
                        )
                    }
                }
            }
        }
        processed
    }

    private suspend fun safelyReadLocal(number: String): CallerInfoEntity? =
        try {
            db.callerInfoDao().getCallerInfo(number)
        } catch (_: Exception) {
            null
        }

    private suspend fun safelySaveUseful(
        number: String,
        callerInfo: CallerInfoEntity,
        expectedCacheEpoch: Long
    ): Boolean {
        if (callerInfo.number != number || !hasUsefulCallerInformation(callerInfo)) return false
        val savedAt = now()
        val saved = callerInfo.copy(
            number = number,
            error = null,
            timestamp = savedAt,
            lastSuccessfullyUpdatedMillis = savedAt
        )
        return storageMutex.withLock {
            if (cacheEpoch.get() != expectedCacheEpoch) return@withLock false
            try {
                val prefs = context.getSharedPreferences("Settings", Context.MODE_PRIVATE)
                val limitValue = prefs.getString("max_history_size", "1000")
                if (limitValue == "Unlimited") {
                    db.callerInfoDao().insertCallerInfo(saved)
                } else {
                    db.callerInfoDao().insertAndTrim(saved, limitValue?.toIntOrNull() ?: 1000)
                }
                true
            } catch (_: Exception) {
                false
            }
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
        } catch (_: Exception) {
            // Caller presentation must remain independent of retry persistence.
        }
    }

    private suspend fun removePending(number: String) {
        try {
            db.pendingCallerLookupDao().delete(number)
        } catch (_: Exception) {
            // A later bounded run can remove it.
        }
    }

    private fun canAcceptResult(
        number: String,
        callerInfo: CallerInfoEntity,
        requestStillValid: () -> Boolean
    ): Boolean =
        callerInfo.number == number &&
            requestStillValid() &&
            hasUsefulCallerInformation(callerInfo)

    private suspend fun sharedRemoteLookup(number: String): RemoteLookupOutcome {
        val deferred = synchronized(inFlightRefreshes) {
            inFlightRefreshes[number] ?: refreshScope.async {
                fetchFromTelegram(number)
            }.also { created ->
                inFlightRefreshes[number] = created
                created.invokeOnCompletion {
                    inFlightRefreshes.remove(number, created)
                }
            }
        }
        return try {
            deferred.await()
        } catch (_: CancellationException) {
            RemoteLookupOutcome.Cancelled
        }
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
                telegramManager.ensureJoined("TrueCalleRobot", isBot = true)

                val searchResult =
                    telegramManager.sendSuspend(TdApi.SearchPublicChat("TrueCalleRobot"))
                if (searchResult !is TdApi.Chat) {
                    return@withContext RemoteLookupOutcome.Failure
                }

                val chatId = searchResult.id
                val content =
                    TdApi.InputMessageText(TdApi.FormattedText(number, null), null, true)
                val sentMessage = telegramManager.sendSuspend(
                    TdApi.SendMessage(chatId, null, null, null, null, content)
                )
                val sentMessageId = (sentMessage as? TdApi.Message)?.id
                    ?: return@withContext RemoteLookupOutcome.Failure

                val update = withTimeoutOrNull(30_000) {
                    telegramManager.updates.first { objectValue ->
                        responseTextFor(objectValue, chatId, sentMessageId)?.let(::isFinalResponse)
                            ?: false
                    }
                } ?: return@withContext RemoteLookupOutcome.TemporarilyUnavailable

                val formattedText = formattedTextFor(update)
                    ?: return@withContext RemoteLookupOutcome.ParsingFailure
                classifyParsedResponse(number, formattedText)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                RemoteLookupOutcome.TemporarilyUnavailable
            } catch (_: Exception) {
                if (isNetworkAvailable()) {
                    RemoteLookupOutcome.Failure
                } else {
                    RemoteLookupOutcome.TemporarilyUnavailable
                }
            }
        }

    private fun responseTextFor(value: Any, chatId: Long, sentMessageId: Long): String? =
        when (value) {
            is TdApi.UpdateNewMessage -> value.message.takeIf {
                it.chatId == chatId && !it.isOutgoing && it.id > sentMessageId
            }?.content?.let(::extractFormattedText)?.text
            is TdApi.UpdateMessageContent -> value.takeIf {
                it.chatId == chatId && it.messageId > sentMessageId
            }?.newContent?.let(::extractFormattedText)?.text
            else -> null
        }

    private fun formattedTextFor(value: Any): TdApi.FormattedText? =
        when (value) {
            is TdApi.UpdateNewMessage -> extractFormattedText(value.message.content)
            is TdApi.UpdateMessageContent -> extractFormattedText(value.newContent)
            else -> null
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
    data object Cancelled : RemoteLookupOutcome
    data object Failure : RemoteLookupOutcome
}
