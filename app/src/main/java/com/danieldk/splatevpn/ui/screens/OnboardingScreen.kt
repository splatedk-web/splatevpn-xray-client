package com.danieldk.splatevpn.ui.screens

import com.danieldk.splatevpn.ui.components.SplateTextButton as TextButton
import com.danieldk.splatevpn.ui.components.SplateCheckbox as Checkbox
import com.danieldk.splatevpn.ui.components.SplateButton as Button
import com.danieldk.splatevpn.ui.components.SplateOutlinedButton as OutlinedButton
import com.danieldk.splatevpn.ui.components.SplateSwitch as Switch

import com.danieldk.splatevpn.ui.components.RoutingModeOptions
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danieldk.splatevpn.R
import com.danieldk.splatevpn.data.RoutingRegion
import com.danieldk.splatevpn.data.TOR_TOP100_SUBSCRIPTION
import com.danieldk.splatevpn.data.torBridgeTransport
import com.danieldk.splatevpn.ui.MainViewModel
import com.danieldk.splatevpn.ui.components.GlassSettingsAction
import com.danieldk.splatevpn.ui.components.LiquidAmbientBackground
import com.danieldk.splatevpn.ui.components.liquidGlass
import com.danieldk.splatevpn.ui.components.rememberQrImporter
import com.danieldk.splatevpn.ui.components.tvFocusOutline
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import com.danieldk.splatevpn.ui.theme.*
import kotlinx.coroutines.launch

private data class SetupStepBadge(
    val titleRu: String,
    val titleEn: String,
    val icon: ImageVector
)

private val setupSteps = listOf(
    SetupStepBadge("Язык", "Language", Icons.Default.Language),
    SetupStepBadge("Обход", "Routing", Icons.AutoMirrored.Filled.AltRoute),
    SetupStepBadge("Серверы & Tor", "Servers & Tor", Icons.Default.Dns),
    SetupStepBadge("Приложения", "Apps", Icons.Default.Apps),
    SetupStepBadge("Желательно", "Recommended", Icons.Default.NotificationsActive),
    SetupStepBadge("Обучение", "Tour", Icons.Default.School),
    SetupStepBadge("Старт", "Ready", Icons.Default.CheckCircle)
)

