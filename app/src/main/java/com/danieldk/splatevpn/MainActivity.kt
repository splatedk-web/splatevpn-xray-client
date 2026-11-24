package com.danieldk.splatevpn

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.danieldk.splatevpn.ui.theme.UiMotionDurationScale
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import com.danieldk.splatevpn.ui.MainViewModel
import com.danieldk.splatevpn.ui.screens.MainScreen
import com.danieldk.splatevpn.ui.theme.SplateVPNTheme
import com.danieldk.splatevpn.vpn.SplateVpnService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job

import android.os.Build
import android.content.pm.PackageManager
import android.Manifest
import android.util.Log

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private var entryConnectionAttempted: Boolean
        get() = viewModel.entryConnectionAttempted
        set(value) { viewModel.entryConnectionAttempted = value }
    private var vpnPermissionPending = false
    private var entryConnectionJob: Job? = null

    private val viewModel: MainViewModel by viewModels()

    private val vpnPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        vpnPermissionPending = false
        entryConnectionAttempted = true
        Log.d("MainActivity", "vpnPermissionLauncher result: ${result.resultCode}")
        if (result.resultCode == Activity.RESULT_OK) startVpnServiceInternal()
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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

        fun systemAnimationScale() = runCatching {
            Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f)
        val motionScale = UiMotionDurationScale(viewModel.performanceSettings.value.animationsEnabled, systemAnimationScale())
        val scaleObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { motionScale.setSystemScale(systemAnimationScale()) }
        }
        val observingScale = runCatching {
            contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, scaleObserver)
        }.isSuccess
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                if (observingScale) contentResolver.unregisterContentObserver(scaleObserver)
            }
        })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.performanceSettings.collect { motionScale.setAnimationsEnabled(it.animationsEnabled) }
            }
        }
        val uiRecomposer = window.decorView.createLifecycleAwareWindowRecomposer(motionScale, lifecycle)
        setContent(parent = uiRecomposer) {
            val needsOnboarding by viewModel.needsOnboarding.collectAsState()
            val themeSetting by viewModel.appTheme.collectAsState()
            val languageSetting by viewModel.appLanguage.collectAsState()
            val performance by viewModel.performanceSettings.collectAsState()
            val television = remember { com.danieldk.splatevpn.ui.screens.isTelevisionDevice(this@MainActivity) }
            CompositionLocalProvider(com.danieldk.splatevpn.ui.components.LocalTvControls provides television) {
                SplateVPNTheme(
                    themeSetting = themeSetting,
                    languageSetting = languageSetting,
                    performanceSettings = performance
                ) {
                    if (needsOnboarding) com.danieldk.splatevpn.ui.screens.OnboardingScreen(viewModel, themeSetting, ::requestVpnConnection) else MainScreen(
                        viewModel = viewModel,
                        onToggleConnect = {
                            val state = viewModel.vpnState.value
                            val shouldStart = state == com.danieldk.splatevpn.vpn.VpnState.DISCONNECTED ||
                                state == com.danieldk.splatevpn.vpn.VpnState.FAILED
                            Log.d("MainActivity", "onToggleConnect clicked, state=$state")
                            if (shouldStart) {
                                requestVpnConnection()
                            } else {
                                stopVpnServiceInternal()
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (entryConnectionAttempted || vpnPermissionPending) return
        entryConnectionAttempted = true
        entryConnectionJob = lifecycleScope.launch {
            viewModel.isStartupLoading.first { !it }
            lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.STARTED) }
            if (viewModel.autoConnectOnOpen.value && !viewModel.needsOnboarding.value) requestVpnConnection()
        }
    }

    override fun onStop() {
        entryConnectionJob?.cancel()
        if (!vpnPermissionPending && !isChangingConfigurations) entryConnectionAttempted = false
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        checkVpnStateSync()
    }

    private fun requestVpnConnection() {
        if (viewModel.shouldDeferTvSetupConnection()) return
        if (viewModel.vpnState.value !in setOf(com.danieldk.splatevpn.vpn.VpnState.DISCONNECTED, com.danieldk.splatevpn.vpn.VpnState.FAILED) || vpnPermissionPending) return
        entryConnectionAttempted = true
        val permission = VpnService.prepare(this)
        if (permission != null) {
            vpnPermissionPending = true
            vpnPermissionLauncher.launch(permission)
        } else startVpnServiceInternal()
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
        if (viewModel.shouldDeferTvSetupConnection()) return
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
        try { handleCheckedIntentExtras(intent) }
        catch (_: android.os.BadParcelableException) { Log.w("MainActivity", "Rejected invalid external intent data") }
        catch (_: ClassCastException) { Log.w("MainActivity", "Rejected invalid external intent extra types") }
    }

    private fun handleCheckedIntentExtras(intent: Intent?) {
        if (intent == null) return
        if (intent.action == android.service.quicksettings.TileService.ACTION_QS_TILE_PREFERENCES) {
            viewModel.selectTab(com.danieldk.splatevpn.ui.screens.AppTab.HOME)
            return
        }
        val openTab = intent.getStringExtra("OPEN_TAB")
        if (openTab == "servers") {
            viewModel.selectTab(com.danieldk.splatevpn.ui.screens.AppTab.SERVERS)
            intent.removeExtra("OPEN_TAB")
        }
        val autoConnect = intent.getBooleanExtra("AUTO_CONNECT", false)
        if (autoConnect) {
            intent.removeExtra("AUTO_CONNECT")
            if (InternalActivityEntry.isInternal(this, intent)) requestVpnConnection()
        }

        // Handle incoming deep link URLs (vless://, vmess://, trojan://, ss://, sub://, etc.)
        val uri = intent.data
        if (uri != null) {
            val fullUrl = uri.toString()
            intent.data = null
            Log.d("MainActivity", "Received deep link: ${uri.scheme}://...")
            if (viewModel.receiveDeviceTransferInvitation(fullUrl)) return

            val promptMessage = if (com.danieldk.splatevpn.data.LinkParser.isSubscriptionImportLink(fullUrl) ||
                fullUrl.startsWith("http://", ignoreCase = true) || fullUrl.startsWith("https://", ignoreCase = true)
            ) {
                "Импортировать подписку из внешней ссылки?"
            } else {
                "Импортировать конфигурацию прокси из внешней ссылки?"
            }

            android.app.AlertDialog.Builder(this)
                .setTitle("Импорт конфигурации")
                .setMessage("$promptMessage\n\n${uri.scheme}://${uri.host ?: ""}")
                .setPositiveButton("Импортировать") { _, _ ->
                    viewModel.addServersFromLinks(fullUrl, onImportError = { message ->
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    }) { count ->
                        if (count > 0) {
                            Toast.makeText(this, "Импортировано узлов: $count", Toast.LENGTH_SHORT).show()
                            viewModel.selectTab(com.danieldk.splatevpn.ui.screens.AppTab.SERVERS)
                        } else {
                            Toast.makeText(this, "Не удалось распознать ссылку или подписку", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton("Отмена", null)
                .show()
        }
    }
}
