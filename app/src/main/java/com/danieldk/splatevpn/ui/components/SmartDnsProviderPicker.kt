package com.danieldk.splatevpn.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danieldk.splatevpn.data.SmartDnsProviders
import com.danieldk.splatevpn.ui.theme.LocalAppStrings
import com.danieldk.splatevpn.ui.theme.LocalAppThemeColors

@Composable
internal fun SmartDnsProviderPicker(selected: String, onSelect: (String) -> Unit) {
    val strings = LocalAppStrings.current
    val colors = LocalAppThemeColors.current
    var expanded by remember { mutableStateOf(false) }
    val provider = SmartDnsProviders.forAi(selected)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(strings.aiDnsProviderTitle, color = colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Box {
            Row(Modifier.fillMaxWidth().liquidGlass(cornerRadius = 12).clickable { expanded = true }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(provider.label, color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(provider.host, color = colors.textSecondary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Default.ExpandMore, strings.aiDnsProviderTitle, tint = colors.accentCyan)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
                modifier = Modifier.heightIn(max = 360.dp).widthIn(min = 260.dp, max = 360.dp),
                containerColor = colors.surfaceElevated) {
                SmartDnsProviders.all.forEach { item ->
                    DropdownMenuItem(text = {
                        Column {
                            Text(item.label, color = if (item.key == provider.key) colors.accentCyan else colors.textPrimary,
                                fontWeight = if (item.key == provider.key) FontWeight.Bold else FontWeight.Normal)
                            Text(item.host, color = colors.textSecondary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }, onClick = { expanded = false; onSelect(item.key) })
                }
            }
        }
        Text(strings.aiDnsProviderHint, color = colors.textSecondary, fontSize = 10.sp, lineHeight = 14.sp)
    }
}
