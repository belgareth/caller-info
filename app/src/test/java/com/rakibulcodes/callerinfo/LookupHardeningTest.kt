package com.rakibulcodes.callerinfo

import com.rakibulcodes.callerinfo.data.BotMessageEnvelope
import com.rakibulcodes.callerinfo.data.LookupTransactionTimeoutException
import com.rakibulcodes.callerinfo.data.PendingLookupRunResult
import com.rakibulcodes.callerinfo.data.PendingRunFailure
import com.rakibulcodes.callerinfo.data.RemoteLookupTransactionCoordinator
import com.rakibulcodes.callerinfo.data.RequestGenerationTracker
import com.rakibulcodes.callerinfo.data.SameKeySingleFlight
import com.rakibulcodes.callerinfo.data.SaveCallerResult
import com.rakibulcodes.callerinfo.data.TdlibClientGateway
import com.rakibulcodes.callerinfo.data.TdlibCommandFailureException
import com.rakibulcodes.callerinfo.data.TdlibCommandTimeoutException
import com.rakibulcodes.callerinfo.data.TdlibCommandTransport
import com.rakibulcodes.callerinfo.data.TdlibUnavailableException
import com.rakibulcodes.callerinfo.data.TelegramReadinessTracker
import com.rakibulcodes.callerinfo.data.WorkerRunAction
import com.rakibulcodes.callerinfo.data.awaitMatchingResponse
import com.rakibulcodes.callerinfo.data.decideWorkerRunAction
import com.rakibulcodes.callerinfo.data.isCorrelatedFinalResponse
import com.rakibulcodes.callerinfo.data.isCallerRecordSafeToPersist
import com.rakibulcodes.callerinfo.data.saveCallerRecord
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LookupHardeningTest {
    @Test
    fun responseCorrelationRejectsCrossRequestAndUnrelatedMessages() {
        val finalClassifier: (String) -> Boolean = { it == "FINAL" }
        val valid = envelope(replyMessageId = 50, text = "FINAL")

        assertTrue(isCorrelatedFinalResponse(valid, 9, 50, finalClassifier))
        assertTrue(
            isCorrelatedFinalResponse(
                valid.copy(replyChatId = null, replyMessageId = null),
                9,
                50,
                finalClassifier
            )
        )
        assertFalse(isCorrelatedFinalResponse(valid.copy(chatId = 10), 9, 50, finalClassifier))
        assertFalse(isCorrelatedFinalResponse(valid.copy(isOutgoing = true), 9, 50, finalClassifier))
        assertFalse(isCorrelatedFinalResponse(valid.copy(messageId = 50), 9, 50, finalClassifier))
        assertFalse(
            isCorrelatedFinalResponse(valid.copy(replyMessageId = 51), 9, 50, finalClassifier)
        )
        assertFalse(
            isCorrelatedFinalResponse(valid.copy(replyChatId = 10), 9, 50, finalClassifier)
        )
        assertFalse(
            isCorrelatedFinalResponse(valid.copy(hasUnsupportedReplyType = true), 9, 50, finalClassifier)
        )
        assertFalse(isCorrelatedFinalResponse(valid.copy(text = "PROGRESS"), 9, 50, finalClassifier))
        assertFalse(isCorrelatedFinalResponse(valid.copy(text = null), 9, 50, finalClassifier))
    }

    @Test
    fun serializedTransactionsForDifferentKeysNeverOverlap() = runBlocking {
        val coordinator = RemoteLookupTransactionCoordinator(timeoutMillis = 5_000)
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()

        val first = launch {
            coordinator.run {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()
        val second = launch {
            coordinator.run {
                secondEntered.complete(Unit)
            }
        }
        yield()

        assertFalse(secondEntered.isCompleted)
        releaseFirst.complete(Unit)
        first.join()
        second.join()
        assertTrue(secondEntered.isCompleted)
    }

    @Test
    fun queuedDifferentRequestCannotConsumeEarlierTransactionResponse() = runBlocking {
        val coordinator = RemoteLookupTransactionCoordinator(timeoutMillis = 5_000)
        val sentRequests = mutableListOf<String>()
        val firstResponse = CompletableDeferred<String>()
        val secondResponse = CompletableDeferred<String>()
        val first = async {
            coordinator.run {
                sentRequests += "request-a"
                firstResponse.await()
            }
        }
        yield()
        val second = async {
            coordinator.run {
                sentRequests += "request-b"
                secondResponse.await()
            }
        }
        yield()

        assertEquals(listOf("request-a"), sentRequests)
        firstResponse.complete("response-a")
        assertEquals("response-a", first.await())
        yield()
        assertEquals(listOf("request-a", "request-b"), sentRequests)
        secondResponse.complete("response-b")
        assertEquals("response-b", second.await())
    }

    @Test
    fun sameKeyLookupIsSingleFlight() = runBlocking {
        val singleFlight = SameKeySingleFlight<String, String>(this)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val executions = AtomicInteger()
        val first = async {
            singleFlight.run("same-key") {
                executions.incrementAndGet()
                entered.complete(Unit)
                release.await()
                "result"
            }
        }
        entered.await()
        val second = async {
            singleFlight.run("same-key") {
                executions.incrementAndGet()
                "wrong"
            }
        }

        release.complete(Unit)
        assertEquals("result", first.await())
        assertEquals("result", second.await())
        assertEquals(1, executions.get())
    }

    @Test
    fun manualRequestGenerationRejectsOlderResult() {
        val tracker = RequestGenerationTracker()
        val first = tracker.begin()
        val second = tracker.begin()

        assertFalse(tracker.isCurrent(first))
        assertTrue(tracker.isCurrent(second))
        tracker.invalidate(first)
        assertTrue(tracker.isCurrent(second))
        tracker.invalidate(second)
        assertFalse(tracker.isCurrent(second))
    }

    @Test
    fun unavailableTransportFailsImmediately() = runBlocking {
        assertSuspendThrows<TdlibUnavailableException> {
            transport(nativeAvailable = false, gateway = null).execute(TdApi.GetMe())
        }
        assertSuspendThrows<TdlibUnavailableException> {
            transport(nativeAvailable = true, gateway = null).execute(TdApi.GetMe())
        }
    }

    @Test
    fun transportMapsErrorsAndReturnsExpectedResult() = runBlocking {
        assertSuspendThrows<TdlibCommandFailureException> {
            transport(
                gateway = TdlibClientGateway { _, callback ->
                    callback(TdApi.Error(500, "neutral"))
                }
            ).execute(TdApi.GetMe())
        }

        val expected = TdApi.Ok()
        val actual = transport(
            gateway = TdlibClientGateway { _, callback -> callback(expected) }
        ).execute(TdApi.GetMe())
        assertSame(expected, actual)
    }

    @Test
    fun commandAndCompleteTransactionTimeoutsAreBounded() = runBlocking {
        assertSuspendThrows<TdlibCommandTimeoutException> {
            transport(
                timeoutMillis = 1,
                gateway = TdlibClientGateway { _, _ -> }
            ).execute(TdApi.GetMe())
        }
        assertSuspendThrows<LookupTransactionTimeoutException> {
            RemoteLookupTransactionCoordinator(timeoutMillis = 1).run {
                awaitCancellation()
            }
        }
    }

    @Test
    fun finalResponseWaitIgnoresProgressAndEnforcesItsOwnTimeout() = runBlocking {
        val candidates = ArrayDeque(listOf("PROGRESS", "FINAL"))
        assertEquals(
            "FINAL",
            awaitMatchingResponse(
                timeoutMillis = 1_000,
                next = { candidates.removeFirstOrNull() },
                matches = { it == "FINAL" }
            )
        )
        assertEquals(
            null,
            awaitMatchingResponse<String>(
                timeoutMillis = 1,
                next = { awaitCancellation() },
                matches = { true }
            )
        )
    }

    @Test
    fun lateTransportCallbackAfterCancellationIsIgnored() = runBlocking {
        val callbackReady = CompletableDeferred<(TdApi.Object) -> Unit>()
        val transport = transport(
            gateway = TdlibClientGateway { _, callback ->
                callbackReady.complete(callback)
            }
        )
        val job = launch { transport.execute(TdApi.GetMe()) }
        val callback = callbackReady.await()

        job.cancelAndJoin()
        callback(TdApi.Ok())
        assertTrue(job.isCancelled)
    }

    @Test
    fun callerCancellationIsNotConvertedIntoTransportOrTransactionFailure() = runBlocking {
        assertSuspendThrows<TimeoutCancellationException> {
            withTimeout(1) {
                transport(
                    timeoutMillis = 10_000,
                    gateway = TdlibClientGateway { _, _ -> }
                ).execute(TdApi.GetMe())
            }
        }
        assertSuspendThrows<TimeoutCancellationException> {
            withTimeout(1) {
                RemoteLookupTransactionCoordinator(timeoutMillis = 10_000).run {
                    awaitCancellation()
                }
            }
        }
    }

    @Test
    fun readinessRequiresNativeClientAndCurrentReadyState() {
        val readiness = TelegramReadinessTracker()
        readiness.updateAuthorizationState(TdApi.AuthorizationStateReady())
        assertFalse(readiness.isReady())

        readiness.updateNativeAvailable(true)
        readiness.updateClientAvailable(true)
        assertTrue(readiness.isReady())

        readiness.updateAuthorizationState(TdApi.AuthorizationStateClosing())
        assertFalse(readiness.isReady())
        readiness.updateAuthorizationState(TdApi.AuthorizationStateReady())
        readiness.updateClientAvailable(false)
        assertFalse(readiness.isReady())
    }

    @Test
    fun durableSaveDistinguishesSavedFailedAndCleared() = runBlocking {
        var epoch = 4L
        var writes = 0
        assertEquals(
            SaveCallerResult.Saved,
            saveCallerRecord(4, { epoch }) { writes++ }
        )
        assertEquals(1, writes)

        assertEquals(
            SaveCallerResult.Failed,
            saveCallerRecord(4, { epoch }) { error("write failed") }
        )

        epoch = 5
        assertEquals(
            SaveCallerResult.RejectedAfterUserClear,
            saveCallerRecord(4, { epoch }) { writes++ }
        )
        assertEquals(1, writes)
    }

    @Test
    fun usefulCallerCanOnlyBePersistedUnderItsExactCanonicalKey() {
        assertTrue(isCallerRecordSafeToPersist("canonical-a", "canonical-a", true))
        assertFalse(isCallerRecordSafeToPersist("canonical-a", "canonical-b", true))
        assertFalse(isCallerRecordSafeToPersist("canonical-a", "canonical-a", false))
        assertFalse(isCallerRecordSafeToPersist("", "", true))
    }

    @Test
    fun durableSavePropagatesCancellation() = runBlocking {
        assertSuspendThrows<CancellationException> {
            saveCallerRecord(1, { 1 }) { throw CancellationException("cancelled") }
        }
    }

    @Test
    fun workerDecisionRetriesAndSchedulesRemainingRows() {
        val now = 1_000L
        assertEquals(
            WorkerRunAction.Retry,
            decideWorkerRunAction(result(failure = PendingRunFailure.TRANSIENT), now)
        )
        assertEquals(
            WorkerRunAction.ScheduleContinuation(0),
            decideWorkerRunAction(result(eligibleNow = 2), now)
        )
        assertEquals(
            WorkerRunAction.ScheduleContinuation(400),
            decideWorkerRunAction(result(earliestFuture = 1_400), now)
        )
        assertEquals(
            WorkerRunAction.Success,
            decideWorkerRunAction(result(), now)
        )
    }

    private fun envelope(
        replyMessageId: Long?,
        text: String?
    ) = BotMessageEnvelope(
        chatId = 9,
        messageId = 60,
        isOutgoing = false,
        replyChatId = 9,
        replyMessageId = replyMessageId,
        hasUnsupportedReplyType = false,
        text = text
    )

    private fun transport(
        nativeAvailable: Boolean = true,
        gateway: TdlibClientGateway?,
        timeoutMillis: Long = 1_000
    ) = TdlibCommandTransport(
        gatewaySnapshot = { gateway },
        nativeAvailable = { nativeAvailable },
        commandTimeoutMillis = timeoutMillis
    )

    private fun result(
        failure: PendingRunFailure = PendingRunFailure.NONE,
        eligibleNow: Int = 0,
        earliestFuture: Long? = null
    ) = PendingLookupRunResult(
        processedCount = 0,
        failure = failure,
        eligibleNowCount = eligibleNow,
        earliestFutureEligibleMillis = earliestFuture
    )

    private suspend inline fun <reified T : Throwable> assertSuspendThrows(
        crossinline block: suspend () -> Unit
    ) {
        try {
            block()
            fail("Expected ${T::class.java.simpleName}")
        } catch (error: Throwable) {
            if (error !is T) throw error
        }
    }
}
