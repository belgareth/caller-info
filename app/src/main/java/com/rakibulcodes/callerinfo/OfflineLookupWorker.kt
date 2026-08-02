package com.rakibulcodes.callerinfo

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.rakibulcodes.callerinfo.data.CallerInfoRepository
import com.rakibulcodes.callerinfo.data.WorkerRunAction
import com.rakibulcodes.callerinfo.data.decideWorkerRunAction
import com.rakibulcodes.callerinfo.data.database.AppDatabase
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

class OfflineLookupWorker(
    context: Context,
    workerParameters: WorkerParameters
) : CoroutineWorker(context, workerParameters) {
    override suspend fun doWork(): Result =
        try {
            val runResult = CallerInfoRepository.getInstance(applicationContext)
                .processPendingLookups()
            when (
                val action = decideWorkerRunAction(
                    runResult,
                    System.currentTimeMillis()
                )
            ) {
                WorkerRunAction.Success -> Result.success()
                WorkerRunAction.Retry -> boundedRetry()
                is WorkerRunAction.ScheduleContinuation -> {
                    OfflineLookupScheduler.enqueueContinuation(
                        applicationContext,
                        action.initialDelayMillis
                    )
                    Result.success()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            boundedRetry()
        }

    private suspend fun boundedRetry(): Result =
        if (runAttemptCount + 1 < MAXIMUM_WORKER_ATTEMPTS) {
            Result.retry()
        } else {
            val hasPending = try {
                AppDatabase.getDatabase(applicationContext)
                    .pendingCallerLookupDao()
                    .count() > 0
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                true
            }
            if (hasPending) {
                OfflineLookupScheduler.enqueueContinuation(
                    applicationContext,
                    WORK_MANAGER_EXHAUSTION_DELAY_MILLIS
                )
            }
            Result.success()
        }

    companion object {
        private const val MAXIMUM_WORKER_ATTEMPTS = 3
        private const val WORK_MANAGER_EXHAUSTION_DELAY_MILLIS = 30 * 60 * 1000L
    }
}

object OfflineLookupScheduler {
    private const val UNIQUE_WORK_NAME = "bounded_offline_caller_lookup"

    fun enqueue(context: Context) {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request(initialDelayMillis = 0)
        )
    }

    fun enqueueContinuation(context: Context, initialDelayMillis: Long) {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request(initialDelayMillis)
        )
    }

    fun enqueueUserRequested(context: Context) {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request(initialDelayMillis = 0)
        )
    }

    suspend fun enqueueAuthorizationReady(context: Context) {
        if (
            AppDatabase.getDatabase(context.applicationContext)
                .pendingCallerLookupDao()
                .count() > 0
        ) {
            enqueueContinuation(context, initialDelayMillis = 0)
        }
    }

    private fun request(initialDelayMillis: Long) =
        OneTimeWorkRequestBuilder<OfflineLookupWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setInitialDelay(initialDelayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                15,
                TimeUnit.MINUTES
            )
            .build()
}
