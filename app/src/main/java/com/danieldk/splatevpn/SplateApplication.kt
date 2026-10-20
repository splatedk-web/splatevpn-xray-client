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

    override fun onCreate() {
        super.onCreate()
        if (android.os.Build.VERSION.SDK_INT >= 28 && getProcessName().endsWith(":ai_dns_verifier")) return
        if (android.os.Build.VERSION.SDK_INT < 28) {
            val manager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            if (manager.runningAppProcesses?.any { it.pid == android.os.Process.myPid() && it.processName.endsWith(":ai_dns_verifier") } == true) return
        }
        SubscriptionUpdateWorker.schedule(this, settingsManager.getAutoUpdateInterval())
        com.danieldk.splatevpn.data.AdFilterUpdateWorker.schedule(this)
    }
}
