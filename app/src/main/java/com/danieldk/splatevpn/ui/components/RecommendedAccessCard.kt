package com.danieldk.splatevpn.ui.components

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.danieldk.splatevpn.R
import com.danieldk.splatevpn.ui.theme.*

internal data class RecommendedAccessState(val notifications: Boolean, val batteryExempt: Boolean, val backgroundRestricted: Boolean)
internal fun readRecommendedAccess(context: Context) = RecommendedAccessState(
    NotificationManagerCompat.from(context).areNotificationsEnabled() && (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED),
    context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true,
    Build.VERSION.SDK_INT >= 28 && context.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted == true
)
internal fun openNotificationSettings(context: Context) {
    openAccessSettings(context, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
}
private fun openAccessSettings(context: Context, intent: Intent) {
    try { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    catch (_: Exception) {
        try { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: Exception) { Toast.makeText(context, context.getString(R.string.recommended_open_failed), Toast.LENGTH_LONG).show() }
    }
}

@Composable
internal fun RecommendedAccessCard() {
    val context = LocalContext.current
    var state by remember { mutableStateOf(readRecommendedAccess(context)) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { state = readRecommendedAccess(context) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, context) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) state = readRecommendedAccess(context) }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    RecommendedAccessContent(state,
        onNotifications = {
            if (Build.VERSION.SDK_INT >= 33 && !asked && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                asked = true; request.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else openNotificationSettings(context)
        },
        onBattery = { openAccessSettings(context, if (state.batteryExempt) Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) else Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))) },
        onBackground = { openAccessSettings(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) },
        onVpn = { openAccessSettings(context, Intent(Settings.ACTION_VPN_SETTINGS)) })
}

@Composable
internal fun RecommendedAccessContent(state: RecommendedAccessState, onNotifications: () -> Unit, onBattery: () -> Unit, onBackground: () -> Unit, onVpn: () -> Unit) {
    val r = LocalAppResources.current; val colors = LocalAppThemeColors.current
    Column(Modifier.fillMaxWidth().liquidGlass(cornerRadius = 20).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(r.getString(R.string.recommended_title), color = colors.textPrimary, style = MaterialTheme.typography.titleMedium)
        Text(r.getString(R.string.recommended_optional), color = colors.textSecondary, style = MaterialTheme.typography.bodySmall)
        AccessRow("notifications", r.getString(R.string.recommended_notifications), r.getString(if (state.notifications) R.string.recommended_enabled else R.string.recommended_notifications_hint), state.notifications, onNotifications)
        AccessRow("battery", r.getString(R.string.recommended_battery), r.getString(if (state.batteryExempt) R.string.recommended_battery_enabled else R.string.recommended_battery_hint), state.batteryExempt, onBattery)
        AccessRow("background", r.getString(R.string.recommended_background), r.getString(if (state.backgroundRestricted) R.string.recommended_background_restricted else R.string.recommended_background_hint), false, onBackground)
        AccessRow("vpn", r.getString(R.string.recommended_vpn), r.getString(R.string.recommended_vpn_hint), false, onVpn)
    }
}
@Composable
private fun AccessRow(tag: String, title: String, hint: String, granted: Boolean, onClick: () -> Unit) {
    val colors = LocalAppThemeColors.current; val r = LocalAppResources.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = colors.textPrimary, style = MaterialTheme.typography.titleSmall)
        Text(hint, color = if (granted) colors.accentEmerald else colors.textSecondary, style = MaterialTheme.typography.bodySmall)
        SplateTextButton(onClick, modifier = Modifier.testTag("recommended:$tag")) { Text(r.getString(if (granted) R.string.recommended_manage else R.string.recommended_open)) }
    }
}
