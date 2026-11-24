package com.danieldk.splatevpn.ui.theme

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

// Базовая тёмная палитра.
val BackgroundDark = Color(0xFF070B14)
val BackgroundDeep = Color(0xFF030509)
val SurfaceDark = Color(0xFF0F1626)
val SurfaceElevated = Color(0xFF151E33)

// Границы.
val BorderSubtle = Color(0xFF1E2B42)
val BorderMedium = Color(0xFF2A3B5A)
val GlassWhite = Color(0x0FFFFFFF)
val GlassBorder = Color(0x1FFFFFFF)

// Текст.
val TextPrimary = Color(0xFFF8FAFC)
val TextSecondary = Color(0xFF94A3B8)
val TextTertiary = Color(0xFF475569)

// Акценты.
val PrimaryBlue = Color(0xFF3B82F6)
val AccentCyan = Color(0xFF00F0FF)
val AccentTeal = Color(0xFF00E5FF)
val AccentEmerald = Color(0xFF10E79D)
val AccentAmber = Color(0xFFFFB300)
val AccentCrimson = Color(0xFFFF3366)
val AccentViolet = Color(0xFFA78BFA)

/**
 * Фоновые эффекты тем.
 */
enum class BackdropStyle {
    /** Лунный свет, заснеженные склоны и мягкий снегопад. */
    WINTER,
    /** Мягкие светящиеся сферы (базовая тёмная). */
    SOFT,

    /** Чёрный фон с мерцающими звёздами (AMOLED). */
    STARS,

    /** Фиолетовые стеклянные монолиты и мягкий контурный свет. */
    GRID,

    /** Плавные световые ленты (Северное сияние). */
    AURORA,

    /** Закатное солнце в стиле synthwave (Закат). */
    SUNSET,

    /** Расходящиеся концентрические кольца (OnePlus). */
    RINGS,

    /** Светлые пастельные облака (Светлая). */
    PASTEL,

    /** Подводные лучи, волны и воздушные пузырьки. */
    OCEAN,

    /** Ночной сад с ветвями и летящими лепестками. */
    SAKURA,
    PONY,
    BLOOM
}

/** Ключи тем, сохраняемые в настройках. Порядок определяет порядок в сетке выбора. */
val ThemeKeys: List<String> = listOf(
    "dark", "amoled", "light", "system", "cyberpunk", "winter", "winter_light", "red", "aurora", "sunset", "ocean", "sakura", "pony", "winx"
)

/** Old backups and installations retain their selected replacement theme. */
fun canonicalThemeKey(key: String): String = when (val normalized = key.lowercase()) {
    "oneplus" -> "red"
    else -> normalized
}

data class AppThemeColors(
    val background: Color,
    val backgroundDeep: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val borderSubtle: Color,
    val borderMedium: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    /** Основной акцент темы (исторически назывался Cyan). */
    val accentCyan: Color,
    val accentEmerald: Color,
    val accentAmber: Color,
    val accentCrimson: Color,
    val accentViolet: Color,
    val accentBlue: Color,
    val glassSurface: Color,
    val glassSurfaceElevated: Color,
    val glassBorderLight: Color,
    val glassBorderDark: Color,
    val glassSpecular: Color,
    /** Цвет контента (текст/иконки) поверх заливки основного акцента. */
    val onAccent: Color,
    /** Второй цвет градиентов и выделений. */
    val accentSecondary: Color,
    /** Вторая и третья сферы анимированного фона. */
    val ambientB: Color,
    val ambientC: Color,
    /** Множитель яркости фоновых сфер. */
    val ambientIntensity: Float,
    val backdrop: BackdropStyle,
    val isDark: Boolean,
    /** Цвет активного защищенного туннеля (подключено). Адаптируется под визуальный стиль каждой темы. */
    val accentConnected: Color = accentCyan
)

/**
 * Вычисляет стеклянные поверхности из базовой палитры.
 */
