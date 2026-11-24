package com.danieldk.splatevpn.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danieldk.splatevpn.ui.theme.*

@Composable
internal fun GlassSettingsCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalAppThemeColors.current
    CompositionLocalProvider(LocalContentColor provides c.textPrimary,
        LocalTextStyle provides MaterialTheme.typography.bodyMedium) {
        Column(modifier.fillMaxWidth().liquidGlass(cornerRadius = 20, innerSheen = true).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}

@Composable
internal fun GlassSettingsAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val c = LocalAppThemeColors.current
    Box(Modifier.fillMaxWidth().defaultMinSize(minHeight = 46.dp)
        .liquidGlass(cornerRadius = 12, accentBorder = if (enabled) c.accentCyan else c.borderSubtle, innerSheen = true)
        .tactileClickable(enabled = enabled, targetScale = .97f, onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (enabled) c.accentCyan else c.textTertiary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun GlassSettingsHelp(text: String) {
    val c = LocalAppThemeColors.current
    val app = LocalAppStrings.current
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).tactileClickable { expanded = !expanded }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(LocalAppResources.current.getString(com.danieldk.splatevpn.R.string.help_details), color = c.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Icon(Icons.Default.ExpandMore, null, tint = c.accentCyan, modifier = Modifier.size(18.dp))
        }
        AnimatedVisibility(expanded) { Text(text, color = c.textSecondary, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(bottom = 12.dp)) }
    }
}
