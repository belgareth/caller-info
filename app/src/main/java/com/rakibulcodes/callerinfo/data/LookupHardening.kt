package com.rakibulcodes.callerinfo.data

import java.util.concurrent.ConcurrentHashMap
import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi
import java.util.concurrent.atomic.AtomicBoolean

object LookupTimeouts {
    const val TDLIB_COMMAND_MILLIS = 10_000L
    const val FINAL_RESPONSE_MILLIS = 30_000L
    const val COMPLETE_TRANSACTION_MILLIS = 120_000L
}

object RemoteUpdatePolicy {
    const val BUFFER_CAPACITY = 64
    const val QUARANTINE_MAXIMUM_SIZE = 32
    const val QUARANTINE_MILLIS = 5 * 60 * 1000L
}

class BoundedRemoteUpdateBuffer<T>(
    capacity: Int = RemoteUpdatePolicy.BUFFER_CAPACITY
) {
    private val channel = Channel<T>(
        capacity = capacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    fun offer(value: T): Boolean = channel.trySend(value).isSuccess

    suspend fun receive(): T = channel.receive()

    fun close() {
        channel.close()
    }
}

class TdlibUnavailableException : Exception("Remote transport unavailable")

class TdlibCommandFailureException(val errorCode: Int) :
    Exception("Remote command failed")

class TdlibCommandTimeoutException : Exception("Remote command timed out")

class LookupTransactionTimeoutException : Exception("Remote lookup timed out")

fun interface TdlibClientGateway {
    fun send(
        query: TdApi.Function<out TdApi.Object>,
        callback: (TdApi.Object) -> Unit
    )
}

class TdlibCommandTransport(
    private val gatewaySnapshot: () -> TdlibClientGateway?,
    private val nativeAvailable: () -> Boolean,
    private val commandTimeoutMillis: Long = LookupTimeouts.TDLIB_COMMAND_MILLIS
) {
    suspend fun execute(query: TdApi.Function<out TdApi.Object>): TdApi.Object {
        if (!nativeAvailable()) throw TdlibUnavailableException()
        val gateway = gatewaySnapshot() ?: throw TdlibUnavailableException()

        return withTimeoutOrNull(commandTimeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                val completed = AtomicBoolean(false)
                continuation.invokeOnCancellation { completed.set(true) }
                try {
                    gateway.send(query) { result ->
                        if (completed.compareAndSet(false, true)) {
                            if (result is TdApi.Error) {
                                continuation.resumeWith(
                                    Result.failure(TdlibCommandFailureException(result.code))
                                )
                            } else {
                                continuation.resumeWith(Result.success(result))
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    if (completed.compareAndSet(false, true)) {
                        continuation.resumeWith(Result.failure(cancelled))
                    }
                } catch (_: Exception) {
                    if (completed.compareAndSet(false, true)) {
                        continuation.resumeWith(Result.failure(TdlibUnavailableException()))
                    }
                } catch (_: LinkageError) {
                    if (completed.compareAndSet(false, true)) {
                        continuation.resumeWith(Result.failure(TdlibUnavailableException()))
                    }
                }
            }
        } ?: throw TdlibCommandTimeoutException()
    }
}

class RemoteLookupTransactionCoordinator(
    private val timeoutMillis: Long = LookupTimeouts.COMPLETE_TRANSACTION_MILLIS
) {
    private val transactionMutex = Mutex()

    suspend fun <T> run(block: suspend () -> T): T {
        val completed = withTimeoutOrNull(timeoutMillis) {
            transactionMutex.withLock {
                CompletedTransaction(block())
            }
        }
        return completed?.value ?: throw LookupTransactionTimeoutException()
    }

    private data class CompletedTransaction<T>(val value: T)
}

object RemoteBotTransactionCoordinator {
    private val coordinator = RemoteLookupTransactionCoordinator()

    suspend fun <T> run(block: suspend () -> T): T = coordinator.run(block)
}

class SameKeySingleFlight<K : Any, V : Any>(
    private val scope: CoroutineScope
) {
    private val inFlight = ConcurrentHashMap<K, Deferred<V>>()

    suspend fun run(key: K, block: suspend () -> V): V {
        val deferred = synchronized(inFlight) {
            inFlight[key] ?: scope.async {
                block()
            }.also { created ->
                inFlight[key] = created
                created.invokeOnCompletion {
                    inFlight.remove(key, created)
                }
            }
        }
        return deferred.await()
    }
}

data class BotMessageEnvelope(
    val chatId: Long,
    val messageId: Long,
    val isOutgoing: Boolean,
    val replyChatId: Long?,
    val replyMessageId: Long?,
    val hasUnsupportedReplyType: Boolean,
    val dedicatedCanonicalNumber: String?,
    val text: String?
)

private val DEDICATED_NUMBER_FIELD = Regex(
    """(?im)^\s*(?:<strong>\s*)?(?:number|phone|phone_number)\s*:\s*""" +
        """(?:</strong>\s*)?(?:<code>\s*)?([^<\r\n]+)"""
)

fun extractDedicatedCanonicalNumber(
    responseText: String,
    normalize: (String) -> String
): String? {
    val dedicatedValue = DEDICATED_NUMBER_FIELD
        .findAll(responseText)
        .map { match -> match.groupValues[1].trim() }
        .firstOrNull()
        ?: return null
    return normalize(dedicatedValue).takeIf(String::isNotBlank)
}

enum class ResponseCorrelation {
    EXACT_REPLY,
    EXACT_DEDICATED_NUMBER,
    REJECTED
}

fun correlateFinalResponse(
    message: BotMessageEnvelope,
    expectedChatId: Long,
    sentMessageId: Long,
    expectedCanonicalNumber: String,
    isQuarantinedReply: (Long, Long) -> Boolean = { _, _ -> false },
    isSupportedFinalResponse: (String) -> Boolean
): ResponseCorrelation {
    if (message.chatId != expectedChatId) return ResponseCorrelation.REJECTED
    if (message.isOutgoing) return ResponseCorrelation.REJECTED
    if (message.messageId <= sentMessageId) return ResponseCorrelation.REJECTED
    if (message.hasUnsupportedReplyType) return ResponseCorrelation.REJECTED
    val text = message.text ?: return ResponseCorrelation.REJECTED
    if (!isSupportedFinalResponse(text)) return ResponseCorrelation.REJECTED

    val replyMessageId = message.replyMessageId
    if (replyMessageId != null) {
        val replyChatId = message.replyChatId ?: return ResponseCorrelation.REJECTED
        if (isQuarantinedReply(replyChatId, replyMessageId)) {
            return ResponseCorrelation.REJECTED
        }
        return if (
            replyChatId == expectedChatId &&
            replyMessageId == sentMessageId
        ) {
            ResponseCorrelation.EXACT_REPLY
        } else {
            ResponseCorrelation.REJECTED
        }
    }

    return if (
        message.replyChatId == null &&
        message.dedicatedCanonicalNumber == expectedCanonicalNumber &&
        expectedCanonicalNumber.isNotBlank()
    ) {
        ResponseCorrelation.EXACT_DEDICATED_NUMBER
    } else {
        ResponseCorrelation.REJECTED
    }
}

data class RemoteRequestIdentity(
    val chatId: Long,
    val messageId: Long
)

fun remapRemoteRequestIdentityAfterSendSuccess(
    current: RemoteRequestIdentity,
    updateChatId: Long,
    oldMessageId: Long,
    newMessageId: Long
): RemoteRequestIdentity? =
    if (
        current.chatId == updateChatId &&
        current.messageId == oldMessageId &&
        newMessageId != oldMessageId
    ) {
        RemoteRequestIdentity(updateChatId, newMessageId)
    } else {
        null
    }

class LateResponseQuarantine(
    private val nowMillis: () -> Long,
    private val retentionMillis: Long = RemoteUpdatePolicy.QUARANTINE_MILLIS,
    private val maximumSize: Int = RemoteUpdatePolicy.QUARANTINE_MAXIMUM_SIZE
) {
    private data class Entry(
        val identity: RemoteRequestIdentity,
        val expiresAtMillis: Long
    )

    private val entries = ArrayDeque<Entry>()

    @Synchronized
    fun add(identity: RemoteRequestIdentity) {
        removeExpired()
        entries.removeAll { it.identity == identity }
        entries.addLast(Entry(identity, nowMillis() + retentionMillis))
        while (entries.size > maximumSize) entries.removeFirst()
    }

    @Synchronized
    fun contains(chatId: Long, messageId: Long): Boolean {
        removeExpired()
        return entries.any {
            it.identity.chatId == chatId && it.identity.messageId == messageId
        }
    }

    @Synchronized
    fun size(): Int {
        removeExpired()
        return entries.size
    }

    private fun removeExpired() {
        val currentTime = nowMillis()
        entries.removeAll { it.expiresAtMillis <= currentTime }
    }
}

suspend fun <T : Any> awaitCorrelatedResponse(
    timeoutMillis: Long,
    next: suspend () -> T?,
    correlation: (T) -> ResponseCorrelation,
    countsAsUncorrelated: (T) -> Boolean = { true }
): CorrelatedResponseResult<T> {
    var sawRejectedCandidate = false
    val match = withTimeoutOrNull(timeoutMillis) {
        var accepted: CorrelatedResponseResult.Accepted<T>? = null
        while (accepted == null) {
            val candidate = next() ?: continue
            val result = correlation(candidate)
            if (result == ResponseCorrelation.REJECTED) {
                if (countsAsUncorrelated(candidate)) {
                    sawRejectedCandidate = true
                }
                continue
            }
            accepted = CorrelatedResponseResult.Accepted(candidate, result)
        }
        accepted
    }
    return match ?: if (sawRejectedCandidate) {
        CorrelatedResponseResult.Uncorrelated
    } else {
        CorrelatedResponseResult.Timeout
    }
}

sealed interface CorrelatedResponseResult<out T> {
    data class Accepted<T>(
        val value: T,
        val correlation: ResponseCorrelation
    ) : CorrelatedResponseResult<T>

    data object Timeout : CorrelatedResponseResult<Nothing>
    data object Uncorrelated : CorrelatedResponseResult<Nothing>
}

suspend fun <T : Any> awaitMatchingResponse(
    timeoutMillis: Long,
    next: suspend () -> T?,
    matches: (T) -> Boolean
): T? = withTimeoutOrNull(timeoutMillis) {
    var matched: T? = null
    while (matched == null) {
        val candidate = next() ?: continue
        if (matches(candidate)) matched = candidate
    }
    matched
}

class RequestGenerationTracker {
    private var nextGeneration = 0L
    private var activeGeneration: Long? = null

    @Synchronized
    fun begin(): Long {
        val generation = ++nextGeneration
        activeGeneration = generation
        return generation
    }

    @Synchronized
    fun isCurrent(generation: Long): Boolean = activeGeneration == generation

    @Synchronized
    fun invalidate(generation: Long) {
        if (activeGeneration == generation) activeGeneration = null
    }
}

sealed interface SaveCallerResult {
    data object Saved : SaveCallerResult
    data object RejectedAfterUserClear : SaveCallerResult
    data object Failed : SaveCallerResult
}

enum class RemoteLookupFailure {
    CONNECTIVITY_UNAVAILABLE,
    TEMPORARY_TRANSPORT_FAILURE,
    AUTHENTICATION_NOT_READY,
    RATE_LIMITED,
    RESPONSE_TIMEOUT,
    UNCORRELATED_RESPONSE,
    PERMANENT_NOT_FOUND,
    PARSING_OR_PROTOCOL_FAILURE,
    PERSISTENCE_FAILURE,
    CANCELLED
}

fun remoteLookupFailureMessage(failure: RemoteLookupFailure): String = when (failure) {
    RemoteLookupFailure.CONNECTIVITY_UNAVAILABLE ->
        "Internet unavailable"
    RemoteLookupFailure.TEMPORARY_TRANSPORT_FAILURE ->
        "Remote lookup is temporarily unavailable"
    RemoteLookupFailure.AUTHENTICATION_NOT_READY ->
        "Remote lookup is not ready. Open the app and check sign-in."
    RemoteLookupFailure.RATE_LIMITED ->
        "Remote lookup is temporarily unavailable. Try again later."
    RemoteLookupFailure.RESPONSE_TIMEOUT ->
        "Remote service did not respond in time"
    RemoteLookupFailure.UNCORRELATED_RESPONSE ->
        "The response could not be verified"
    RemoteLookupFailure.PERMANENT_NOT_FOUND ->
        "Caller information was not found"
    RemoteLookupFailure.PARSING_OR_PROTOCOL_FAILURE ->
        "The response could not be processed"
    RemoteLookupFailure.PERSISTENCE_FAILURE ->
        "Caller information was found but could not be saved for offline use"
    RemoteLookupFailure.CANCELLED -> ""
}

data class RetryFailurePolicy(
    val incrementAttempt: Boolean,
    val delayMillis: Long
)

fun retryPolicyFor(failure: RemoteLookupFailure): RetryFailurePolicy? = when (failure) {
    RemoteLookupFailure.CONNECTIVITY_UNAVAILABLE,
    RemoteLookupFailure.TEMPORARY_TRANSPORT_FAILURE ->
        RetryFailurePolicy(incrementAttempt = true, delayMillis = 0)
    RemoteLookupFailure.AUTHENTICATION_NOT_READY ->
        RetryFailurePolicy(incrementAttempt = false, delayMillis = 6 * 60 * 60 * 1000L)
    RemoteLookupFailure.RATE_LIMITED ->
        RetryFailurePolicy(incrementAttempt = false, delayMillis = 6 * 60 * 60 * 1000L)
    RemoteLookupFailure.RESPONSE_TIMEOUT,
    RemoteLookupFailure.UNCORRELATED_RESPONSE,
    RemoteLookupFailure.PERSISTENCE_FAILURE ->
        RetryFailurePolicy(incrementAttempt = false, delayMillis = 30 * 60 * 1000L)
    RemoteLookupFailure.PARSING_OR_PROTOCOL_FAILURE,
    RemoteLookupFailure.PERMANENT_NOT_FOUND,
    RemoteLookupFailure.CANCELLED -> null
}

fun isCallerRecordSafeToPersist(
    expectedCanonicalNumber: String,
    recordCanonicalNumber: String,
    hasUsefulIdentification: Boolean
): Boolean =
    expectedCanonicalNumber.isNotBlank() &&
        recordCanonicalNumber == expectedCanonicalNumber &&
        hasUsefulIdentification

suspend fun saveCallerRecord(
    expectedCacheEpoch: Long,
    currentCacheEpoch: () -> Long,
    write: suspend () -> Unit
): SaveCallerResult {
    if (currentCacheEpoch() != expectedCacheEpoch) {
        return SaveCallerResult.RejectedAfterUserClear
    }
    return try {
        write()
        if (currentCacheEpoch() == expectedCacheEpoch) {
            SaveCallerResult.Saved
        } else {
            SaveCallerResult.RejectedAfterUserClear
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        SaveCallerResult.Failed
    }
}

enum class PendingRunFailure {
    NONE,
    TRANSIENT
}

data class PendingLookupRunResult(
    val processedCount: Int,
    val failure: PendingRunFailure,
    val eligibleNowCount: Int,
    val earliestFutureEligibleMillis: Long?
)

sealed interface WorkerRunAction {
    data object Success : WorkerRunAction
    data object Retry : WorkerRunAction
    data class ScheduleContinuation(val initialDelayMillis: Long) : WorkerRunAction
}

fun decideWorkerRunAction(
    result: PendingLookupRunResult,
    nowMillis: Long
): WorkerRunAction = when {
    result.failure == PendingRunFailure.TRANSIENT -> WorkerRunAction.Retry
    result.eligibleNowCount > 0 -> WorkerRunAction.ScheduleContinuation(0)
    result.earliestFutureEligibleMillis != null ->
        WorkerRunAction.ScheduleContinuation(
            (result.earliestFutureEligibleMillis - nowMillis).coerceAtLeast(0)
        )
    else -> WorkerRunAction.Success
}

class TelegramReadinessTracker {
    @Volatile
    private var nativeAvailable = false

    @Volatile
    private var clientAvailable = false

    @Volatile
    private var authorizationReady = false

    fun updateNativeAvailable(available: Boolean) {
        nativeAvailable = available
        if (!available) authorizationReady = false
    }

    fun updateClientAvailable(available: Boolean) {
        clientAvailable = available
        if (!available) authorizationReady = false
    }

    fun updateAuthorizationState(state: TdApi.AuthorizationState) {
        authorizationReady = state is TdApi.AuthorizationStateReady
    }

    fun isReady(): Boolean =
        nativeAvailable && clientAvailable && authorizationReady
}

const val MAXIMUM_EXTERNAL_LOOKUP_INPUT_LENGTH = 256

sealed interface ExternalLookupValidation {
    data class Valid(val normalizedNumber: String) : ExternalLookupValidation
    data object Rejected : ExternalLookupValidation
}

fun validateExternalLookup(
    action: String?,
    mimeType: String?,
    uriScheme: String?,
    input: String?,
    normalize: (String) -> String
): ExternalLookupValidation {
    val supportedAction =
        action == "android.intent.action.SEND" ||
            action == "android.intent.action.PROCESS_TEXT"
    if (!supportedAction || mimeType != "text/plain" || uriScheme != null) {
        return ExternalLookupValidation.Rejected
    }
    val value = input ?: return ExternalLookupValidation.Rejected
    if (
        value.isBlank() ||
        value.length > MAXIMUM_EXTERNAL_LOOKUP_INPUT_LENGTH ||
        value.contains('\n') ||
        value.contains('\r')
    ) {
        return ExternalLookupValidation.Rejected
    }
    val normalized = normalize(value)
    return if (normalized.isBlank()) {
        ExternalLookupValidation.Rejected
    } else {
        ExternalLookupValidation.Valid(normalized)
    }
}

class ExternalLookupConfirmationState {
    private val generations = RequestGenerationTracker()
    private var confirmedGeneration: Long? = null

    fun begin(): Long {
        confirmedGeneration = null
        return generations.begin()
    }

    fun confirm(generation: Long): Boolean {
        if (!generations.isCurrent(generation) || confirmedGeneration != null) return false
        confirmedGeneration = generation
        return true
    }

    fun canLookup(generation: Long): Boolean =
        generations.isCurrent(generation) && confirmedGeneration == generation

    fun cancel(generation: Long) {
        generations.invalidate(generation)
        if (confirmedGeneration == generation) confirmedGeneration = null
    }
}

enum class CallerPresentationMode {
    UNLOCKED_FULL,
    LOCKED_REDACTED
}

enum class IncomingPresentationRoute {
    LOCKED_CALLER_CARD,
    UNLOCKED_OVERLAY
}

fun selectIncomingPresentationRoute(
    deviceLocked: Boolean,
    lockedPresentationAlreadyActive: Boolean
): IncomingPresentationRoute =
    if (deviceLocked || lockedPresentationAlreadyActive) {
        IncomingPresentationRoute.LOCKED_CALLER_CARD
    } else {
        IncomingPresentationRoute.UNLOCKED_OVERLAY
    }

data class CallerPresentationInput(
    val name: String?,
    val number: String,
    val verificationState: String?,
    val email: String?,
    val address: String?,
    val location: String?,
    val lookupSource: String?,
    val recentCall: String?
)

data class CallerPresentationModel(
    val mode: CallerPresentationMode,
    val name: String?,
    val number: String,
    val verificationState: String?,
    val email: String?,
    val address: String?,
    val location: String?,
    val lookupSource: String?,
    val recentCall: String?,
    val actionsVisible: Boolean,
    val diagnosticsVisible: Boolean
)

fun callerPresentation(
    input: CallerPresentationInput,
    mode: CallerPresentationMode
): CallerPresentationModel =
    if (mode == CallerPresentationMode.LOCKED_REDACTED) {
        CallerPresentationModel(
            mode = mode,
            name = input.name,
            number = input.number,
            verificationState = input.verificationState,
            email = null,
            address = null,
            location = null,
            lookupSource = null,
            recentCall = null,
            actionsVisible = false,
            diagnosticsVisible = false
        )
    } else {
        CallerPresentationModel(
            mode = mode,
            name = input.name,
            number = input.number,
            verificationState = input.verificationState,
            email = input.email,
            address = input.address,
            location = input.location,
            lookupSource = input.lookupSource,
            recentCall = input.recentCall,
            actionsVisible = true,
            diagnosticsVisible = true
        )
    }

class GenerationBoundPresentationState {
    private var generation: Long? = null
    private var normalizedNumber: String? = null

    @Synchronized
    fun show(newGeneration: Long, newNormalizedNumber: String): Boolean {
        if (newGeneration < 0 || newNormalizedNumber.isBlank()) return false
        generation = newGeneration
        normalizedNumber = newNormalizedNumber
        return true
    }

    @Synchronized
    fun matches(expectedGeneration: Long, expectedNormalizedNumber: String): Boolean =
        generation == expectedGeneration && normalizedNumber == expectedNormalizedNumber

    @Synchronized
    fun close(expectedGeneration: Long): Boolean {
        if (generation != expectedGeneration) return false
        generation = null
        normalizedNumber = null
        return true
    }
}