private fun buildPalette(
    isDark: Boolean,
    background: Color,
    backgroundDeep: Color,
    surface: Color,
    surfaceElevated: Color,
    borderSubtle: Color,
    borderMedium: Color,
    textPrimary: Color,
    textSecondary: Color,
    textTertiary: Color,
    accent: Color,
    accentSecondary: Color,
    accentEmerald: Color,
    accentAmber: Color,
    accentCrimson: Color,
    accentViolet: Color,
    accentBlue: Color,
    onAccent: Color,
    ambientB: Color,
    ambientC: Color,
    ambientIntensity: Float,
    backdrop: BackdropStyle,
    glassAlpha: Float,
    glassBorderLight: Color,
    glassBorderDark: Color,
    glassSpecular: Color,
    accentConnected: Color = accent
): AppThemeColors = AppThemeColors(
    background = background,
    backgroundDeep = backgroundDeep,
    surface = surface,
    surfaceElevated = surfaceElevated,
    borderSubtle = borderSubtle,
    borderMedium = borderMedium,
    textPrimary = textPrimary,
    textSecondary = textSecondary,
    textTertiary = textTertiary,
    accentCyan = accent,
    accentEmerald = accentEmerald,
    accentAmber = accentAmber,
    accentCrimson = accentCrimson,
    accentViolet = accentViolet,
    accentBlue = accentBlue,
    glassSurface = surface.copy(alpha = glassAlpha),
    glassSurfaceElevated = surfaceElevated.copy(alpha = (glassAlpha + 0.08f).coerceAtMost(0.96f)),
    glassBorderLight = glassBorderLight,
    glassBorderDark = glassBorderDark,
    glassSpecular = glassSpecular,
    onAccent = onAccent,
    accentSecondary = accentSecondary,
    ambientB = ambientB,
    ambientC = ambientC,
    ambientIntensity = ambientIntensity,
    backdrop = backdrop,
    isDark = isDark,
    accentConnected = accentConnected
)

/** Тёмная: глубокий космический индиго, яркий циановый неон. */
val DarkThemeColors = buildPalette(
    isDark = true,
    background = Color(0xFF050914),
    backgroundDeep = Color(0xFF020408),
    surface = Color(0xFF0C1424),
    surfaceElevated = Color(0xFF141F38),
    borderSubtle = Color(0xFF1D2E4E),
    borderMedium = Color(0xFF2C436D),
    textPrimary = Color(0xFFF5F9FC),
    textSecondary = Color(0xFF96ACC8),
    textTertiary = Color(0xFF94A4BC),
    accent = Color(0xFF7BDDEC),
    accentSecondary = Color(0xFF829FFF),
    accentEmerald = Color(0xFF53DBAB),
    accentAmber = Color(0xFFF4C474),
    accentCrimson = Color(0xFFFF728A),
    accentViolet = Color(0xFFA488FA),
    accentBlue = Color(0xFF829FFF),
    onAccent = Color(0xFF001218),
    ambientB = Color(0xFF847CC7),
    ambientC = Color(0xFF2860EB),
    ambientIntensity = 0.88f,
    backdrop = BackdropStyle.SOFT,
    glassAlpha = 0.86f,
    glassBorderLight = Color(0x6EA0C5FF),
    glassBorderDark = Color(0x385580FF),
    glassSpecular = Color(0x44FFFFFF)
)

/** AMOLED: абсолютно чёрный фон, электрический неоново-аквамариновый акцент и глубокое звёздное небо. */
val AmoledThemeColors = buildPalette(
    isDark = true,
    background = Color(0xFF000000),
    backgroundDeep = Color(0xFF000000),
    surface = Color(0xFF060809),
    surfaceElevated = Color(0xFF0E1114),
    borderSubtle = Color(0xFF18202A),
    borderMedium = Color(0xFF253040),
    textPrimary = Color(0xFFF8FCFF),
    textSecondary = Color(0xFF8EA4BC),
    textTertiary = Color(0xFF879BA8),
    accent = Color(0xFF8EF0CF),
    accentSecondary = Color(0xFF67C9E0),
    accentEmerald = Color(0xFF00F5B0),
    accentAmber = Color(0xFFFFC942),
    accentCrimson = Color(0xFFFF3366),
    accentViolet = Color(0xFF8B9AFF),
    accentBlue = Color(0xFF00C8FF),
    onAccent = Color(0xFF001A16),
    ambientB = Color(0xFF0077B6),
    ambientC = Color(0xFF8EF0CF),
    ambientIntensity = 0.50f,
    backdrop = BackdropStyle.STARS,
    glassAlpha = 0.90f,
    glassBorderLight = Color(0x406CCFB8),
    glassBorderDark = Color(0x1AFFFFFF),
    glassSpecular = Color(0x28FFFFFF)
)

