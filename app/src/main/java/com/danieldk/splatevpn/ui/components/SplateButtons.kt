package com.danieldk.splatevpn.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.danieldk.splatevpn.ui.theme.LocalAppThemeColors

/** Actions use the same glass material as cards, retaining semantic accent colors. */
internal val SplateActionShape = com.danieldk.splatevpn.ui.theme.SplateShapes.small

@Composable
internal fun SplateButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = SplateActionShape, colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = null, border: BorderStroke? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    interactionSource: MutableInteractionSource? = null, content: @Composable RowScope.() -> Unit
) {
    val interactions = interactionSource ?: remember { MutableInteractionSource() }
    val theme = LocalAppThemeColors.current
    val accent = colors.containerColor.takeIf { it != Color.Unspecified && it.alpha > 0f } ?: theme.accentCyan
    val foreground = if (enabled) accent else theme.textTertiary
    androidx.compose.material3.Button(onClick,
        modifier.tvFocusOutline(enabled).heightIn(min = if (LocalTvControls.current) 56.dp else 44.dp)
            .elasticPressFeedback(interactions, enabled)
            .liquidGlass(shape = shape, accentBorder = foreground, borderAlpha = if (enabled) .65f else .2f,
                elevated = enabled)
            .background(accent.copy(alpha = if (enabled) .10f else .02f)),
        enabled, shape,
        colors.copy(containerColor = Color.Transparent, contentColor = foreground,
            disabledContainerColor = Color.Transparent, disabledContentColor = theme.textTertiary),
        elevation, border, contentPadding, interactions, content)
}

@Composable
internal fun SplateOutlinedButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = SplateActionShape,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(contentColor = LocalAppThemeColors.current.textPrimary),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    interactionSource: MutableInteractionSource? = null, content: @Composable RowScope.() -> Unit
) {
    val interactions = interactionSource ?: remember { MutableInteractionSource() }
    val theme = LocalAppThemeColors.current
    val foreground = if (enabled) colors.contentColor else theme.textTertiary
    androidx.compose.material3.OutlinedButton(onClick,
        modifier.tvFocusOutline(enabled).heightIn(min = if (LocalTvControls.current) 56.dp else 44.dp)
            .elasticPressFeedback(interactions, enabled).liquidGlass(shape = shape,
            accentBorder = if (enabled) theme.borderMedium else theme.borderSubtle,
            borderAlpha = if (enabled) .55f else .2f), enabled,
        shape, colors.copy(containerColor = Color.Transparent, contentColor = foreground,
            disabledContainerColor = Color.Transparent, disabledContentColor = theme.textTertiary),
        elevation, border, contentPadding, interactions, content)
}

@Composable
internal fun SplateTonalButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = SplateActionShape, colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(),
    elevation: ButtonElevation? = null, border: BorderStroke? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    interactionSource: MutableInteractionSource? = null, content: @Composable RowScope.() -> Unit
) = SplateOutlinedButton(onClick, modifier, enabled, shape, colors, elevation, border, contentPadding, interactionSource, content)
