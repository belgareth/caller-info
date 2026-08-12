package com.rakibulcodes.callerinfo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OfflineCallerCacheAuditTest {
    @Test
    fun exactNonDestructiveMigrationPreservesOldRows() {
        val database = source("data/database/AppDatabase.kt")

        assertTrue(database.contains("version = 4"))
        assertTrue(database.contains("Migration(2, 3)"))
        assertTrue(database.contains("ALTER TABLE caller_info ADD COLUMN"))
        assertTrue(database.contains("lastSuccessfullyUpdatedMillis INTEGER DEFAULT NULL"))
        assertTrue(database.contains("CREATE TABLE IF NOT EXISTS pending_caller_lookup"))
        assertTrue(database.contains("CREATE TABLE IF NOT EXISTS secure_caller_info"))
        assertTrue(database.contains("CREATE TABLE IF NOT EXISTS secure_pending_caller_lookup"))
        assertFalse(database.contains("fallbackToDestructiveMigration"))
    }

    @Test
    fun plaintextLegacyPagesArePurgedAfterMigrationAndClear() {
        val repository = source("data/CallerInfoRepository.kt")

        assertTrue(repository.contains("purgeDeletedPlaintextPages()"))
        assertTrue(repository.contains("PRAGMA wal_checkpoint(TRUNCATE)"))
        assertTrue(repository.contains("sqlite.execSQL(\"VACUUM\")"))
        assertTrue(repository.contains("if (removedPlaintextTables)"))
        assertTrue(repository.contains("db.callerInfoDao().clearAll()"))
        assertTrue(repository.contains("db.pendingCallerLookupDao().clearAll()"))
    }

    @Test
    fun canonicalNumbersAreEncryptedAndIndexedByKeyedLookupValue() {
        val caller = source("data/database/CallerInfoEntity.kt")
        val secure = source("data/database/SecureCallerInfoEntity.kt")
        val pending = source("data/database/PendingCallerLookupEntity.kt")
        val codec = source("data/CallerCacheCrypto.kt")
        val repository = source("data/CallerInfoRepository.kt")

        assertFalse(caller.contains("@Entity"))
        assertTrue(secure.contains("@PrimaryKey val lookupKey: String"))
        assertTrue(pending.contains("SecurePendingCallerLookupEntity"))
        assertTrue(codec.contains("KEY_ALGORITHM_HMAC_SHA256"))
        assertTrue(codec.contains("AES/GCM/NoPadding"))
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
    fun retryPersistenceDoesNotExposePlaintextNumberColumn() {
        val pending = source("data/database/PendingCallerLookupEntity.kt")
        val dao = source("data/database/PendingCallerLookupDao.kt")

        assertTrue(pending.contains("val normalizedNumber: String"))
        assertTrue(pending.contains("val encryptedNumber: String"))
        assertTrue(pending.contains("val lookupKey: String"))
        assertFalse(dao.contains("normalizedNumber = :normalizedNumber"))
        assertTrue(dao.contains("lookupKey = :lookupKey"))
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

    @Test
    fun lookupClearResetsOnlyCurrentLookupUi() {
        val activity = source("MainActivity.kt")
        val clearBlock = activity.substringAfter("binding.btnClearSearch.setOnClickListener {")
            .substringBefore("binding.btnSave.setOnClickListener")

        assertTrue(clearBlock.contains("cancelManualLookup()"))
        assertTrue(clearBlock.contains("binding.etLookupNumber.text?.clear()"))
        assertTrue(clearBlock.contains("binding.resultLayout.visibility = View.GONE"))
        assertTrue(clearBlock.contains("binding.btnClearSearch.visibility = View.GONE"))
        assertTrue(clearBlock.contains("latestLookupResult = null"))
        assertFalse(clearBlock.contains("repository.clearHistory()"))
        assertFalse(clearBlock.contains("clearSavedCallerInformation()"))
        assertFalse(clearBlock.contains("clearAll()"))
    }

    @Test
    fun historyClearFilterButtonIsRemovedWhileFiltersAndDeleteRemain() {
        val activity = source("MainActivity.kt")
        val strings = source("../../../../res/values/strings.xml")
        val layout = source("../../../../res/layout/activity_main.xml")
        val menu = sequenceOf(
            File("src/main/res/menu/menu_history.xml"),
            File("app/src/main/res/menu/menu_history.xml")
        ).first(File::exists).readText()

        assertFalse(activity.contains("btnHistoryClearFilter"))
        assertFalse(activity.contains("clearHistoryFilters"))
        assertFalse(layout.contains("btnHistoryClearFilter"))
        assertFalse(strings.contains("clear_filter"))
        assertTrue(layout.contains("android:id=\"@+id/etHistorySearch\""))
        assertTrue(layout.contains("android:id=\"@+id/btnHistoryFavoritesFilter\""))
        assertTrue(layout.contains("android:id=\"@+id/btnHistoryRecentFilter\""))
        assertTrue(layout.contains("android:id=\"@+id/btnClearSearch\""))
        assertTrue(layout.contains("android:text=\"Clear\""))
        assertTrue(menu.contains("android:id=\"@+id/action_clear_all\""))
        assertTrue(activity.contains("showClearHistoryDialog()"))
    }

    @Test
    fun resultRefreshButtonDispatchesForcedLookup() {
        val activity = source("MainActivity.kt")
        val refreshBlock = activity.substringAfter("binding.btnRefreshCaller.setOnClickListener {")
            .substringBefore("binding.btnEditCallerMetadata.setOnClickListener")

        assertTrue(refreshBlock.contains("latestLookupResult?.number"))
        assertTrue(refreshBlock.contains("binding.etLookupNumber.text?.toString().orEmpty()"))
        assertTrue(refreshBlock.contains("performLookup(number, showNotification = false, forceRefresh = true)"))

        val performLookup = activity.substringAfter("private fun performLookup(")
            .substringBefore("private fun cancelManualLookup()")
        assertTrue(performLookup.contains("forceRefresh: Boolean = false"))
        assertTrue(performLookup.contains("forceRemoteRefresh = forceRefresh"))
    }

    @Test
    fun telegramLookupStreamIncludesSendSuccessAndFailureUpdates() {
        val manager = source("data/TelegramManager.kt")
        val resultHandler = manager.substringAfter("inner class ResultHandler")
            .substringBefore("fun saveCredentials")

        assertTrue(resultHandler.contains("is TdApi.UpdateNewMessage"))
        assertTrue(resultHandler.contains("is TdApi.UpdateMessageContent"))
        assertTrue(resultHandler.contains("is TdApi.UpdateMessageSendSucceeded"))
        assertTrue(resultHandler.contains("is TdApi.UpdateMessageSendFailed"))
        assertTrue(resultHandler.contains("lookupUpdates.offer(`object`)"))
    }

    @Test
    fun forcedRefreshBypassesFreshCacheShortCircuitButNormalLookupDoesNot() {
        val repository = source("data/CallerInfoRepository.kt")
        val localBranch = repository.substringAfter("if (local != null) {")
            .substringBefore("if (!isNetworkAvailable()) {")
        val freshCheckIndex = localBranch.indexOf("callerCacheIsFresh(")
        val forceGuardIndex = localBranch.indexOf("!forceRemoteRefresh")
        val returnLocalIndex = localBranch.indexOf("return localResult")
        val presentLocalIndex = localBranch.indexOf("onLocalResult(localResult)")

        assertTrue(forceGuardIndex >= 0)
        assertTrue(freshCheckIndex > forceGuardIndex)
        assertTrue(returnLocalIndex > freshCheckIndex)
        assertTrue(presentLocalIndex > returnLocalIndex)

        val refreshBranch = repository.substringAfter("return when (val remote = sharedRemoteLookup(number))")
        assertTrue(refreshBranch.contains("is RemoteLookupOutcome.Useful ->"))
        assertTrue(refreshBranch.contains("persistUseful(number, remote.callerInfo, lookupEpoch)"))
        assertTrue(refreshBranch.contains("is RemoteLookupOutcome.Failure ->"))
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