/** Светлая: свежие небесно-голубые + лавандовые тона, воздушные пастельные облака. */
val LightThemeColors = buildPalette(
    isDark = false,
    background = Color(0xFFF2F5FA),
    backgroundDeep = Color(0xFFE6EDF5),
    surface = Color(0xFFFFFFFF),
    surfaceElevated = Color(0xFFF7F9FC),
    borderSubtle = Color(0xFFD7E0EA),
    borderMedium = Color(0xFFA8B8CA),
    textPrimary = Color(0xFF0C1428),
    textSecondary = Color(0xFF43536B),
    textTertiary = Color(0xFF43536B),
    accent = Color(0xFF245FBE),
    accentSecondary = Color(0xFF7052B8),
    accentEmerald = Color(0xFF007A55),
    accentAmber = Color(0xFF965600),
    accentCrimson = Color(0xFFC72C4B),
    accentViolet = Color(0xFF7043BA),
    accentBlue = Color(0xFF245FBE),
    onAccent = Color(0xFFFFFFFF),
    ambientB = Color(0xFFFF85CC),
    ambientC = Color(0xFF40C8FF),
    ambientIntensity = 0.82f,
    backdrop = BackdropStyle.PASTEL,
    glassAlpha = 0.92f,
    glassBorderLight = Color(0xDDFFFFFF),
    glassBorderDark = Color(0x503B7CF5),
    glassSpecular = Color(0xBBFFFFFF)
)

/** Winter: ink-blue night, ice-blue glass and pearl snow. */
val CyberpunkThemeColors = buildPalette(
    isDark = true,
    background = Color(0xFF120C22), backgroundDeep = Color(0xFF070510),
    surface = Color(0xFF21172F), surfaceElevated = Color(0xFF30233F),
    borderSubtle = Color(0xFF4D3B61), borderMedium = Color(0xFF80669B),
    textPrimary = Color(0xFFF7F1FF), textSecondary = Color(0xFFD5C5E8), textTertiary = Color(0xFFC5B5D8),
    accent = Color(0xFFD1ADFF), accentSecondary = Color(0xFF80E6DD),
    accentEmerald = Color(0xFF91DBBB), accentAmber = Color(0xFFF4D59B),
    accentCrimson = Color(0xFFFFA9CC), accentViolet = Color(0xFFCEB8FF), accentBlue = Color(0xFFACCEFF),
    onAccent = Color(0xFF21112F), ambientB = Color(0xFF7954A9), ambientC = Color(0xFF528E98),
    ambientIntensity = .42f, backdrop = BackdropStyle.GRID, glassAlpha = .88f,
    glassBorderLight = Color(0x708E74AC), glassBorderDark = Color(0x382B1C41), glassSpecular = Color(0x70EEE0FF)
)

val WinterThemeColors = buildPalette(
    isDark = true,
    background = Color(0xFF0B1829),
    backgroundDeep = Color(0xFF050E1B),
    surface = Color(0xFF14263B),
    surfaceElevated = Color(0xFF1C334B),
    borderSubtle = Color(0xFF30475F),
    borderMedium = Color(0xFF506B87),
    textPrimary = Color(0xFFF2F7FF),
    textSecondary = Color(0xFFBDD0E5),
    textTertiary = Color(0xFFADC2DA),
    accent = Color(0xFFADDFFF),
    accentSecondary = Color(0xFFD9D7FF),
    accentEmerald = Color(0xFF8DDCC5),
    accentAmber = Color(0xFFF4D29A),
    accentCrimson = Color(0xFFFFA6B4),
    accentViolet = Color(0xFFC8BDF5),
    accentBlue = Color(0xFFADDFFF),
    onAccent = Color(0xFF10263C),
    ambientB = Color(0xFF749CC8),
    ambientC = Color(0xFFBCCBEC),
    ambientIntensity = 0.55f,
    backdrop = BackdropStyle.WINTER,
    glassAlpha = 0.88f,
    glassBorderLight = Color(0x709BC7E8),
    glassBorderDark = Color(0x383D7195),
    glassSpecular = Color(0x70E5F4FF)
)

