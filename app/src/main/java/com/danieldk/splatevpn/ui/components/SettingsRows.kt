package com.danieldk.splatevpn.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danieldk.splatevpn.ui.theme.LocalAppThemeColors

/** The radio aligns with the first title line, regardless of the description's height. */
@Composable
internal fun SettingsChoiceRow(title: String, description: String, selected: Boolean,
    modifier: Modifier = Modifier, tag: String = "settingChoice", enabled: Boolean = true, onSelect: () -> Unit) {
    val colors = LocalAppThemeColors.current
    val titleLineHeight = with(LocalDensity.current) { 20.sp.toDp() }
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
        .tvFocusOutline(enabled).selectable(selected, enabled, Role.RadioButton, onClick = onSelect)
        .testTag(tag).padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.width(40.dp).height(titleLineHeight).testTag("$tag:indicator"), contentAlignment = Alignment.Center) {
            SplateRadioButton(selected, onClick = null, enabled = enabled, modifier = Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = colors.textPrimary, style = MaterialTheme.typography.labelLarge,
                lineHeight = 20.sp, modifier = Modifier.testTag("$tag:title"))
            if (description.isNotBlank()) Text(description, color = colors.textSecondary,
                style = MaterialTheme.typography.bodySmall, lineHeight = 17.sp, modifier = Modifier.testTag("$tag:description"))
        }
    }
}

@Composable
internal fun SettingsToggleContent(icon: ImageVector?, title: String, subtitle: String,
    checked: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    val colors = LocalAppThemeColors.current
    Row(modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(12.dp))
        .tvFocusOutline().toggleable(checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) Box(Modifier.size(40.dp)
            .background(colors.accentCyan.copy(alpha = .08f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (checked) colors.accentCyan else colors.textSecondary, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = colors.textPrimary, style = MaterialTheme.typography.titleSmall, lineHeight = 20.sp)
            if (subtitle.isNotBlank()) Text(subtitle, color = colors.textSecondary,
                style = MaterialTheme.typography.bodySmall, lineHeight = 17.sp)
        }
        SplateSwitch(checked, onCheckedChange = null)
    }
}

