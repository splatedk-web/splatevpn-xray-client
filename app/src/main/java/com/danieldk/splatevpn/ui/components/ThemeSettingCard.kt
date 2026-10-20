package com.danieldk.splatevpn.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danieldk.splatevpn.ui.theme.LocalAppThemeColors
import com.danieldk.splatevpn.ui.theme.resolveThemeColors

@Composable
fun ThemeSettingCard(
    modifier: Modifier = Modifier,
    label: String,
    themeKey: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val colors = LocalAppThemeColors.current
    val isDark = isSystemInDarkTheme()
    val previewColors = androidx.compose.runtime.remember(themeKey, isDark) { resolveThemeColors(themeKey, isDark) }
    val shape = RoundedCornerShape(16.dp)
    val showArtwork = hasThemeObject(previewColors.backdrop) || themeKey in listOf("pony", "winx")

    val animatedBorderWidth by animateDpAsState(
        targetValue = if (isSelected) 2.dp else 1.dp,
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "theme_card_border_width"
    )

    Column(
        modifier = modifier
            .height(140.dp)
            .clip(shape)
            .background(colors.surface)
            .border(
                width = animatedBorderWidth,
                color = if (isSelected) colors.accentCyan else colors.borderSubtle,
                shape = shape
            )
            .semantics { selected = isSelected }
            .clickable(role = Role.RadioButton, onClick = onClick)
    ) {
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            ThemeBackdropPreview(previewColors, Modifier.fillMaxSize())
            // Show the actual surface, text hierarchy and filled accent in every preview.
            Column(Modifier.fillMaxWidth(if (showArtwork) 0.48f else 0.78f)
                .align(if (showArtwork) Alignment.CenterStart else Alignment.Center)
                .padding(start = if (showArtwork) 6.dp else 0.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(previewColors.glassSurface)
                .border(1.dp, previewColors.borderMedium, RoundedCornerShape(10.dp))
                .padding(9.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(previewColors.accentCyan))
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.width(42.dp).height(4.dp).clip(CircleShape).background(previewColors.textPrimary.copy(alpha = 0.85f)))
                }
                Box(Modifier.fillMaxWidth(0.68f).height(3.dp).clip(CircleShape).background(previewColors.textSecondary.copy(alpha = 0.7f)))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(previewColors.accentSecondary, previewColors.accentViolet, previewColors.accentEmerald).forEach { tone ->
                        Box(Modifier.size(5.dp).clip(CircleShape).background(tone))
                    }
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.width(26.dp).height(9.dp).clip(CircleShape).background(previewColors.accentCyan))
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                color = colors.textPrimary,
                fontSize = 12.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            AnimatedVisibility(
                visible = isSelected,
                enter = fadeIn(tween(200)) + scaleIn(tween(200)),
                exit = fadeOut(tween(150)) + scaleOut(tween(150))
            ) {
                Box(
                    Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(colors.accentCyan),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Check, null, Modifier.size(12.dp), tint = colors.onAccent)
                }
            }
        }
    }
}
