package com.danieldk.splatevpn.data

import android.content.Context
import androidx.work.*
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

class GeoDataUpdateWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    @EntryPoint @InstallIn(SingletonComponent::class)
    interface Dependencies { fun geodata(): GeoDataRepository }
    override suspend fun doWork(): Result {
        if (!enabled(applicationContext)) return Result.success()
        if (!NetworkAccessPolicy.updatesAllowed(applicationContext) || !NetworkAccessPolicy.isDefaultUnmetered(applicationContext)) return Result.retry()
        val repository = EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java).geodata()
        return if (repository.refresh()) Result.success() else if (runAttemptCount < 2) Result.retry() else Result.failure()
    }
    companion object {
        fun enabled(context: Context) = context.getSharedPreferences("geodata_updates", Context.MODE_PRIVATE).getBoolean("automatic", false)
        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences("geodata_updates", Context.MODE_PRIVATE).edit().putBoolean("automatic", enabled).apply()
            schedule(context)
        }
        fun schedule(context: Context) {
            val work = WorkManager.getInstance(context)
            if (!enabled(context)) { work.cancelUniqueWork("splate_geodata_update"); return }
            work.enqueueUniquePeriodicWork("splate_geodata_update", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<GeoDataUpdateWorker>(24, TimeUnit.HOURS)
                    .setInitialDelay(24, TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresStorageNotLow(true).build()).build())
        }
    }
}
