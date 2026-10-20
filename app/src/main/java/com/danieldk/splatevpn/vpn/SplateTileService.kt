package com.danieldk.splatevpn.vpn

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.danieldk.splatevpn.MainActivity
import com.danieldk.splatevpn.R
import com.danieldk.splatevpn.data.ServerRepository
import com.danieldk.splatevpn.data.SettingsManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

@RequiresApi(Build.VERSION_CODES.N)
@AndroidEntryPoint
class SplateTileService : TileService() {

    @Inject
    lateinit var vpnStatusManager: VpnStatusManager

    @Inject
    lateinit var serverRepository: ServerRepository

    @Inject
    lateinit var settingsManager: SettingsManager

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var statusJob: Job? = null

    companion object {
        fun requestUpdate(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    requestListeningState(
                        context.applicationContext,
                        ComponentName(context.applicationContext, SplateTileService::class.java)
                    )
                } catch (e: Exception) {
                    // ignore if QS tile is not bound or system error
                }
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTileState(vpnStatusManager.state.value)
        statusJob?.cancel()
        statusJob = serviceScope.launch {
            vpnStatusManager.state.collect { state ->
                updateTileState(state)
            }
        }
    }

    override fun onStopListening() {
        super.onStopListening()
        statusJob?.cancel()
        statusJob = null
    }

    override fun onClick() {
        super.onClick()
        val currentState = vpnStatusManager.state.value
        val isConnectedOrConnecting = currentState == VpnState.CONNECTED ||
                currentState == VpnState.SELECTING_SERVER ||
                currentState == VpnState.STARTING_CORE ||
                currentState == VpnState.CONNECTING ||
                currentState == VpnState.HEALTH_CHECKING ||
                currentState == VpnState.RECONNECTING

        if (isConnectedOrConnecting) {
            // Мгновенная визуальная обратная связь в шторке
            val tile = qsTile
            if (tile != null) {
                tile.state = Tile.STATE_INACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Отключение..."
                }
                tile.updateTile()
            }
            val stopIntent = Intent(this, SplateVpnService::class.java).apply {
                action = "STOP"
            }
            startService(stopIntent)
        } else {
            val vpnPrepareIntent = VpnService.prepare(this)
            if (vpnPrepareIntent != null) {
                // Требуется подтверждение разрешения VPN диалогом системы
                val appIntent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("AUTO_CONNECT", true)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val pendingIntent = PendingIntent.getActivity(
                        this, 0, appIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    startActivityAndCollapse(pendingIntent)
                } else {
                    @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
                    startActivityAndCollapse(appIntent)
                }
            } else {
                // Разрешение уже есть — мгновенно стартуем VPN
                val tile = qsTile
                if (tile != null) {
                    tile.state = Tile.STATE_ACTIVE
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        tile.subtitle = "Подключение..."
                    }
                    tile.updateTile()
                }
                val startIntent = Intent(this, SplateVpnService::class.java).apply {
                    action = "START"
                }
                ContextCompat.startForegroundService(this, startIntent)
            }
        }
    }

    private fun updateTileState(state: VpnState) {
        val tile = qsTile ?: return

        tile.state = when (state) {
            VpnState.CONNECTED -> Tile.STATE_ACTIVE
            VpnState.SELECTING_SERVER, VpnState.STARTING_CORE, VpnState.CONNECTING, VpnState.HEALTH_CHECKING, VpnState.RECONNECTING -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }

        tile.label = "SplateVPN"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_vpn)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when (state) {
                VpnState.CONNECTED -> {
                    val serverName = settingsManager.getSelectedServerName()
                    if (serverName.isNotBlank()) serverName else "Подключено"
                }
                VpnState.SELECTING_SERVER, VpnState.STARTING_CORE, VpnState.CONNECTING, VpnState.HEALTH_CHECKING, VpnState.RECONNECTING -> "Подключение..."
                VpnState.FAILED -> "Ошибка"
                else -> "Отключено"
            }
        }

        tile.updateTile()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
