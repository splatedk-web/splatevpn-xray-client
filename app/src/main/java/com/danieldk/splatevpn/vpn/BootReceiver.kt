package com.danieldk.splatevpn.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import androidx.core.content.ContextCompat
import com.danieldk.splatevpn.data.SettingsManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var settingsManager: SettingsManager

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        val autoConnect = settingsManager.isAutoConnectOnBoot()
        Log.d("BootReceiver", "Device boot completed. autoConnect=$autoConnect")

        if (autoConnect) {
            val prepareIntent = VpnService.prepare(context)
            if (prepareIntent == null) {
                Log.d("BootReceiver", "Starting SplateVpnService on boot")
                val vpnIntent = Intent(context, SplateVpnService::class.java).apply {
                    action = "START"
                }
                ContextCompat.startForegroundService(context, vpnIntent)
            } else {
                Log.w("BootReceiver", "Cannot auto-start VPN on boot: VpnService requires user preparation")
            }
        }
    }
}
