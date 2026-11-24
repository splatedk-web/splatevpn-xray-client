package com.danieldk.splatevpn.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danieldk.splatevpn.ui.components.*
import com.danieldk.splatevpn.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class TourLesson(
    val titleRu: String,
    val titleEn: String,
    val helpRu: String,
    val helpEn: String,
    val tab: Int
)

private val tourLessons = listOf(
    TourLesson(
        titleRu = "Подключение VPN",
        titleEn = "VPN Connection",
        helpRu = "Нажмите на большую кнопку питания. На главном экране она мгновенно включает и отключает защищённый туннель.",
        helpEn = "Tap the large power button. On the Home screen, it toggles the secure tunnel on and off.",
        tab = 0
    ),
    TourLesson(
        titleRu = "Выбор сервера",
        titleEn = "Choose a server",
        helpRu = "Откройте карточку текущего сервера и выберите другой узел. Список показывает пинг в реальном времени; полный каталог — во вкладке «Серверы».",
        helpEn = "Open the current server card and select another node. It shows real-time ping; the Servers tab has the full catalog.",
        tab = 0
    ),
    TourLesson(
        titleRu = "Добавление серверов",
        titleEn = "Add servers",
        helpRu = "Нажмите «+» во вкладке «Серверы» и выберите ссылку или QR-код. Попробуйте импортировать учебную конфигурацию.",
        helpEn = "Tap '+' in Servers and choose a link or QR code. Try importing the example configuration.",
        tab = 1
    ),
    TourLesson(
        titleRu = "Tor и готовые мосты TOP-100",
        titleEn = "Tor & TOP-100 Bridges",
        helpRu = "Во вкладке «Серверы» переключитесь на вкладку «Tor» и нажмите «Загрузить TOP-100». Приложение загрузит 100 проверенных мостов от igareck (WebTunnel, obfs4), пробивающих любые блокировки ТСПУ в РФ без собственного сервера!",
        helpEn = "Switch to the 'Tor' tab in Servers and tap 'Download TOP-100'. The app pulls 100 verified community bridges (WebTunnel, obfs4) that bypass strict DPI blocking in censored regions!",
        tab = 1
    ),
    TourLesson(
        titleRu = "Автоподбор серверов",
        titleEn = "Automatic selection",
        helpRu = "Кнопка «Авто» на главной выбирает сервер с проверкой HTTPS через туннель. В режиме контроля связи клиент ищет замену при отказе. Открытый порт сам по себе не доказывает наличие интернета; режим «Только начальный подбор» не переключает сервер после подключения.",
        helpEn = "Auto on Home selects a server using HTTPS through its tunnel. Monitoring modes find a replacement after failure. An open port alone does not prove internet access; Initial selection only does not switch servers after connecting.",
        tab = 0
    ),
    TourLesson(
        titleRu = "Режимы обхода (Smart / Весь трафик)",
        titleEn = "Routing modes (Smart / All traffic)",
        helpRu = "Режим «Smart» направляет через VPN только заблокированные ресурсы вашей страны, оставляя локальные сервисы быстрыми. «Весь трафик» шифрует 100% данных.",
        helpEn = "Smart mode routes only blocked domains for your region via VPN, keeping local services at full speed. All traffic encrypts 100% of data.",
        tab = 0
    ),
    TourLesson(
        titleRu = "Раздельное туннелирование (Apps)",
        titleEn = "Per-app routing (Apps)",
        helpRu = "Откройте вкладку «Приложения» и выберите браузер. Вы можете пускать через VPN только выбранные программы или исключать банковские приложения.",
        helpEn = "Open the Apps tab and select the browser. You can choose exactly which apps enter the VPN or exclude banking applications.",
        tab = 2
    ),
    TourLesson(
        titleRu = "Кастомизация и темы",
        titleEn = "Appearance & Themes",
        helpRu = "Откройте «Настройки» ➔ «Оформление» и переключитесь на тему «Зима». Приложение поддерживает живой блюр, прозрачность и акцентные подсветки.",
        helpEn = "Open Settings ➔ Appearance and switch to the Winter theme. The client supports dynamic frosted glass, transparency, and accent glows.",
        tab = 3
    ),
    TourLesson(
        titleRu = "Защита конфиденциальности (Спойлер)",
        titleEn = "Privacy spoiler (Hidden IP)",
        helpRu = "Нажмите на спойлер с IP-адресом сервера, чтобы временно открыть его. Защита от подглядывания автоматически скроет адрес через 15 секунд.",
        helpEn = "Tap the blurred IP spoiler to reveal it temporarily. The anti-peeking shield will automatically hide it again after 15 seconds.",
        tab = 0
    )
)

