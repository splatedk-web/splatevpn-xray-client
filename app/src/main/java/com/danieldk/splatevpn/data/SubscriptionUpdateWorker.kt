package com.danieldk.splatevpn.data

import android.content.Context
import android.util.Log
import androidx.work.*
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

class SubscriptionUpdateWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WorkerDependencies {
        fun serverRepository(): ServerRepository
        fun subscriptionRepository(): SubscriptionRepository
        fun appLogManager(): AppLogManager
        fun settingsManager(): SettingsManager
    }

    override suspend fun doWork(): Result {
        return try {
            val entryPoint = EntryPointAccessors.fromApplication(
                applicationContext,
                WorkerDependencies::class.java
            )
            val serverRepo = entryPoint.subscriptionRepository()
            val logger = entryPoint.appLogManager()
            val settings = entryPoint.settingsManager()
            if (!NetworkAccessPolicy.updatesAllowed(applicationContext)) return Result.retry()
            if (!SubscriptionUpdatePolicy.canRun(settings.getAutoUpdateInterval(),
                    settings.needsOnboarding.value, settings.canUseNetworkSettings())) return Result.success()

            logger.i("SubscriptionWorker", "Запуск фонового обновления подписок...")
            val report = serverRepo.refreshSubscriptions(manual = false, retryOnly = runAttemptCount > 0,
                dueOnly = inputData.getBoolean(DUE_ONLY, false))
            val count = report.updated
            logger.i("SubscriptionWorker", "Фоновое обновление подписок завершено (обновлено: $count)")
            if (report.failures.isNotEmpty()) logger.w("SubscriptionWorker", "Не обновлено: ${report.failures.size}; временных ошибок: ${report.retryableFailures}")
            when {
                report.retryableFailures > 0 && runAttemptCount < 3 -> Result.retry()
                report.failures.isNotEmpty() -> Result.failure()
                else -> Result.success()
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) {
            Log.e("SubscriptionWorker", "Ошибка при фоновом обновлении подписок", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val WORK_NAME = "splate_subscription_auto_update"
        private const val CATCH_UP_WORK_NAME = "splate_subscription_catch_up"
        private const val DUE_ONLY = "due_only"

        fun schedule(context: Context, interval: String) {
            val workManager = WorkManager.getInstance(context)

            if (interval == "off") {
                Log.d("SubscriptionWorker", "Auto-update disabled, cancelling scheduled work")
                workManager.cancelUniqueWork(WORK_NAME)
                workManager.cancelUniqueWork(CATCH_UP_WORK_NAME)
                return
            }

            val hours = requireNotNull(SubscriptionUpdatePolicy.intervalHours(interval))

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<SubscriptionUpdateWorker>(hours, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setInitialDelay(hours, TimeUnit.HOURS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, SubscriptionUpdatePolicy.RETRY_DELAY_MINUTES, TimeUnit.MINUTES)
                .build()

            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest
            )
            enqueueCatchUp(context, interval)
            Log.d("SubscriptionWorker", "Scheduled auto-update every $hours hours")
        }

        /** Rechecks the database at execution time; fresh sources cause no network request. */
        fun enqueueCatchUp(context: Context, interval: String) {
            val workManager = WorkManager.getInstance(context)
            if (SubscriptionUpdatePolicy.intervalHours(interval) == null) {
                workManager.cancelUniqueWork(CATCH_UP_WORK_NAME)
                return
            }
            val request = OneTimeWorkRequestBuilder<SubscriptionUpdateWorker>()
                .setInputData(workDataOf(DUE_ONLY to true))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, SubscriptionUpdatePolicy.RETRY_DELAY_MINUTES, TimeUnit.MINUTES)
                .build()
            workManager.enqueueUniqueWork(CATCH_UP_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
