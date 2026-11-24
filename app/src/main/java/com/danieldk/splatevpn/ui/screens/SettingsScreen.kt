package com.danieldk.splatevpn.ui.screens

import com.danieldk.splatevpn.ui.components.SplateTextButton as TextButton
import com.danieldk.splatevpn.ui.components.SplateIconButton as IconButton
import com.danieldk.splatevpn.ui.components.SplateSwitch as Switch
import com.danieldk.splatevpn.ui.components.SplateRadioButton as RadioButton

import com.danieldk.splatevpn.ui.components.SplateAlertDialog as AlertDialog

import com.danieldk.splatevpn.ui.components.SplateButton as Button
import com.danieldk.splatevpn.ui.components.SplateOutlinedButton as OutlinedButton

import com.danieldk.splatevpn.ui.theme.localized

import android.os.Build
import android.widget.Toast
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danieldk.splatevpn.R
import com.danieldk.splatevpn.ui.MainViewModel
import com.danieldk.splatevpn.ui.components.KillSwitchCard
import com.danieldk.splatevpn.ui.components.BalancerSettingsCard
import com.danieldk.splatevpn.ui.components.GlassTransparencyControl
import com.danieldk.splatevpn.ui.components.SmartDnsProviderPicker
import com.danieldk.splatevpn.ui.components.AiDnsSelectionCard
import com.danieldk.splatevpn.ui.components.PageHeader
import com.danieldk.splatevpn.ui.components.SettingsToggleContent
import com.danieldk.splatevpn.ui.components.liquidGlass
import com.danieldk.splatevpn.ui.components.tactileClickable
import com.danieldk.splatevpn.ui.components.tvFocusOutline
import com.danieldk.splatevpn.ui.components.LocalTvControls
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.danieldk.splatevpn.ui.theme.LocalAppStrings
import com.danieldk.splatevpn.ui.theme.LocalAppThemeColors
import com.danieldk.splatevpn.ui.theme.aiDnsTools

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    resetRequest: Int = 0,
    initialCategory: String? = null
) {
    val uiMode by viewModel.uiMode.collectAsState()
    var themesExpanded by remember { mutableStateOf(false) }
    var showMtuDialog by remember { mutableStateOf(false) }
    var showTlsCustomDialog by remember { mutableStateOf(false) }
    var showShareLanDialog by remember { mutableStateOf(false) }
    var showTourDialog by rememberSaveable { mutableStateOf(false) }

    val strings = LocalAppStrings.current
    val colors = LocalAppThemeColors.current
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    val navigation = rememberSettingsNavigationState(resetRequest,
        SettingsCategory.entries.firstOrNull { it.name == initialCategory })
    val selectedCategory = navigation.category
    val tvControls = LocalTvControls.current
    val pageFocus = remember(navigation, selectedCategory) { FocusRequester() }
    val settingsQuery = navigation.query
    // Animate only the new page's graphics layer: no second page or per-frame list composition.
    val animationsEnabled = com.danieldk.splatevpn.ui.theme.LocalUiPerformanceSettings.current.animationsEnabled
    val pageEntrance = remember(selectedCategory) { Animatable(if (animationsEnabled) 0f else 1f) }
    LaunchedEffect(pageEntrance, animationsEnabled) {
        if (!animationsEnabled) pageEntrance.snapTo(1f)
        else pageEntrance.animateTo(1f, tween(durationMillis = 220, easing = FastOutSlowInEasing))
    }
    val pageStateHolder = rememberSaveableStateHolder()
    val coroutineScope = rememberCoroutineScope()
    var importMode by rememberSaveable { mutableStateOf(com.danieldk.splatevpn.data.BackupManager.ImportMode.MERGE) }
    var showImportMode by remember { mutableStateOf(false) }
    var backupPassword by remember { mutableStateOf("") }
    var encryptBackup by remember { mutableStateOf(true) }
    var confirmBackupPassword by remember { mutableStateOf("") }
    val resources = com.danieldk.splatevpn.ui.theme.LocalAppResources.current

    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val secret = if (encryptBackup) backupPassword.toCharArray() else null
                val res = try { viewModel.exportBackup(uri, secret) } finally { secret?.fill('\u0000') }
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
                val secret = backupPassword.takeIf { it.isNotEmpty() }?.toCharArray()
                val res = try { viewModel.importBackup(uri, importMode, secret) } finally { secret?.fill('\u0000') }
                if (res.isSuccess) {
                    Toast.makeText(context, String.format(strings.backupImportSuccess, res.getOrNull() ?: 0), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, String.format(strings.backupImportError, res.exceptionOrNull()?.message ?: ""), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    BackHandler(enabled = selectedCategory != null || settingsQuery.isNotEmpty()) {
        navigation.home()
    }
    LaunchedEffect(uiMode, selectedCategory) {
        if (selectedCategory != null && selectedCategory !in visibleSettingsCategories(uiMode)) {
            navigation.home()
        }
    }

    var isUpdatingSubs by remember { mutableStateOf(false) }
    var showLogsDialog by remember { mutableStateOf(false) }
    var lanPassword by remember(viewModel) { mutableStateOf<String?>(null) }
    var isRotatingLanPassword by remember { mutableStateOf(false) }
    LaunchedEffect(viewModel, selectedCategory, showShareLanDialog) {
        if (selectedCategory == SettingsCategory.SHARING || showShareLanDialog) {
            lanPassword = withContext(Dispatchers.IO) { viewModel.getLanPassword() }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        // Compose only the active page. AnimatedContent used to keep both large
        // settings pages alive and measure them during every transition.
        pageStateHolder.SaveableStateProvider(selectedCategory?.name ?: "categories") {
        val listState = rememberLazyListState()
        LaunchedEffect(selectedCategory, resetRequest) {
            if (tvControls) {
                listState.scrollToItem(0)
                withFrameNanos { }
                pageFocus.requestFocus()
            }
        }
        fun visible(category: SettingsCategory) = selectedCategory == category
        LazyColumn(
            state = listState,
            modifier = Modifier
                .widthIn(max = 680.dp)
                .fillMaxSize()
                .graphicsLayer {
                    alpha = 0.55f + pageEntrance.value * 0.45f
                    translationX = (1f - pageEntrance.value) * 16.dp.toPx() * if (selectedCategory == null) -1f else 1f
                }
                .testTag("settingsList"),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(top = 20.dp, start = 20.dp, end = 20.dp,
                bottom = 16.dp + if (tvControls) LocalBottomDockInset.current else maxOf(LocalBottomDockInset.current, 96.dp))
        ) {
            if (!tvControls || selectedCategory == null) {
            item(key = "header") {
            PageHeader(title = strings.settingsTitle, subtitle = strings.settingsSubtitle)
            }

            if (selectedCategory == null) {
                item(key = "ui_mode") {
                    UiModeSelectorCard(
                        mode = uiMode,
                        onSelect = viewModel::setUiMode
                    )
                }
            }

            if (!uiMode.isSimple) item(key = "search") {
            OutlinedTextField(
                value = settingsQuery,
                onValueChange = navigation::search,
                placeholder = { Text(strings.localized("Найти настройку…", "Search settings…")) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (settingsQuery.isNotEmpty()) IconButton(onClick = { navigation.search("") }) {
                    Icon(Icons.Default.Close, strings.localized("Очистить поиск", "Clear search"))
                } },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = colors.textPrimary,
                    unfocusedTextColor = colors.textPrimary, focusedBorderColor = colors.accentCyan,
                    unfocusedBorderColor = colors.borderSubtle)
            )
            }
            }
            if (selectedCategory == null) {
                val baseCategories = visibleSettingsCategories(uiMode)
                val categories = if (uiMode.isSimple) baseCategories else baseCategories.filter { it.matches(settingsQuery) || strings.localized(it.ru, it.en).contains(settingsQuery.trim(), true) || strings.localized(it.hintsRu, it.hintsEn).contains(settingsQuery.trim(), true) }
                if (categories.isEmpty()) item(key = "empty") {
                    Text(strings.localized("Ничего не найдено. Попробуйте DNS, тему или бэкап.", "No matches. Try DNS, theme or backup."), color = colors.textSecondary)
                }
                items(categories, key = { "category:${it.name}" }, contentType = { "category" }) { category ->
                    Row(Modifier.then(if (tvControls && category == categories.firstOrNull()) Modifier.focusRequester(pageFocus) else Modifier)
                        .fillMaxWidth().testTag("settingsCategory:${category.name}")
                        .tactileClickable(targetScale = 0.98f) {
                            navigation.open(category)
                            if (!tvControls) focusManager.clearFocus()
                        }.liquidGlass(cornerRadius = 20, innerSheen = true).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(40.dp).background(colors.accentCyan.copy(alpha = 0.10f), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(category.icon, null, Modifier.size(22.dp), tint = colors.accentCyan)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(strings.localized(category.ru, category.en), color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text(strings.localized(category.hintsRu, category.hintsEn), color = colors.textSecondary, fontSize = 12.sp, lineHeight = 17.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.textSecondary)
                    }
                }
            } else {
                item(key = "categoryTitle") {
                    Row(
                        modifier = Modifier
                            .liquidGlass(
                                cornerRadius = 12,
                                innerSheen = true,
                                accentBorder = colors.accentCyan.copy(alpha = 0.45f)
                            )
                            .focusRequester(pageFocus)
                            .tvFocusOutline()
                            .tactileClickable(targetScale = 0.96f, onClick = navigation::home)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            tint = colors.accentCyan,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = strings.localized("Все настройки", "All settings"),
                            color = colors.textPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(selectedCategory.icon, null, Modifier.size(26.dp), tint = colors.accentCyan)
                        Text(strings.localized(selectedCategory.ru, selectedCategory.en), color = colors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }

            if (visible(SettingsCategory.APPEARANCE)) {
            item(key = "appearance:0") {
            val appTheme by viewModel.appTheme.collectAsState()
            val appLanguage by viewModel.appLanguage.collectAsState()
            val glassTransparency by viewModel.glassTransparency.collectAsState()
            val glassBlurEnabled by viewModel.glassBlurEnabled.collectAsState()
            val performanceSettings by viewModel.performanceSettings.collectAsState()
            // Section 1: Appearance & Interface (Theme & Language)
            SectionHeader(title = strings.sectionAppearance)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .liquidGlass(cornerRadius = 20, innerSheen = true)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Theme Selection
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
                        "cyberpunk" to strings.localized("Киберпанк", "Cyberpunk"),
                        "winter" to strings.themeWinter,
                        "winter_light" to strings.themeWinterLight,
                        "red" to strings.themeRed,
                        "aurora" to strings.themeAurora,
                        "sunset" to strings.themeSunset,
                        "ocean" to strings.themeOcean,
                        "sakura" to strings.themeSakura,
                        "pony" to strings.themePony,
                        "winx" to strings.themeWinx
                    )
                    val visibleThemes = if (themesExpanded) themesList else themesList.take(4)
                    com.danieldk.splatevpn.ui.components.ThemeSelectionGrid(
                        themes = themesList, expanded = themesExpanded, selected = appTheme.lowercase(),
                        onSelect = viewModel::setAppTheme)
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

                com.danieldk.splatevpn.ui.components.UiPerformanceSettingsCard(performanceSettings, glassBlurEnabled,
                    viewModel::setUiAnimationsEnabled, viewModel::setDecorativeBackgroundEnabled,
                    viewModel::setGlassHighlightsEnabled, viewModel::setLightUiMode, viewModel::restoreFullUiEffects)

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

                    LanguageChoices(appLanguage, viewModel::setAppLanguage)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { showTourDialog = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.School, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(strings.localized("Тренажёр", "Interactive Tour"), fontSize = 12.sp)
                        }
                        TextButton(
                            onClick = viewModel::showOnboarding,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(com.danieldk.splatevpn.ui.theme.LocalAppResources.current.getString(com.danieldk.splatevpn.R.string.onboarding_reopen), fontSize = 12.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            }
            }


            if (visible(SettingsCategory.TOR)) {
                item(key = "tor") {
                    com.danieldk.splatevpn.ui.components.TorSettingsCard(viewModel)
                }
            }

            if (visible(SettingsCategory.CONNECTION)) {
            item(key = "connection-network-policy") { com.danieldk.splatevpn.ui.components.SourcePrivacyCard(viewModel) }
            item(key = "connection-notifications") { com.danieldk.splatevpn.ui.components.NotificationSettingsCard(viewModel) }
            item(key = "connection-recommended") { com.danieldk.splatevpn.ui.components.RecommendedAccessCard() }
            item(key = "connection:1") {
            val isAutoSelect by viewModel.isAutoSelect.collectAsState()
            val balancerPreferences by viewModel.balancerPreferences.collectAsState()
            val autoConnectOnOpen by viewModel.autoConnectOnOpen.collectAsState()
            val isAutoConnectOnBoot by viewModel.isAutoConnectOnBoot.collectAsState()
            val autoUpdateInterval by viewModel.autoUpdateInterval.collectAsState()
            // Section 2: Automation & Subscriptions
            SectionHeader(title = strings.sectionAutomation)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .liquidGlass(cornerRadius = 20, innerSheen = true)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Auto-select lowest ping server
                SettingToggleRow(
                    icon = Icons.Default.Bolt,
                    title = strings.autoSelectTitle,
                    subtitle = strings.autoSelectSubtitle,
                    checked = isAutoSelect,
                    onCheckedChange = { viewModel.setAutoSelect(it) },
                    modifier = Modifier.testTag("settingsAutoBalancer")
                )

                BalancerSettingsCard(balancerPreferences, isAutoSelect, viewModel::setBalancerPreferences, embedded = true)

                HorizontalDivider(color = colors.borderSubtle.copy(alpha = 0.6f))

                SettingToggleRow(
                    icon = Icons.AutoMirrored.Filled.Login,
                    title = strings.localized("Подключаться при открытии", "Connect when opening the app"),
                    subtitle = strings.localized("Автоматически подключать выбранный VPN или Tor при входе в приложение", "Start the selected VPN or Tor mode when entering the app"),
                    checked = autoConnectOnOpen,
                    onCheckedChange = viewModel::setAutoConnectOnOpen,
                    modifier = Modifier.testTag("settingsAutoConnectOnOpen")
                )
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

                    val intervals = listOf("off" to strings.intervalOff,
                        "1h" to strings.localized("1 ч", "1 h"), "3h" to strings.localized("3 ч", "3 h"),
                        "6h" to strings.interval6h, "12h" to strings.interval12h, "24h" to strings.interval24h)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        intervals.chunked(3).forEach { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { (key, label) ->
                                    SelectableSettingChip(
                                        modifier = Modifier.weight(1f).testTag("subscriptionInterval:$key"),
                                        label = label,
                                        isSelected = autoUpdateInterval == key,
                                        onClick = { viewModel.setAutoUpdateInterval(key) }
                                    )
                                }
                            }
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
            }

            if (visible(SettingsCategory.NETWORK)) {
            item(key = "network:2") {
            val routingRegion by viewModel.routingRegion.collectAsState()
            val routingMode by viewModel.routingMode.collectAsState()
            val dnsProvider by viewModel.dnsProvider.collectAsState()
            val customDohUrl by viewModel.customDohUrl.collectAsState()
            val pingMode by viewModel.pingMode.collectAsState()
            Column(Modifier.fillMaxWidth().liquidGlass(cornerRadius = 20, innerSheen = true).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                RegionChoices(routingRegion, viewModel::setRoutingRegion)
                // Routing mode independent of region
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
                            Text(if (routingMode == "smart") com.danieldk.splatevpn.ui.theme.LocalAppResources.current.getString(com.danieldk.splatevpn.R.string.smart_local_description) else strings.globalTunnelDesc, color = colors.textSecondary, fontSize = 11.sp)
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
                            isSelected = routingMode == "smart",
                            onClick = { viewModel.setRoutingMode("smart") }
                        )
                        SelectableSettingChip(
                            modifier = Modifier.weight(1f),
                            label = strings.globalTunnel,
                            isSelected = routingMode == "global",
                            onClick = { viewModel.setRoutingMode("global") }
                        )
                    }
                }

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

                    val dnsChecks by viewModel.dnsProviderChecks.collectAsState()
                    com.danieldk.splatevpn.ui.components.DnsProviderChoices(
                        selected = dnsProvider, customUrl = customDohUrl, checks = dnsChecks,
                        canCheck = viewModel.vpnState.collectAsState().value in setOf(com.danieldk.splatevpn.vpn.VpnState.CONNECTED, com.danieldk.splatevpn.vpn.VpnState.DISCONNECTED, com.danieldk.splatevpn.vpn.VpnState.FAILED),
                        onSelect = viewModel::setDnsProvider, onCustomUrl = viewModel::setCustomDohUrl,
                        onCheck = viewModel::checkDnsProviders, onCancel = viewModel::cancelDnsProviderChecks)
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
            }

            if (visible(SettingsCategory.AI)) {
            item(key = "ai:3") {
            val isBypassAi by viewModel.isBypassAi.collectAsState()
            val aiRoutingMode by viewModel.aiRoutingMode.collectAsState()
            val isAiDnsAutoSwitch by viewModel.isAiDnsAutoSwitch.collectAsState()
            val aiDnsProvider by viewModel.aiDnsProvider.collectAsState()
            val isAiTlsFragment by viewModel.isAiTlsFragment.collectAsState()
            val aiDnsSelection by viewModel.aiDnsSelection.collectAsState()
            val vpnConnectedForAi by viewModel.isConnected.collectAsState()
            Column(Modifier.fillMaxWidth().liquidGlass(cornerRadius = 20, innerSheen = true).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
                    SelectableSettingChip(
                        modifier = Modifier.fillMaxWidth(),
                        label = strings.aiDnsTools.viaVpn,
                        isSelected = aiRoutingMode == "dns_vpn",
                        onClick = { viewModel.setAiRoutingMode("dns_vpn") }
                    )
                    if (aiRoutingMode == "dns_vpn") {
                        Text(strings.aiDnsTools.viaVpnHint, fontSize = 12.sp, color = colors.textSecondary)
                    }
                    if (aiRoutingMode != "vpn") {
                        SmartDnsProviderPicker(aiDnsProvider, viewModel::setAiDnsProvider)
                    }
                    if (aiRoutingMode in setOf("dns_ai", "dns_vpn")) {
                        SettingToggleRow(icon = Icons.Default.AutoAwesome,
                            title = strings.aiDnsTools.autoSwitchTitle, subtitle = strings.aiDnsTools.autoSwitchHint,
                            checked = isAiDnsAutoSwitch, onCheckedChange = viewModel::setAiDnsAutoSwitch)
                        SettingToggleRow(icon = Icons.AutoMirrored.Filled.CallSplit,
                            title = strings.aiTlsFragmentTitle, subtitle = strings.aiTlsFragmentHint,
                            checked = isAiTlsFragment, onCheckedChange = viewModel::setAiTlsFragment)
                        AiDnsSelectionCard(aiDnsSelection, viewModel::startAiDnsSelection, viewModel::cancelAiDnsSelection,
                            startEnabled = aiRoutingMode != "dns_vpn" || vpnConnectedForAi)
                    }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            }
            }

            if (visible(SettingsCategory.PROTECTION)) {
            item(key = "protection:4") {
            val isBlockIpv6 by viewModel.isBlockIpv6.collectAsState()
            val isAdBlocker by viewModel.isAdBlocker.collectAsState()
            val adFilterState by viewModel.adFilterState.collectAsState()
            val adBlockAllowlist by viewModel.adBlockAllowlist.collectAsState()
            Column(Modifier.fillMaxWidth().liquidGlass(cornerRadius = 20, innerSheen = true).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
            }

            if (visible(SettingsCategory.ADVANCED)) {
            item(key = "advanced:5") {
            val xhttpMode by viewModel.xhttpMode.collectAsState()
            val isRouteOnlySniffing by viewModel.isRouteOnlySniffing.collectAsState()
            val isBlockQuic by viewModel.isBlockQuic.collectAsState()
            val isFakeDns by viewModel.isFakeDns.collectAsState()
            val mtu by viewModel.mtu.collectAsState()
            val tlsMixedSni by viewModel.tlsMixedSni.collectAsState()
            val isTlsFragment by viewModel.isTlsFragment.collectAsState()
            val tlsFragmentPreset by viewModel.tlsFragmentPreset.collectAsState()
            val tlsFragmentLength by viewModel.tlsFragmentLength.collectAsState()
            val tlsFragmentInterval by viewModel.tlsFragmentInterval.collectAsState()
            val tlsFragmentPackets by viewModel.tlsFragmentPackets.collectAsState()
            val isMux by viewModel.isMux.collectAsState()
            val muxConcurrency by viewModel.muxConcurrency.collectAsState()
            Column(Modifier.fillMaxWidth().liquidGlass(cornerRadius = 20, innerSheen = true).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
                SettingToggleRow(
                    icon = Icons.Default.FilterAlt,
                    title = resources.getString(R.string.tls_mixed_sni_title),
                    subtitle = resources.getString(R.string.tls_mixed_sni_hint),
                    checked = tlsMixedSni,
                    onCheckedChange = viewModel::setTlsMixedSni
                )
                HorizontalDivider(color = colors.borderSubtle)
                TextButton(onClick = { showMtuDialog = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(resources.getString(R.string.vpn_mtu_value, mtu))
                }
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
                            SelectableSettingChip(
                                modifier = Modifier.fillMaxWidth(),
                                label = resources.getString(R.string.tls_two_parts),
                                isSelected = tlsFragmentPreset == "two_parts",
                                onClick = { viewModel.setTlsFragmentPreset("two_parts") }
                            )
                            Text(
                                text = String.format(strings.tlsParametersSummary, tlsFragmentLength, tlsFragmentInterval,
                                    if (tlsFragmentPackets == com.danieldk.splatevpn.data.TLS_HELLO_TWO_PARTS) "TLS ClientHello" else tlsFragmentPackets),
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
            }

            if (visible(SettingsCategory.SHARING)) {
            item(key = "local-mixed-proxy") { com.danieldk.splatevpn.ui.components.LocalProxySettingsCard(viewModel) }
            item(key = "sharing:6") {
            val isShareLan by viewModel.isShareLan.collectAsState()
            Column(Modifier.fillMaxWidth().liquidGlass(cornerRadius = 20, innerSheen = true).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
                        val endpoints by rememberSettingsLanEndpoints(viewModel, isShareLan)
                        var selectedInterface by remember { mutableStateOf(viewModel.getLanInterface()) }
                        var showPassword by remember { mutableStateOf(false) }
                        val lanAuthentication by viewModel.lanAuthentication.collectAsState()
                        Text(strings.localized("Интерфейс раздачи", "Sharing interface"), color = colors.textPrimary)
                        (listOf("" to strings.localized("Автоматически", "Automatic")) + endpoints.map { it.interfaceName to "${it.displayName}: ${it.ipAddress}" }).distinctBy { it.first }.forEach { (name, label) ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = selectedInterface == name, onClick = { selectedInterface = name; viewModel.setLanInterface(name) })
                                Text(label, color = colors.textSecondary, fontSize = 12.sp)
                            }
                        }
                        SettingToggleRow(icon = Icons.Default.Lock, title = resources.getString(com.danieldk.splatevpn.R.string.lan_password_toggle),
                            subtitle = resources.getString(com.danieldk.splatevpn.R.string.lan_password_hint), checked = lanAuthentication,
                            onCheckedChange = viewModel::setLanAuthenticationEnabled)
                        if (lanAuthentication) {
                        Text(strings.localized("Прокси требует авторизацию. Логин: splate", "Proxy authentication is required. Username: splate"), color = colors.textPrimary, fontSize = 12.sp)
                        OutlinedTextField(value = lanPassword.orEmpty(), onValueChange = {}, readOnly = true,
                            label = { Text(strings.localized("Пароль", "Password")) }, modifier = Modifier.fillMaxWidth(),
                            visualTransformation = if (showPassword) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            trailingIcon = { IconButton(onClick = { showPassword = !showPassword }) { Icon(Icons.Default.Visibility, contentDescription = strings.localized("Показать пароль", "Show password")) } })
                        TextButton(enabled = !isRotatingLanPassword && lanPassword != null, onClick = {
                            isRotatingLanPassword = true
                            coroutineScope.launch {
                                try {
                                    lanPassword = withContext(Dispatchers.IO) { viewModel.rotateLanPassword() }
                                } finally {
                                    isRotatingLanPassword = false
                                }
                            }
                        }) { Text(strings.localized("Сменить пароль", "Rotate password")) }
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .liquidGlass(cornerRadius = 12, innerSheen = false, accentBorder = colors.accentCyan.copy(alpha = 0.35f))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (endpoints.isNotEmpty()) {
                                endpoints.filter { selectedInterface.isBlank() || it.interfaceName == selectedInterface }.take(1).forEach { ep ->
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

                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = { showShareLanDialog = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(Icons.Default.Devices, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(strings.shareLanInstructionsBtn, fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            }
            }

            if (visible(SettingsCategory.ROUTING)) {
            item(key = "routing:7") {
            // Section 4: Custom Domain Routes
            SectionHeader(title = strings.sectionCustomRoutes)
            com.danieldk.splatevpn.ui.components.RoutingToolsCard(viewModel)
            Spacer(Modifier.height(24.dp))
            }
            }

            if (visible(SettingsCategory.ABOUT)) {
            item(key = "about:8") {
            com.danieldk.splatevpn.ui.components.GlassSettingsCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.Favorite, contentDescription = null, tint = colors.accentCrimson, modifier = Modifier.size(28.dp))
                    Text(strings.localized("Поддержать проект", "Support the project"), color = colors.textPrimary,
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Text(strings.localized("Добровольный донат помогает развивать SplateVPN. Спасибо за поддержку!",
                    "A voluntary donation helps develop SplateVPN. Thank you for your support!"), color = colors.textSecondary)
                com.danieldk.splatevpn.ui.components.SplateButton(
                    modifier = Modifier.fillMaxWidth().testTag("settingsDonate"),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accentCrimson,
                        contentColor = com.danieldk.splatevpn.ui.theme.contentColorOn(colors.accentCrimson)), onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.donationalerts.com/r/danieldkgt")))
                }) { Text(strings.localized("Пожертвовать через DonationAlerts", "Donate via DonationAlerts")) }
            }
            Spacer(Modifier.height(16.dp))
            // Section 5: Core Info
            SectionHeader(title = strings.sectionCore)
            com.danieldk.splatevpn.ui.components.GeoDataCard(viewModel)
            Spacer(Modifier.height(16.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .liquidGlass(cornerRadius = 20, innerSheen = true)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                InfoRow(label = resources.getString(R.string.about_app_version), value = com.danieldk.splatevpn.BuildConfig.VERSION_NAME)
                InfoRow(label = strings.coreVersion, value = "Xray ${com.danieldk.splatevpn.SplateConstants.XRAY_VERSION} (XTLS)")
                InfoRow(label = strings.coreArch, value = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
                InfoRow(label = strings.coreTunnel, value = "hev-socks5-tunnel")
                InfoRow(label = strings.coreProtocols,
                    value = "VLESS, VMess, Trojan, Shadowsocks, Hysteria2, AmneziaWG / WireGuard, Tor, SOCKS5, HTTP")
                InfoRow(label = resources.getString(R.string.about_transports), value = "TCP, WebSocket, gRPC, XHTTP, XDrive")
                InfoRow(label = strings.coreFakeDns, value = "198.18.0.0/15 (65535 IPs)")

            }

            Spacer(Modifier.height(16.dp))
            com.danieldk.splatevpn.ui.components.GlassSettingsCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(48.dp).liquidGlass(cornerRadius = 14, accentBorder = colors.accentCyan, innerSheen = true), contentAlignment = Alignment.Center) {
                        Text("</>", color = colors.accentCyan, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    }
                    Column {
                        Text("SplateVPN · GitHub", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text(com.danieldk.splatevpn.ui.theme.LocalAppResources.current.getString(com.danieldk.splatevpn.R.string.github_title), color = colors.accentCyan, fontSize = 12.sp)
                    }
                }
                Text(com.danieldk.splatevpn.ui.theme.LocalAppResources.current.getString(com.danieldk.splatevpn.R.string.github_description), color = colors.textSecondary, fontSize = 13.sp)
                com.danieldk.splatevpn.ui.components.GlassSettingsAction(com.danieldk.splatevpn.ui.theme.LocalAppResources.current.getString(com.danieldk.splatevpn.R.string.github_open)) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(com.danieldk.splatevpn.SplateConstants.REPOSITORY_URL)))
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            }
            }

            if (visible(SettingsCategory.ABOUT)) {
            item(key = "about:9") {
            // Section: App Updates
            SectionHeader(title = strings.sectionAppUpdates)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .liquidGlass(cornerRadius = 20, innerSheen = true)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val isChecking by viewModel.isCheckingUpdate.collectAsState()
                val updateResult by viewModel.updateCheckResult.collectAsState()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .liquidGlass(
                            cornerRadius = 14,
                            accentBorder = colors.accentCyan.copy(alpha = 0.6f),
                            innerSheen = true
                        )
                        .tactileClickable(targetScale = 0.96f) {
                            if (!isChecking) viewModel.checkForUpdates()
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
                if (!isChecking) {
                    val status = when (val result = updateResult) {
                        is com.danieldk.splatevpn.data.UpdateCheckResult.Available ->
                            resources.getString(R.string.update_result_available, result.info.version)
                        com.danieldk.splatevpn.data.UpdateCheckResult.UpToDate ->
                            resources.getString(R.string.update_result_current)
                        com.danieldk.splatevpn.data.UpdateCheckResult.WifiRequired ->
                            resources.getString(R.string.update_result_wifi)
                        com.danieldk.splatevpn.data.UpdateCheckResult.Failed ->
                            resources.getString(R.string.update_result_failed)
                        null -> null
                    }
                    status?.let { Text(it, color = colors.textSecondary, fontSize = 13.sp) }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            }
            }

            if (visible(SettingsCategory.CONNECTION)) {
            item(key = "connection:10") {
            // Section: Quick Settings Tile
            SectionHeader(title = strings.sectionQuickSettings)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .liquidGlass(cornerRadius = 20, innerSheen = true)
                    .padding(16.dp),
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
            }

            if (visible(SettingsCategory.DIAGNOSTICS)) {
            item(key = "diagnostics:11") {
            // Section 5: Diagnostics & Logs
            ConnectionToolsSection(viewModel)
            Spacer(modifier = Modifier.height(24.dp))
            SectionHeader(title = strings.sectionDiagnostics)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .liquidGlass(cornerRadius = 20, innerSheen = true)
                    .padding(16.dp),
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
            }

            if (visible(SettingsCategory.DIAGNOSTICS)) {
                item(key = "anonymous-reports") {
                    com.danieldk.splatevpn.ui.components.AnonymousReportsCard(viewModel.anonymousReports)
                }
            }
            if (visible(SettingsCategory.SYNC)) {
                item(key = "sync-cloud-link") { com.danieldk.splatevpn.ui.components.CloudBackupLinkCard(viewModel.cloudBackupLinkImporter) }
                item(key = "source-privacy") { com.danieldk.splatevpn.ui.components.SourcePrivacyCard(viewModel) }
                item(key = "cloud-backup") {
                    com.danieldk.splatevpn.ui.components.CloudBackupSettingsCard(viewModel.cloudBackupManager)
                }
                item(key = "device-transfer") {
                    val connectionState by viewModel.vpnState.collectAsState()
                    com.danieldk.splatevpn.ui.components.DeviceTransferSettingsCard(
                        viewModel.deviceTransferManager, viewModel.webDavSyncManager,
                        connectionState !in setOf(com.danieldk.splatevpn.vpn.VpnState.DISCONNECTED, com.danieldk.splatevpn.vpn.VpnState.FAILED))
                }
            }

            if (visible(SettingsCategory.BACKUP)) {
            item(key = "backup-cloud-link") { com.danieldk.splatevpn.ui.components.CloudBackupLinkCard(viewModel.cloudBackupLinkImporter) }
            item(key = "backup:12") {
            // Section 6: Backup & Restore
            SectionHeader(title = strings.sectionBackupRestore)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .liquidGlass(cornerRadius = 20, innerSheen = true)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = strings.backupRestoreDesc,
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
                Text(strings.localized("Для восстановления из облака вставьте ссылку на файл в карточку выше. Кнопки ниже сохраняют или открывают файл через системный диалог.",
                    "To restore from the cloud, paste the file link in the card above. The buttons below save or open a file through the system picker."),
                    color = colors.textSecondary, fontSize = 12.sp)

                SettingToggleRow(icon = Icons.Default.Lock,
                    title = resources.getString(com.danieldk.splatevpn.R.string.backup_encrypt),
                    subtitle = resources.getString(com.danieldk.splatevpn.R.string.backup_encrypt_hint),
                    checked = encryptBackup, onCheckedChange = { encryptBackup = it })
                OutlinedTextField(backupPassword, { backupPassword = it.take(1024) }, modifier = Modifier.fillMaxWidth(),
                    label = { Text(resources.getString(com.danieldk.splatevpn.R.string.backup_password)) },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password), singleLine = true)
                if (encryptBackup) OutlinedTextField(confirmBackupPassword, { confirmBackupPassword = it.take(1024) }, modifier = Modifier.fillMaxWidth(),
                    label = { Text(resources.getString(com.danieldk.splatevpn.R.string.backup_confirm_password)) },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password), singleLine = true)
                Text(resources.getString(com.danieldk.splatevpn.R.string.backup_password_help), color = colors.textSecondary, fontSize = 12.sp)

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
                                if (!encryptBackup || backupPassword.length >= 8 && backupPassword == confirmBackupPassword) exportLauncher.launch("splate_backup_${System.currentTimeMillis()}.json")
                                else Toast.makeText(context, resources.getString(com.danieldk.splatevpn.R.string.backup_password_mismatch), Toast.LENGTH_LONG).show()
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
                title = { Text(strings.localized("Как восстановить копию?", "How should the backup be restored?"), color = colors.textPrimary) },
                text = {
                    Column {
                        com.danieldk.splatevpn.data.BackupManager.ImportMode.entries.forEach { mode ->
                            Row(Modifier.fillMaxWidth().tvFocusOutline().clickable { importMode = mode }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = importMode == mode, onClick = { importMode = mode })
                                Column(Modifier.weight(1f)) {
                                    Text(if (mode == com.danieldk.splatevpn.data.BackupManager.ImportMode.MERGE) {
                                        strings.localized("Объединить", "Merge")
                                    } else strings.localized("Заменить данные", "Replace data"), color = colors.textPrimary)
                                    Text(if (mode == com.danieldk.splatevpn.data.BackupManager.ImportMode.MERGE) {
                                        strings.localized("Добавить серверы и правила. Настройки из файла применятся.", "Add servers and rules. Settings from the file will apply.")
                                    } else strings.localized("Заменить серверы, подписки, списки приложений и маршруты, включая пустые списки.", "Replace servers, subscriptions, app lists and routes, including empty lists."), color = colors.textSecondary, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showImportMode = false; importLauncher.launch(arrayOf("application/json", "*/*")) }) {
                    Text(strings.localized("Выбрать файл", "Choose file"))
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

        if (showMtuDialog) {
            val mtu by viewModel.mtu.collectAsState()
            var input by remember { mutableStateOf(mtu.toString()) }
            val value = input.toIntOrNull()?.takeIf { it in 1280..1500 }
            AlertDialog(
                onDismissRequest = { showMtuDialog = false },
                title = { Text(resources.getString(R.string.vpn_mtu_title)) },
                text = {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it.filter { char -> char in '0'..'9' }.take(4) },
                        label = { Text(resources.getString(R.string.vpn_mtu_range)) },
                        isError = value == null,
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                        )
                    )
                },
                confirmButton = {
                    TextButton(enabled = value != null, onClick = {
                        value?.let(viewModel::setMtu)
                        showMtuDialog = false
                    }) { Text(strings.save) }
                },
                dismissButton = {
                    TextButton(onClick = { showMtuDialog = false }) { Text(strings.cancel) }
                }
            )
        }

        if (showTlsCustomDialog) {
            val tlsFragmentLength by viewModel.tlsFragmentLength.collectAsState()
            val tlsFragmentInterval by viewModel.tlsFragmentInterval.collectAsState()
            val tlsFragmentPackets by viewModel.tlsFragmentPackets.collectAsState()
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
                        Text(strings.save, fontWeight = FontWeight.Bold)
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
            val isShareLan by viewModel.isShareLan.collectAsState()
            val vpnConnected by viewModel.isConnected.collectAsState()
            val activeRouting by viewModel.activeRouting.collectAsState()
            val lanAuthentication by viewModel.lanAuthentication.collectAsState()
            val endpoints by rememberSettingsLanEndpoints(viewModel, isShareLan)
            if (!lanAuthentication || lanPassword != null) com.danieldk.splatevpn.ui.components.ShareLanInstructionsDialog(
                endpoints = endpoints.filter { it.ipAddress == activeRouting?.lanAddress },
                vpnConnected = vpnConnected,
                password = if (lanAuthentication) lanPassword else null,
                sharingActive = isShareLan && activeRouting?.lanAddress != null,
                onDismiss = { showShareLanDialog = false }
            )
        }

        if (showTourDialog) {
            com.danieldk.splatevpn.ui.components.ToolDialog(
                onDismissRequest = { showTourDialog = false },
                maxWidth = 640.dp,
                contentPadding = PaddingValues(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.School, null, tint = colors.accentCyan, modifier = Modifier.size(20.dp))
                        Text(
                            text = strings.localized("Интерактивный тренажёр", "Interactive Tour"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary
                        )
                    }
                    IconButton(onClick = { showTourDialog = false }) {
                        Icon(Icons.Default.Close, contentDescription = null, tint = colors.textSecondary)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                ) {
                    OnboardingTour(
                        modifier = Modifier.fillMaxWidth(),
                        onFinished = { showTourDialog = false },
                        onBack = { showTourDialog = false },
                        showSkip = false,
                        compactHeader = true
                    )
                }
            }
        }
    }
}

/** Enumerating network interfaces can block; never do it from composition. */
@Composable
private fun rememberSettingsLanEndpoints(viewModel: MainViewModel, sharing: Boolean): State<List<com.danieldk.splatevpn.vpn.LanEndpoint>> {
    val endpoints = remember(viewModel, sharing) { mutableStateOf(emptyList<com.danieldk.splatevpn.vpn.LanEndpoint>()) }
    LaunchedEffect(viewModel, sharing) {
        while (true) {
            endpoints.value = withContext(Dispatchers.IO) { viewModel.getActiveLanEndpoints() }
            kotlinx.coroutines.delay(3_000)
        }
    }
    return endpoints
}

private val SettingsCategory.icon: ImageVector
    get() = when (this) {
        SettingsCategory.TOR -> Icons.Default.Language
        SettingsCategory.CONNECTION -> Icons.Default.PowerSettingsNew
        SettingsCategory.NETWORK -> Icons.Default.Dns
        SettingsCategory.ROUTING -> Icons.AutoMirrored.Filled.AltRoute
        SettingsCategory.AI -> Icons.Default.AutoAwesome
        SettingsCategory.PROTECTION -> Icons.Default.Shield
        SettingsCategory.APPEARANCE -> Icons.Default.Palette
        SettingsCategory.BACKUP -> Icons.Default.Backup
        SettingsCategory.SYNC -> Icons.Default.Sync
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
        modifier = Modifier.padding(bottom = 12.dp, start = 4.dp)
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
            text = title,
            color = colors.textSecondary,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.1.sp
        )
    }
}

@Composable
fun SettingToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    SettingsToggleContent(icon, title, subtitle, checked, modifier, onCheckedChange)
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
            lineHeight = 16.sp,
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
