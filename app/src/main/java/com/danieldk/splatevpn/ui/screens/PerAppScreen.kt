package com.danieldk.splatevpn.ui.screens

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danieldk.splatevpn.data.AppRoutingMode
import com.danieldk.splatevpn.ui.MainViewModel
import com.danieldk.splatevpn.ui.components.PageHeader
import com.danieldk.splatevpn.ui.components.liquidGlass
import com.danieldk.splatevpn.ui.theme.LocalAppStrings
import com.danieldk.splatevpn.ui.theme.LocalAppThemeColors
import com.danieldk.splatevpn.ui.theme.appRouting

data class AppItem(val name: String, val packageName: String, val icon: Bitmap?)

@Composable
fun PerAppScreen(viewModel: MainViewModel) {
    val global by viewModel.isGlobalAppProxy.collectAsState()
    val mode by viewModel.perAppMode.collectAsState()
    val packages by viewModel.perAppPackages.collectAsState()
    val connected by viewModel.isConnected.collectAsState()
    val apps by viewModel.installedApps.collectAsState()
    val loading by viewModel.isLoadingApps.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(Unit) { viewModel.loadInstalledApps(context, forceReload = apps.size <= 1) }
    AppRoutingContent(AppRoutingMode.fromSettings(global, mode), packages, connected, apps, loading,
        onRefresh = { viewModel.loadInstalledApps(context, forceReload = true) },
        onApply = viewModel::applyAppRouting)
}

