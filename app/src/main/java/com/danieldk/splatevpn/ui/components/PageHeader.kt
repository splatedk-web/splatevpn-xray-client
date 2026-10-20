package com.danieldk.splatevpn.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danieldk.splatevpn.ui.theme.LocalAppThemeColors

/** Shared heading typography and spacing for the main menu pages. */
@Composable
internal fun PageHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    count: String? = null,
    countColor: Color = LocalAppThemeColors.current.accentCyan,
    trailing: @Composable (() -> Unit)? = null
) {
    val colors = LocalAppThemeColors.current
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, Modifier.weight(1f, fill = false), color = colors.textPrimary,
                    fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold)
                if (count != null) {
                    Text(count, color = countColor, fontSize = 12.sp, lineHeight = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.background(countColor.copy(alpha = 0.13f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
            Text(subtitle, color = colors.textSecondary, fontSize = 12.sp, lineHeight = 17.sp)
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}