/** A separate daylight material and object atlas, with readable blue ink over pearl snow. */
val WinterLightThemeColors = buildPalette(
    isDark = false,
    background = Color(0xFFF0F6FC),
    backgroundDeep = Color(0xFFE1EBF7),
    surface = Color(0xFFFAFCFF),
    surfaceElevated = Color(0xFFF3F7FC),
    borderSubtle = Color(0xFFC1D1E2),
    borderMedium = Color(0xFF8AA6C2),
    textPrimary = Color(0xFF17324B),
    textSecondary = Color(0xFF30485F),
    textTertiary = Color(0xFF3C526B),
    accent = Color(0xFF23629F),
    accentSecondary = Color(0xFF5A58A6),
    accentEmerald = Color(0xFF0B705D),
    accentAmber = Color(0xFF8C570B),
    accentCrimson = Color(0xFFAD3357),
    accentViolet = Color(0xFF68519F),
    accentBlue = Color(0xFF23629F),
    onAccent = Color.White,
    ambientB = Color(0xFF8BB7D9),
    ambientC = Color(0xFFB8B1DC),
    ambientIntensity = .48f,
    backdrop = BackdropStyle.WINTER,
    glassAlpha = .92f,
    glassBorderLight = Color(0xEFFFFFFF),
    glassBorderDark = Color(0x607B9BBA),
    glassSpecular = Color(0xCFFFFFFF)
)

/** OnePlus: фирменный насыщенный красный на графитовом чёрном, расходящиеся кольца. */
val RedThemeColors = buildPalette(
    isDark = true,
    background = Color(0xFF080A0C),
    backgroundDeep = Color(0xFF030304),
    surface = Color(0xFF131518),
    surfaceElevated = Color(0xFF1B1E24),
    borderSubtle = Color(0xFF282E38),
    borderMedium = Color(0xFF3A424F),
    textPrimary = Color(0xFFF8F9FA),
    textSecondary = Color(0xFF9EA5B2),
    textTertiary = Color(0xFF9BA3AF),
    accent = Color(0xFFFF8C93),
    accentSecondary = Color(0xFFF1B1A0),
    accentEmerald = Color(0xFF67D8AA),
    accentAmber = Color(0xFFF0BF69),
    accentCrimson = Color(0xFFFF7890),
    accentViolet = Color(0xFFB0B5C2),
    accentBlue = Color(0xFFFF8C93),
    onAccent = Color(0xFF21070B),
    ambientB = Color(0xFFD5796E),
    ambientC = Color(0xFF920018),
    ambientIntensity = 0.82f,
    backdrop = BackdropStyle.RINGS,
    glassAlpha = 0.86f,
    glassBorderLight = Color(0x60F19CA3),
    glassBorderDark = Color(0x28FFFFFF),
    glassSpecular = Color(0x32FFFFFF)
)

/** Северное сияние: богатые изумрудно-бирюзово-фиолетовые ленты на глубокой арктической ночи. */
val AuroraThemeColors = buildPalette(
    isDark = true,
    background = Color(0xFF020E16),
    backgroundDeep = Color(0xFF01080E),
    surface = Color(0xFF06202C),
    surfaceElevated = Color(0xFF0B3040),
    borderSubtle = Color(0xFF135060),
    borderMedium = Color(0xFF1E7890),
    textPrimary = Color(0xFFE8FFF5),
    textSecondary = Color(0xFFA7C9C4),
    textTertiary = Color(0xFF87BAB3),
    accent = Color(0xFF83E4C2),
    accentSecondary = Color(0xFF85C9ED),
    accentEmerald = Color(0xFF00E676),
    accentAmber = Color(0xFFFFD04F),
    accentCrimson = Color(0xFFFF8198),
    accentViolet = Color(0xFFA78BFA),
    accentBlue = Color(0xFF85C9ED),
    onAccent = Color(0xFF001A10),
    ambientB = Color(0xFFA78BFA),
    ambientC = Color(0xFF85C9ED),
    ambientIntensity = 0.85f,
    backdrop = BackdropStyle.AURORA,
    glassAlpha = 0.84f,
    glassBorderLight = Color(0x6092DCC4),
    glassBorderDark = Color(0x3800C0FF),
    glassSpecular = Color(0x4420FFB0)
)

