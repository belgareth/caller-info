package com.rakibulcodes.callerinfo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OfflineCallerCacheAuditTest {
    @Test
    fun exactNonDestructiveMigrationPreservesOldRows() {
        val database = source("data/database/AppDatabase.kt")

        assertTrue(database.contains("version = 3"))
        assertTrue(database.contains("Migration(2, 3)"))
        assertTrue(database.contains("ALTER TABLE caller_info ADD COLUMN"))
        assertTrue(database.contains("lastSuccessfullyUpdatedMillis INTEGER DEFAULT NULL"))
        assertTrue(database.contains("CREATE TABLE IF NOT EXISTS pending_caller_lookup"))
        assertFalse(database.contains("fallbackToDestructiveMigration"))
    }

    @Test
    fun canonicalNumberIsTheOnlyCallerAndQueueKey() {
        val caller = source("data/database/CallerInfoEntity.kt")
        val pending = source("data/database/PendingCallerLookupEntity.kt")
        val repository = source("data/CallerInfoRepository.kt")

        assertTrue(caller.contains("@PrimaryKey val number: String"))
        assertTrue(pending.contains("@PrimaryKey val normalizedNumber: String"))
        assertTrue(repository.contains("val number = sanitizeNumber(rawNumber)"))
        assertFalse(repository.contains("takeLast("))
        assertFalse(repository.contains("endsWith("))
    }

    @Test
    fun localLookupPrecedesConnectivityAndRemoteWork() {
        val repository = source("data/CallerInfoRepository.kt")
        val localIndex = repository.indexOf("safelyReadLocal(number)")
        val connectivityIndex = repository.indexOf("if (!isNetworkAvailable())")
        val remoteIndex = repository.indexOf("sharedRemoteLookup(number)")

        assertTrue(localIndex >= 0)
        assertTrue(connectivityIndex > localIndex)
        assertTrue(remoteIndex > connectivityIndex)
    }

    @Test
    fun onlyUsefulCurrentRemoteResultsAreSaved() {
        val repository = source("data/CallerInfoRepository.kt")

        assertTrue(repository.contains("isCallerRecordSafeToPersist"))
        assertTrue(repository.contains("hasUsefulCallerInformation(callerInfo)"))
        assertTrue(repository.contains("lastSuccessfullyUpdatedMillis = savedAt"))
        assertFalse(repository.contains("insertCallerInfo(finalResult)"))
    }

    @Test
    fun staleLocalDataIsPresentedBeforeSharedRefresh() {
        val repository = source("data/CallerInfoRepository.kt")
        val presentIndex = repository.indexOf("onLocalResult(localResult)")
        val refreshIndex = repository.indexOf("sharedRemoteLookup(number)")

        assertTrue(presentIndex >= 0)
        assertTrue(refreshIndex > presentIndex)
        assertTrue(repository.contains("singleFlight.run(number)"))
        assertTrue(repository.contains("RemoteBotTransactionCoordinator.run"))
    }

    @Test
    fun failedRefreshReturnsTheExistingLocalRecord() {
        val repository = source("data/CallerInfoRepository.kt")

        assertTrue(repository.contains("is RemoteLookupOutcome.Failure -> {"))
        assertTrue(repository.contains("queueRetry(number, remote.reason, lookupPendingQueueEpoch)"))
        assertTrue(repository.contains("pendingQueueEpoch.get() != expectedPendingQueueEpoch"))
        assertTrue(repository.contains("localResult"))
        assertFalse(repository.contains("deleteByNumber(number)"))
    }

    @Test
    fun retryItemsContainOnlyFourAllowedFields() {
        val pending = source("data/database/PendingCallerLookupEntity.kt")
        val propertyLines = pending.lineSequence()
            .map(String::trim)
            .filter { it.startsWith("val ") || it.startsWith("@PrimaryKey val ") }
            .toList()

        assertTrue(propertyLines.size == 4)
        assertTrue(pending.contains("normalizedNumber"))
        assertTrue(pending.contains("createdTimestampMillis"))
        assertTrue(pending.contains("nextEligibleRetryTimestampMillis"))
        assertTrue(pending.contains("attemptCount"))
    }

    @Test
    fun retryProcessingIsUniqueConstrainedAndBounded() {
        val worker = source("OfflineLookupWorker.kt")
        val repository = source("data/CallerInfoRepository.kt")

        assertTrue(worker.contains("NetworkType.CONNECTED"))
        assertTrue(worker.contains("ExistingWorkPolicy.KEEP"))
        assertTrue(worker.contains("bounded_offline_caller_lookup"))
        assertTrue(repository.contains("CallerCachePolicy.MAXIMUM_ITEMS_PER_RUN"))
        assertFalse(worker.contains("delay("))
        assertTrue(worker.contains("setInitialDelay"))
    }

    @Test
    fun onlyDurableSuccessAndPermanentNotFoundRemoveExistingRetries() {
        val repository = source("data/CallerInfoRepository.kt")

        assertTrue(repository.contains("RemoteLookupFailure.PERMANENT_NOT_FOUND"))
        assertTrue(repository.contains("retryPolicyFor(outcome.reason)"))
        assertTrue(repository.contains("removePendingAfterSave = true"))
        assertTrue(repository.contains("db.withTransaction"))
        assertTrue(repository.contains("SaveCallerResult.Failed ->"))
    }

    @Test
    fun staleRequestsAndNoncanonicalQueueRowsCannotBeRetried() {
        val repository = source("data/CallerInfoRepository.kt")

        assertTrue(repository.contains("RemoteLookupFailure.CONNECTIVITY_UNAVAILABLE"))
        assertTrue(repository.contains("val canonicalNumber = sanitizeNumber(item.normalizedNumber)"))
        assertTrue(repository.contains("canonicalNumber != item.normalizedNumber"))
    }

    @Test
    fun clearActionIsConfirmedAndDoesNotClearPreferencesOrSessions() {
        val activity = source("MainActivity.kt")
        val repository = source("data/CallerInfoRepository.kt")

        assertTrue(activity.contains("showClearPendingLookupsDialog"))
        assertTrue(activity.contains(".setNegativeButton(android.R.string.cancel, null)"))
        assertTrue(repository.contains("suspend fun clearPendingLookups()"))
        assertTrue(repository.contains("db.pendingCallerLookupDao().clearAll()"))
        assertTrue(repository.contains("pendingQueueEpoch.incrementAndGet()"))
    }

    @Test
    fun screeningStillRespondsBeforeAllOptionalWork() {
        val coordinator = source("CallScreeningSafeguards.kt")
        val responseIndex = coordinator.indexOf("responder.respond()")
        val dispatchIndex = coordinator.indexOf("dispatcher.dispatch()")

        assertTrue(responseIndex >= 0)
        assertTrue(dispatchIndex > responseIndex)
    }

    @Test
    fun sensitiveCallerDataIsNotLogged() {
        val repository = source("data/CallerInfoRepository.kt")
        val worker = source("OfflineLookupWorker.kt")

        assertFalse(repository.contains("Log."))
        assertFalse(repository.contains("println("))
        assertFalse(worker.contains("Log."))
        assertFalse(worker.contains("println("))
    }

    @Test
    fun clearUiUsesExactLabelAndDescription() {
        val strings = source("../../../../res/values/strings.xml")
        val layout = source("../../../../res/layout/activity_main.xml")

        assertTrue(strings.contains(">Clear saved caller information</string>"))
        assertTrue(strings.contains(">Remove caller information saved for offline use</string>"))
        assertTrue(strings.contains(">Clear pending lookups</string>"))
        assertTrue(layout.contains("btnClearSavedCallerInfo"))
        assertTrue(layout.contains("btnClearPendingLookups"))
    }

    @Test
    fun enteringSettingsRefreshesPendingCountFromDurableState() {
        val activity = source("MainActivity.kt")
        val settingsBranch = activity.substringAfter("R.id.nav_settings -> {")
            .substringBefore("R.id.nav_info -> {")

        assertTrue(settingsBranch.contains("refreshPendingLookupStatus()"))
    }

    private fun source(relativePath: String): String {
        val base = File("src/main/java/com/rakibulcodes/callerinfo")
        val candidate = File(base, relativePath)
        if (candidate.exists()) return candidate.readText()
        val fromRoot = File("app", candidate.path)
        require(fromRoot.exists()) { "Missing source: $relativePath" }
        return fromRoot.readText()
    }
}
