package com.rakibulcodes.callerinfo.data

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
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
    val text: String?
)

fun isCorrelatedFinalResponse(
    message: BotMessageEnvelope,
    expectedChatId: Long,
    sentMessageId: Long,
    isSupportedFinalResponse: (String) -> Boolean
): Boolean {
    if (message.chatId != expectedChatId) return false
    if (message.isOutgoing) return false
    if (message.messageId <= sentMessageId) return false
    if (message.hasUnsupportedReplyType) return false

    if (message.replyMessageId != null) {
        val replyChatMatches =
            message.replyChatId == null ||
                message.replyChatId == 0L ||
                message.replyChatId == expectedChatId
        if (!replyChatMatches || message.replyMessageId != sentMessageId) return false
    }

    val text = message.text ?: return false
    return isSupportedFinalResponse(text)
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