/** Закат: жаркие коралловые, золотые и глубокие пурпурные тона, ретро-солнце на горизонте. */
val SunsetThemeColors = buildPalette(
    isDark = true,
    background = Color(0xFF120414),
    backgroundDeep = Color(0xFF06010A),
    surface = Color(0xFF220E28),
    surfaceElevated = Color(0xFF301438),
    borderSubtle = Color(0xFF413044),
    borderMedium = Color(0xFF69506B),
    textPrimary = Color(0xFFFFF5ED),
    textSecondary = Color(0xFFDFC0B8),
    textTertiary = Color(0xFFB99BAA),
    accent = Color(0xFFFFAA87),
    accentSecondary = Color(0xFFF4CC89),
    accentEmerald = Color(0xFF25E8A0),
    accentAmber = Color(0xFFF4C474),
    accentCrimson = Color(0xFFFF7890),
    accentViolet = Color(0xFFC8A0ED),
    accentBlue = Color(0xFFFF8545),
    onAccent = Color(0xFF1C0804),
    ambientB = Color(0xFFF4CC89),
    ambientC = Color(0xFFA681B2),
    ambientIntensity = 0.85f,
    backdrop = BackdropStyle.SUNSET,
    glassAlpha = 0.88f,
    glassBorderLight = Color(0x68EAB2A2),
    glassBorderDark = Color(0x38BB6EFF),
    glassSpecular = Color(0x44FFDCC0)
)


val OceanThemeColors = buildPalette(
    isDark = true,
    background = Color(0xFF041721),
    backgroundDeep = Color(0xFF020C16),
    surface = Color(0xFF0B2230),
    surfaceElevated = Color(0xFF13303F),
    borderSubtle = Color(0xFF2D5361),
    borderMedium = Color(0xFF46717E),
    textPrimary = Color(0xFFEAF8FF),
    textSecondary = Color(0xFFB7D2D7),
    textTertiary = Color(0xFFA4C2CC),
    accent = Color(0xFF88DDC7),
    accentSecondary = Color(0xFF9ACCE8),
    accentEmerald = Color(0xFF62DEB4),
    accentAmber = Color(0xFFF4CB83),
    accentCrimson = Color(0xFFFF839C),
    accentViolet = Color(0xFFC1ABEE),
    accentBlue = Color(0xFF9BBFF9),
    onAccent = Color(0xFF031820),
    ambientB = Color(0xFF4E8DA0),
    ambientC = Color(0xFF64BBA7),
    ambientIntensity = .65f,
    backdrop = BackdropStyle.OCEAN,
    glassAlpha = 0.84f,
    glassBorderLight = Color(0x6082DCEC),
    glassBorderDark = Color(0x38376787),
    glassSpecular = Color(0x44D7F4FF)
)

val SakuraThemeColors = buildPalette(
    isDark = true,
    background = Color(0xFF140D1D),
    backgroundDeep = Color(0xFF090610),
    surface = Color(0xFF251A30),
    surfaceElevated = Color(0xFF30233D),
    borderSubtle = Color(0xFF493650),
    borderMedium = Color(0xFF6E536F),
    textPrimary = Color(0xFFFFF2F6),
    textSecondary = Color(0xFFE2CADB),
    textTertiary = Color(0xFFC4ADC8),
    accent = Color(0xFFF4B5CD),
    accentSecondary = Color(0xFFCCB8EF),
    accentEmerald = Color(0xFF83DDB5),
    accentAmber = Color(0xFFF0D19A),
    accentCrimson = Color(0xFFFF8FA9),
    accentViolet = Color(0xFFCCB8EF),
    accentBlue = Color(0xFFA8C5F2),
    onAccent = Color(0xFF250E1C),
    ambientB = Color(0xFFB18AC8),
    ambientC = Color(0xFFEC9BB9),
    ambientIntensity = 0.9f,
    backdrop = BackdropStyle.SAKURA,
    glassAlpha = 0.84f,
    glassBorderLight = Color(0x68F4B5CD),
    glassBorderDark = Color(0x386E536F),
    glassSpecular = Color(0x44FFF0F7)
)

/**
 * Разрешает ключ темы в палитру. Для "system" использует системный режим,
 * для неизвестных ключей — базовую тёмную тему.
 */
