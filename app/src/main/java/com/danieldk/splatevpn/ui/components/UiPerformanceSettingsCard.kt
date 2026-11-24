package com.danieldk.splatevpn.ui.components

import com.danieldk.splatevpn.ui.components.SplateSwitch as Switch

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danieldk.splatevpn.data.UiPerformanceSettings
import com.danieldk.splatevpn.ui.theme.LocalAppStrings
import com.danieldk.splatevpn.ui.theme.LocalAppThemeColors
import com.danieldk.splatevpn.ui.theme.localized
import com.danieldk.splatevpn.ui.components.SplateButton as Button
import com.danieldk.splatevpn.ui.components.SplateOutlinedButton as OutlinedButton

@Composable
fun UiPerformanceSettingsCard(
    settings: UiPerformanceSettings,
    glassBlurEnabled: Boolean,
    onAnimationsChanged: (Boolean) -> Unit,
    onBackgroundChanged: (Boolean) -> Unit,
    onHighlightsChanged: (Boolean) -> Unit,
    onLightMode: () -> Unit,
    onRestoreEffects: () -> Unit
) {
    val strings = LocalAppStrings.current
    val colors = LocalAppThemeColors.current
    val lightMode = !settings.animationsEnabled && !settings.decorativeBackgroundEnabled &&
        !settings.glassHighlightsEnabled && !glassBlurEnabled
    val fullEffects = settings == UiPerformanceSettings() && glassBlurEnabled
    Column(Modifier.fillMaxWidth().testTag("performanceSettings"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Speed, null, tint = colors.accentCyan, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(strings.localized("Производительность", "Performance"),
                color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
        Text(strings.localized("Уменьшите количество эффектов для снижения нагрузки на устройство. Настройки применяются сразу.",
            "Reduce visual effects to lower device load. Changes apply immediately."),
            color = colors.textSecondary, fontSize = 12.sp)
        PerformanceToggle(strings.localized("Анимации интерфейса", "Interface animations"),
            strings.localized("Переходы, движение и реакции элементов", "Transitions, movement and animated feedback"),
            settings.animationsEnabled, onAnimationsChanged, "performance:animations")
        PerformanceToggle(strings.localized("Декоративный фон", "Decorative background"),
            strings.localized("Сетка, частицы, иллюстрации и украшения темы", "Theme grids, particles, illustrations and decorations"),
            settings.decorativeBackgroundEnabled, onBackgroundChanged, "performance:background")
        PerformanceToggle(strings.localized("Блики на стекле", "Glass highlights"),
            strings.localized("Декоративные блики и градиенты панелей", "Decorative highlights and panel gradients"),
            settings.glassHighlightsEnabled, onHighlightsChanged, "performance:highlights")
        Button(onClick = onLightMode, enabled = !lightMode,
            modifier = Modifier.fillMaxWidth().testTag("performance:lightMode")) {
            Text(strings.localized("Лёгкий режим", "Lightweight mode"))
        }
        OutlinedButton(onClick = onRestoreEffects, enabled = !fullEffects,
            modifier = Modifier.fillMaxWidth().testTag("performance:restoreEffects")) {
            Text(strings.localized("Включить все эффекты", "Enable all effects"))
        }
        Text(strings.localized("Лёгкий режим также отключает размытие стекла. Его можно настроить отдельно ниже.",
            "Lightweight mode also disables glass blur. You can adjust blur separately below."),
            color = colors.textTertiary, fontSize = 11.sp)
    }
}

@Composable
private fun PerformanceToggle(title: String, subtitle: String, checked: Boolean,
    onCheckedChange: (Boolean) -> Unit, tag: String) {
    val colors = LocalAppThemeColors.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = colors.textSecondary, fontSize = 11.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = Modifier.testTag(tag))
    }
}
