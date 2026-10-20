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
import com.danieldk.splatevpn.vpn.SplateVpnService
import com.danieldk.splatevpn.vpn.VpnState
import com.danieldk.splatevpn.vpn.VpnStatusManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

class SplateQuickWidgetProvider : AppWidgetProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface QuickWidgetEntryPoint {
        fun vpnStatusManager(): VpnStatusManager
    }

    companion object {
        const val ACTION_TOGGLE_VPN = "com.danieldk.splatevpn.ACTION_TOGGLE_VPN"

        fun notifyUpdate(context: Context) {
            try {
                val widgetManager = AppWidgetManager.getInstance(context)
                val ids = widgetManager.getAppWidgetIds(
                    ComponentName(context, SplateQuickWidgetProvider::class.java)
                )
                if (ids != null && ids.isNotEmpty()) {
                    val intent = Intent(context, SplateQuickWidgetProvider::class.java).apply {
                        action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                    }
                    context.sendBroadcast(intent)
                }
            } catch (e: Exception) {
                // Ignore during shutdown
            }
        }

        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            appWidgetManager.updateAppWidget(appWidgetId, createViews(context, appWidgetManager.getAppWidgetOptions(appWidgetId)))
        }

        internal fun createViews(context: Context, options: Bundle): RemoteViews {
            val entryPoint = try {
                EntryPointAccessors.fromApplication(context.applicationContext, QuickWidgetEntryPoint::class.java)
            } catch (e: Exception) {
                null
            }

            val vpnStatusManager = entryPoint?.vpnStatusManager()
            val state = vpnStatusManager?.state?.value ?: if (SplateVpnService.isServiceActive) {
                VpnState.CONNECTED
            } else {
                VpnState.DISCONNECTED
            }
            val rttMs = vpnStatusManager?.rttMs?.value ?: -1L

            val views = RemoteViews(context.packageName, R.layout.widget_splate_quick)
            val strings = widgetResources(context)
            val (width, height) = widgetDimensions(context, options, 64, 64)
            val fontScale = context.resources.configuration.fontScale.coerceAtLeast(1f)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val ringSize = minOf(44, (height - 8).coerceAtLeast(24), (width - 8).coerceAtLeast(24)).toFloat()
                views.setViewLayoutWidth(R.id.quick_widget_ring, ringSize, android.util.TypedValue.COMPLEX_UNIT_DIP)
                views.setViewLayoutHeight(R.id.quick_widget_ring, ringSize, android.util.TypedValue.COMPLEX_UNIT_DIP)
            }
            views.setViewVisibility(R.id.quick_widget_label, if (height >= 80 * fontScale && width >= 64 * fontScale) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.quick_widget_detail,
                if (height >= 108 * fontScale && width >= 70 && state == VpnState.CONNECTED && rttMs > 0) View.VISIBLE else View.GONE)
            views.setTextViewText(R.id.quick_widget_detail, strings.getString(R.string.widget_ping, rttMs.coerceAtLeast(0)))


            val labelId = when (state) {
                VpnState.CONNECTED -> R.string.widget_quick_on
                VpnState.DISCONNECTED -> R.string.widget_quick_off
                VpnState.FAILED -> R.string.widget_quick_failed
                else -> R.string.widget_quick_connecting
            }
            val accent = WidgetAppearance(context).statusColor(state)
            views.setTextViewText(R.id.quick_widget_label, strings.getString(labelId))
            views.setTextColor(R.id.quick_widget_label, accent)
            views.setInt(R.id.quick_widget_icon, "setColorFilter", accent)
            views.setContentDescription(R.id.quick_widget_root,
                "${strings.getString(R.string.widget_quick_name)}. ${strings.getString(labelId)}. " +
                    strings.getString(if (state != VpnState.DISCONNECTED && state != VpnState.FAILED) R.string.widget_stop_vpn else R.string.widget_start_vpn))
            WidgetAppearance(context).applyQuick(views, state, width, height)

            // Click on widget -> Toggle VPN
            val toggleIntent = Intent(context, SplateQuickWidgetProvider::class.java).apply {
                action = ACTION_TOGGLE_VPN
            }
            val togglePendingIntent = PendingIntent.getBroadcast(
                context,
                201,
                toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.quick_widget_root, togglePendingIntent)

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
            val ids = widgetManager.getAppWidgetIds(ComponentName(context, SplateQuickWidgetProvider::class.java))

            // Мгновенная визуальная обратная связь для пользователя
            if (ids != null && ids.isNotEmpty()) {
                val views = RemoteViews(context.packageName, R.layout.widget_splate_quick)
                val strings = widgetResources(context)
                views.setTextViewText(R.id.quick_widget_label, strings.getString(R.string.widget_quick_connecting))
                WidgetAppearance(context).applyQuickState(views, VpnState.CONNECTING)
                views.setViewVisibility(R.id.quick_widget_detail, View.GONE)
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
            SplateAppWidgetProvider.notifyUpdate(context)
        }
    }
}
