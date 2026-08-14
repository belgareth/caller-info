package com.rakibulcodes.callerinfo

import com.rakibulcodes.callerinfo.data.BotMessageEnvelope
import com.rakibulcodes.callerinfo.data.BoundedRemoteUpdateBuffer
import com.rakibulcodes.callerinfo.data.DatabaseKeyPlan
import com.rakibulcodes.callerinfo.data.CorrelatedResponseResult
import com.rakibulcodes.callerinfo.data.LateResponseQuarantine
import com.rakibulcodes.callerinfo.data.LookupTransactionTimeoutException
import com.rakibulcodes.callerinfo.data.PendingLookupRunResult
import com.rakibulcodes.callerinfo.data.PendingRunFailure
import com.rakibulcodes.callerinfo.data.RemoteLookupTransactionCoordinator
import com.rakibulcodes.callerinfo.data.RemoteLookupFailure
import com.rakibulcodes.callerinfo.data.RemoteRequestIdentity
import com.rakibulcodes.callerinfo.data.ResponseCorrelation
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
import com.rakibulcodes.callerinfo.data.awaitCorrelatedResponse
import com.rakibulcodes.callerinfo.data.activateDatabaseKeyPlan
import com.rakibulcodes.callerinfo.data.callerPresentation
import com.rakibulcodes.callerinfo.data.CallerPresentationInput
import com.rakibulcodes.callerinfo.data.CallerPresentationMode
import com.rakibulcodes.callerinfo.data.correlateFinalResponse
import com.rakibulcodes.callerinfo.data.decideWorkerRunAction
import com.rakibulcodes.callerinfo.data.remoteLookupFailureMessage
import com.rakibulcodes.callerinfo.data.retryPolicyFor
import com.rakibulcodes.callerinfo.data.validateExternalLookup
import com.rakibulcodes.callerinfo.data.ExternalLookupValidation
import com.rakibulcodes.callerinfo.data.GenerationBoundPresentationState
import com.rakibulcodes.callerinfo.data.IncomingPresentationRoute
import com.rakibulcodes.callerinfo.data.extractDedicatedCanonicalNumber
import com.rakibulcodes.callerinfo.data.isCallerRecordSafeToPersist
import com.rakibulcodes.callerinfo.data.saveCallerRecord
import com.rakibulcodes.callerinfo.data.selectIncomingPresentationRoute
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
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

        assertEquals(
            ResponseCorrelation.EXACT_REPLY,
            correlateFinalResponse(valid, 9, 50, "canonical-a", isSupportedFinalResponse = finalClassifier)
        )
        assertEquals(
            ResponseCorrelation.EXACT_DEDICATED_NUMBER,
            correlateFinalResponse(
                valid.copy(
                    replyChatId = null,
                    replyMessageId = null,
                    dedicatedCanonicalNumber = "canonical-a"
                ),
                9,
                50,
                "canonical-a",
                isSupportedFinalResponse = finalClassifier
            )
        )
        val rejected = listOf(
            valid.copy(chatId = 10),
            valid.copy(isOutgoing = true),
            valid.copy(messageId = 50),
            valid.copy(replyMessageId = 51),
            valid.copy(replyChatId = 10),
            valid.copy(hasUnsupportedReplyType = true),
            valid.copy(text = "PROGRESS"),
            valid.copy(text = null),
            valid.copy(replyChatId = null, replyMessageId = null),
            valid.copy(
                replyChatId = null,
                replyMessageId = null,
                dedicatedCanonicalNumber = "canonical-b"
            )
        )
        rejected.forEach {
            assertEquals(
                ResponseCorrelation.REJECTED,
                correlateFinalResponse(
                    it,
                    9,
                    50,
                    "canonical-a",
                    isSupportedFinalResponse = finalClassifier
                )
            )
        }
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
    fun cancelledPresentationWaiterDoesNotCancelRepositoryOwnedPersistence() = runBlocking {
        val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val singleFlight = SameKeySingleFlight<String, String>(repositoryScope)
        val remoteStarted = CompletableDeferred<Unit>()
        val releaseRemote = CompletableDeferred<Unit>()
        val persisted = CompletableDeferred<Unit>()

        val presentationWaiter = launch {
            singleFlight.run("same-key") {
                remoteStarted.complete(Unit)
                releaseRemote.await()
                persisted.complete(Unit)
                "useful"
            }
        }
        remoteStarted.await()
        presentationWaiter.cancelAndJoin()
        releaseRemote.complete(Unit)

        withTimeout(2_000) { persisted.await() }
        repositoryScope.cancel()
    }

    @Test
    fun sameKeyRepositoryOperationPersistsOnlyOnce() = runBlocking {
        val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val singleFlight = SameKeySingleFlight<String, String>(repositoryScope)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val saves = AtomicInteger()
        val first = async {
            singleFlight.run("same-key") {
                entered.complete(Unit)
                release.await()
                saves.incrementAndGet()
                "useful"
            }
        }
        entered.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            singleFlight.run("same-key") { "duplicate" }
        }
        release.complete(Unit)

        assertEquals("useful", first.await())
        assertEquals("useful", second.await())
        assertEquals(1, saves.get())
        repositoryScope.cancel()
    }

    @Test
    fun clearEpochRejectsLateRepositoryPersistence() = runBlocking {
        var epoch = 4L
        var writes = 0
        val result = saveCallerRecord(
            expectedCacheEpoch = 4L,
            currentCacheEpoch = { epoch }
        ) {
            writes++
        }
        assertEquals(SaveCallerResult.Saved, result)

        epoch = 5L
        val lateResult = saveCallerRecord(
            expectedCacheEpoch = 4L,
            currentCacheEpoch = { epoch }
        ) {
            writes++
        }
        assertEquals(SaveCallerResult.RejectedAfterUserClear, lateResult)
        assertEquals(1, writes)
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
    fun setupTimeoutReleasesGlobalTransactionForNextLookup() = runBlocking {
        val coordinator = RemoteLookupTransactionCoordinator(timeoutMillis = 50)
        assertSuspendThrows<LookupTransactionTimeoutException> {
            coordinator.run { awaitCancellation() }
        }
        assertEquals("lookup", coordinator.run { "lookup" })
    }

    @Test
    fun finalResponseWaitIgnoresProgressAndEnforcesItsOwnTimeout() = runBlocking {
        val candidates = ArrayDeque(listOf("PROGRESS", "FINAL"))
        val accepted = awaitCorrelatedResponse(
                timeoutMillis = 1_000,
                next = { candidates.removeFirstOrNull() },
                correlation = {
                    if (it == "FINAL") {
                        ResponseCorrelation.EXACT_REPLY
                    } else {
                        ResponseCorrelation.REJECTED
                    }
                }
            )
        assertEquals("FINAL", (accepted as CorrelatedResponseResult.Accepted).value)
        assertEquals(
            CorrelatedResponseResult.Timeout,
            awaitCorrelatedResponse<String>(
                timeoutMillis = 1,
                next = { awaitCancellation() },
                correlation = { ResponseCorrelation.EXACT_REPLY }
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
    fun emptyDatabaseKeyMigrationChangesKeyOnlyAfterOpeningExistingData() = runBlocking {
        val newKey = ByteArray(32) { 7 }
        val operations = mutableListOf<String>()
        activateDatabaseKeyPlan(
            plan = DatabaseKeyPlan.MigrateFromEmpty(newKey),
            openWithKey = { key ->
                assertTrue(key.isEmpty())
                operations += "open-existing"
            },
            changeKey = { key ->
                assertTrue(key.contentEquals(newKey))
                operations += "change-key"
            },
            markActive = { key ->
                assertTrue(key.contentEquals(newKey))
                operations += "mark-active"
                true
            }
        )
        assertEquals(
            listOf("open-existing", "change-key", "mark-active"),
            operations
        )
    }

    @Test
    fun interruptedDatabaseKeyMigrationReopensWithNewKeyWithoutDeletion() = runBlocking {
        val newKey = ByteArray(32) { 8 }
        val openedKeys = mutableListOf<ByteArray>()
        var changed = false
        activateDatabaseKeyPlan(
            plan = DatabaseKeyPlan.MigrateFromEmpty(newKey),
            openWithKey = { key ->
                openedKeys += key
                if (key.isEmpty()) throw TdlibCommandFailureException(401)
            },
            changeKey = { changed = true },
            markActive = { true }
        )
        assertEquals(2, openedKeys.size)
        assertTrue(openedKeys.first().isEmpty())
        assertTrue(openedKeys.last().contentEquals(newKey))
        assertFalse(changed)
    }

    @Test
    fun failedDatabaseKeyChangeNeverMarksMigrationComplete() = runBlocking {
        var marked = false
        assertSuspendThrows<TdlibCommandFailureException> {
            activateDatabaseKeyPlan(
                plan = DatabaseKeyPlan.MigrateFromEmpty(ByteArray(32) { 9 }),
                openWithKey = { },
                changeKey = { throw TdlibCommandFailureException(500) },
                markActive = {
                    marked = true
                    true
                }
            )
        }
        assertFalse(marked)
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

    @Test
    fun delayedResponseFromEarlierRequestCannotBecomeCurrentResult() = runBlocking {
        val candidates = ArrayDeque(
            listOf(
                envelope(null, "FINAL", dedicatedCanonicalNumber = "canonical-a"),
                envelope(50, "FINAL").copy(messageId = 61)
            )
        )
        val result = awaitCorrelatedResponse(
            timeoutMillis = 1_000,
            next = { candidates.removeFirstOrNull() },
            correlation = { candidate ->
                correlateFinalResponse(
                    message = candidate,
                    expectedChatId = 9,
                    sentMessageId = 50,
                    expectedCanonicalNumber = "canonical-b",
                    isSupportedFinalResponse = { it == "FINAL" }
                )
            }
        )
        assertTrue(result is CorrelatedResponseResult.Accepted)
        assertEquals(61, (result as CorrelatedResponseResult.Accepted).value.messageId)
    }

    @Test
    fun suffixAndArbitraryTextCannotCorrelateReplylessResponses() {
        val candidate = envelope(
            replyMessageId = null,
            text = "FINAL address 2222222222",
            dedicatedCanonicalNumber = "1111111111"
        )
        assertEquals(
            ResponseCorrelation.REJECTED,
            correlateFinalResponse(
                candidate,
                expectedChatId = 9,
                sentMessageId = 50,
                expectedCanonicalNumber = "9111111111",
                isSupportedFinalResponse = { true }
            )
        )
        assertEquals(
            ResponseCorrelation.REJECTED,
            correlateFinalResponse(
                candidate.copy(dedicatedCanonicalNumber = null),
                expectedChatId = 9,
                sentMessageId = 50,
                expectedCanonicalNumber = "2222222222",
                isSupportedFinalResponse = { true }
            )
        )
    }

    @Test
    fun dedicatedNumberParserReadsOnlyDedicatedFields() {
        val normalize: (String) -> String = { value -> value.filter(Char::isDigit) }
        assertEquals(
            "0000000000",
            extractDedicatedCanonicalNumber("Name: Sample caller\nNumber: 000 000 0000", normalize)
        )
        assertEquals(
            "0000000000",
            extractDedicatedCanonicalNumber(
                "<strong>Phone:</strong> <code>000 000 0000</code>",
                normalize
            )
        )
        assertEquals(
            null,
            extractDedicatedCanonicalNumber(
                "Address: 0000000000\nDescription: 0000000000",
                normalize
            )
        )
        assertEquals(
            null,
            extractDedicatedCanonicalNumber("Searching for 0000000000", normalize)
        )
    }

    @Test
    fun quarantineIsBoundedExpiresAndRejectsOldExactReplies() {
        var now = 10L
        val quarantine = LateResponseQuarantine(
            nowMillis = { now },
            retentionMillis = 100,
            maximumSize = 2
        )
        quarantine.add(RemoteRequestIdentity(9, 40))
        quarantine.add(RemoteRequestIdentity(9, 41))
        quarantine.add(RemoteRequestIdentity(9, 42))
        assertEquals(2, quarantine.size())
        assertFalse(quarantine.contains(9, 40))
        assertTrue(quarantine.contains(9, 42))
        assertEquals(
            ResponseCorrelation.REJECTED,
            correlateFinalResponse(
                envelope(42, "FINAL").copy(messageId = 70),
                9,
                42,
                "canonical",
                isQuarantinedReply = quarantine::contains,
                isSupportedFinalResponse = { true }
            )
        )
        now = 111
        assertEquals(0, quarantine.size())
    }

    @Test
    fun boundedUpdateFloodCannotTurnIrrelevantMessageIntoCallerResult() = runBlocking {
        val buffer = BoundedRemoteUpdateBuffer<BotMessageEnvelope>(capacity = 64)
        repeat(100) { index ->
            buffer.offer(
                envelope(null, "PROGRESS").copy(
                    messageId = 100L + index,
                    dedicatedCanonicalNumber = "0000000001"
                )
            )
        }
        buffer.offer(
            envelope(null, "FINAL", dedicatedCanonicalNumber = "0000000002")
                .copy(messageId = 300)
        )
        val result = awaitCorrelatedResponse(
            timeoutMillis = 1_000,
            next = { buffer.receive() },
            correlation = { message ->
                correlateFinalResponse(
                    message,
                    expectedChatId = 9,
                    sentMessageId = 50,
                    expectedCanonicalNumber = "0000000002",
                    isSupportedFinalResponse = { it == "FINAL" }
                )
            }
        )
        assertTrue(result is CorrelatedResponseResult.Accepted)
        assertEquals(300, (result as CorrelatedResponseResult.Accepted).value.messageId)
        buffer.close()
    }

    @Test
    fun neutralFailureMessagesAreExactAndDistinct() {
        val expected = mapOf(
            RemoteLookupFailure.CONNECTIVITY_UNAVAILABLE to "Internet unavailable",
            RemoteLookupFailure.TEMPORARY_TRANSPORT_FAILURE to
                "Remote lookup is temporarily unavailable",
            RemoteLookupFailure.AUTHENTICATION_NOT_READY to
                "Remote lookup is not ready. Open the app and check sign-in.",
            RemoteLookupFailure.RATE_LIMITED to
                "Remote lookup is temporarily unavailable. Try again later.",
            RemoteLookupFailure.RESPONSE_TIMEOUT to "Remote service did not respond in time",
            RemoteLookupFailure.UNCORRELATED_RESPONSE to "The response could not be verified",
            RemoteLookupFailure.PERMANENT_NOT_FOUND to "Caller information was not found",
            RemoteLookupFailure.PARSING_OR_PROTOCOL_FAILURE to
                "The response could not be processed",
            RemoteLookupFailure.PERSISTENCE_FAILURE to
                "Caller information was found but could not be saved for offline use"
        )
        expected.forEach { (failure, message) ->
            assertEquals(message, remoteLookupFailureMessage(failure))
        }
        assertEquals("", remoteLookupFailureMessage(RemoteLookupFailure.CANCELLED))
    }

    @Test
    fun retryPoliciesSeparateConnectivityFromOtherFailures() {
        assertTrue(
            retryPolicyFor(RemoteLookupFailure.CONNECTIVITY_UNAVAILABLE)!!.incrementAttempt
        )
        assertTrue(
            retryPolicyFor(RemoteLookupFailure.TEMPORARY_TRANSPORT_FAILURE)!!.incrementAttempt
        )
        listOf(
            RemoteLookupFailure.AUTHENTICATION_NOT_READY,
            RemoteLookupFailure.RATE_LIMITED,
            RemoteLookupFailure.RESPONSE_TIMEOUT,
            RemoteLookupFailure.UNCORRELATED_RESPONSE,
            RemoteLookupFailure.PERSISTENCE_FAILURE
        ).forEach { failure ->
            val policy = retryPolicyFor(failure)
            assertFalse(policy!!.incrementAttempt)
            assertTrue(policy.delayMillis > 0)
        }
        assertEquals(null, retryPolicyFor(RemoteLookupFailure.PERMANENT_NOT_FOUND))
        assertEquals(null, retryPolicyFor(RemoteLookupFailure.PARSING_OR_PROTOCOL_FAILURE))
        assertEquals(null, retryPolicyFor(RemoteLookupFailure.CANCELLED))
    }

    @Test
    fun externalLookupValidationRequiresSupportedSingleLineText() {
        val normalize: (String) -> String = { value -> value.filter(Char::isDigit) }
        assertEquals(
            ExternalLookupValidation.Valid("0000000000"),
            validateExternalLookup(
                "android.intent.action.SEND",
                "text/plain",
                null,
                "000 000 0000",
                normalize
            )
        )
        listOf(
            validateExternalLookup(null, "text/plain", null, "0000000000", normalize),
            validateExternalLookup(
                "android.intent.action.SEND",
                "text/html",
                null,
                "0000000000",
                normalize
            ),
            validateExternalLookup(
                "android.intent.action.SEND",
                "text/plain",
                "content",
                "0000000000",
                normalize
            ),
            validateExternalLookup(
                "android.intent.action.SEND",
                "text/plain",
                null,
                "000\n000",
                normalize
            ),
            validateExternalLookup(
                "android.intent.action.SEND",
                "text/plain",
                null,
                "0".repeat(257),
                normalize
            )
        ).forEach { assertEquals(ExternalLookupValidation.Rejected, it) }
    }

    @Test
    fun lockedPresentationRedactsAuxiliaryCallerData() {
        val locked = callerPresentation(
            CallerPresentationInput(
                name = "Sample caller",
                number = "0000000000",
                verificationState = NumberVerificationState.PASSED.name,
                email = "sample@example.invalid",
                address = "Hidden detail",
                location = "Hidden detail",
                lookupSource = "Remote lookup",
                recentCall = "Earlier"
            ),
            CallerPresentationMode.LOCKED_REDACTED
        )
        assertEquals("Sample caller", locked.name)
        assertEquals("0000000000", locked.number)
        assertEquals(NumberVerificationState.PASSED.name, locked.verificationState)
        assertEquals(null, locked.email)
        assertEquals(null, locked.address)
        assertEquals(null, locked.location)
        assertEquals(null, locked.lookupSource)
        assertEquals(null, locked.recentCall)
        assertFalse(locked.actionsVisible)
        assertFalse(locked.diagnosticsVisible)
    }

    @Test
    fun oldLockedGenerationCannotUpdateOrCloseNewPresentation() {
        val state = GenerationBoundPresentationState()
        assertTrue(state.show(1, "0000000001"))
        assertTrue(state.show(2, "0000000002"))
        assertFalse(state.matches(1, "0000000001"))
        assertTrue(state.matches(2, "0000000002"))
        assertFalse(state.close(1))
        assertTrue(state.matches(2, "0000000002"))
        assertTrue(state.close(2))
        assertFalse(state.matches(2, "0000000002"))
    }

    @Test
    fun lockedAndUnlockedIncomingPresentationRoutesAreDeterministic() {
        assertEquals(
            IncomingPresentationRoute.LOCKED_CALLER_CARD,
            selectIncomingPresentationRoute(true, false)
        )
        assertEquals(
            IncomingPresentationRoute.LOCKED_CALLER_CARD,
            selectIncomingPresentationRoute(false, true)
        )
        assertEquals(
            IncomingPresentationRoute.UNLOCKED_OVERLAY,
            selectIncomingPresentationRoute(false, false)
        )
    }

    private fun envelope(
        replyMessageId: Long?,
        text: String?,
        dedicatedCanonicalNumber: String? = null
    ) = BotMessageEnvelope(
        chatId = 9,
        messageId = 60,
        isOutgoing = false,
        replyChatId = if (replyMessageId == null) null else 9,
        replyMessageId = replyMessageId,
        hasUnsupportedReplyType = false,
        dedicatedCanonicalNumber = dedicatedCanonicalNumber,
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