@Composable
fun OnboardingScreen(viewModel: MainViewModel, theme: String, onConnect: () -> Unit = {}) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val strings = LocalAppStrings.current
    val colors = LocalAppThemeColors.current

    fun tick() = haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    fun buzz() = haptic.performHapticFeedback(HapticFeedbackType.LongPress)

    val countrySuggestion = remember(context) { suggestSetupCountry(context) }
    val firstSetup = remember(context) {
        context.getSharedPreferences("splate_prefs", android.content.Context.MODE_PRIVATE).getInt("onboarding_version", 0) == 0
    }
    val performance by viewModel.performanceSettings.collectAsState()
    val initialLanguage by viewModel.appLanguage.collectAsState()
    val initialRegion by viewModel.routingRegion.collectAsState()
    val initialMode by viewModel.routingMode.collectAsState()
    val torSettings by viewModel.torSettings.collectAsState()

    var language by rememberSaveable { mutableStateOf(initialLanguage) }
    var region by rememberSaveable { mutableStateOf(if (firstSetup) countrySuggestion?.region?.id ?: initialRegion else initialRegion) }
    var mode by rememberSaveable { mutableStateOf(initialMode) }
    var step by rememberSaveable { mutableIntStateOf(0) }
    var quickSetup by rememberSaveable { mutableStateOf(false) }
    var tourRequested by rememberSaveable { mutableStateOf(false) }
    var link by rememberSaveable { mutableStateOf("") }
    val initialPresets = remember { viewModel.regionalPresetsEnabled() }
    var presets by rememberSaveable { mutableStateOf(initialPresets) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var backupUri by rememberSaveable { mutableStateOf<String?>(null) }
    var backupPassword by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    // Server & Tor tab inside step 2
    var serverSourceTab by rememberSaveable { mutableIntStateOf(0) } // 0 = VPN, 1 = Tor TOP-100
    var preferTorMode by rememberSaveable { mutableStateOf(torSettings.enabled) }
    var torDownloading by remember { mutableStateOf(false) }
    var torDownloadMessage by remember { mutableStateOf<String?>(null) }
    var torDownloadError by remember { mutableStateOf<String?>(null) }

    val global by viewModel.isGlobalAppProxy.collectAsState()
    val perAppMode by viewModel.perAppMode.collectAsState()
    val savedPackages by viewModel.perAppPackages.collectAsState()
    val installedApps by viewModel.installedApps.collectAsState()
    val loadingApps by viewModel.isLoadingApps.collectAsState()
    val television = remember(context) { isTelevisionDevice(context) }
    val inputModeManager = LocalInputModeManager.current
    if (television) {
        LaunchedEffect(inputModeManager) {
            @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
            inputModeManager.requestInputMode(InputMode.Keyboard)
        }
    }
    var appModeName by rememberSaveable { mutableStateOf(com.danieldk.splatevpn.data.AppRoutingMode.fromSettings(global, perAppMode).name) }
    var appPackages by rememberSaveable { mutableStateOf(savedPackages.toList()) }
    var appsEdited by rememberSaveable { mutableStateOf(false) }
    var choosingApps by rememberSaveable { mutableStateOf(false) }
    val servers by viewModel.allServers.collectAsState()

    val backupPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            backupUri = uri.toString()
            link = ""
            error = false
            tick()
        }
    }

    fun downloadTop100Bridges() {
        if (torDownloading) return
        scope.launch {
            torDownloading = true
            torDownloadMessage = null
            torDownloadError = null
            buzz()
            val result = viewModel.updateTorSubscription(
                TOR_TOP100_SUBSCRIPTION,
                torSettings.copy(
                    autoUpdate = true,
                    useBridges = true,
                    bridgeTransport = "auto"
                )
            )
            torDownloading = false
            result.fold(
                onSuccess = { count ->
                    buzz()
                    torDownloadMessage = String.format(
                        strings.localized(
                            "Загружено %d мостов Tor (WebTunnel · obfs4 · Vanilla)!",
                            "Loaded %d Tor bridges (WebTunnel · obfs4 · Vanilla)!"
                        ),
                        count
                    )
                    preferTorMode = true
                },
                onFailure = { err ->
                    torDownloadError = err.message ?: strings.localized("Ошибка загрузки списка мостов", "Failed to fetch Tor bridges")
                }
            )
        }
    }

    fun finish(connect: Boolean) {
        buzz()
        fun apply() {
            scope.launch {
                if (preferTorMode) {
                    viewModel.selectConnectionMode(true)
                }
                viewModel.completeOnboarding(
                    language,
                    region,
                    mode,
                    presets,
                    if (appsEdited) com.danieldk.splatevpn.data.AppRoutingMode.valueOf(appModeName) else null,
                    appPackages.toSet()
                ) {
                    if (connect && !viewModel.shouldDeferTvSetupConnection()) onConnect()
                }
            }
        }
        if (backupUri != null) {
            scope.launch {
                busy = true
                val password = backupPassword.takeIf { it.isNotEmpty() }?.toCharArray()
                try {
                    val result = viewModel.importBackup(android.net.Uri.parse(backupUri), password = password)
                    if (result.isSuccess) apply() else error = true
                } finally {
                    password?.fill('\u0000')
                    busy = false
                }
            }
        } else if (link.isNotBlank()) {
            busy = true
            viewModel.addServersFromLinks(link.trim()) { count ->
                busy = false
                if (count > 0) apply() else error = true
            }
        } else {
            apply()
        }
    }

    SplateVPNTheme(themeSetting = theme, languageSetting = language, performanceSettings = performance) {
        val r = LocalAppResources.current

        if (choosingApps) {
            LaunchedEffect(Unit) { viewModel.loadInstalledApps(context) }
            androidx.activity.compose.BackHandler { choosingApps = false }
            LiquidAmbientBackground {
                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Box(Modifier.padding(16.dp)) {
                        GlassSettingsAction(r.getString(R.string.onboarding_return), onClick = { choosingApps = false })
                    }
                    Box(Modifier.weight(1f)) {
                        AppRoutingContent(
                            com.danieldk.splatevpn.data.AppRoutingMode.valueOf(appModeName),
                            appPackages.toSet(),
                            false,
                            installedApps,
                            loadingApps,
                            onRefresh = { viewModel.loadInstalledApps(context, forceReload = true) },
                            onApply = { chosen, packages ->
                                appModeName = chosen.name
                                appPackages = packages.toList()
                                appsEdited = true
                                choosingApps = false
                                tick()
                            }
                        )
                    }
                }
            }
            return@SplateVPNTheme
        }

        androidx.activity.compose.BackHandler(enabled = step > 0 && !busy) {
            tick()
            step--
        }

        val qr = rememberQrImporter { scanned ->
            link = scanned
            backupUri = null
            error = false
            tick()
        }

        LiquidAmbientBackground(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                // Header Step Navigation Strip
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    setupSteps.forEachIndexed { index, badge ->
                        val isCurrent = step == index
                        val isDone = step > index
                        val chipWidth by animateDpAsState(
                            targetValue = if (isCurrent) 120.dp else 42.dp,
                            animationSpec = spring(stiffness = 500f),
                            label = "stepWidth"
                        )
                        Box(
                            modifier = Modifier
                                .width(chipWidth)
                                .height(36.dp)
                                .liquidGlass(
                                    cornerRadius = 12,
                                    accentBorder = if (isCurrent) colors.accentCyan else if (isDone) colors.accentEmerald.copy(alpha = 0.5f) else null,
                                    backgroundColor = if (isCurrent) colors.accentCyan.copy(alpha = 0.16f) else if (isDone) colors.accentEmerald.copy(alpha = 0.1f) else null,
                                    innerSheen = isCurrent
                                )
                                .tvFocusOutline(index <= step && !busy)
                                .clickable(enabled = index <= step && !busy) {
                                    if (step != index) {
                                        tick()
                                        step = index
                                    }
                                }
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = if (isDone) Icons.Default.Check else badge.icon,
                                    contentDescription = null,
                                    tint = if (isCurrent) colors.accentCyan else if (isDone) colors.accentEmerald else colors.textSecondary.copy(alpha = 0.6f),
                                    modifier = Modifier.size(16.dp)
                                )
                                if (isCurrent) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = strings.localized(badge.titleRu, badge.titleEn),
                                        color = colors.textPrimary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Progress Bar
                LinearProgressIndicator(
                    progress = { (step + 1).toFloat() / setupSteps.size },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(1.5.dp)),
                    color = colors.accentCyan,
                    trackColor = colors.borderSubtle.copy(alpha = 0.4f)
                )

                Spacer(Modifier.height(14.dp))

                // Step content animated
                AnimatedContent(
                    targetState = step,
                    modifier = Modifier.weight(1f),
                    transitionSpec = {
                        val duration = if (performance.animationsEnabled) 240 else 0
                        (fadeIn(tween(duration)) + slideInHorizontally(tween(duration)) { if (targetState > initialState) it / 8 else -it / 8 }) togetherWith
                            fadeOut(tween(if (performance.animationsEnabled) 120 else 0))
                    },
                    label = "setupStep"
                ) { displayedStep ->
                    if (displayedStep == 5) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .liquidGlass(cornerRadius = 24, innerSheen = true)
                                .padding(12.dp)
                        ) {
                            if (!tourRequested) {
                                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Text(strings.localized("Показать обучение?", "Would you like a tour?"),
                                        style = MaterialTheme.typography.headlineSmall, color = colors.textPrimary)
                                    Text(strings.localized(
                                        "Разберём подключение, быстрый автоподбор и маршруты приложений. Автоподбор проверяет HTTPS через сервер и в режиме контроля связи ищет замену при отказе. Его можно отключить на главной.",
                                        "Learn about connecting, fast automatic selection and app routing. Auto-selection checks HTTPS through a server and, in a monitoring mode, finds a replacement after failure. You can turn it off on Home."), color = colors.textSecondary)
                                    Button(onClick = { tourRequested = true }, modifier = Modifier.fillMaxWidth().testTag("onboardingShowTour")) {
                                        Text(strings.localized("Показать", "Show me"))
                                    }
                                    OutlinedButton(onClick = { step = 6 }, modifier = Modifier.fillMaxWidth().testTag("onboardingSkipTour")) {
                                        Text(strings.localized("Продолжить без обучения", "Continue without the tour"))
                                    }
                                }
                            } else OnboardingTour(
                                modifier = Modifier.fillMaxSize(),
                                onFinished = {
                                    tick()
                                    step = 6
                                },
                                onBack = {
                                    tick()
                                    step = 4
                                },
                                showSkip = true,
                                compactHeader = true
                            )
                        }
                    } else {
                        val stepScroll = rememberScrollState()
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .liquidGlass(cornerRadius = 24, innerSheen = true)
                                .padding(20.dp)
                                .verticalScroll(stepScroll),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            when (displayedStep) {
                                0 -> {
                                Text(
                                    text = r.getString(R.string.onboarding_title),
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.textPrimary
                                )
                                Text(
                                    text = r.getString(R.string.onboarding_system_hint),
                                    color = colors.textSecondary,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = r.getString(R.string.onboarding_language),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.textPrimary
                                )
                                LanguageChoices(language) {
                                    tick()
                                    language = it
                                }

                                countrySuggestion?.let { suggestion ->
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .liquidGlass(cornerRadius = 14, accentBorder = colors.accentCyan.copy(alpha = 0.5f), innerSheen = true)
                                            .padding(12.dp)
                                    ) {
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(
                                                r.getString(R.string.setup_country_suggested, "${suggestion.flag} ${suggestion.displayName(r.configuration.locales[0])}"),
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = colors.accentCyan
                                            )
                                            Text(
                                                r.getString(when (suggestion.source) {
                                                    SetupCountrySource.NETWORK -> R.string.setup_country_network
                                                    SetupCountrySource.SIM -> R.string.setup_country_sim
                                                    SetupCountrySource.DEVICE -> R.string.setup_country_device
                                                }),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = colors.textSecondary
                                            )
                                        }
                                    }
                                }

                                RegionChoices(region) {
                                    tick()
                                    region = it
                                }
                            }

                            1 -> {
                                if (quickSetup) {
                                    Text(strings.localized("Короткая настройка", "Quick setup"),
                                        style = MaterialTheme.typography.titleLarge, color = colors.textPrimary)
                                    Text(strings.localized("Подтвердите регион и маршрут. Подробное обучение будет пропущено.",
                                        "Confirm your region and routing. The detailed tour will be skipped."), color = colors.textSecondary)
                                    RegionChoices(region) { tick(); region = it }
                                }
                                Text(
                                    text = r.getString(R.string.onboarding_route),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.textPrimary
                                )
                                Text(
                                    text = strings.localized(
                                        "«Smart» ускоряет интернет, пропуская сайты вашей страны напрямую. «Весь трафик» шифрует 100% данных.",
                                        "Smart mode accelerates speed by routing domestic traffic directly. Full traffic encrypts 100% of data."
                                    ),
                                    color = colors.textSecondary,
                                    fontSize = 13.sp
                                )
                                RoutingModeOptions(mode, "", !busy) {
                                    tick()
                                    mode = it
                                }
                            }

                            2 -> {
                                Text(
                                    text = strings.localized("Серверы и подключение", "Servers & Connection"),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.textPrimary
                                )

                                // Segmented Tabs: VPN vs Tor TOP-100
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .liquidGlass(cornerRadius = 14, innerSheen = true)
                                        .padding(4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(40.dp)
                                            .liquidGlass(
                                                cornerRadius = 10,
                                                accentBorder = if (serverSourceTab == 0) colors.accentCyan else null,
                                                backgroundColor = if (serverSourceTab == 0) colors.accentCyan.copy(alpha = 0.16f) else null,
                                                innerSheen = serverSourceTab == 0
                                            )
                                            .tvFocusOutline()
                                            .clickable {
                                                tick()
                                                serverSourceTab = 0
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Shield, null, modifier = Modifier.size(16.dp), tint = if (serverSourceTab == 0) colors.accentCyan else colors.textSecondary)
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                text = "VPN",
                                                color = if (serverSourceTab == 0) colors.textPrimary else colors.textSecondary,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp
                                            )
                                        }
                                    }

                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(40.dp)
                                            .liquidGlass(
                                                cornerRadius = 10,
                                                accentBorder = if (serverSourceTab == 1) colors.accentEmerald else null,
                                                backgroundColor = if (serverSourceTab == 1) colors.accentEmerald.copy(alpha = 0.16f) else null,
                                                innerSheen = serverSourceTab == 1
                                            )
                                            .tvFocusOutline()
                                            .clickable {
                                                tick()
                                                serverSourceTab = 1
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Public, null, modifier = Modifier.size(16.dp), tint = if (serverSourceTab == 1) colors.accentEmerald else colors.textSecondary)
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                text = "Tor TOP-100",
                                                color = if (serverSourceTab == 1) colors.textPrimary else colors.textSecondary,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp
                                            )
                                        }
                                    }
                                }

                                if (serverSourceTab == 0) {
                                    // VPN Section
                                    Text(
                                        text = r.getString(R.string.onboarding_server_help),
                                        color = colors.textSecondary,
                                        fontSize = 13.sp
                                    )
                                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                        OutlinedTextField(
                                            value = link,
                                            onValueChange = {
                                                link = it
                                                backupUri = null
                                                error = false
                                            },
                                            label = { Text(r.getString(R.string.onboarding_server_hint)) },
                                            modifier = Modifier.fillMaxWidth(),
                                            minLines = 2,
                                            maxLines = 4
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (!television) {
                                            Box(Modifier.weight(1f)) {
                                                GlassSettingsAction(r.getString(R.string.qr_import_title), onClick = qr)
                                            }
                                        }
                                        Box(Modifier.weight(1f)) {
                                            GlassSettingsAction(
                                                r.getString(R.string.onboarding_backup),
                                                onClick = { backupPicker.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }
                                            )
                                        }
                                    }

                                    if (backupUri != null) {
                                        Text(r.getString(R.string.onboarding_backup_selected), color = colors.accentCyan, fontSize = 12.sp)
                                        OutlinedTextField(
                                            value = backupPassword,
                                            onValueChange = { backupPassword = it.take(1024) },
                                            label = { Text(r.getString(R.string.backup_password)) },
                                            singleLine = true,
                                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .tvFocusOutline()
                                            .clickable {
                                                tick()
                                                presets = !presets
                                            }
                                            .padding(vertical = 4.dp)
                                    ) {
                                        Checkbox(checked = presets, onCheckedChange = { presets = it; tick() })
                                        Spacer(Modifier.width(8.dp))
                                        Text(r.getString(R.string.onboarding_presets), color = colors.textPrimary, fontSize = 13.sp)
                                    }
                                } else {
                                    // Tor TOP-100 Section
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .liquidGlass(cornerRadius = 16, accentBorder = colors.accentEmerald, innerSheen = true)
                                            .padding(14.dp)
                                    ) {
                                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(34.dp)
                                                        .clip(CircleShape)
                                                        .background(colors.accentEmerald.copy(alpha = 0.18f)),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(Icons.Default.Public, contentDescription = null, tint = colors.accentEmerald, modifier = Modifier.size(20.dp))
                                                }
                                                Spacer(Modifier.width(10.dp))
                                                Column {
                                                    Text(
                                                        text = strings.localized("Мосты Tor TOP-100 от igareck", "Tor TOP-100 Bridges by igareck"),
                                                        color = colors.textPrimary,
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 14.sp
                                                    )
                                                    Text(
                                                        text = strings.localized("Готовое решение без личного VPS", "Ready bypass without a private VPS"),
                                                        color = colors.accentEmerald,
                                                        fontSize = 11.sp
                                                    )
                                                }
                                            }

                                            Text(
                                                text = strings.localized(
                                                    "В РФ стандартные узлы Tor заблокированы. Этот набор включает актуальные мосты WebTunnel (маскировка под HTTPS) и obfs4, которые обходят ТСПУ и белые списки.",
                                                    "Tor guards are blocked in censored zones. This pool includes verified WebTunnel (HTTPS disguise) and obfs4 bridges bypassing deep packet inspection."
                                                ),
                                                color = colors.textSecondary,
                                                fontSize = 12.sp,
                                                lineHeight = 16.sp
                                            )

                                            Button(
                                                onClick = ::downloadTop100Bridges,
                                                enabled = !torDownloading && !busy,
                                                modifier = Modifier.fillMaxWidth(),
                                                colors = ButtonDefaults.buttonColors(containerColor = colors.accentEmerald)
                                            ) {
                                                Icon(Icons.Default.CloudDownload, null, Modifier.size(18.dp))
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                    text = if (torSettings.bridges.isNotEmpty()) strings.localized("Обновить TOP-100 мостов", "Refresh TOP-100 bridges")
                                                    else strings.localized("Загрузить TOP-100 мостов Tor", "Download Tor TOP-100 bridges"),
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }

                                            if (torDownloading) {
                                                LinearProgressIndicator(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    color = colors.accentEmerald,
                                                    trackColor = colors.borderSubtle
                                                )
                                                Text(
                                                    text = strings.localized("Загрузка и парсинг мостов с GitHub…", "Fetching and validating bridges from GitHub…"),
                                                    color = colors.accentEmerald,
                                                    fontSize = 11.sp
                                                )
                                            }

                                            torDownloadMessage?.let { msg ->
                                                Text(msg, color = colors.accentEmerald, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                            }
                                            torDownloadError?.let { err ->
                                                Text(err, color = colors.accentCrimson, fontSize = 12.sp)
                                            }

                                            if (torSettings.bridges.isNotEmpty()) {
                                                val wtCount = torSettings.bridges.count { torBridgeTransport(it) == "webtunnel" }
                                                val obfs4Count = torSettings.bridges.count { torBridgeTransport(it) == "obfs4" }
                                                val vanillaCount = torSettings.bridges.count { torBridgeTransport(it) == "vanilla" }
                                                Row(
                                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    BadgeChip("WebTunnel · $wtCount", colors.accentEmerald)
                                                    BadgeChip("obfs4 · $obfs4Count", colors.accentCyan)
                                                    BadgeChip("Vanilla · $vanillaCount", colors.accentAmber)
                                                }

                                                HorizontalDivider(color = colors.borderSubtle, thickness = 0.5.dp)

                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Column(Modifier.weight(1f)) {
                                                        Text(
                                                            text = strings.localized("Основной режим: Tor", "Default mode: Tor"),
                                                            color = colors.textPrimary,
                                                            fontWeight = FontWeight.SemiBold,
                                                            fontSize = 13.sp
                                                        )
                                                        Text(
                                                            text = strings.localized("Подключаться через Tor сразу после настройки", "Connect via Tor directly after setup"),
                                                            color = colors.textSecondary,
                                                            fontSize = 11.sp
                                                        )
                                                    }
                                                    Switch(
                                                        checked = preferTorMode,
                                                        onCheckedChange = {
                                                            tick()
                                                            preferTorMode = it
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            3 -> {
                                Text(
                                    text = r.getString(R.string.onboarding_apps),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.textPrimary
                                )
                                Text(
                                    text = r.getString(R.string.onboarding_apps_help),
                                    color = colors.textSecondary,
                                    fontSize = 13.sp
                                )
                                GlassSettingsAction(
                                    label = LocalAppStrings.current.appRoutingTitle,
                                    onClick = {
                                        tick()
                                        choosingApps = true
                                    }
                                )
                                if (appsEdited) {
                                    Text(
                                        text = LocalAppStrings.current.appRouting.title(com.danieldk.splatevpn.data.AppRoutingMode.valueOf(appModeName)) +
                                            " · ${appPackages.size} " + strings.localized("прил.", "apps"),
                                        color = colors.accentCyan,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }

                            4 -> com.danieldk.splatevpn.ui.components.RecommendedAccessCard()
                            6 -> {
                                Text(
                                    text = r.getString(R.string.onboarding_ready),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.textPrimary
                                )

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .liquidGlass(cornerRadius = 16, accentBorder = colors.accentCyan.copy(alpha = 0.4f), innerSheen = true)
                                        .padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    SummaryBadge(strings.localized("Язык", "Language"), if (language == "system") LocalAppStrings.current.langSystem else languageLabel(language))
                                    SummaryBadge(strings.localized("Регион правил", "Region"), "${countryFlag(region)} ${r.getString(regionLabel(region))}")
                                    SummaryBadge(strings.localized("Режим обхода", "Routing"), r.getString(if (mode == "global") R.string.onboarding_full else R.string.onboarding_smart))
                                    if (link.isNotBlank()) SummaryBadge(strings.localized("VPN ссылка", "VPN link"), strings.localized("Готова к импорту", "Ready to import"))
                                    if (backupUri != null) SummaryBadge(strings.localized("Резервная копия", "Backup"), strings.localized("Выбран файл бэкапа", "Backup file selected"))
                                    if (torSettings.bridges.isNotEmpty()) {
                                        SummaryBadge(
                                            strings.localized("Мосты Tor", "Tor bridges"),
                                            String.format(strings.localized("%d мостов (TOP-100 igareck)", "%d TOP-100 bridges"), torSettings.bridges.size)
                                        )
                                    }
                                    SummaryBadge(strings.localized("Режим запуска", "Startup mode"), if (preferTorMode) "🧅 Tor" else "🛡️ VPN")
                                    if (appsEdited) {
                                        SummaryBadge(strings.localized("Приложения", "Apps"), LocalAppStrings.current.appRouting.title(com.danieldk.splatevpn.data.AppRoutingMode.valueOf(appModeName)))
                                    }
                                }

                                Text(
                                    text = r.getString(R.string.onboarding_permission),
                                    color = colors.textSecondary,
                                    fontSize = 12.sp
                                )

                                if (television) {
                                    Text(
                                        text = strings.localized(
                                            "После настройки можно перенести конфигурации с телефона по QR-коду.",
                                            "After setup, you can transfer configs from your phone via QR code."
                                        ),
                                        color = colors.accentAmber,
                                        fontSize = 12.sp
                                    )
                                }

                                if (error) {
                                    Text(r.getString(R.string.onboarding_import_failed), color = colors.accentCrimson, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }

                Spacer(Modifier.height(14.dp))

                if (busy) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp),
                        color = colors.accentCyan
                    )
                    Spacer(Modifier.height(8.dp))
                }

                // The tour has its own navigation; optional access never blocks the next step.
                if (step != 5) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            modifier = Modifier.testTag("onboardingBackOrSkip"),
                            enabled = !busy,
                            onClick = {
                                tick()
                                if (step > 0) step--
                                else { quickSetup = true; step = 1 }
                            }
                        ) {
                            Text(r.getString(if (step == 0) R.string.onboarding_skip else R.string.onboarding_back), color = colors.textSecondary)
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 12.dp).testTag("onboardingNext")
                        ) {
                            GlassSettingsAction(
                                label = r.getString(if (quickSetup && step == 1 || step == 6) R.string.onboarding_done else R.string.onboarding_next),
                                enabled = !busy,
                                onClick = {
                                    tick()
                                    if (step == 0) quickSetup = false
                                    if (quickSetup && step == 1) finish(false)
                                    else if (step < 6) step++ else finish(false)
                                }
                            )
                        }
                    }
                }

                val hasConfigs = servers.isNotEmpty() || link.isNotBlank() || backupUri != null || presets || torSettings.bridges.isNotEmpty() || preferTorMode
                if (!television && step == 6 && hasConfigs) {
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { finish(true) },
                        enabled = !busy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (preferTorMode) colors.accentEmerald else colors.accentCyan
                        )
                    ) {
                        Icon(Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = strings.localized("Завершить и подключиться", "Finish and Connect"),
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryBadge(label: String, value: String) {
    val colors = LocalAppThemeColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = colors.textSecondary, fontSize = 12.sp)
        Text(value, color = colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun BadgeChip(text: String, color: Color) {
    Box(
        modifier = Modifier
            .liquidGlass(cornerRadius = 8, accentBorder = color.copy(alpha = 0.5f), backgroundColor = color.copy(alpha = 0.12f), innerSheen = false)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

internal fun regionLabel(region: String): Int = when (region) {
    "ru" -> R.string.region_ru
    "cn" -> R.string.region_cn
    "ir" -> R.string.region_ir
    else -> R.string.region_international
}

private fun languageLabel(language: String) = when (language) {
    "ru" -> "Русский"
    "en" -> "English"
    "zh-Hans" -> "简体中文"
    "fa" -> "فارسی"
    else -> "System / Системный / 系统 / سیستم"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LanguageChoices(selected: String, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("system", "ru", "en", "zh-Hans", "fa").forEach { key ->
            SelectableSettingChip(
                label = if (key == "system") LocalAppStrings.current.langSystem else languageLabel(key),
                isSelected = selected == key,
                onClick = { onSelect(key) }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RegionChoices(selected: String, onSelect: (String) -> Unit) {
    val r = LocalAppResources.current
    val colors = LocalAppThemeColors.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(r.getString(R.string.region_title), color = colors.textPrimary, style = MaterialTheme.typography.titleMedium)
            Text(r.getString(R.string.region_help), color = colors.textSecondary, style = MaterialTheme.typography.bodySmall)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RoutingRegion.entries.forEach { region ->
                SelectableSettingChip(
                    modifier = Modifier.testTag("onboardingRegion:${region.id}"),
                    label = "${countryFlag(region.id)} ${r.getString(regionLabel(region.id))}",
                    isSelected = selected == region.id,
                    onClick = { onSelect(region.id) }
                )
            }
        }
    }
}
