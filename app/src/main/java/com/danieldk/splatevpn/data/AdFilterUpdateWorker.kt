package com.danieldk.splatevpn.data

import android.content.Context
import androidx.work.*
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

class AdFilterUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    @EntryPoint @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun filters(): AdFilterRepository
        fun settings(): SettingsManager
    }
    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java)
        if (!deps.settings().isAdBlocker()) return Result.success()
        // A background update does not interrupt the VPN; the next connection loads the new base.
        return if (deps.filters().refresh()) Result.success() else Result.retry()
    }
    companion object {
        fun schedule(context: Context) {
            val work = PeriodicWorkRequestBuilder<AdFilterUpdateWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(24, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("splate_ad_filter_update", ExistingPeriodicWorkPolicy.KEEP, work)
        }
    }
}
