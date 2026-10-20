package com.danieldk.splatevpn.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.danieldk.splatevpn.MainActivity
import com.danieldk.splatevpn.R
import com.danieldk.splatevpn.data.SettingsManager
import com.danieldk.splatevpn.vpn.SplateVpnService
import com.danieldk.splatevpn.vpn.VpnState
import com.danieldk.splatevpn.vpn.VpnStatusManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

class SplateAppWidgetProvider : AppWidgetProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WidgetEntryPoint {
        fun vpnStatusManager(): VpnStatusManager
        fun settingsManager(): SettingsManager
    }

    companion object {
        const val ACTION_TOGGLE_VPN = "com.danieldk.splatevpn.ACTION_TOGGLE_VPN"
        const val EXTRA_OPEN_TAB = "OPEN_TAB"

        fun notifyUpdate(context: Context) {
            try {
                val widgetManager = AppWidgetManager.getInstance(context)
                val ids = widgetManager.getAppWidgetIds(
                    ComponentName(context, SplateAppWidgetProvider::class.java)
                )
                if (ids != null && ids.isNotEmpty()) {
                    val intent = Intent(context, SplateAppWidgetProvider::class.java).apply {
                        action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                    }
                    context.sendBroadcast(intent)
                }
            } catch (e: Exception) {
                // Ignore during shutdown or uninstalled widget
            }
        }

        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            appWidgetManager.updateAppWidget(appWidgetId, createViews(context, appWidgetManager.getAppWidgetOptions(appWidgetId)))
        }

        internal fun createViews(context: Context, options: Bundle): RemoteViews {
            val entryPoint = try {
                EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
            } catch (e: Exception) {
                null
            }

            val vpnStatusManager = entryPoint?.vpnStatusManager()
            val settingsManager = entryPoint?.settingsManager()

            val state = vpnStatusManager?.state?.value ?: if (SplateVpnService.isServiceActive) {
                VpnState.CONNECTED
            } else {
                VpnState.DISCONNECTED
            }

            val serverName = settingsManager?.getSelectedServerName() ?: run {
                val prefs = context.getSharedPreferences("splate_prefs", Context.MODE_PRIVATE)
                prefs.getString("selected_server_name", "") ?: ""
            }

            val countryFlag = vpnStatusManager?.countryFlag?.value ?: "🌐"
            val rttMs = vpnStatusManager?.rttMs?.value ?: -1L
            val downSpeed = vpnStatusManager?.downloadSpeed?.value ?: "0 KB/s"
            val upSpeed = vpnStatusManager?.uploadSpeed?.value ?: "0 KB/s"

            val views = RemoteViews(context.packageName, R.layout.widget_splate)
            val strings = widgetResources(context)
            val (width, height) = widgetDimensions(context, options, 300, 128)
            val fontScale = context.resources.configuration.fontScale.coerceAtLeast(1f)
            views.setViewVisibility(R.id.widget_status_row, if (height >= 60 * fontScale) View.VISIBLE else View.GONE)
            val showPing = width >= 350 * fontScale && state == VpnState.CONNECTED && rttMs > 0
            views.setViewVisibility(R.id.widget_icon_container, if (width >= 260 * fontScale) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widget_servers_btn, if (width >= 390 * fontScale) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widget_details, if (height >= 116 * fontScale) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widget_brand_title, if (height >= 100 * fontScale) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widget_metrics, if (state == VpnState.CONNECTED) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widget_stats, if (state == VpnState.CONNECTED) View.GONE else View.VISIBLE)
            views.setTextViewText(R.id.widget_download_label, strings.getString(R.string.widget_download))
            views.setTextViewText(R.id.widget_upload_label, strings.getString(R.string.widget_upload))
            views.setTextViewText(R.id.widget_download, downSpeed)
            views.setTextViewText(R.id.widget_upload, upSpeed)
            views.setContentDescription(R.id.widget_servers_btn, strings.getString(R.string.widget_servers))

            val icon = when (state) {
                VpnState.CONNECTED -> R.drawable.ic_widget_shield_on
                VpnState.DISCONNECTED, VpnState.FAILED -> R.drawable.ic_widget_shield_off
                else -> R.drawable.ic_widget_shield_connecting
            }
            views.setImageViewResource(R.id.widget_icon, icon)
            views.setViewVisibility(R.id.widget_ping, if (showPing) View.VISIBLE else View.GONE)

            val statusId = when (state) {
                VpnState.CONNECTED -> R.string.widget_connected
                VpnState.DISCONNECTED -> R.string.widget_disconnected
                VpnState.FAILED -> R.string.widget_failed
                else -> R.string.widget_connecting
            }
            val accent = WidgetAppearance(context).statusColor(state)
            val serverLabel = if (serverName.isNotBlank()) {
                if (state == VpnState.CONNECTED && countryFlag.isNotBlank() && countryFlag != "🌐" &&
                    !serverName.trimStart().startsWith(countryFlag)) "$countryFlag $serverName" else serverName
            } else strings.getString(if (state == VpnState.CONNECTED) R.string.widget_tunnel_active else R.string.widget_choose_server)
            views.setTextViewText(R.id.widget_status, strings.getString(statusId))
            views.setTextColor(R.id.widget_status, accent)
            views.setTextViewText(R.id.widget_server, serverLabel)
            views.setTextViewText(R.id.widget_ping, strings.getString(R.string.widget_ping, rttMs.coerceAtLeast(0)))
            views.setTextViewText(R.id.widget_stats, when (state) {
                VpnState.CONNECTED -> strings.getString(R.string.widget_traffic, downSpeed, upSpeed)
                VpnState.DISCONNECTED -> strings.getString(R.string.widget_tap_to_connect)
                VpnState.FAILED -> strings.getString(R.string.widget_tap_to_retry)
                else -> strings.getString(R.string.widget_starting)
            })
            views.setContentDescription(R.id.widget_action_btn, strings.getString(
                if (state != VpnState.DISCONNECTED && state != VpnState.FAILED) R.string.widget_stop_vpn else R.string.widget_start_vpn
            ))
            views.setContentDescription(R.id.widget_root, "${strings.getString(statusId)}. $serverLabel")
            WidgetAppearance(context).applyFull(views, state, width, height)

            // Click on widget body -> Open MainActivity (Home)
            val appIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val appPendingIntent = PendingIntent.getActivity(
                context,
                100,
                appIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, appPendingIntent)

            // Click on Servers shortcut -> Open MainActivity on Servers Tab
            val serversIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_OPEN_TAB, "servers")
            }
            val serversPendingIntent = PendingIntent.getActivity(
                context,
                102,
                serversIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_servers_btn, serversPendingIntent)
            views.setOnClickPendingIntent(R.id.widget_server, serversPendingIntent)

            // Click on Action button -> Toggle VPN
            val toggleIntent = Intent(context, SplateAppWidgetProvider::class.java).apply {
                action = ACTION_TOGGLE_VPN
            }
            val togglePendingIntent = PendingIntent.getBroadcast(
                context,
                101,
                toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_action_btn, togglePendingIntent)

            return views
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        updateWidget(context, manager, id)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) notifyUpdate(context)

        if (intent.action == ACTION_TOGGLE_VPN) {
            val isConnected = widgetCanStop(context)
            val widgetManager = AppWidgetManager.getInstance(context)
            val ids = widgetManager.getAppWidgetIds(ComponentName(context, SplateAppWidgetProvider::class.java))

            // Мгновенная визуальная обратная связь для пользователя
            if (ids != null && ids.isNotEmpty()) {
                val views = RemoteViews(context.packageName, R.layout.widget_splate)
                val strings = widgetResources(context)
                if (isConnected) {
                    views.setTextViewText(R.id.widget_status, strings.getString(R.string.widget_disconnecting))
                    views.setTextViewText(R.id.widget_stats, strings.getString(R.string.widget_stopping))
                } else {
                    views.setTextViewText(R.id.widget_status, strings.getString(R.string.widget_connecting))
                    views.setTextViewText(R.id.widget_stats, strings.getString(R.string.widget_starting))
                }
                WidgetAppearance(context).applyFullState(views, VpnState.CONNECTING)
                views.setViewVisibility(R.id.widget_metrics, View.GONE)
                views.setViewVisibility(R.id.widget_stats, View.VISIBLE)
                views.setViewVisibility(R.id.widget_ping, View.GONE)
                widgetManager.partiallyUpdateAppWidget(ids, views)
            }

            if (isConnected) {
                val stopIntent = Intent(context, SplateVpnService::class.java).apply {
                    action = "STOP"
                }
                context.startService(stopIntent)
            } else {
                val prepareIntent = VpnService.prepare(context)
                if (prepareIntent != null) {
                    val appIntent = Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra("AUTO_CONNECT", true)
                    }
                    context.startActivity(appIntent)
                } else {
                    val startIntent = Intent(context, SplateVpnService::class.java).apply {
                        action = "START"
                    }
                    ContextCompat.startForegroundService(context, startIntent)
                }
            }

            notifyUpdate(context)
            SplateQuickWidgetProvider.notifyUpdate(context)
        }
    }
}
