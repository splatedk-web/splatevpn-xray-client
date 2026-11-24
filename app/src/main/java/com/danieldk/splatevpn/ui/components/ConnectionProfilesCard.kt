package com.danieldk.splatevpn.ui.components

import com.danieldk.splatevpn.ui.components.tvFocusOutline

import com.danieldk.splatevpn.ui.components.SplateTextButton as TextButton
import com.danieldk.splatevpn.ui.components.SplateIconButton as IconButton
import com.danieldk.splatevpn.ui.components.SplateRadioButton as RadioButton

import com.danieldk.splatevpn.ui.components.SplateAlertDialog as AlertDialog

import com.danieldk.splatevpn.ui.components.SplateButton as Button
import com.danieldk.splatevpn.ui.components.SplateOutlinedButton as OutlinedButton

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danieldk.splatevpn.ui.MainViewModel
import com.danieldk.splatevpn.ui.theme.*
import com.danieldk.splatevpn.vpn.VpnState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConnectionProfilesCard(viewModel: MainViewModel) {
    val colors = LocalAppThemeColors.current
    val strings = LocalAppStrings.current

    val saved by viewModel.networkPreferences.collectAsState()
    val active by viewModel.activeRouting.collectAsState()
    val state by viewModel.vpnState.collectAsState()
    val profiles by viewModel.connectionProfiles.collectAsState()
    val canUndo by viewModel.canUndoConnectionProfile.collectAsState()
    val scope = rememberCoroutineScope()
    var opened by rememberSaveable { mutableStateOf(false) }
    var draft by remember { mutableStateOf("smart") }
    var profileId by remember { mutableStateOf<String?>(null) }
    var myProfiles by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val connected = state == VpnState.CONNECTED
    val transitioning = state !in setOf(VpnState.CONNECTED, VpnState.DISCONNECTED, VpnState.FAILED)
    val shown = if (connected) active?.preferences ?: saved else saved
    val savedMode = saved["routing_mode"] as? String ?: "smart"
    ConnectionModeRow(smart = (shown["routing_mode"] ?: "smart") == "smart",
        updating = transitioning || connected && active != null && active!!.preferences != saved, enabled = !transitioning) {
        draft = savedMode; profileId = null; myProfiles = false; error = false; opened = true
    }

    if (opened) ModalBottomSheet(
        onDismissRequest = { if (!busy) opened = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surface,
        dragHandle = { BottomSheetDefaults.DragHandle(color = colors.textTertiary) }
    ) {
        rememberAndApplyPopupWindowBlur(blurBehind = true, backgroundBlur = false)
        Column(Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.85f)) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(strings.localized("Режим подключения", "Connection mode"), color = colors.textPrimary,
                    fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = { opened = false }, enabled = !busy) {
                    Icon(Icons.Default.Close, strings.localized("Закрыть", "Close"), tint = colors.textSecondary)
                }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RoutingModeOptions(selectedMode = if (profileId == null) draft else null, currentMode = savedMode, enabled = !busy) {
                    draft = it; profileId = null
                }
                Text(if (profileId != null) {
                    val selectedName = profiles.firstOrNull { it.id == profileId }?.name.orEmpty()
                    String.format(strings.localized("Будут применены сетевые настройки профиля «%s».",
                        "Network settings from profile “%s” will be applied."), selectedName)
                } else strings.localized("Выбор приложений и настройки ИИ остаются прежними.", "Application selection and AI settings stay as configured."), color = colors.textTertiary, fontSize = 12.sp)
                TextButton(onClick = { myProfiles = !myProfiles }, enabled = !busy, contentPadding = PaddingValues(0.dp)) {
                    Text(strings.localized("Мои профили", "My profiles") + if (profiles.isEmpty()) "" else " (${profiles.size})")
                    Icon(if (myProfiles) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                }
                AnimatedVisibility(myProfiles) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(strings.localized("Профиль сохраняет все сетевые настройки, включая приложения и DNS.", "A profile saves all network settings, including applications and DNS."),
                            color = colors.textSecondary, fontSize = 12.sp)
                        profiles.forEach { profile ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedButton(onClick = { profileId = profile.id }, enabled = !busy, modifier = Modifier.weight(1f)) {
                                    if (profileId == profile.id) { Icon(Icons.Default.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
                                    Text(profile.name)
                                }
                                IconButton(enabled = !busy, onClick = {
                                    busy = true
                                    scope.launch {
                                        error = viewModel.deleteConnectionProfile(profile.id).isFailure
                                        if (!error && profileId == profile.id) profileId = null
                                        busy = false
                                    }
                                }) { Icon(Icons.Default.DeleteOutline, String.format(strings.localized("Удалить %s", "Delete %s"), profile.name), tint = colors.textSecondary) }
                            }
                        }
                        TextButton(onClick = { saving = true; name = ""; error = false }, enabled = !busy && profiles.size < 8) {
                            Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                            Text(strings.localized("Сохранить текущие настройки", "Save current settings"))
                        }
                        if (canUndo) TextButton(onClick = {
                            busy = true
                            scope.launch { error = viewModel.applyConnectionProfile(null).isFailure; busy = false; if (!error) opened = false }
                        }, enabled = !busy) { Text(strings.localized("Отменить последний профиль", "Undo last profile")) }
                    }
                }
                if (error) Text(strings.localized("Не удалось сохранить изменения. Проверьте настройки и свободное место.", "Could not save changes. Check settings and available storage."), color = colors.accentCrimson, fontSize = 12.sp)
            }
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (connected) Text(strings.localized("VPN переподключится после применения.", "VPN will reconnect after applying."),
                    color = colors.textSecondary, fontSize = 12.sp)
                Button(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    enabled = !busy && !transitioning && (profileId != null || draft != savedMode), onClick = {
                        if (profileId == null) {
                            viewModel.setRoutingMode(draft)
                            opened = false
                        } else {
                            val id = profileId!!
                            busy = true
                            scope.launch { error = viewModel.applyConnectionProfile(id).isFailure; busy = false; if (!error) opened = false }
                        }
                    }) {
                    Text(if (busy) { strings.localized("Применяется…", "Applying…") }
                        else { strings.localized("Применить", "Apply") })
                }
            }
        }
    }
    if (saving) AlertDialog(onDismissRequest = { if (!busy) saving = false }, containerColor = colors.surface,
        title = { Text(strings.localized("Новый профиль", "New profile")) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = name, onValueChange = { name = it.take(48) }, singleLine = true,
                enabled = !busy, label = { Text(strings.localized("Название", "Name")) })
            if (error) Text(strings.localized("Не удалось сохранить профиль.", "Could not save profile."), color = colors.accentCrimson)
        } },
        confirmButton = { TextButton(enabled = !busy && name.isNotBlank(), onClick = {
            busy = true
            scope.launch { error = viewModel.saveConnectionProfile(name).isFailure; busy = false; if (!error) saving = false }
        }) { Text(strings.localized("Сохранить", "Save")) } },
        dismissButton = { TextButton(enabled = !busy, onClick = { saving = false }) { Text(strings.localized("Отмена", "Cancel")) } })
}

