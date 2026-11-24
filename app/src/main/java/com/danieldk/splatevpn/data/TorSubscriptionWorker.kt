package com.danieldk.splatevpn.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

class TorSubscriptionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies { fun torRepository(): TorRepository }

    override suspend fun doWork(): Result {
        try {
            if (!NetworkAccessPolicy.updatesAllowed(applicationContext)) return Result.retry()
            val repository = EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java).torRepository()
            val settings = repository.load()
            if (!settings.autoUpdate) return Result.success()
            val errors = settings.bridgeSubscriptions.mapNotNull { source ->
                repository.updateSubscription(source.url, refreshOnly = true).exceptionOrNull()
            }
            return when {
                errors.isEmpty() -> Result.success()
                runAttemptCount < 3 && errors.any(::isRetryableSubscriptionFailure) -> Result.retry()
                else -> Result.failure()
            }
        } catch (error: CancellationException) { throw error
        } catch (_: Exception) { return if (runAttemptCount < 3) Result.retry() else Result.failure() }
    }

    companion object {
        private const val NAME = "splate_tor_subscription_update"
        fun schedule(context: Context, enabled: Boolean) {
            val manager = WorkManager.getInstance(context)
            if (!enabled) { manager.cancelUniqueWork(NAME); return }
            val work = PeriodicWorkRequestBuilder<TorSubscriptionWorker>(12, TimeUnit.HOURS)
                .setInitialDelay(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            manager.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, work)
        }
    }
}