/** A guided, offline replica of the client with rich tactile feedback, pulsing spotlights, and Tor TOP-100 bridges simulator. */
@Composable
internal fun OnboardingTour(
    modifier: Modifier = Modifier,
    onFinished: () -> Unit = {},
    onBack: () -> Unit = {},
    showSkip: Boolean = true,
    compactHeader: Boolean = false
) {
    val strings = LocalAppStrings.current
    val colors = LocalAppThemeColors.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val animate = LocalUiPerformanceSettings.current.animationsEnabled

    fun buzz() = haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    fun tick() = haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)

    var lesson by rememberSaveable { mutableIntStateOf(0) }
    var completed by rememberSaveable { mutableIntStateOf(0) }
    var tab by rememberSaveable { mutableIntStateOf(0) }

    // Simulator interactive state
    var connected by rememberSaveable { mutableStateOf(false) }
    var node by rememberSaveable { mutableIntStateOf(0) }
    var choosingNode by rememberSaveable { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var importPreview by rememberSaveable { mutableStateOf<String?>(null) }
    var imported by rememberSaveable { mutableStateOf(false) }

    // Tor simulator state
    var serverMode by rememberSaveable { mutableIntStateOf(0) } // 0 = VPN, 1 = Tor
    var torDownloading by remember { mutableStateOf(false) }
    var torDownloaded by rememberSaveable { mutableStateOf(false) }
    var torSelectedTransport by rememberSaveable { mutableStateOf("auto") }

    var automatic by rememberSaveable { mutableStateOf(false) }
    var speed by rememberSaveable { mutableStateOf(false) }
    var smart by rememberSaveable { mutableStateOf(false) }
    var appSelected by rememberSaveable { mutableStateOf(false) }
    var appearance by rememberSaveable { mutableStateOf(false) }
    var winter by rememberSaveable { mutableStateOf(false) }

    val done = (completed and (1 shl lesson)) != 0
    val current = tourLessons[lesson]

    fun mark(index: Int) {
        if (lesson == index) {
            completed = completed or (1 shl index)
            buzz()
        }
    }

    fun go(index: Int) {
        tick()
        lesson = index.coerceIn(0, tourLessons.lastIndex)
        tab = tourLessons[lesson].tab
        if (lesson == 3) serverMode = 1
        choosingNode = false
        adding = false
        importPreview = null
        appearance = false
    }

    val tourScrollState = rememberScrollState()
    LaunchedEffect(lesson) {
        tourScrollState.scrollTo(0)
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .testTag("onboardingTrainer")
    ) {
        val isBounded = maxHeight < androidx.compose.ui.unit.Dp.Infinity && maxHeight > 240.dp
        Column(
            modifier = if (isBounded) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Header with title and interactive animated stepper
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (compactHeader) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(colors.accentCyan.copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.School, null, tint = colors.accentCyan, modifier = Modifier.size(15.dp))
                            }
                            Text(
                                text = "${lesson + 1}/${tourLessons.size} · ${strings.localized(current.titleRu, current.titleEn)}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (showSkip) {
                            SplateTextButton(onClick = onFinished) {
                                Text(strings.localized("Пропустить", "Skip"), fontSize = 12.sp, color = colors.textSecondary)
                            }
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(colors.accentCyan.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.School, null, tint = colors.accentCyan, modifier = Modifier.size(18.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                strings.localized("Интерактивный тренажёр", "Interactive Training"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                strings.localized("Учебный интерфейс · настройки не меняются", "Safe replica · settings unchanged"),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textSecondary
                            )
                        }
                        Text(
                            "${lesson + 1}/${tourLessons.size}",
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = colors.accentCyan
                        )
                    }
                }

                // Animated Stepper Capsules
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    tourLessons.forEachIndexed { i, _ ->
                        val isStepDone = (completed and (1 shl i)) != 0
                        val isCurrent = i == lesson
                        val pillWeight by animateFloatAsState(
                            targetValue = if (isCurrent) 2.6f else 1.0f,
                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                            label = "stepper_weight_$i"
                        )
                        val pillColor by animateColorAsState(
                            targetValue = when {
                                isCurrent -> colors.accentCyan
                                isStepDone -> colors.accentEmerald
                                else -> colors.borderSubtle.copy(alpha = 0.35f)
                            },
                            animationSpec = tween(250),
                            label = "stepper_color_$i"
                        )
                        Box(
                            modifier = Modifier
                                .weight(pillWeight)
                                .height(if (isCurrent) 6.dp else 4.dp)
                                .clip(CircleShape)
                                .background(pillColor)
                                .clickable { go(i) }
                        )
                    }
                }
            }

            // Scrollable middle content
            val middleModifier = if (isBounded) {
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(tourScrollState)
            } else {
                Modifier.fillMaxWidth()
            }

            Column(
                modifier = middleModifier,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Lesson Card with liquid glass styling
                AnimatedContent(
                    targetState = lesson,
                    transitionSpec = {
                        (fadeIn(tween(if (animate) 140 else 0))).togetherWith(fadeOut(tween(if (animate) 90 else 0)))
                    },
                    label = "tourLessonCard"
                ) { index ->
                    val item = tourLessons[index]
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .liquidGlass(
                                cornerRadius = 16,
                                accentBorder = if (done) colors.accentEmerald.copy(alpha = 0.6f) else colors.accentCyan.copy(alpha = 0.5f),
                                innerSheen = true
                            )
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(if (done) colors.accentEmerald.copy(alpha = 0.2f) else colors.accentCyan.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (done) {
                            Icon(Icons.Default.Check, null, tint = colors.accentEmerald, modifier = Modifier.size(16.dp))
                        } else {
                            Text("${index + 1}", color = colors.accentCyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                    Text(
                        strings.localized(item.titleRu, item.titleEn),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    if (!done) {
                        TourGuidePill(strings.localized("Задание", "Task"))
                    }
                }
                Text(
                    strings.localized(item.helpRu, item.helpEn),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    lineHeight = 19.sp
                )
            }
        }

        // The Simulated Phone Client Window with authentic liquid glass, status, and navigation
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(colors.backgroundDeep)
                .border(1.dp, colors.borderMedium, RoundedCornerShape(18.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Demo client top header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.VpnLock, null, tint = colors.accentCyan, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text("SPLATE VPN", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(colors.accentCyan.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(strings.localized("ДЕМО-РЕЖИМ", "DEMO MODE"), color = colors.accentCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Main simulated screen content
            Crossfade(targetState = tab, animationSpec = tween(if (animate) 200 else 0), label = "tourScreenCrossfade") { page ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    when (page) {
                        0 -> {
                            // TAB 0: HOME / CONTROL
                            val tint by animateColorAsState(
                                targetValue = if (connected) colors.accentConnected else colors.accentCyan,
                                animationSpec = tween(if (animate) 220 else 0),
                                label = "tourConnectTint"
                            )
                            val infinitePulse = rememberInfiniteTransition(label = "powerPulse")
                            val powerScale by infinitePulse.animateFloat(
                                initialValue = 1.0f,
                                targetValue = if (!connected && lesson == 0 && !done) 1.06f else 1.0f,
                                animationSpec = infiniteRepeatable(tween(750, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                                label = "powerScale"
                            )

                            Box(
                                modifier = Modifier
                                    .size(54.dp)
                                    .graphicsLayer {
                                        scaleX = powerScale
                                        scaleY = powerScale
                                    }
                                    .clip(CircleShape)
                                    .background(tint.copy(alpha = if (connected) 0.22f else 0.12f))
                                    .border(if (lesson == 0 && !done) 2.5.dp else 1.2.dp, tint, CircleShape)
                                    .tactileClickable {
                                        buzz()
                                        connected = !connected
                                        if (connected) mark(0)
                                    }
                                    .testTag("tour_connect"),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.PowerSettingsNew,
                                    strings.localized("Подключить VPN", "Connect VPN"),
                                    tint = tint,
                                    modifier = Modifier.size(26.dp)
                                )
                            }

                            Text(
                                if (connected) strings.encryptedSession.format("00:42") else strings.standbyUnprotected,
                                color = if (connected) colors.accentConnected else colors.textSecondary,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (connected) FontWeight.Bold else FontWeight.Normal
                            )

                            // Server card row
                            TourAction(
                                icon = Icons.Default.Dns,
                                label = if (node == 0) "🇩🇪 ${strings.localized("Германия", "Germany")}" else "🇳🇱 ${strings.localized("Нидерланды", "Netherlands")}",
                                active = lesson == 1 && !choosingNode && !done,
                                tag = "tour_server",
                                trailing = if (node == 0) "48 ms" else "76 ms"
                            ) {
                                buzz()
                                choosingNode = true
                            }

                            // Privacy spoiler lesson (lesson 8)
                            if (lesson == 8) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .tourHighlight(!done)
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.VisibilityOff, null, modifier = Modifier.size(18.dp), tint = colors.accentCyan)
                                    Spacer(Modifier.width(6.dp))
                                    SpoilerAddress(
                                        address = "192.0.2.24:443",
                                        modifier = Modifier.weight(1f),
                                        onReveal = {
                                            buzz()
                                            mark(8)
                                        }
                                    )
                                }
                            }

                            // Auto balancer lesson (lesson 4)
                            if (lesson == 4) {
                                TourAction(
                                    icon = Icons.Default.Bolt,
                                    label = strings.localized(if (automatic) "Автоподбор включён" else "Автоподбор серверов", if (automatic) "Auto selection on" else "Auto selection"),
                                    active = lesson == 4 && !automatic && !done,
                                    tag = "tour_auto"
                                ) {
                                    buzz()
                                    automatic = !automatic
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TourOption(strings.localized("Задержка", "Latency"), !speed, "tour_latency", Modifier.weight(1f)) {
                                        tick()
                                        speed = false
                                    }
                                    TourOption(
                                        strings.localized("Скорость", "Speed"),
                                        speed,
                                        "tour_speed",
                                        Modifier.weight(1f).tourHighlight(lesson == 4 && automatic && !speed && !done)
                                    ) {
                                        buzz()
                                        speed = true
                                        if (automatic) mark(4)
                                    }
                                }
                            }

                            // Traffic mode lesson (lesson 5)
                            if (lesson == 5) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TourOption(
                                        "Smart (Региональный)",
                                        smart,
                                        "tour_smart",
                                        Modifier.weight(1f).tourHighlight(lesson == 5 && !smart && !done)
                                    ) {
                                        buzz()
                                        smart = true
                                        mark(5)
                                    }
                                    TourOption(strings.localized("Весь трафик", "All traffic"), !smart, "tour_global", Modifier.weight(1f)) {
                                        tick()
                                        smart = false
                                    }
                                }
                            }
                        }

                        1 -> {
                            // TAB 1: SERVERS & TOR
                            // Top selector: VPN vs Tor
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .liquidGlass(cornerRadius = 14, innerSheen = true)
                                    .padding(4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (serverMode == 0) colors.accentCyan.copy(alpha = 0.20f) else Color.Transparent)
                                        .tourHighlight(lesson == 3 && serverMode == 1 && !done, shape = RoundedCornerShape(10.dp))
                                        .tactileClickable {
                                            tick()
                                            serverMode = 0
                                        }
                                        .testTag("tour_vpn_mode_tab")
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Icon(Icons.Default.Shield, null, tint = if (serverMode == 0) colors.accentCyan else colors.textSecondary, modifier = Modifier.size(16.dp))
                                        Text("VPN", color = if (serverMode == 0) colors.textPrimary else colors.textSecondary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    }
                                }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (serverMode == 1) colors.accentEmerald.copy(alpha = 0.20f) else Color.Transparent)
                                        .tourHighlight(lesson == 3 && serverMode == 0 && !done, shape = RoundedCornerShape(10.dp), accentColor = colors.accentEmerald)
                                        .tactileClickable {
                                            buzz()
                                            serverMode = 1
                                        }
                                        .testTag("tour_tor_mode_tab")
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Icon(Icons.Default.Public, null, tint = if (serverMode == 1) colors.accentEmerald else colors.textSecondary, modifier = Modifier.size(16.dp))
                                        Text("Tor", color = if (serverMode == 1) colors.textPrimary else colors.textSecondary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        if (lesson == 3 && serverMode == 0 && !done) {
                                            Box(Modifier.size(8.dp).clip(CircleShape).background(colors.accentAmber))
                                        }
                                    }
                                }
                            }

                            if (serverMode == 0) {
                                // VPN Server List
                                TourAction(
                                    icon = Icons.Default.Add,
                                    label = strings.localized("Добавить сервер (+)", "Add server (+)"),
                                    active = lesson == 2 && !adding && !done,
                                    tag = "tour_add"
                                ) {
                                    buzz()
                                    adding = true
                                }
                                if (adding) {
                                    TourAction(
                                        icon = Icons.Default.Link,
                                        label = strings.localized("Из ссылки (Буфер)", "From link (Clipboard)"),
                                        active = lesson == 2 && adding && importPreview == null && !done,
                                        tag = "tour_import_link"
                                    ) {
                                        buzz()
                                        importPreview = "link"
                                    }
                                    TourAction(
                                        icon = Icons.Default.QrCodeScanner,
                                        label = strings.localized("Сканировать QR-код", "Scan QR code"),
                                        active = false,
                                        tag = "tour_import_qr"
                                    ) {
                                        buzz()
                                        importPreview = "qr"
                                    }
                                }
                                TourAction(Icons.Default.Dns, "🇩🇪 ${strings.localized("Германия (VLESS)", "Germany (VLESS)")}", false, "tour_node_0", "48 ms") {
                                    tick()
                                    node = 0
                                }
                                if (imported) {
                                    TourAction(Icons.Default.CheckCircle, strings.localized("Учебный сервер добавлен ✓", "Example server added ✓"), false, "tour_imported") {}
                                }
                            } else {
                                // TOR SIMULATOR (Lesson 3)
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .liquidGlass(cornerRadius = 16, accentBorder = colors.accentEmerald.copy(alpha = 0.45f), innerSheen = true)
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(colors.accentEmerald.copy(alpha = 0.15f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.Default.Public, null, tint = colors.accentEmerald, modifier = Modifier.size(20.dp))
                                        }
                                        Spacer(Modifier.width(10.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text("Tor (The Onion Router)", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                                            Text(
                                                if (torDownloaded) strings.localized("100 проверенных мостов готово", "100 verified bridges ready")
                                                else strings.localized("Обход цензуры ТСПУ в РФ", "Bypass strict DPI blocking in Russia"),
                                                color = if (torDownloaded) colors.accentEmerald else colors.textSecondary,
                                                fontSize = 11.sp
                                            )
                                        }
                                    }

                                    // Quick Import TOP-100 Box
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(colors.surface.copy(alpha = 0.6f))
                                            .border(1.dp, colors.borderSubtle, RoundedCornerShape(12.dp))
                                            .padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.CloudDownload, null, tint = colors.accentAmber, modifier = Modifier.size(17.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                strings.localized("Быстрый импорт TOP-100 (мосты igareck)", "Quick import TOP-100 (igareck bridges)"),
                                                color = colors.textPrimary,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 12.sp
                                            )
                                        }
                                        Text(
                                            strings.localized(
                                                "Свежие мосты для РФ (WebTunnel, obfs4). Обновляются из репозитория igareck/vpn-configs-for-russia.",
                                                "Fresh bridges for Russia (WebTunnel, obfs4) from igareck's repository."
                                            ),
                                            color = colors.textSecondary,
                                            fontSize = 10.5.sp,
                                            lineHeight = 14.sp
                                        )

                                        if (torDownloading) {
                                            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                                LinearProgressIndicator(
                                                    modifier = Modifier.fillMaxWidth().height(4.dp),
                                                    color = colors.accentEmerald,
                                                    trackColor = colors.borderSubtle
                                                )
                                                Text(
                                                    strings.localized("Загрузка 100 мостов igareck с GitHub…", "Downloading 100 igareck bridges from GitHub…"),
                                                    color = colors.accentEmerald,
                                                    fontSize = 10.5.sp
                                                )
                                            }
                                        } else {
                                            Button(
                                                onClick = {
                                                    buzz()
                                                    scope.launch {
                                                        torDownloading = true
                                                        delay(750)
                                                        torDownloading = false
                                                        torDownloaded = true
                                                        mark(3)
                                                        buzz()
                                                    }
                                                },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .tourHighlight(lesson == 3 && !torDownloaded, accentColor = colors.accentEmerald)
                                                    .testTag("tour_tor_top100"),
                                                colors = ButtonDefaults.buttonColors(containerColor = colors.accentEmerald)
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    Icon(
                                                        if (torDownloaded) Icons.Default.CheckCircle else Icons.Default.CloudDownload,
                                                        null,
                                                        Modifier.size(16.dp)
                                                    )
                                                    Text(
                                                        strings.localized(
                                                            if (torDownloaded) "✓ TOP-100 загружен (обновить)" else "Загрузить TOP-100 мостов",
                                                            if (torDownloaded) "✓ TOP-100 loaded (refresh)" else "Download TOP-100 bridges"
                                                        ),
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    if (torDownloaded) {
                                        // Transport filter chips
                                        Text(
                                            strings.localized("Транспорты мостов:", "Bridge transports:"),
                                            color = colors.textSecondary,
                                            fontSize = 10.5.sp
                                        )
                                        Row(
                                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            listOf(
                                                "auto" to strings.localized("Авто · 100", "Auto · 100"),
                                                "webtunnel" to "WebTunnel · 38",
                                                "obfs4" to "obfs4 · 52",
                                                "vanilla" to "Vanilla · 10"
                                            ).forEach { (type, label) ->
                                                val selected = torSelectedTransport == type
                                                FilterChip(
                                                    selected = selected,
                                                    onClick = {
                                                        tick()
                                                        torSelectedTransport = type
                                                    },
                                                    label = { Text(label, fontSize = 10.5.sp) },
                                                    colors = FilterChipDefaults.filterChipColors(
                                                        selectedContainerColor = colors.accentEmerald.copy(alpha = 0.20f),
                                                        selectedLabelColor = colors.accentEmerald
                                                    )
                                                )
                                            }
                                        }

                                        // Educational callout explaining why WebTunnel works in Russia
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(colors.accentCyan.copy(alpha = 0.08f))
                                                .border(1.dp, colors.accentCyan.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                                                .padding(9.dp),
                                            verticalArrangement = Arrangement.spacedBy(3.dp)
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                                Icon(Icons.Default.Lightbulb, null, tint = colors.accentAmber, modifier = Modifier.size(15.dp))
                                                Text(
                                                    strings.localized("Секрет обхода блокировок", "Censorship bypass secret"),
                                                    color = colors.accentCyan,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 11.sp
                                                )
                                            }
                                            Text(
                                                strings.localized(
                                                    "WebTunnel маскирует трафик под обычный веб-сайт с HTTPS, а obfs4 шифрует его шумом. ТСПУ пропускает соединение!",
                                                    "WebTunnel disguises traffic as normal HTTPS web traffic, and obfs4 obfuscates it. DPI lets the connection through!"
                                                ),
                                                color = colors.textPrimary,
                                                fontSize = 10.5.sp,
                                                lineHeight = 14.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        2 -> {
                            // TAB 2: APPS / ROUTING
                            Text(strings.appRoutingTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                strings.localized("Через VPN — только выбранные приложения", "Use VPN for selected apps only"),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textSecondary
                            )
                            TourAction(
                                icon = if (appSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                label = strings.localized("Веб-браузер (Chrome / Firefox)", "Web browser (Chrome / Firefox)"),
                                active = lesson == 6 && !done,
                                tag = "tour_app"
                            ) {
                                buzz()
                                appSelected = !appSelected
                                if (appSelected) mark(6)
                            }
                        }

                        3 -> {
                            // TAB 3: SETTINGS
                            if (appearance) {
                                TourAction(Icons.AutoMirrored.Filled.ArrowBack, strings.localized("Все настройки", "All settings"), false, "tour_settings_back") {
                                    tick()
                                    appearance = false
                                }
                                TourAction(
                                    icon = Icons.Default.AcUnit,
                                    label = strings.themeWinter,
                                    active = lesson == 7 && !done,
                                    tag = "tour_winter",
                                    trailing = if (winter) "✓ Активна" else ""
                                ) {
                                    buzz()
                                    winter = true
                                    mark(7)
                                }
                            } else {
                                Text(strings.settingsTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                TourAction(
                                    icon = Icons.Default.Palette,
                                    label = strings.localized("Оформление и темы", "Appearance & Themes"),
                                    active = lesson == 7 && !done,
                                    tag = "tour_appearance"
                                ) {
                                    buzz()
                                    appearance = true
                                }
                            }
                        }
                    }
                }
            }

            // Bottom Navigation Strip of the Simulated Client
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .liquidGlass(cornerRadius = 14, innerSheen = true)
                    .padding(2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf(Icons.Default.Shield, Icons.Default.Dns, Icons.Default.Apps, Icons.Default.Tune).forEachIndexed { index, icon ->
                    val label = listOf(strings.tabControl, strings.tabNodes, strings.tabRouting, strings.tabConfig)[index]
                    val isTarget = current.tab == index && tab != index && !done
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (tab == index) colors.accentCyan.copy(alpha = 0.16f) else Color.Transparent)
                            .tourHighlight(isTarget, shape = RoundedCornerShape(10.dp))
                            .tactileClickable {
                                tick()
                                tab = index
                            }
                            .testTag("tour_nav_$index")
                            .padding(vertical = 5.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Icon(icon, label, tint = if (tab == index) colors.accentCyan else colors.textSecondary, modifier = Modifier.size(16.dp))
                        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp)
                    }
                }
            }
        }

        // Feedback / Success Celebratory Strip
        AnimatedContent(
            targetState = done,
            transitionSpec = {
                (fadeIn(tween(160))).togetherWith(fadeOut(tween(100)))
            },
            label = "celebrationBanner"
        ) { isComplete ->
            if (isComplete) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.accentEmerald.copy(alpha = 0.14f))
                        .border(1.dp, colors.accentEmerald.copy(alpha = 0.50f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.CheckCircle, null, tint = colors.accentEmerald, modifier = Modifier.size(20.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            strings.localized("Отлично! Урок освоен.", "Great job! Lesson completed."),
                            color = colors.textPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                        Text(
                            strings.localized("Нажмите «Дальше» для перехода к следующему шагу.", "Tap 'Continue' to proceed to the next step."),
                            color = colors.textSecondary,
                            fontSize = 11.sp
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.accentCyan.copy(alpha = 0.08f))
                        .border(1.dp, colors.accentCyan.copy(alpha = 0.30f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.TouchApp, null, tint = colors.accentCyan, modifier = Modifier.size(18.dp))
                    Text(
                        strings.localized("Нажмите на подсвеченный элемент интерфейса", "Tap the highlighted control on the demo screen"),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    } // Close middle scrollable column

        // Bottom Navigation Buttons (Back & Continue / Finish) - pinned at bottom
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SplateTextButton(
                onClick = {
                    if (lesson > 0) go(lesson - 1) else onBack()
                }
            ) {
                Text(strings.localized("Назад", "Back"))
            }

            SplateButton(
                onClick = {
                    buzz()
                    if (lesson == tourLessons.lastIndex) onFinished() else go(lesson + 1)
                },
                enabled = done,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .testTag("tour_next"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (lesson == tourLessons.lastIndex) colors.accentEmerald else colors.accentCyan
                )
            ) {
                Text(
                    strings.localized(
                        if (lesson == tourLessons.lastIndex) "Завершить обучение 🚀" else "Дальше ➔",
                        if (lesson == tourLessons.lastIndex) "Complete training 🚀" else "Continue ➔"
                    ),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
        }
    } // Close outer Column
} // Close BoxWithConstraints

    // Modal dialog for link / QR import
    importPreview?.let { method ->
        SplateAlertDialog(
            onDismissRequest = { importPreview = null },
            title = {
                Text(strings.localized(if (method == "qr") "Импорт по QR-коду" else "Ссылка сервера / подписки", if (method == "qr") "Import QR" else "Server link"))
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (method == "qr") {
                        Icon(Icons.Default.QrCodeScanner, null, tint = colors.accentCyan, modifier = Modifier.size(90.dp))
                        Text(
                            strings.localized(
                                "Наведите камеру на QR-код от вашего провайдера. В настоящем клиенте камера моментально распознает конфигурацию на лету.",
                                "Point your camera at a provider QR code. The real client parses the configuration instantly on-device."
                            ),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        OutlinedTextField(
                            value = "vless://demo@splate.vpn:443?security=reality&sni=cloudflare.com#Splate_Demo",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(strings.localized("Учебная конфигурация", "Example config")) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            strings.localized(
                                "Скопируйте ссылку сервера VLESS/Shadowsocks или подписки, вставьте в поле и нажмите «Добавить».",
                                "Copy a VLESS/Shadowsocks server or subscription link, paste it into the field, and tap Add."
                            ),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            },
            confirmButton = {
                SplateButton(
                    onClick = {
                        buzz()
                        imported = true
                        adding = false
                        importPreview = null
                        mark(2)
                    },
                    modifier = Modifier
                        .tourHighlight(lesson == 2 && !done)
                        .testTag("tour_import_confirm")
                ) {
                    Text(strings.localized("Добавить пример", "Add example"))
                }
            },
            dismissButton = {
                SplateTextButton(onClick = { importPreview = null }) {
                    Text(strings.cancel)
                }
            }
        )
    }

    // Modal dialog for quick server choosing
    if (choosingNode) {
        SplateAlertDialog(
            onDismissRequest = { choosingNode = false },
            title = { Text(strings.localized("Быстрый выбор узла", "Quick server selection")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(
                        "🇩🇪 ${strings.localized("Германия (Франкфурт)", "Germany (Frankfurt)")}" to "48 ms",
                        "🇳🇱 ${strings.localized("Нидерланды (Амстердам)", "Netherlands (Amsterdam)")}" to "76 ms"
                    ).forEachIndexed { index, (name, ping) ->
                        TourAction(
                            icon = if (node == index) Icons.Default.CheckCircle else Icons.Default.Dns,
                            label = name,
                            active = lesson == 1 && index != node && !done,
                            tag = "tour_choose_$index",
                            trailing = ping
                        ) {
                            buzz()
                            val changed = node != index
                            node = index
                            choosingNode = false
                            if (changed) mark(1)
                        }
                    }
                }
            },
            confirmButton = {
                SplateTextButton(onClick = { choosingNode = false }) {
                    Text(strings.close)
                }
            }
        )
    }
}

@Composable
private fun Modifier.tourHighlight(
    active: Boolean,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(14.dp),
    accentColor: Color = LocalAppThemeColors.current.accentCyan
): Modifier {
    if (!active) return this
    val animate = LocalUiPerformanceSettings.current.animationsEnabled
    if (!animate) return this.border(1.5.dp, accentColor, shape)

    val infinite = rememberInfiniteTransition(label = "tourPulseAnim")
    val alpha by infinite.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "tourGlowAlpha"
    )

    // Strictly NO scaleX or scaleY: keeps card geometry 100% stable without layout jitter or blur
    return this
        .border(1.5.dp, accentColor.copy(alpha = alpha), shape)
        .background(accentColor.copy(alpha = 0.08f * alpha), shape)
}

@Composable
private fun TourGuidePill(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LocalAppThemeColors.current.accentCyan
) {
    val animate = LocalUiPerformanceSettings.current.animationsEnabled
    val alpha by if (animate) {
        val infinite = rememberInfiniteTransition(label = "pillGlow")
        infinite.animateFloat(
            initialValue = 0.50f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(850, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pillAlpha"
        )
    } else {
        remember { mutableFloatStateOf(1.0f) }
    }

    // Static, perfectly anchored in layout (strictly zero vertical or horizontal offset displacement!)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(color.copy(alpha = 0.14f * alpha))
            .border(1.dp, color.copy(alpha = 0.85f * alpha), RoundedCornerShape(20.dp))
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = Icons.Default.TouchApp,
            contentDescription = null,
            tint = color.copy(alpha = alpha),
            modifier = Modifier.size(13.dp)
        )
        Text(
            text = text,
            color = color.copy(alpha = alpha),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
private fun TourAction(
    icon: ImageVector,
    label: String,
    active: Boolean,
    tag: String,
    trailing: String = "",
    onClick: () -> Unit
) {
    val colors = LocalAppThemeColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .liquidGlass(cornerRadius = 12, innerSheen = true)
            .tourHighlight(active)
            .tactileClickable(onClick = onClick)
            .testTag(tag)
            .heightIn(min = 40.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, null, tint = colors.accentCyan, modifier = Modifier.size(18.dp))
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (trailing.isNotEmpty()) {
            Text(trailing, color = colors.textSecondary, style = MaterialTheme.typography.labelSmall)
        }
        if (active) {
            TourGuidePill(LocalAppStrings.current.localized("Тапните", "Tap"))
        }
    }
}

@Composable
private fun TourOption(
    label: String,
    selected: Boolean,
    tag: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val colors = LocalAppThemeColors.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) colors.accentCyan.copy(alpha = 0.16f) else colors.surface)
            .border(1.dp, if (selected) colors.accentCyan.copy(alpha = 0.6f) else colors.borderSubtle, RoundedCornerShape(10.dp))
            .semantics {
                this.selected = selected
                role = Role.RadioButton
            }
            .tactileClickable(onClick = onClick)
            .testTag(tag)
            .heightIn(min = 40.dp)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (selected) colors.accentCyan else colors.textSecondary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}
