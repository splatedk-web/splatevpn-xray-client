package com.danieldk.splatevpn.data

import android.content.Context
import androidx.work.*
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

class CloudBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    @EntryPoint @InstallIn(SingletonComponent::class)
    interface Dependencies { fun cloudBackupManager(): CloudBackupManager }
    override suspend fun doWork(): Result = try {
        val manager = EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java).cloudBackupManager()
        if (manager.backupNow(scheduled = true).isSuccess) Result.success()
        else if (runAttemptCount < 2) Result.retry() else Result.failure()
    } catch (error: CancellationException) { throw error
    } catch (_: Exception) { if (runAttemptCount < 2) Result.retry() else Result.failure() }

    companion object {
        private const val NAME = "splate_encrypted_cloud_backup"
        fun schedule(context: Context, settings: CloudBackupSettings) {
            val manager = WorkManager.getInstance(context)
            if (!settings.enabled || settings.folderMigrationNotice) { manager.cancelUniqueWork(NAME); return }
            val request = PeriodicWorkRequestBuilder<CloudBackupWorker>(settings.intervalHours.toLong(), TimeUnit.HOURS)
                .setInitialDelay(settings.intervalHours.toLong(), TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(if (settings.unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES).build()
            manager.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
