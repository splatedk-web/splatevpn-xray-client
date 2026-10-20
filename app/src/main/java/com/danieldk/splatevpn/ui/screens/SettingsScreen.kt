package com.danieldk.splatevpn.ui.screens

import android.os.Build
import android.widget.Toast
import kotlinx.coroutines.launch
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.activity.compose.BackHandler
import com.danieldk.splatevpn.ui.theme.RussianAppStrings
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danieldk.splatevpn.ui.MainViewModel
import com.danieldk.splatevpn.ui.components.KillSwitchCard
import com.danieldk.splatevpn.ui.components.BalancerSettingsCard
import com.danieldk.splatevpn.ui.components.GlassTransparencyControl
import com.danieldk.splatevpn.ui.components.SmartDnsProviderPicker
import com.danieldk.splatevpn.ui.components.AiDnsSelectionCard
import com.danieldk.splatevpn.data.SmartDnsProviders
import com.danieldk.splatevpn.ui.components.PageHeader
import com.danieldk.splatevpn.ui.components.ThemeSettingCard
import com.danieldk.splatevpn.ui.components.glassmorphism
import com.danieldk.splatevpn.ui.components.liquidGlass
import com.danieldk.splatevpn.ui.components.tactileClickable
import com.danieldk.splatevpn.ui.theme.LocalAppStrings
import com.danieldk.splatevpn.ui.theme.LocalAppThemeColors

