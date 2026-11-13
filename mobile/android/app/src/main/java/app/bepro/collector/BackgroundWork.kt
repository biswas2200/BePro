package app.bepro.collector

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Periodic collection. Uploads opportunistically; data stays buffered when offline. */
class CollectWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result {
    CollectionRunner.run(applicationContext)
    return Result.success()
  }
}

/** One-off sync with a network constraint, retried with exponential backoff on failure. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result {
    val result = CollectionRunner.run(applicationContext)
    val uploadFailed = result.error?.contains("upload:") == true
    return if (uploadFailed && runAttemptCount < 8) Result.retry() else Result.success()
  }
}

object BackgroundWork {

  private const val PERIODIC = "bepro-collect"
  private const val ONE_TIME = "bepro-sync-once"

  fun schedule(context: Context) {
    val request = PeriodicWorkRequestBuilder<CollectWorker>(15, TimeUnit.MINUTES)
      // "Battery not low" is false below ~15% unless charging, matching the plan's battery rule.
      .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
      .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
      .build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
  }

  fun syncSoon(context: Context) {
    val request = OneTimeWorkRequestBuilder<SyncWorker>()
      .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
      .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
      .build()
    WorkManager.getInstance(context).enqueueUniqueWork(ONE_TIME, ExistingWorkPolicy.KEEP, request)
  }

  fun cancel(context: Context) {
    WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
    WorkManager.getInstance(context).cancelUniqueWork(ONE_TIME)
  }
}