@Composable
internal fun RoutingModeOptions(selectedMode: String?, currentMode: String, enabled: Boolean, onSelect: (String) -> Unit) {
    val strings = LocalAppStrings.current

    val colors = LocalAppThemeColors.current
    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf("smart", "global").forEach { mode ->
            val selected = selectedMode == mode
            Surface(modifier = Modifier.liquidGlass(cornerRadius = 16, accentBorder = if (selected) colors.accentCyan else null),
                color = androidx.compose.ui.graphics.Color.Transparent,
                shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp,
                    if (selected) colors.accentCyan else colors.textTertiary.copy(alpha = 0.25f))) {
                Row(Modifier.fillMaxWidth().testTag("routingChoice:$mode")
                    .tvFocusOutline().selectable(selected, enabled, Role.RadioButton) { onSelect(mode) }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RadioButton(selected, onClick = null, enabled = enabled)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (mode == "smart") { strings.localized("Умный маршрут", "Smart routing") }
                            else { strings.localized("Весь трафик", "All traffic") }, color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
                        Text(if (mode == "smart") { LocalAppResources.current.getString(com.danieldk.splatevpn.R.string.smart_local_description) }
                            else { strings.localized("Трафик выбранных приложений — через VPN, с учётом исключений.", "VPN for routed applications, with your exceptions.") }, color = colors.textSecondary, fontSize = 12.sp)
                        if (mode == currentMode) Text(strings.localized("Текущий режим", "Current mode"), color = colors.accentCyan, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
internal fun ConnectionModeRow(smart: Boolean, updating: Boolean, enabled: Boolean, onChange: () -> Unit) {
    val colors = LocalAppThemeColors.current
    val strings = LocalAppStrings.current

    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("connectionMode").liquidGlass(cornerRadius = 16)
        .tvFocusOutline().clickable(enabled = enabled, role = Role.Button, onClick = onChange)
        .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(if (smart) { strings.localized("Умный маршрут", "Smart routing") }
                else { strings.localized("Весь трафик", "All traffic") },
                color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            if (updating) Text(strings.localized("Применяется…", "Updating…"), color = colors.accentAmber, fontSize = 11.sp)
        }
        Text(strings.localized("Изменить", "Change"), color = colors.accentCyan, fontSize = 13.sp)
        Icon(Icons.Default.ChevronRight, null, tint = colors.textTertiary, modifier = Modifier.size(18.dp))
    }
}
