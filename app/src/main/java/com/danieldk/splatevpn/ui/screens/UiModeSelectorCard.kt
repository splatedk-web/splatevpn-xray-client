package com.danieldk.splatevpn.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danieldk.splatevpn.data.AppUiMode
import com.danieldk.splatevpn.ui.components.SlidingTabStrip
import com.danieldk.splatevpn.ui.components.liquidGlass
import com.danieldk.splatevpn.ui.components.tvFocusOutline
import com.danieldk.splatevpn.ui.theme.LocalAppStrings
import com.danieldk.splatevpn.ui.theme.LocalAppThemeColors
import com.danieldk.splatevpn.ui.theme.localized

@Composable
internal fun UiModeSelectorCard(
    mode: AppUiMode,
    onSelect: (AppUiMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppThemeColors.current
    val strings = LocalAppStrings.current
    val modes = listOf(AppUiMode.SIMPLE, AppUiMode.PRO)
    val selectedIndex = modes.indexOf(mode).coerceAtLeast(0)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .liquidGlass(cornerRadius = 20, innerSheen = true)
            .padding(16.dp)
            .testTag("uiModeSelectorCard"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = strings.localized("Режим интерфейса", "Interface mode"),
            color = colors.textPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )
        SlidingTabStrip(
            selectedIndex = selectedIndex,
            accents = listOf(colors.accentEmerald, colors.accentCyan),
            onSelect = { index -> modes.getOrNull(index)?.let(onSelect) },
            modifier = Modifier.selectableGroup()
        ) { selected ->
            modes.forEachIndexed { index, item ->
                val isSelected = selected == index
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .testTag("uiMode:${item.name}")
                        .tvFocusOutline()
                        .selectable(
                            selected = mode == item,
                            role = Role.Tab
                        ) { onSelect(item) }
                        .padding(horizontal = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (item == AppUiMode.SIMPLE) Icons.Default.AutoAwesome else Icons.Default.Tune,
                        contentDescription = null,
                        tint = if (isSelected) colors.textPrimary else colors.textSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (item == AppUiMode.SIMPLE) {
                            strings.localized("Простой", "Simple")
                        } else {
                            strings.localized("Профи", "Pro")
                        },
                        color = if (isSelected) colors.textPrimary else colors.textSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
        Text(
            text = if (mode.isSimple) {
                strings.localized(
                    "Минимум настроек: подключение, выбор сервера и базовая защита.",
                    "Minimal interface: connect button, server picker, and basic protection."
                )
            } else {
                strings.localized(
                    "Полный доступ: серверы, маршрутизация, Tor, балансировщик, DNS и диагностика.",
                    "Full access: servers, routing rules, Tor, balancer, DNS, and diagnostics."
                )
            },
            color = colors.textSecondary,
            fontSize = 12.sp,
            lineHeight = 17.sp
        )
    }
}
