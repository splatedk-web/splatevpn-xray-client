package com.danieldk.splatevpn

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.danieldk.splatevpn.ui.MainViewModel
import com.danieldk.splatevpn.ui.screens.MainScreen
import com.danieldk.splatevpn.ui.theme.SplateVPNTheme
import com.danieldk.splatevpn.vpn.SplateVpnService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import android.util.Log

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val vpnPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        Log.d("MainActivity", "vpnPermissionLauncher result: ${result.resultCode}")
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnServiceInternal()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Subscribe to server change reconnect events
        lifecycleScope.launch {
            viewModel.reconnectEvent.collect {
                Log.d("MainActivity", "Reconnect event: seamlessly reloading new server node")
                reloadVpnServiceInternal()
            }
        }

        setContent {
            val themeSetting by viewModel.appTheme.collectAsState()
            val languageSetting by viewModel.appLanguage.collectAsState()
            SplateVPNTheme(
                themeSetting = themeSetting,
                languageSetting = languageSetting
            ) {
                MainScreen(
                    viewModel = viewModel,
                    onToggleConnect = {
                        val currentlyConnected = viewModel.isConnected.value
                        Log.d("MainActivity", "onToggleConnect clicked, currentlyConnected=$currentlyConnected")
                        if (!currentlyConnected) {
                            val intent = VpnService.prepare(this)
                            Log.d("MainActivity", "VpnService.prepare returned: $intent")
                            if (intent != null) {
                                vpnPermissionLauncher.launch(intent)
                            } else {
                                startVpnServiceInternal()
                            }
                        } else {
                            stopVpnServiceInternal()
                        }
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkVpnStateSync()
    }

    private fun checkVpnStateSync() {
        val needsPrepare = VpnService.prepare(this) != null
        val serviceRunning = SplateVpnService.isServiceActive
        if (needsPrepare || !serviceRunning) {
            if (viewModel.isConnected.value) {
                Log.w("MainActivity", "VPN state out of sync (needsPrepare=$needsPrepare, serviceRunning=$serviceRunning). Resetting UI to disconnected.")
                viewModel.syncDisconnectedState()
            }
        }
    }

    private fun startVpnServiceInternal() {
        Log.d("MainActivity", "startVpnServiceInternal starting SplateVpnService")
        val intent = Intent(this, SplateVpnService::class.java).apply {
            action = "START"
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun reloadVpnServiceInternal() {
        Log.d("MainActivity", "reloadVpnServiceInternal reloading SplateVpnService")
        val intent = Intent(this, SplateVpnService::class.java).apply {
            action = "RELOAD"
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopVpnServiceInternal() {
        Log.d("MainActivity", "stopVpnServiceInternal stopping SplateVpnService")
        val intent = Intent(this, SplateVpnService::class.java).apply {
            action = "STOP"
        }
        startService(intent)
    }
}