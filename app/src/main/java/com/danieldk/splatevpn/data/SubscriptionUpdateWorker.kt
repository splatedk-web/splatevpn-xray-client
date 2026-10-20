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
        fun appLogManager(): AppLogManager
    }

    override suspend fun doWork(): Result {
        return try {
            val entryPoint = EntryPointAccessors.fromApplication(
                applicationContext,
                WorkerDependencies::class.java
            )
            val serverRepo = entryPoint.serverRepository()
            val logger = entryPoint.appLogManager()

            logger.i("SubscriptionWorker", "Запуск фонового обновления подписок...")
            val count = serverRepo.updateAllSubscriptions()
            logger.i("SubscriptionWorker", "Фоновое обновление подписок завершено (обновлено: $count)")
            Result.success()
        } catch (e: Exception) {
            Log.e("SubscriptionWorker", "Ошибка при фоновом обновлении подписок", e)
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "splate_subscription_auto_update"

        fun schedule(context: Context, interval: String) {
            val workManager = WorkManager.getInstance(context)

            if (interval == "off") {
                Log.d("SubscriptionWorker", "Auto-update disabled, cancelling scheduled work")
                workManager.cancelUniqueWork(WORK_NAME)
                return
            }

            val hours = when (interval) {
                "6h" -> 6L
                "12h" -> 12L
                "24h" -> 24L
                else -> 24L
            }

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<SubscriptionUpdateWorker>(hours, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setInitialDelay(hours, TimeUnit.HOURS)
                .build()

            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest
            )
            Log.d("SubscriptionWorker", "Scheduled auto-update every $hours hours")
        }
    }
}
