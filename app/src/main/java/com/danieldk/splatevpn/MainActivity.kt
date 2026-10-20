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
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import android.widget.Toast
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.danieldk.splatevpn.ui.MainViewModel
import com.danieldk.splatevpn.ui.screens.MainScreen
import com.danieldk.splatevpn.ui.theme.SplateVPNTheme
import com.danieldk.splatevpn.vpn.SplateVpnService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

import android.os.Build
import android.content.pm.PackageManager
import android.Manifest
import android.util.Log

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        Log.d("MainActivity", "POST_NOTIFICATIONS permission granted: $isGranted")
    }

    private val vpnPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        Log.d("MainActivity", "vpnPermissionLauncher result: ${result.resultCode}")
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnServiceInternal()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        checkNotificationPermission()
        handleIntentExtras(intent)

        // Subscribe to server change reconnect events with lifecycle safety
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.reconnectEvent.collect {
                    Log.d("MainActivity", "Reconnect event: seamlessly reloading new server node")
                    reloadVpnServiceInternal()
                }
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
                        val state = viewModel.vpnState.value
                        val shouldStart = state == com.danieldk.splatevpn.vpn.VpnState.DISCONNECTED ||
                            state == com.danieldk.splatevpn.vpn.VpnState.FAILED
                        Log.d("MainActivity", "onToggleConnect clicked, state=$state")
                        if (shouldStart) {
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

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntentExtras(intent)
    }

    private fun handleIntentExtras(intent: Intent?) {
        if (intent == null) return
        val openTab = intent.getStringExtra("OPEN_TAB")
        if (openTab == "servers") {
            viewModel.selectTab(com.danieldk.splatevpn.ui.screens.AppTab.SERVERS)
            intent.removeExtra("OPEN_TAB")
        }
        val autoConnect = intent.getBooleanExtra("AUTO_CONNECT", false)
        if (autoConnect) {
            intent.removeExtra("AUTO_CONNECT")
            if (!viewModel.isConnected.value) {
                val vpnIntent = VpnService.prepare(this)
                if (vpnIntent != null) {
                    vpnPermissionLauncher.launch(vpnIntent)
                } else {
                    startVpnServiceInternal()
                }
            }
        }

        // Handle incoming deep link URLs (vless://, vmess://, trojan://, ss://, sub://, etc.)
        val uri = intent.data
        if (uri != null) {
            val fullUrl = uri.toString()
            intent.data = null
            Log.d("MainActivity", "Received deep link: ${uri.scheme}://...")
            viewModel.addServersFromLinks(fullUrl) { count ->
                if (count > 0) {
                    Toast.makeText(this, "Импортировано узлов: $count", Toast.LENGTH_SHORT).show()
                    viewModel.selectTab(com.danieldk.splatevpn.ui.screens.AppTab.SERVERS)
                } else {
                    Toast.makeText(this, "Не удалось распознать ссылку или подписку", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