@Composable
fun SettingsScreen(
    viewModel: MainViewModel
) {
    val routingMode by viewModel.routingMode.collectAsState()
    val isAutoSelect by viewModel.isAutoSelect.collectAsState()
    val balancerPreferences by viewModel.balancerPreferences.collectAsState()
    val glassTransparency by viewModel.glassTransparency.collectAsState()
    val glassBlurEnabled by viewModel.glassBlurEnabled.collectAsState()
    val isAutoConnectOnBoot by viewModel.isAutoConnectOnBoot.collectAsState()
    val dnsProvider by viewModel.dnsProvider.collectAsState()
    val xhttpMode by viewModel.xhttpMode.collectAsState()
    val autoUpdateInterval by viewModel.autoUpdateInterval.collectAsState()
    val isBlockIpv6 by viewModel.isBlockIpv6.collectAsState()
    val isAdBlocker by viewModel.isAdBlocker.collectAsState()
    val adFilterState by viewModel.adFilterState.collectAsState()
    val adBlockAllowlist by viewModel.adBlockAllowlist.collectAsState()
    val isRouteOnlySniffing by viewModel.isRouteOnlySniffing.collectAsState()
    val isBlockQuic by viewModel.isBlockQuic.collectAsState()
    val isFakeDns by viewModel.isFakeDns.collectAsState()
    val isBypassAi by viewModel.isBypassAi.collectAsState()
    val aiRoutingMode by viewModel.aiRoutingMode.collectAsState()
    val aiDnsProvider by viewModel.aiDnsProvider.collectAsState()
    val isAiTlsFragment by viewModel.isAiTlsFragment.collectAsState()
    val aiDnsSelection by viewModel.aiDnsSelection.collectAsState()
    val appTheme by viewModel.appTheme.collectAsState()
    var themesExpanded by remember { mutableStateOf(false) }
    val appLanguage by viewModel.appLanguage.collectAsState()
    val customRoutes by viewModel.customRoutes.collectAsState()
    val pingMode by viewModel.pingMode.collectAsState()
    val isTlsFragment by viewModel.isTlsFragment.collectAsState()
    val tlsFragmentPreset by viewModel.tlsFragmentPreset.collectAsState()
    val tlsFragmentLength by viewModel.tlsFragmentLength.collectAsState()
    val tlsFragmentInterval by viewModel.tlsFragmentInterval.collectAsState()
    val tlsFragmentPackets by viewModel.tlsFragmentPackets.collectAsState()
    val customDohUrl by viewModel.customDohUrl.collectAsState()
    val isMux by viewModel.isMux.collectAsState()
    val muxConcurrency by viewModel.muxConcurrency.collectAsState()
    val isShareLan by viewModel.isShareLan.collectAsState()

    var showTlsCustomDialog by remember { mutableStateOf(false) }
    var showShareLanDialog by remember { mutableStateOf(false) }

    val strings = LocalAppStrings.current
    val colors = LocalAppThemeColors.current
    val context = LocalContext.current

    val russian = strings is RussianAppStrings
    var categoryName by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsQuery by rememberSaveable { mutableStateOf("") }
    val selectedCategory = SettingsCategory.entries.firstOrNull { it.name == categoryName }
    val pageStateHolder = rememberSaveableStateHolder()
    val coroutineScope = rememberCoroutineScope()
    var importMode by rememberSaveable { mutableStateOf(com.danieldk.splatevpn.data.BackupManager.ImportMode.MERGE) }
    var showImportMode by remember { mutableStateOf(false) }

    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val res = viewModel.exportBackup(uri)
                if (res.isSuccess) {
                    Toast.makeText(context, strings.backupExportSuccess, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, String.format(strings.backupExportError, res.exceptionOrNull()?.message ?: ""), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val res = viewModel.importBackup(uri, importMode)
                if (res.isSuccess) {
                    Toast.makeText(context, String.format(strings.backupImportSuccess, res.getOrNull() ?: 0), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, String.format(strings.backupImportError, res.exceptionOrNull()?.message ?: ""), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    BackHandler(enabled = selectedCategory != null || settingsQuery.isNotEmpty()) {
        categoryName = null
        settingsQuery = ""
    }

    var isUpdatingSubs by remember { mutableStateOf(false) }
    var showLogsDialog by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        AnimatedContent(
            targetState = selectedCategory,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                val direction = if (targetState == null) -1 else 1
                (slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { it / 6 * direction } +
                    fadeIn(tween(220, delayMillis = 60))) togetherWith
                    (slideOutHorizontally(tween(220, easing = FastOutSlowInEasing)) { -it / 12 * direction } +
                        fadeOut(tween(140)))
            },
            label = "settings_page"
        ) { selectedCategory ->
        pageStateHolder.SaveableStateProvider(selectedCategory?.name ?: "categories") {
        val scrollState = rememberScrollState()
        fun visible(category: SettingsCategory) = selectedCategory == category
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 680.dp)
                .verticalScroll(scrollState)
                .padding(top = 20.dp, start = 16.dp, end = 16.dp, bottom = 16.dp + LocalBottomDockInset.current)
        ) {
            PageHeader(title = strings.settingsTitle, subtitle = strings.settingsSubtitle)

            Spacer(modifier = Modifier.height(20.dp))

            OutlinedTextField(
                value = settingsQuery,
                onValueChange = { settingsQuery = it; categoryName = null },
                placeholder = { Text(if (russian) "Найти настройку…" else "Search settings…") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (settingsQuery.isNotEmpty()) IconButton(onClick = { settingsQuery = "" }) {
                    Icon(Icons.Default.Close, if (russian) "Очистить поиск" else "Clear search")
                } },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = colors.textPrimary,
                    unfocusedTextColor = colors.textPrimary, focusedBorderColor = colors.accentCyan,
                    unfocusedBorderColor = colors.borderSubtle)
            )
            Spacer(Modifier.height(12.dp))
            if (selectedCategory == null) {
                val categories = SettingsCategory.entries.filter { it.matches(settingsQuery) }
                if (categories.isEmpty()) Text(if (russian) "Ничего не найдено. Попробуйте DNS, тему или бэкап." else "No matches. Try DNS, theme or backup.", color = colors.textSecondary)
                categories.forEach { category ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        .tactileClickable(targetScale = 0.98f) {
                            categoryName = category.name
                            settingsQuery = ""
                        }.liquidGlass(cornerRadius = 16).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(40.dp).background(colors.accentCyan.copy(alpha = 0.10f), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(category.icon, null, Modifier.size(22.dp), tint = colors.accentCyan)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(category.title(russian), color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text(category.hint(russian), color = colors.textSecondary, fontSize = 12.sp, lineHeight = 17.sp)
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.textSecondary)
                    }
                }
            } else {
                TextButton(onClick = { categoryName = null }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (russian) "Все настройки" else "All settings")
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(selectedCategory.icon, null, Modifier.size(26.dp), tint = colors.accentCyan)
                    Text(selectedCategory.title(russian), color = colors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(16.dp))
            }

            if (visible(SettingsCategory.APPEARANCE)) {
            // Section 1: Appearance & Interface (Theme & Language)
            SectionHeader(title = strings.sectionAppearance)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .liquidGlass(cornerRadius = 20, innerSheen = true)
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Theme Selection
                Column(Modifier.animateContentSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .liquidGlass(cornerRadius = 10, innerSheen = true),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Palette, contentDescription = null, tint = colors.accentCyan, modifier = Modifier.size(20.dp))
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(strings.themeTitle, color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(strings.themeSubtitle, color = colors.textSecondary, fontSize = 11.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Static previews share the full-screen theme renderer.
                    val themesList = listOf(
                        "dark" to strings.themeDark,
                        "amoled" to strings.themeAmoled,
                        "light" to strings.themeLight,
                        "system" to strings.themeSystem,
                        "cyberpunk" to strings.themeCyberpunk,
                        "oneplus" to strings.themeOnePlus,
                        "aurora" to strings.themeAurora,
                        "sunset" to strings.themeSunset,
                        "ocean" to strings.themeOcean,
                        "sakura" to strings.themeSakura,
                        "pony" to strings.themePony,
                        "winx" to strings.themeWinx
                    )
                    val visibleThemes = if (themesExpanded) themesList else themesList.take(4)
                    visibleThemes.chunked(2).forEachIndexed { index, pair ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            pair.forEach { (key, label) ->
                                ThemeSettingCard(
                                    modifier = Modifier.weight(1f),
                                    label = label,
                                    themeKey = key,
                                    isSelected = appTheme.lowercase() == key,
                                    onClick = { viewModel.setAppTheme(key) }
                                )
                            }
                        }
                        if (index < (visibleThemes.size - 1) / 2) {
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    if (!themesExpanded && visibleThemes.none { it.first == appTheme.lowercase() }) {
                        Text(strings.themeCurrent.format(themesList.firstOrNull { it.first == appTheme.lowercase() }?.second ?: strings.themeDark),
                            color = colors.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
                    }
                    OutlinedButton(
                        onClick = { themesExpanded = !themesExpanded },
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, colors.borderMedium)
                    ) {
                        Text(if (themesExpanded) strings.themeShowLess else strings.themeShowAll.format(themesList.size))
                        Spacer(Modifier.width(8.dp))
                        Icon(if (themesExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.size(18.dp))
                    }
                }

                HorizontalDivider(color = colors.borderSubtle.copy(alpha = 0.6f))

                GlassTransparencyControl(glassTransparency, viewModel::previewGlassTransparency,
                    viewModel::commitGlassTransparency, { viewModel.setGlassTransparency(0f) },
                    glassBlurEnabled, viewModel::setGlassBlurEnabled)

                HorizontalDivider(color = colors.borderSubtle.copy(alpha = 0.6f))

                // Language Selection
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .liquidGlass(cornerRadius = 10, innerSheen = true),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Translate, contentDescription = null, tint = colors.accentCyan, modifier = Modifier.size(20.dp))
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(strings.languageTitle, color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(strings.languageSubtitle, color = colors.textSecondary, fontSize = 11.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val languages = listOf(
                            "ru" to strings.langRu,
                            "en" to strings.langEn,
                            "system" to strings.langSystem
                        )
                        languages.forEach { (key, label) ->
                            SelectableSettingChip(
                                modifier = Modifier.weight(1f),
                                label = label,
                                isSelected = appLanguage.lowercase() == key,
                                onClick = { viewModel.setAppLanguage(key) }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            }

            if (visible(SettingsCategory.CONNECTION)) {
            // Section 2: Automation & Subscriptions
            SectionHeader(title = strings.sectionAutomation)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .liquidGlass(cornerRadius = 20, innerSheen = true)
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Auto-select lowest ping server
                SettingToggleRow(
                    icon = Icons.Default.Bolt,
                    title = strings.autoSelectTitle,
                    subtitle = strings.autoSelectSubtitle,
                    checked = isAutoSelect,
                    onCheckedChange = { viewModel.setAutoSelect(it) }
                )

                BalancerSettingsCard(balancerPreferences, isAutoSelect, viewModel::setBalancerPreferences)

                HorizontalDivider(color = colors.borderSubtle.copy(alpha = 0.6f))

                // Auto-connect on device boot
                SettingToggleRow(
                    icon = Icons.Default.PowerSettingsNew,
                    title = strings.autoConnectOnBootTitle,
                    subtitle = strings.autoConnectOnBootSubtitle,
                    checked = isAutoConnectOnBoot,
                    onCheckedChange = { viewModel.setAutoConnectOnBoot(it) }
                )

                HorizontalDivider(color = colors.borderSubtle.copy(alpha = 0.6f))

                // Battery Optimization Exemption
                val powerManager = remember { context.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager }
                var isIgnoringBattery by remember {
                    mutableStateOf(powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true)
                }

                val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                        if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                            isIgnoringBattery = powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .liquidGlass(cornerRadius = 12, innerSheen = true)
                        .clickable {
                            try {
                                val intent = android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = android.net.Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                try {
                                    val intent = android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                    context.startActivity(intent)
                                } catch (ex: Exception) {
                                    Toast.makeText(context, strings.batterySettingsOpenError, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .liquidGlass(cornerRadius = 10, innerSheen = true),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.BatterySaver, contentDescription = null, tint = colors.accentCyan, modifier = Modifier.size(20.dp))
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(strings.batteryOptimizationTitle, color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (isIgnoringBattery) strings.batteryOptimizationDisabled else strings.batteryOptimizationPrompt,
                            color = if (isIgnoringBattery) colors.accentEmerald else colors.accentAmber,
                            fontSize = 11.sp,
                            lineHeight = 14.sp
                        )
                    }

                    Icon(
                        imageVector = if (isIgnoringBattery) Icons.Default.CheckCircle else Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = if (isIgnoringBattery) colors.accentEmerald else colors.textSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                HorizontalDivider(color = colors.borderSubtle.copy(alpha = 0.6f))

                // Auto-update subscriptions interval
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .liquidGlass(cornerRadius = 10, innerSheen = true),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Update, contentDescription = null, tint = colors.accentCyan, modifier = Modifier.size(20.dp))
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(strings.autoUpdateTitle, color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(strings.autoUpdateSubtitle, color = colors.textSecondary, fontSize = 11.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val intervals = listOf("off" to strings.intervalOff, "6h" to strings.interval6h, "12h" to strings.interval12h, "24h" to strings.interval24h)
                        intervals.forEach { (key, label) ->
                            SelectableSettingChip(
                                modifier = Modifier.weight(1f),
                                label = label,
                                isSelected = autoUpdateInterval == key,
                                onClick = { viewModel.setAutoUpdateInterval(key) }
                            )
                        }
                    }
                }

                HorizontalDivider(color = colors.borderSubtle.copy(alpha = 0.6f))

                // Update all subscriptions now button with liquid glass
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .liquidGlass(cornerRadius = 12, innerSheen = true)
                        .tactileClickable(targetScale = 0.96f, enabled = !isUpdatingSubs) {
                            isUpdatingSubs = true
                            viewModel.updateAllSubscriptions { report ->
                                isUpdatingSubs = false
                                Toast.makeText(context, String.format(strings.subscriptionRefreshSummary, report.updated, report.failures.size), Toast.LENGTH_SHORT).show()
                            }
                        }
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isUpdatingSubs) {
                        CircularProgressIndicator(color = colors.accentCyan, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                    } else {
                        Icon(Icons.Default.Sync, contentDescription = null, tint = colors.accentCyan, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(if (isUpdatingSubs) strings.updating else strings.updateAllSubsNow, color = colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            }

            if (visible(SettingsCategory.NETWORK)) {
            Column(Modifier.fillMaxWidth().liquidGlass(cornerRadius = 20).padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Routing Mode (Smart RU vs Global)
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .liquidGlass(cornerRadius = 10, innerSheen = true),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.AutoMirrored.Filled.AltRoute, contentDescription = null, tint = colors.accentCyan, modifier = Modifier.size(20.dp))
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(strings.routingProfile, color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(if (routingMode == "smart_ru") strings.smartRouteDesc else strings.globalTunnelDesc, color = colors.textSecondary, fontSize = 11.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SelectableSettingChip(
                            modifier = Modifier.weight(1f),
                            label = strings.smartRoute,
                            isSelected = routingMode == "smart_ru",
                            onClick = { viewModel.setRoutingMode("smart_ru") }
                        )
                        SelectableSettingChip(
                            modifier = Modifier.weight(1f),
                            label = strings.globalTunnel,
                            isSelected = routingMode == "global",
                            onClick = { viewModel.setRoutingMode("global") }
                        )
                    }
                }

                HorizontalDivider(color = colors.glassBorderLight.copy(alpha = 0.2f))
                HorizontalDivider(color = colors.borderSubtle)
                // DNS Provider
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .liquidGlass(cornerRadius = 10, innerSheen = true),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Dns, contentDescription = null, tint = colors.accentCyan, modifier = Modifier.size(20.dp))
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(strings.dnsProviderTitle, color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(strings.dnsProviderSubtitle, color = colors.textSecondary, fontSize = 11.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val dnsOptions = listOf(
                            "cloudflare" to "Cloudflare",
                            "google" to "Google",
                            "quad9" to "Quad9",
                            "adguard" to "AdGuard",
                            "yandex" to "Yandex",
                            "dns_ai" to "DNS-AI",
                            "custom" to strings.dnsCustom
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            dnsOptions.take(4).forEach { (key, label) ->
                                SelectableSettingChip(
                                    modifier = Modifier.weight(1f),
                                    label = label,
                                    isSelected = dnsProvider.lowercase() == key,
                                    onClick = { viewModel.setDnsProvider(key) }
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            dnsOptions.drop(4).forEach { (key, label) ->
                                SelectableSettingChip(
                                    modifier = Modifier.weight(1f),
                                    label = label,
                                    isSelected = dnsProvider.lowercase() == key,
                                    onClick = { viewModel.setDnsProvider(key) }
                                )
                            }
                        }

                        SmartDnsProviders.all.drop(1).chunked(3).forEach { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { provider ->
                                    SelectableSettingChip(Modifier.weight(1f), label = provider.label,
                                        isSelected = dnsProvider == provider.key,
                                        onClick = { viewModel.setDnsProvider(provider.key) })
                                }
                            }
                        }

                        if (dnsProvider.lowercase() == "custom") {
                            Spacer(modifier = Modifier.height(6.dp))
                            var editingDohUrl by remember(customDohUrl) { mutableStateOf(customDohUrl) }
                            OutlinedTextField(
                                value = editingDohUrl,
                                onValueChange = {
                                    editingDohUrl = it
                                    viewModel.setCustomDohUrl(it)
                                },
                                placeholder = { Text(strings.dnsCustomUrlHint, fontSize = 11.sp, color = colors.textSecondary) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = colors.accentCyan,
                                    unfocusedBorderColor = colors.borderSubtle,
                                    focusedTextColor = colors.textPrimary,
                                    unfocusedTextColor = colors.textPrimary
                                ),
                                leadingIcon = {
                                    Icon(Icons.Default.Link, contentDescription = null, tint = colors.accentCyan, modifier = Modifier.size(16.dp))
                                }
                            )
                        }
                    }
                }
                HorizontalDivider(color = colors.borderSubtle)
                // Ping Mode
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .liquidGlass(cornerRadius = 10, innerSheen = true),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.NetworkPing, contentDescription = null, tint = colors.accentCyan, modifier = Modifier.size(20.dp))
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(strings.pingModeTitle, color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(strings.pingModeSubtitle, color = colors.textSecondary, fontSize = 11.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val pingOptions = listOf(
                            "real_delay" to strings.pingModeRealDelay,
                            "tcp" to strings.pingModeTcp,
                            "icmp" to strings.pingModeIcmp
                        )
                        pingOptions.forEach { (key, label) ->
                            val isSelected = when (key) {
                                "real_delay" -> pingMode.lowercase() in listOf("real_delay", "http", "real")
                                else -> pingMode.lowercase() == key
                            }
                            SelectableSettingChip(
                                modifier = Modifier.weight(1f),
                                label = label,
                                isSelected = isSelected,
                                onClick = { viewModel.setPingMode(key) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = when (pingMode.lowercase()) {
                            "real_delay", "http", "real" -> strings.pingModeRealDelayDesc
                            "icmp" -> strings.pingModeIcmpDesc
                            else -> strings.pingModeTcpDesc
                        },
                        color = colors.textSecondary,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(start = 2.dp)
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            }

            if (visible(SettingsCategory.AI)) {
            Column(Modifier.fillMaxWidth().liquidGlass(cornerRadius = 20).padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Bypass AI Services
                SettingToggleRow(
                    icon = Icons.Default.AutoAwesome,
                    title = strings.bypassAiTitle,
                    subtitle = strings.bypassAiSubtitle,
                    checked = isBypassAi,
                    onCheckedChange = { viewModel.setBypassAi(it) }
                )

                AnimatedVisibility(isBypassAi, enter = expandVertically(tween(260)) + fadeIn(), exit = shrinkVertically(tween(220)) + fadeOut()) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("dns_ai" to "SmartDNS", "vpn" to "VPN").forEach { (key, label) ->
                            SelectableSettingChip(
                                modifier = Modifier.weight(1f),
                                label = label,
                                isSelected = aiRoutingMode == key,
                                onClick = { viewModel.setAiRoutingMode(key) }
                            )
                        }
                    }
                    if (aiRoutingMode != "vpn") {
                        SmartDnsProviderPicker(aiDnsProvider, viewModel::setAiDnsProvider)
                        SettingToggleRow(icon = Icons.AutoMirrored.Filled.CallSplit,
                            title = strings.aiTlsFragmentTitle, subtitle = strings.aiTlsFragmentHint,
                            checked = isAiTlsFragment, onCheckedChange = viewModel::setAiTlsFragment)
                        AiDnsSelectionCard(aiDnsSelection, viewModel::startAiDnsSelection, viewModel::cancelAiDnsSelection)
                    }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            }

            if (visible(SettingsCategory.PROTECTION)) {
            Column(Modifier.fillMaxWidth().liquidGlass(cornerRadius = 20).padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Block IPv6 Leak
                SettingToggleRow(
                    icon = Icons.Default.VpnLock,
                    title = strings.blockIpv6Title,
                    subtitle = strings.blockIpv6Subtitle,
                    checked = isBlockIpv6,
                    onCheckedChange = { viewModel.setBlockIpv6(it) }
                )
                HorizontalDivider(color = colors.borderSubtle)
                // Ad Blocker (Western & Russian networks)
                SettingToggleRow(
                    icon = Icons.Default.Shield,
                    title = strings.adBlockerTitle,
                    subtitle = strings.adBlockerSubtitle,
                    checked = isAdBlocker,
                    onCheckedChange = { viewModel.setAdBlocker(it) }
                )
                AnimatedVisibility(isAdBlocker, enter = expandVertically(tween(260)) + fadeIn(), exit = shrinkVertically(tween(220)) + fadeOut()) {
                com.danieldk.splatevpn.ui.components.AdFilterCard(
                    adFilterState, adBlockAllowlist, viewModel::updateAdFilters, viewModel::setAdBlockAllowlist)
                }
                HorizontalDivider(color = colors.borderSubtle)
                KillSwitchCard()
            }
            Spacer(Modifier.height(24.dp))
            }

            if (visible(SettingsCategory.ADVANCED)) {
            Column(Modifier.fillMaxWidth().liquidGlass(cornerRadius = 20).padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // XHTTP Transport Mode
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .liquidGlass(cornerRadius = 10, innerSheen = true),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Speed, contentDescription = null, tint = colors.accentCyan, modifier = Modifier.size(20.dp))
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(strings.xhttpModeTitle, color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(strings.xhttpModeSubtitle, color = colors.textSecondary, fontSize = 11.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val modes = listOf(
                            "auto" to "Auto",
                            "stream-one" to "Stream-1",
                            "stream-up" to "Stream-Up",
                            "packet-up" to "Packet"
                        )
                        modes.forEach { (key, label) ->
                            SelectableSettingChip(
                                modifier = Modifier.weight(1f),
                                label = label,
                                isSelected = xhttpMode.lowercase() == key,
                                onClick = { viewModel.setXhttpMode(key) }
                            )
                        }
                    }
                }
                HorizontalDivider(color = colors.borderSubtle)
                // FakeDNS (Split-DNS)
                SettingToggleRow(
                    icon = Icons.Default.Security,
                    title = strings.fakeDnsTitle,
                    subtitle = strings.fakeDnsSubtitle,
                    checked = isFakeDns,
                    onCheckedChange = { viewModel.setFakeDns(it) }
                )
                HorizontalDivider(color = colors.borderSubtle)
                // Block QUIC (UDP 443)
                SettingToggleRow(
                    icon = Icons.Default.Block,
                    title = strings.blockQuicTitle,
                    subtitle = strings.blockQuicSubtitle,
                    checked = isBlockQuic,
                    onCheckedChange = { viewModel.setBlockQuic(it) }
                )
                HorizontalDivider(color = colors.borderSubtle)
                // RouteOnly Sniffing
                SettingToggleRow(
                    icon = Icons.Default.FilterAlt,
                    title = strings.sniffingTitle,
                    subtitle = strings.sniffingSubtitle,
                    checked = isRouteOnlySniffing,
                    onCheckedChange = { viewModel.setRouteOnlySniffing(it) }
                )
                HorizontalDivider(color = colors.borderSubtle)
                // TLS Fragmentation (ClientHello Fragmentation)
                Column {
                    SettingToggleRow(
                        icon = Icons.AutoMirrored.Filled.CallSplit,
                        title = strings.tlsFragmentTitle,
                        subtitle = strings.tlsFragmentSubtitle,
                        checked = isTlsFragment,
                        onCheckedChange = { viewModel.setTlsFragment(it) }
                    )

                    AnimatedVisibility(isTlsFragment, enter = expandVertically(tween(260)) + fadeIn(), exit = shrinkVertically(tween(220)) + fadeOut()) {
                        Column {
                        Spacer(modifier = Modifier.height(10.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .liquidGlass(cornerRadius = 10, innerSheen = false)
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = strings.tlsFragmentPresetTitle,
                                color = colors.textSecondary,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                SelectableSettingChip(
                                    modifier = Modifier.weight(1f),
                                    label = strings.tlsPresetStandard,
                                    isSelected = tlsFragmentPreset == "standard",
                                    onClick = { viewModel.setTlsFragmentPreset("standard") }
                                )
                                SelectableSettingChip(
                                    modifier = Modifier.weight(1f),
                                    label = strings.tlsPresetAggressive,
                                    isSelected = tlsFragmentPreset == "aggressive",
                                    onClick = { viewModel.setTlsFragmentPreset("aggressive") }
                                )
                                SelectableSettingChip(
                                    modifier = Modifier.weight(1f),
                                    label = strings.tlsPresetCustom,
                                    isSelected = tlsFragmentPreset == "custom",
                                    onClick = { showTlsCustomDialog = true }
                                )
                            }
                            Text(
                                text = String.format(strings.tlsParametersSummary, tlsFragmentLength, tlsFragmentInterval, tlsFragmentPackets),
                                color = colors.textSecondary,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
                }
                HorizontalDivider(color = colors.borderSubtle)
                // Multiplexing (Mux.Cool)
                Column(Modifier.animateContentSize(tween(260))) {
                    SettingToggleRow(
                        icon = Icons.AutoMirrored.Filled.AltRoute,
                        title = strings.muxTitle,
                        subtitle = strings.muxSubtitle,
                        checked = isMux,
                        onCheckedChange = { viewModel.setMux(it) }
                    )

                    if (isMux) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .liquidGlass(cornerRadius = 10, innerSheen = false)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${strings.muxConcurrencyLabel}: $muxConcurrency",
                                color = colors.textPrimary,
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(4, 8, 16, 32).forEach { count ->
                                    SelectableSettingChip(
                                        label = "$count",
                                        isSelected = muxConcurrency == count,
                                        onClick = { viewModel.setMuxConcurrency(count) },
                                        modifier = Modifier.width(42.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            }

            if (visible(SettingsCategory.SHARING)) {
            Column(Modifier.fillMaxWidth().liquidGlass(cornerRadius = 20).padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // VPN Sharing over Wi-Fi Hotspot & USB Modem (Non-Root)
                Column {
                    SettingToggleRow(
                        icon = Icons.Default.WifiTethering,
                        title = strings.shareLanTitle,
                        subtitle = strings.shareLanSubtitle,
                        checked = isShareLan,
                        onCheckedChange = { viewModel.setShareLan(it) }
                    )

                    if (isShareLan) {
                        Spacer(modifier = Modifier.height(10.dp))
                        val endpoints by produceState(initialValue = viewModel.getActiveLanEndpoints(), isShareLan) {
                            while (true) {
                                value = viewModel.getActiveLanEndpoints()
                                kotlinx.coroutines.delay(3000)
                            }
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .liquidGlass(cornerRadius = 12, innerSheen = false, accentBorder = colors.accentCyan.copy(alpha = 0.35f))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (endpoints.isNotEmpty()) {
                                endpoints.forEach { ep ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(com.danieldk.splatevpn.ui.components.lanEndpointLabel(ep, strings), color = colors.textSecondary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                                            Text("HTTP: ${ep.httpProxyString}", color = colors.accentCyan, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                        }
                                        Row(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(colors.accentCyan.copy(alpha = 0.15f))
                                                .tactileClickable {
                                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                                    clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("proxy", ep.httpProxyString))
                                                    Toast.makeText(context, String.format(strings.shareLanCopied, ep.httpProxyString), Toast.LENGTH_SHORT).show()
                                                }
                                                .padding(horizontal = 8.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Default.ContentCopy, contentDescription = null, tint = colors.accentCyan, modifier = Modifier.size(12.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(strings.uiCopy, color = colors.accentCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            } else {
                                Text(
                                    text = strings.shareLanNoEndpoints,
                                    color = colors.accentAmber,
                                    fontSize = 10.5.sp,
                                    lineHeight = 14.sp
                                )
                            }

                            // Button to open setup instructions dialog
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .liquidGlass(cornerRadius = 8, innerSheen = true, accentBorder = colors.accentCyan.copy(alpha = 0.5f))
                                    .tactileClickable(targetScale = 0.96f) { showShareLanDialog = true }
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Devices, contentDescription = null, tint = colors.accentCyan, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(strings.shareLanInstructionsBtn, color = colors.accentCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            }

            if (visible(SettingsCategory.ADVANCED)) {
            // Section 4: Custom Domain Routes
            SectionHeader(title = strings.sectionCustomRoutes)
            Text(
                text = strings.customRoutesSubtitle,
                color = colors.textSecondary,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .liquidGlass(cornerRadius = 20, innerSheen = true)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Domain input
                var newDomain by remember { mutableStateOf("") }
                var selectedMode by remember { mutableStateOf("proxy") }

                OutlinedTextField(
                    value = newDomain,
                    onValueChange = { newDomain = it },
                    label = { Text("Domain", color = colors.textSecondary, fontSize = 12.sp) },
                    placeholder = { Text(strings.customRoutesDomainHint, color = colors.textSecondary, fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.accentCyan,
                        unfocusedBorderColor = colors.borderSubtle,
                        cursorColor = colors.accentCyan,
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary
                    ),
                    leadingIcon = {
                        Icon(Icons.Default.Language, contentDescription = null, tint = colors.accentCyan, modifier = Modifier.size(18.dp))
                    }
                )

                // Mode selector
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        "direct" to strings.customRouteDirect,
                        "proxy" to strings.customRouteProxy,
                        "block" to strings.customRouteBlock
                    ).forEach { (key, label) ->
                        val modeColor = when (key) {
                            "direct" -> colors.accentEmerald
                            "proxy" -> colors.accentCyan
                            "block" -> colors.accentCrimson
                            else -> colors.textSecondary
                        }
                        val isSelected = selectedMode == key
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .liquidGlass(
                                    cornerRadius = 10,
                                    accentBorder = if (isSelected) modeColor else null,
                                    innerSheen = true
                                )
                                .tactileClickable(targetScale = 0.94f) { selectedMode = key }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                color = if (isSelected) modeColor else colors.textSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                        }
                    }
                }

                // Add button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .liquidGlass(
                            cornerRadius = 12,
                            accentBorder = colors.accentCyan.copy(alpha = 0.5f),
                            innerSheen = true
                        )
                        .tactileClickable(enabled = newDomain.isNotBlank()) {
                            if (newDomain.isNotBlank()) {
                                viewModel.addCustomRoute(newDomain.trim(), selectedMode)
                                Toast.makeText(context, strings.customRoutesSaved, Toast.LENGTH_SHORT).show()
                                newDomain = ""
                            }
                        }
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = colors.accentCyan, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(strings.customRoutesAddBtn, color = colors.accentCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }

                // Existing routes list
                if (customRoutes.isEmpty()) {
                    Text(
                        text = strings.customRoutesEmpty,
                        color = colors.textSecondary,
                        fontSize = 11.sp,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                } else {
                    HorizontalDivider(color = colors.borderSubtle.copy(alpha = 0.6f))
                    customRoutes.forEach { (domain, mode) ->
                        val modeColor = when (mode) {
                            "direct" -> colors.accentEmerald
                            "proxy" -> colors.accentCyan
                            "block" -> colors.accentCrimson
                            else -> colors.textSecondary
                        }
                        val modeLabel = when (mode) {
                            "direct" -> strings.customRouteDirect
                            "proxy" -> strings.customRouteProxy
                            "block" -> strings.customRouteBlock
                            else -> mode
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(modeColor)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = domain,
                                color = colors.textPrimary,
                                fontSize = 12.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = modeLabel.uppercase(),
                                color = modeColor,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Remove",
                                tint = colors.textTertiary,
                                modifier = Modifier
                                    .size(18.dp)
                                    .tactileClickable(targetScale = 0.85f) {
                                        viewModel.removeCustomRoute(domain)
                                        Toast.makeText(context, strings.customRoutesRemoved, Toast.LENGTH_SHORT).show()
                                    }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            }

            if (visible(SettingsCategory.ABOUT)) {
            // Section 5: Core Info
            SectionHeader(title = strings.sectionCore)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .liquidGlass(cornerRadius = 20, innerSheen = true)
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                InfoRow(label = strings.coreVersion, value = "Xray ${com.danieldk.splatevpn.SplateConstants.XRAY_VERSION} (XTLS)")
                InfoRow(label = strings.coreArch, value = "arm64-v8a (Native PIE)")
                InfoRow(label = strings.coreTunnel, value = "hev-socks5-tunnel (tun0)")
                InfoRow(label = strings.coreProtocols, value = "VLESS, VMess, Trojan, Shadowsocks, XHTTP, WS, gRPC")
                InfoRow(label = strings.coreFakeDns, value = "198.18.0.0/15 (65535 IPs)")
            }

            Spacer(modifier = Modifier.height(24.dp))

            }

            if (visible(SettingsCategory.ABOUT)) {
            // Section: App Updates
            SectionHeader(title = strings.sectionAppUpdates)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .liquidGlass(cornerRadius = 20, innerSheen = true)
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val isChecking by viewModel.isCheckingUpdate.collectAsState()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .liquidGlass(
                            cornerRadius = 14,
                            accentBorder = colors.accentCyan.copy(alpha = 0.6f),
                            innerSheen = true
                        )
                        .tactileClickable(targetScale = 0.96f) {
                            if (!isChecking) {
                                viewModel.checkForUpdates()
                                Toast.makeText(context, strings.updateChecking, Toast.LENGTH_SHORT).show()
                            }
                        }
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isChecking) {
                        CircularProgressIndicator(
                            color = colors.accentCyan,
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = colors.accentCyan, modifier = Modifier.size(18.dp))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (isChecking) strings.updateChecking else strings.updateCheck,
                        color = colors.textPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            }

            if (visible(SettingsCategory.CONNECTION)) {
            // Section: Quick Settings Tile
            SectionHeader(title = strings.sectionQuickSettings)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .liquidGlass(cornerRadius = 20, innerSheen = true)
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = strings.quickSettingsDesc,
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .liquidGlass(
                            cornerRadius = 14,
                            accentBorder = colors.accentEmerald.copy(alpha = 0.6f),
                            innerSheen = true
                        )
                        .tactileClickable(targetScale = 0.96f) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                val statusBarManager = context.getSystemService(android.app.StatusBarManager::class.java)
                                statusBarManager?.requestAddTileService(
                                    android.content.ComponentName(context, com.danieldk.splatevpn.vpn.SplateTileService::class.java),
                                    "SplateVPN",
                                    android.graphics.drawable.Icon.createWithResource(context, com.danieldk.splatevpn.R.drawable.ic_qs_vpn),
                                    context.mainExecutor
                                ) { result ->
                                    when (result) {
                                        android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> {
                                            Toast.makeText(context, strings.quickSettingsTileAdded, Toast.LENGTH_SHORT).show()
                                        }
                                        android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> {
                                            Toast.makeText(context, strings.quickSettingsTileAlreadyAdded, Toast.LENGTH_SHORT).show()
                                        }
                                        else -> {}
                                    }
                                }
                            } else {
                                Toast.makeText(
                                    context,
                                    strings.quickSettingsManualPrompt,
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.DashboardCustomize, contentDescription = null, tint = colors.accentEmerald, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        strings.quickSettingsAddTile,
                        color = colors.textPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            }

            if (visible(SettingsCategory.DIAGNOSTICS)) {
            // Section 5: Diagnostics & Logs
            ConnectionToolsSection(viewModel)
            Spacer(modifier = Modifier.height(24.dp))
            SectionHeader(title = strings.sectionDiagnostics)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .liquidGlass(cornerRadius = 20, innerSheen = true)
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = strings.diagnosticsDesc,
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .liquidGlass(
                            cornerRadius = 14,
                            accentBorder = colors.accentCyan.copy(alpha = 0.6f),
                            innerSheen = true
                        )
                        .tactileClickable(targetScale = 0.96f) { showLogsDialog = true }
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.BugReport, contentDescription = null, tint = colors.accentCyan, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        strings.openLogsButton,
                        color = colors.textPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            }

            if (visible(SettingsCategory.BACKUP)) {
            // Section 6: Backup & Restore
            SectionHeader(title = strings.sectionBackupRestore)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .liquidGlass(cornerRadius = 20, innerSheen = true)
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = strings.backupRestoreDesc,
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Export Button
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .liquidGlass(
                                cornerRadius = 14,
                                accentBorder = colors.accentCyan.copy(alpha = 0.5f),
                                innerSheen = true
                            )
                            .tactileClickable(targetScale = 0.96f) {
                                exportLauncher.launch("splate_backup_${System.currentTimeMillis()}.json")
                            }
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.FileDownload, contentDescription = null, tint = colors.accentCyan, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = strings.exportBtn,
                            color = colors.accentCyan,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Import Button
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .liquidGlass(
                                cornerRadius = 14,
                                accentBorder = colors.accentEmerald.copy(alpha = 0.5f),
                                innerSheen = true
                            )
                            .tactileClickable(targetScale = 0.96f) {
                                showImportMode = true
                            }
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.FileUpload, contentDescription = null, tint = colors.accentEmerald, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = strings.importBtn,
                            color = colors.accentEmerald,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Auto-Snapshot Rescue Button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .liquidGlass(
                            cornerRadius = 14,
                            accentBorder = colors.accentViolet.copy(alpha = 0.5f),
                            innerSheen = true
                        )
                        .tactileClickable(targetScale = 0.96f) {
                            viewModel.restoreFromAutoVault { res ->
                                if (res.isSuccess) {
                                    val count = res.getOrNull() ?: 0
                                    Toast.makeText(context, String.format(strings.autoRestoreSuccess, count), Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, strings.autoRestoreNotFound, Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.History, contentDescription = null, tint = colors.accentViolet, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = strings.autoRestoreBtn,
                        color = colors.accentViolet,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            }

        }
        }
        }

        if (showImportMode) {
            AlertDialog(
                onDismissRequest = { showImportMode = false },
                containerColor = colors.surface,
                title = { Text(if (russian) "Как восстановить копию?" else "How should the backup be restored?", color = colors.textPrimary) },
                text = {
                    Column {
                        com.danieldk.splatevpn.data.BackupManager.ImportMode.entries.forEach { mode ->
                            Row(Modifier.fillMaxWidth().clickable { importMode = mode }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = importMode == mode, onClick = { importMode = mode })
                                Column(Modifier.weight(1f)) {
                                    Text(if (mode == com.danieldk.splatevpn.data.BackupManager.ImportMode.MERGE) {
                                        if (russian) "Объединить" else "Merge"
                                    } else if (russian) "Заменить данные" else "Replace data", color = colors.textPrimary)
                                    Text(if (mode == com.danieldk.splatevpn.data.BackupManager.ImportMode.MERGE) {
                                        if (russian) "Добавить серверы и правила. Настройки из файла применятся." else "Add servers and rules. Settings from the file will apply."
                                    } else if (russian) "Заменить серверы, подписки, списки приложений и маршруты, включая пустые списки." else "Replace servers, subscriptions, app lists and routes, including empty lists.", color = colors.textSecondary, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showImportMode = false; importLauncher.launch(arrayOf("application/json", "*/*")) }) {
                    Text(if (russian) "Выбрать файл" else "Choose file")
                } },
                dismissButton = { TextButton(onClick = { showImportMode = false }) { Text(strings.cancel) } }
            )
        }

        if (showLogsDialog) {
            LogsDialog(
                viewModel = viewModel,
                onDismiss = { showLogsDialog = false }
            )
        }

        if (showTlsCustomDialog) {
            var tempLength by remember { mutableStateOf(tlsFragmentLength) }
            var tempInterval by remember { mutableStateOf(tlsFragmentInterval) }
            var tempPackets by remember { mutableStateOf(tlsFragmentPackets) }

            AlertDialog(
                onDismissRequest = { showTlsCustomDialog = false },
                containerColor = colors.surface,
                title = { Text(strings.tlsCustomTitle, color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = tempLength,
                            onValueChange = { tempLength = it },
                            label = { Text(strings.tlsFragmentLengthLabel, fontSize = 11.sp) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = colors.accentCyan,
                                unfocusedBorderColor = colors.borderSubtle,
                                focusedTextColor = colors.textPrimary,
                                unfocusedTextColor = colors.textPrimary
                            )
                        )
                        OutlinedTextField(
                            value = tempInterval,
                            onValueChange = { tempInterval = it },
                            label = { Text(strings.tlsFragmentIntervalLabel, fontSize = 11.sp) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = colors.accentCyan,
                                unfocusedBorderColor = colors.borderSubtle,
                                focusedTextColor = colors.textPrimary,
                                unfocusedTextColor = colors.textPrimary
                            )
                        )
                        OutlinedTextField(
                            value = tempPackets,
                            onValueChange = { tempPackets = it },
                            label = { Text(strings.tlsFragmentPacketsLabel, fontSize = 11.sp) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = colors.accentCyan,
                                unfocusedBorderColor = colors.borderSubtle,
                                focusedTextColor = colors.textPrimary,
                                unfocusedTextColor = colors.textPrimary
                            )
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.setTlsFragmentCustomParams(tempLength, tempInterval, tempPackets)
                            showTlsCustomDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accentCyan)
                    ) {
                        Text(strings.save, color = LocalAppThemeColors.current.onAccent, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showTlsCustomDialog = false }) {
                        Text(strings.cancel, color = colors.textSecondary)
                    }
                }
            )
        }

        if (showShareLanDialog) {
            val endpoints by produceState(initialValue = viewModel.getActiveLanEndpoints(), isShareLan) {
                            while (true) {
                                value = viewModel.getActiveLanEndpoints()
                                kotlinx.coroutines.delay(3000)
                            }
                        }
            com.danieldk.splatevpn.ui.components.ShareLanInstructionsDialog(
                endpoints = endpoints,
                onDismiss = { showShareLanDialog = false }
            )
        }
    }
}

private val SettingsCategory.icon: ImageVector
    get() = when (this) {
        SettingsCategory.CONNECTION -> Icons.Default.PowerSettingsNew
        SettingsCategory.NETWORK -> Icons.Default.Dns
        SettingsCategory.AI -> Icons.Default.AutoAwesome
        SettingsCategory.PROTECTION -> Icons.Default.Shield
        SettingsCategory.APPEARANCE -> Icons.Default.Palette
        SettingsCategory.BACKUP -> Icons.Default.Backup
        SettingsCategory.DIAGNOSTICS -> Icons.Default.NetworkCheck
        SettingsCategory.ADVANCED -> Icons.Default.Tune
        SettingsCategory.SHARING -> Icons.Default.WifiTethering
        SettingsCategory.ABOUT -> Icons.Default.Info
    }

@Composable
fun SectionHeader(title: String) {
    val colors = LocalAppThemeColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 10.dp, start = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(11.dp)
                .clip(RoundedCornerShape(1.5.dp))
                .background(colors.accentCyan)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title.uppercase(),
            color = colors.textSecondary,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp
        )
    }
}

@Composable
fun SettingToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val colors = LocalAppThemeColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .tactileClickable(targetScale = 0.98f) { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .liquidGlass(
                    cornerRadius = 10,
                    accentBorder = if (checked) colors.accentCyan.copy(alpha = 0.40f) else null,
                    innerSheen = true
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (checked) colors.accentCyan else colors.textSecondary,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = colors.textSecondary, fontSize = 11.sp, lineHeight = 14.sp)
        }

        Spacer(modifier = Modifier.width(8.dp))

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.accentCyan,
                checkedTrackColor = colors.accentCyan.copy(alpha = 0.5f)
            )
        )
    }
}

@Composable
fun SelectableSettingChip(
    modifier: Modifier = Modifier,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val colors = LocalAppThemeColors.current
    val textColor by animateColorAsState(
        targetValue = if (isSelected) colors.accentCyan else colors.textPrimary,
        animationSpec = tween(220),
        label = "chip_text"
    )

    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 36.dp)
            .liquidGlass(
                cornerRadius = 10,
                accentBorder = if (isSelected) colors.accentCyan else null,
                backgroundColor = if (isSelected) colors.accentCyan.copy(alpha = 0.14f) else null,
                innerSheen = true
            )
            .tactileClickable(targetScale = 0.93f) { onClick() }
            .padding(horizontal = 8.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    val colors = LocalAppThemeColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            color = colors.textSecondary,
            fontSize = 13.sp,
            modifier = Modifier.weight(0.4f, fill = false)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            value,
            color = colors.textPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.weight(0.6f)
        )
    }
}