@Composable
internal fun AppRoutingContent(
    savedMode: AppRoutingMode,
    savedPackages: Set<String>,
    isConnected: Boolean,
    apps: List<AppItem>,
    isLoadingApps: Boolean,
    onRefresh: () -> Unit,
    onApply: (AppRoutingMode, Set<String>) -> Unit
) {
    val common = LocalAppStrings.current
    val strings = common.appRouting
    val colors = LocalAppThemeColors.current
    var draftName by rememberSaveable(savedMode, savedPackages) { mutableStateOf(savedMode.name) }
    var draftPackages by rememberSaveable(savedMode, savedPackages) { mutableStateOf(savedPackages.toList()) }
    var search by rememberSaveable { mutableStateOf("") }
    var selectedOnly by rememberSaveable { mutableStateOf(false) }
    var modesExpanded by rememberSaveable { mutableStateOf(savedMode == AppRoutingMode.ALL) }
    val mode = AppRoutingMode.valueOf(draftName)
    val selected = remember(draftPackages) { draftPackages.toSet() }
    val dirty = mode != savedMode || (mode != AppRoutingMode.ALL && selected != savedPackages)
    val valid = mode.canApply(selected)
    val accent = if (mode == AppRoutingMode.EXCLUDE) colors.accentAmber else colors.accentCyan
    val bottomInset = 8.dp + if (WindowInsets.ime.getBottom(LocalDensity.current) > 0) 0.dp else LocalBottomDockInset.current
    val matching = remember(apps, search) {
        apps.filter { search.isBlank() || it.name.contains(search, true) || it.packageName.contains(search, true) }
    }
    val filtered = remember(matching, selected, selectedOnly) {
        if (selectedOnly) matching.filter { it.packageName in selected } else matching
    }
    fun discard() {
        draftName = savedMode.name
        draftPackages = savedPackages.toList()
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 680.dp).fillMaxSize().imePadding()) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().testTag("appRoutingList"),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item("header") {
                    PageHeader(strings.title, strings.subtitle, trailing = {
                        IconButton(onClick = onRefresh, enabled = !isLoadingApps,
                            modifier = Modifier.size(48.dp).liquidGlass(cornerRadius = 12)) {
                            if (isLoadingApps) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = accent)
                            else Icon(Icons.Default.Refresh, common.refresh, tint = colors.textSecondary)
                        }
                    })
                }
                item("modes") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(strings.modeHeading, color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        if (!modesExpanded) {
                            Row(Modifier.fillMaxWidth().testTag("appModeSelector").liquidGlass(cornerRadius = 18)
                                .clickable(role = Role.Button) { modesExpanded = true }.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(strings.title(mode), color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                    Text(strings.description(mode), color = colors.textSecondary, fontSize = 11.sp, lineHeight = 16.sp)
                                }
                                Spacer(Modifier.width(12.dp))
                                Icon(Icons.Default.ExpandMore, strings.changeMode, tint = accent)
                            }
                        } else Column(Modifier.fillMaxWidth().liquidGlass(cornerRadius = 18).selectableGroup()) {
                            AppRoutingMode.entries.forEachIndexed { index, option ->
                                if (index > 0) HorizontalDivider(color = colors.borderSubtle, modifier = Modifier.padding(horizontal = 16.dp))
                                Row(Modifier.fillMaxWidth().testTag("appMode${option.name}")
                                    .selectable(selected = mode == option, role = Role.RadioButton, onClick = { draftName = option.name; modesExpanded = false })
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = mode == option, onClick = null,
                                        colors = RadioButtonDefaults.colors(selectedColor = accent))
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(strings.title(option), color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                        Text(strings.description(option), color = colors.textSecondary, fontSize = 11.sp, lineHeight = 16.sp)
                                    }
                                }
                            }
                        }
                    }
                }
                item("explanation") {
                    Column(Modifier.fillMaxWidth().liquidGlass(cornerRadius = 14).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (mode == AppRoutingMode.ALL) Text(strings.allNote, color = colors.textPrimary, fontSize = 12.sp, lineHeight = 18.sp)
                        if (mode == AppRoutingMode.INCLUDE && selected.isEmpty()) {
                            Text(strings.emptyInclude, color = colors.accentAmber, fontSize = 12.sp, lineHeight = 18.sp)
                        } else if (mode == AppRoutingMode.EXCLUDE && selected.isEmpty()) {
                            Text(strings.emptyExclusions, color = colors.textPrimary, fontSize = 12.sp, lineHeight = 18.sp)
                        }
                        Text(strings.rulesNote, color = colors.textSecondary, fontSize = 11.sp, lineHeight = 16.sp)
                    }
                }
                if (mode != AppRoutingMode.ALL) {
                    item("picker") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(strings.pickerHeading(mode), color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            OutlinedTextField(search, onValueChange = { search = it }, singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("appSearch"),
                                placeholder = { Text(common.searchApps, fontSize = 13.sp) },
                                leadingIcon = { Icon(Icons.Default.Search, null) },
                                trailingIcon = { if (search.isNotEmpty()) IconButton(onClick = { search = "" }) { Icon(Icons.Default.Close, strings.clearSearch) } },
                                shape = RoundedCornerShape(14.dp),
                                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent,
                                    unfocusedBorderColor = colors.borderMedium, focusedTextColor = colors.textPrimary, unfocusedTextColor = colors.textPrimary))
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(strings.selected(selected.size), color = accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                FilterChip(selectedOnly, onClick = { selectedOnly = !selectedOnly },
                                    label = { Text(strings.selectedOnly, fontSize = 11.sp) })
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                TextButton(onClick = { draftPackages = (selected + matching.map { it.packageName }).toList() }, enabled = matching.any { it.packageName !in selected }) {
                                    Text(if (search.isBlank()) strings.selectAll else strings.selectVisible, fontSize = 11.sp)
                                }
                                TextButton(onClick = { draftPackages = emptyList() }, enabled = selected.isNotEmpty()) {
                                    Text(strings.clearSelection, color = colors.textSecondary, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                    if (isLoadingApps) item("loading") {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = accent, modifier = Modifier.size(28.dp))
                        }
                    } else if (filtered.isEmpty()) item("empty") {
                        Column(Modifier.fillMaxWidth().liquidGlass(cornerRadius = 14).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(when { search.isNotBlank() -> strings.noMatches; selectedOnly -> strings.noSelected; else -> strings.noApps },
                                color = colors.textSecondary, fontSize = 12.sp, lineHeight = 18.sp)
                            if (selectedOnly || search.isNotBlank()) TextButton(onClick = { selectedOnly = false; search = "" }) { Text(strings.showAll) }
                        }
                    } else items(filtered, key = { "app:${it.packageName}" }) { app ->
                        AppRow(app = app, isChecked = app.packageName in selected,
                            usesVpn = mode.usesVpn(app.packageName, selected), excludeMode = mode == AppRoutingMode.EXCLUDE,
                            onToggle = { draftPackages = if (app.packageName in selected) (selected - app.packageName).toList() else (selected + app.packageName).toList() })
                    }
                }
            }
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = bottomInset)
                .liquidGlass(cornerRadius = 16).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (dirty) strings.pending else strings.saved, color = if (dirty) accent else colors.textPrimary,
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(if (isConnected) strings.connectedHint else strings.disconnectedHint,
                    color = colors.textSecondary, fontSize = 11.sp, lineHeight = 16.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (dirty) TextButton(onClick = ::discard, modifier = Modifier.weight(1f).testTag("appRoutingDiscard")) {
                        Text(strings.discard, fontSize = 12.sp)
                    }
                    Button(onClick = { onApply(mode, selected) }, enabled = dirty && valid,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("appRoutingApply"),
                        colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = colors.onAccent)) {
                        Text(strings.apply, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
fun AppRow(modifier: Modifier = Modifier, app: AppItem, isChecked: Boolean, usesVpn: Boolean, excludeMode: Boolean, onToggle: () -> Unit) {
    val colors = LocalAppThemeColors.current
    val strings = LocalAppStrings.current.appRouting
    val accent = if (excludeMode) colors.accentAmber else colors.accentCyan
    Row(modifier.fillMaxWidth().testTag("app:${app.packageName}").liquidGlass(cornerRadius = 14,
        accentBorder = if (isChecked) accent.copy(alpha = .45f) else null)
        .toggleable(isChecked, role = Role.Checkbox, onValueChange = { onToggle() }).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (app.icon != null) Image(app.icon.asImageBitmap(), null, Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)))
        else Box(Modifier.size(36.dp).background(colors.surfaceElevated, RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Apps, null, tint = colors.textSecondary, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(app.name, color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(strings.destination(usesVpn), color = if (usesVpn) colors.accentCyan else colors.textSecondary, fontSize = 11.sp, lineHeight = 16.sp)
            Text(app.packageName, color = colors.textTertiary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Checkbox(isChecked, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = accent, checkmarkColor = colors.onAccent))
    }
}

fun drawableToBitmap(drawable: Drawable): Bitmap {
    if (drawable is BitmapDrawable && drawable.bitmap != null) return drawable.bitmap
    val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 72
    val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 72
    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
        drawable.setBounds(0, 0, width, height)
        drawable.draw(Canvas(bitmap))
    }
}
