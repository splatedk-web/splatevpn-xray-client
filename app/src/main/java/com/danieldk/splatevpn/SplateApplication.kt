package com.danieldk.splatevpn

import android.app.Application
import com.danieldk.splatevpn.data.SettingsManager
import com.danieldk.splatevpn.data.SubscriptionUpdateWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class SplateApplication : Application() {

    @Inject
    lateinit var settingsManager: SettingsManager
    @Inject lateinit var anonymousReports: com.danieldk.splatevpn.data.AnonymousReportStore
    @Inject lateinit var cloudBackups: com.danieldk.splatevpn.data.CloudBackupManager

    @Inject lateinit var backupManager: dagger.Lazy<com.danieldk.splatevpn.data.BackupManager>

    override fun onCreate() {
        super.onCreate()
        if (android.os.Build.VERSION.SDK_INT >= 28 && getProcessName().endsWith(":ai_dns_verifier")) return
        if (android.os.Build.VERSION.SDK_INT < 28) {
            val manager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            if (manager.runningAppProcesses?.any { it.pid == android.os.Process.myPid() && it.processName.endsWith(":ai_dns_verifier") } == true) return
        }
        if (com.danieldk.splatevpn.data.BackupImportJournal.exists(this)) {
            kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                backupManager.get().recoverInterruptedImport()
            }
        }
        SubscriptionUpdateWorker.schedule(this, settingsManager.getAutoUpdateInterval())
        registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: android.app.Activity) {
                if (activity is MainActivity) {
                    SubscriptionUpdateWorker.enqueueCatchUp(this@SplateApplication, settingsManager.getAutoUpdateInterval())
                }
            }
            override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) = Unit
            override fun onActivityStarted(activity: android.app.Activity) = Unit
            override fun onActivityPaused(activity: android.app.Activity) = Unit
            override fun onActivityStopped(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity: android.app.Activity, state: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: android.app.Activity) = Unit
        })
        anonymousReports.installCrashHandler()
        cloudBackups.start()
        com.danieldk.splatevpn.data.AdFilterUpdateWorker.schedule(this)
        com.danieldk.splatevpn.data.GeoDataUpdateWorker.schedule(this)
    }
}
