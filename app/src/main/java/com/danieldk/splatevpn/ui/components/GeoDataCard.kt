package com.danieldk.splatevpn.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.VpnLock
import com.danieldk.splatevpn.ui.screens.SettingToggleRow
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.danieldk.splatevpn.R
import com.danieldk.splatevpn.data.GeoDataUpdateWorker
import com.danieldk.splatevpn.ui.MainViewModel
import com.danieldk.splatevpn.ui.theme.LocalAppResources
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
internal fun GeoDataCard(viewModel: MainViewModel) {
    val status by viewModel.geoDataStatus.collectAsState()
    val r = LocalAppResources.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val busy = status.busy
    var automatic by remember { mutableStateOf(GeoDataUpdateWorker.enabled(context)) }
    val viaVpn by viewModel.sourceDownloadsViaVpnState.collectAsState()
    GlassSettingsCard {
        Text(r.getString(R.string.geodata_title), style = MaterialTheme.typography.titleMedium)
        Text(status.revision)
        GlassSettingsHelp(r.getString(R.string.geodata_help))
        if (status.checkedAt > 0) Text("${r.getString(R.string.geodata_checked)}: ${DateFormat.getDateTimeInstance().format(Date(status.checkedAt))}", style = MaterialTheme.typography.bodySmall)
        SettingToggleRow(Icons.Default.Update, r.getString(R.string.geodata_auto), "", automatic,
            onCheckedChange = { automatic = it; GeoDataUpdateWorker.setEnabled(context, it) })
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        val resultText = when (status.result) {
            com.danieldk.splatevpn.data.GeoDataResult.UPDATED -> r.getString(R.string.geodata_result_updated, status.revision)
            com.danieldk.splatevpn.data.GeoDataResult.CURRENT -> r.getString(R.string.geodata_result_current)
            com.danieldk.splatevpn.data.GeoDataResult.ROLLED_BACK -> r.getString(R.string.geodata_result_rollback, status.revision)
            com.danieldk.splatevpn.data.GeoDataResult.NO_PREVIOUS -> r.getString(R.string.geodata_result_no_previous)
            com.danieldk.splatevpn.data.GeoDataResult.FAILED -> r.getString(R.string.geodata_failed)
            null -> null
        }
        resultText?.let {
            Text(it, color = if (status.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodySmall)
        }
        GlassSettingsAction(r.getString(R.string.geodata_check), enabled = !busy, onClick = { scope.launch { viewModel.updateGeoData() } })
        GlassSettingsAction(r.getString(R.string.geodata_rollback), enabled = !busy, onClick = { scope.launch { viewModel.rollbackGeoData() } })
        HorizontalDivider()
        Text(r.getString(R.string.source_access_title), style = MaterialTheme.typography.titleMedium)
        SettingToggleRow(Icons.Default.VpnLock, r.getString(R.string.source_access_vpn), "", viaVpn,
            onCheckedChange = { viewModel.setSourceDownloadsViaVpn(it) })
        GlassSettingsHelp(r.getString(R.string.source_access_help) + "\n\n" + r.getString(R.string.source_offline_help))
    }
}
