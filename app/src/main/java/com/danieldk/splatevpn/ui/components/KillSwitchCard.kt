package com.danieldk.splatevpn.ui.components

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.danieldk.splatevpn.ui.theme.LocalAppStrings
import com.danieldk.splatevpn.ui.theme.LocalAppThemeColors
import com.danieldk.splatevpn.vpn.SplateVpnService
import com.danieldk.splatevpn.vpn.VpnProtectionStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Binding reads the public VpnService APIs without starting a tunnel or a foreground service. */
@Composable
private fun rememberVpnProtectionStatus(): State<VpnProtectionStatus> {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState(VpnProtectionStatus(), context, lifecycle) {
        var binder: SplateVpnService.ProtectionBinder? = null
        fun refresh() { value = binder?.readStatus() ?: VpnProtectionStatus() }
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                binder = service as? SplateVpnService.ProtectionBinder
                refresh()
            }
            override fun onServiceDisconnected(name: ComponentName?) { binder = null; refresh() }
            override fun onBindingDied(name: ComponentName?) { binder = null; refresh() }
            override fun onNullBinding(name: ComponentName?) { binder = null; refresh() }
        }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        lifecycle.addObserver(observer)
        var bound = false
        try {
            bound = context.bindService(Intent(context, SplateVpnService::class.java)
                .setAction("${context.packageName}.READ_PROTECTION"), connection, Context.BIND_AUTO_CREATE)
            while (isActive) {
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) refresh()
                delay(1000)
            }
        } catch (_: SecurityException) {
            value = VpnProtectionStatus()
        } finally {
            lifecycle.removeObserver(observer)
            if (bound) try { context.unbindService(connection) } catch (_: IllegalArgumentException) { }
        }
    }
}

@Composable
internal fun KillSwitchCard() {
    val colors = LocalAppThemeColors.current
    val strings = LocalAppStrings.current
    val context = LocalContext.current
    val appLabel = remember(context) { context.applicationInfo.loadLabel(context.packageManager).toString() }
    val status by rememberVpnProtectionStatus()
    val protected = status.level == VpnProtectionStatus.Level.PROTECTED
    val tone = if (protected) colors.accentEmerald else colors.accentAmber
    val label = when (status.level) {
        VpnProtectionStatus.Level.PROTECTED -> strings.killSwitchActive
        VpnProtectionStatus.Level.ALWAYS_ON_ONLY -> strings.killSwitchAlwaysOnly
        VpnProtectionStatus.Level.OFF -> strings.killSwitchOff
        VpnProtectionStatus.Level.UNKNOWN -> strings.killSwitchUnknown
    }
    Column(Modifier.fillMaxWidth().liquidGlass(cornerRadius = 16, elevated = true).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Default.Lock, null, Modifier.size(22.dp), tint = tone)
            Text(strings.killSwitchTitle, color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Text(label, color = tone, fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.background(tone.copy(alpha = 0.13f), RoundedCornerShape(8.dp)).padding(horizontal = 9.dp, vertical = 5.dp))
        Text(if (status.level == VpnProtectionStatus.Level.UNKNOWN) strings.killSwitchUnknownHint else strings.killSwitchBody,
            color = colors.textSecondary, fontSize = 12.sp, lineHeight = 18.sp)
        if (!protected) {
            listOf(strings.killSwitchStepOne.format(appLabel), strings.killSwitchStepTwo, strings.killSwitchStepThree).forEachIndexed { i, step ->
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("${i + 1}.", color = colors.accentCyan, fontSize = 12.sp, lineHeight = 18.sp)
                    Text(step, color = colors.textPrimary, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f))
                }
            }
        }
        Text(strings.killSwitchExcludedApps, color = colors.textSecondary, fontSize = 11.sp, lineHeight = 16.sp)
        OutlinedButton(onClick = {
            try { context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
            catch (_: android.content.ActivityNotFoundException) { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }, modifier = Modifier.fillMaxWidth()) {
            Text(if (protected) strings.killSwitchManage else strings.killSwitchConfigure)
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(16.dp))
        }
    }
}
