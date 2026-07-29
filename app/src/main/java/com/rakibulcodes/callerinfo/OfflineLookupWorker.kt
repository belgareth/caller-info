package com.rakibulcodes.callerinfo

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.rakibulcodes.callerinfo.data.CallerInfoRepository

class OfflineLookupWorker(
    context: Context,
    workerParameters: WorkerParameters
) : CoroutineWorker(context, workerParameters) {
    override suspend fun doWork(): Result =
        try {
            CallerInfoRepository.getInstance(applicationContext).processPendingLookups()
            Result.success()
        } catch (_: Exception) {
            Result.success()
        }
}

object OfflineLookupScheduler {
    private const val UNIQUE_WORK_NAME = "bounded_offline_caller_lookup"

    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<OfflineLookupWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }
}