val PonyThemeColors = LightThemeColors.copy(
    background = Color(0xFFFFFCFF), backgroundDeep = Color(0xFFF3ECFC),
    surface = Color.White, surfaceElevated = Color(0xFFFCF8FF),
    borderSubtle = Color(0xFFE2D5ED), borderMedium = Color(0xFFB8A0CE),
    textPrimary = Color(0xFF30213E), textSecondary = Color(0xFF554464), textTertiary = Color(0xFF695777),
    accentCyan = Color(0xFF7842AC), accentConnected = Color(0xFF7842AC),
    accentSecondary = Color(0xFFA82F75), accentViolet = Color(0xFF7842AC), accentBlue = Color(0xFF2865B0),
    ambientB = Color(0xFFE699CD), ambientC = Color(0xFF8ED8F2), ambientIntensity = .65f,
    glassSurface = Color(0xF5FFFFFF), glassSurfaceElevated = Color(0xF7FCF8FF),
    glassBorderLight = Color(0xEEFFFFFF), glassBorderDark = Color(0x50B8A0CE),
    backdrop = BackdropStyle.PONY
)

val WinxThemeColors = LightThemeColors.copy(
    background = Color(0xFFFFFCFD), backgroundDeep = Color(0xFFFBEFF7),
    surface = Color.White, surfaceElevated = Color(0xFFFFF8FC),
    borderSubtle = Color(0xFFEFD5E4), borderMedium = Color(0xFFCEA2BB),
    textPrimary = Color(0xFF3D2034), textSecondary = Color(0xFF654657), textTertiary = Color(0xFF78576A),
    accentCyan = Color(0xFFAA296F), accentConnected = Color(0xFFAA296F),
    accentSecondary = Color(0xFF276A99), accentViolet = Color(0xFF8142AE), accentBlue = Color(0xFF276A99),
    ambientB = Color(0xFFEFACCA), ambientC = Color(0xFF8AD9EE), ambientIntensity = .65f,
    glassSurface = Color(0xF5FFFFFF), glassSurfaceElevated = Color(0xF7FFF8FC),
    glassBorderLight = Color(0xEEFFFFFF), glassBorderDark = Color(0x50CEA2BB),
    backdrop = BackdropStyle.BLOOM
)

fun resolveThemeColors(themeKey: String, isSystemDark: Boolean): AppThemeColors =
    when (canonicalThemeKey(themeKey)) {
        "amoled" -> AmoledThemeColors
        "light" -> LightThemeColors
        "cyberpunk" -> CyberpunkThemeColors
        "winter" -> WinterThemeColors
        "winter_light" -> WinterLightThemeColors
        "red" -> RedThemeColors
        "aurora" -> AuroraThemeColors
        "sunset" -> SunsetThemeColors
        "ocean" -> OceanThemeColors
        "sakura" -> SakuraThemeColors
        "pony" -> PonyThemeColors
        "winx" -> WinxThemeColors
        "system" -> if (isSystemDark) DarkThemeColors else LightThemeColors
        else -> DarkThemeColors
    }

fun getProtocolColor(protocol: String, colors: AppThemeColors = DarkThemeColors): Color {
    if (!colors.isDark) return when (protocol.lowercase()) {
        "vless" -> colors.accentViolet
        "vmess", "tuic" -> colors.accentBlue
        "trojan", "wireguard", "wg" -> colors.accentEmerald
        "shadowsocks", "ss" -> colors.accentAmber
        "hysteria", "hysteria2", "hy2" -> colors.accentCrimson
        else -> colors.accentCyan
    }
    return when (protocol.lowercase()) {
        "vless" -> Color(0xFFA78BFA) // Violet
        "vmess" -> Color(0xFF00E5FF) // Electric Cyan
        "trojan" -> Color(0xFF10E79D) // Emerald
        "shadowsocks", "ss" -> Color(0xFFFFB300) // Amber
        "hysteria", "hysteria2", "hy2" -> Color(0xFFFF3366) // Neon Crimson
        "tuic" -> Color(0xFF38BDF8) // Sky Blue
        "wireguard", "wg" -> Color(0xFF34D399) // Mint
        else -> Color(0xFF00F0FF)
    }
}

val LocalAppThemeColors = compositionLocalOf { DarkThemeColors }

/** Filled controls choose their own foreground; secondary/error fills can differ from primary. */
internal fun contentColorOn(fill: Color): Color {
    val dark = Color(0xFF071018)
    val lightness = fill.luminance()
    if ((lightness + 0.05f) / (dark.luminance() + 0.05f) >= 4.5f) return dark
    return if (1.05f / (lightness + 0.05f) >= 4.5f) Color.White else Color.Black
}
