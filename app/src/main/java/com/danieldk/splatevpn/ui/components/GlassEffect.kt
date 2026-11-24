package com.danieldk.splatevpn.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.danieldk.splatevpn.ui.theme.LocalAppThemeColors
import com.danieldk.splatevpn.ui.theme.LocalUiPerformanceSettings

/** Keeps the screen behind a popup out of its content, independently of glass/blur settings. */
@Composable
internal fun Modifier.popupSurface(cornerRadius: Dp): Modifier =
    clip(RoundedCornerShape(cornerRadius))
        .background(LocalAppThemeColors.current.surface.copy(alpha = 1f))

/** Translucent material with a softly rolled edge and directional reflections. */
@Composable
fun Modifier.liquidGlass(
    cornerRadius: Int = 20,
    accentBorder: Color? = null,
    backgroundColor: Color? = null,
    borderAlpha: Float = 0.75f,
    innerSheen: Boolean = true,
    elevated: Boolean = false,
    borderBrush: Brush? = null,
    specularSheen: Boolean = innerSheen,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(cornerRadius.dp)
): Modifier {
    val colors = LocalAppThemeColors.current
    val transparency = LocalGlassTransparency.current
    val opacityScale = if (android.os.Build.VERSION.SDK_INT >= 31) 1f - transparency * .92f else 1f - transparency * .63f

    // Полупрозрачные «стеклянные» поверхности: фон темы (сетка, звёзды, сияние) просвечивает сквозь карточки.
    val originalSurface = backgroundColor ?: if (elevated) colors.glassSurfaceElevated else colors.glassSurface
    val baseSurface = if (backgroundColor == null) originalSurface.copy(alpha = originalSurface.alpha * opacityScale) else originalSurface

    if (!LocalUiPerformanceSettings.current.glassHighlightsEnabled) {
        return this.clip(shape)
            .then(if (transparency > .001f && backgroundColor == null) Modifier.sampleGlassBackdrop() else Modifier)
            .background(baseSurface)
            .border(1.dp, accentBorder?.copy(alpha = borderAlpha) ?: colors.borderSubtle, shape)
    }

    return this
        .clip(shape)
        .then(if (transparency > .001f && backgroundColor == null) Modifier.sampleGlassBackdrop() else Modifier)
        .background(baseSurface)
        .glassLighting(shape = shape,
            light = lerp(Color.White, accentBorder ?: colors.glassSpecular, if (accentBorder == null) .06f else .18f).copy(alpha = 1f),
            strength = (borderAlpha / .75f).coerceIn(0f, 1.35f) * if (elevated) 1.08f else .9f,
            isDark = colors.isDark, sheen = specularSheen)
        .then(if (borderBrush != null) Modifier.border(1.dp, borderBrush, shape) else Modifier)
}

/** Cached paint only: reflections stay visible on clear glass without extra layers or clocks. */
private fun Modifier.glassLighting(
    shape: Shape, light: Color, strength: Float, isDark: Boolean, sheen: Boolean
): Modifier = drawWithCache {
    val hairline = .8.dp.toPx()
    val inset = hairline / 2f
    val outline = shape.createOutline(Size((size.width - inset * 2).coerceAtLeast(0f),
        (size.height - inset * 2).coerceAtLeast(0f)), layoutDirection, this)
    fun reflection(alpha: Float) = light.copy(alpha = (alpha * strength).coerceIn(0f, 1f))
    val rim = Brush.sweepGradient(
        0f to reflection(.10f), .12f to reflection(.34f), .27f to reflection(.12f),
        .46f to reflection(.08f), .62f to reflection(.60f), .75f to reflection(.43f),
        .88f to reflection(.10f), 1f to reflection(.10f),
        center = Offset(size.width * .5f, size.height * .5f))
    val rolledEdge = Brush.linearGradient(listOf(reflection(.10f), reflection(.015f), reflection(.07f)),
        start = Offset.Zero, end = Offset(size.width, size.height))
    val surface = if (sheen) Brush.verticalGradient(
        0f to reflection(if (isDark) .10f else .22f), .24f to reflection(.025f),
        .58f to Color.Transparent, 1f to Color.Black.copy(alpha = if (isDark) .035f else .025f)) else null
    val cornerLight = if (sheen) Brush.radialGradient(
        listOf(reflection(if (isDark) .10f else .14f), Color.Transparent),
        center = Offset(size.width * .12f, -size.height * .32f),
        radius = minOf(size.width * .85f, size.height * 1.7f).coerceAtLeast(1f)) else null
    val spreadStroke = Stroke(7.dp.toPx())
    val rolledStroke = Stroke(3.5.dp.toPx())
    val rimStroke = Stroke(hairline)
    onDrawWithContent {
        // Broad reflections remain beneath labels; only the narrow edge is painted over content.
        surface?.let { drawRect(it) }
        cornerLight?.let { drawRect(it) }
        drawContent()
        translate(inset, inset) {
            // Feather the reflection into the surface instead of outlining it a second time.
            drawOutline(outline, rolledEdge, alpha = .25f, style = spreadStroke)
            drawOutline(outline, rolledEdge, alpha = .55f, style = rolledStroke)
            drawOutline(outline, rim, style = rimStroke)
        }
    }
}

/**
 * Overload for Dp cornerRadius
 */
@Composable
fun Modifier.liquidGlass(
    cornerRadius: Dp,
    accentBorder: Color? = null,
    backgroundColor: Color? = null,
    borderAlpha: Float = 0.75f,
    innerSheen: Boolean = true,
    elevated: Boolean = false,
    borderBrush: Brush? = null,
    specularSheen: Boolean = innerSheen
): Modifier = liquidGlass(
    cornerRadius = cornerRadius.value.toInt(),
    accentBorder = accentBorder,
    backgroundColor = backgroundColor,
    borderAlpha = borderAlpha,
    innerSheen = innerSheen,
    elevated = elevated,
    borderBrush = borderBrush,
    specularSheen = specularSheen
)

/** Floating material shares the cards' lighting, with a stronger neutral reflection. */
@Composable
fun Modifier.frostedGlass(
    cornerRadius: Dp = 24.dp,
    elevation: Dp = 12.dp
): Modifier {
    val colors = LocalAppThemeColors.current
    val transparency = LocalGlassTransparency.current
    val opacityScale = if (android.os.Build.VERSION.SDK_INT >= 31) 1f - transparency * .92f else 1f - transparency * .63f
    val shape = RoundedCornerShape(cornerRadius)

    if (!LocalUiPerformanceSettings.current.glassHighlightsEnabled) {
        return this.clip(shape)
            .then(if (transparency > .001f) Modifier.sampleGlassBackdrop() else Modifier)
            .background(colors.surface.copy(alpha = (if (colors.isDark) .95f else .97f) * opacityScale))
            .border(1.dp, colors.borderSubtle, shape)
    }

    val frostedGradient = Brush.verticalGradient(
        colors = listOf(
            lerp(colors.surfaceElevated, Color.White, if (colors.isDark) 0.035f else 0.12f)
                .copy(alpha = (if (colors.isDark) 0.90f else 0.93f) * opacityScale),
            colors.surface.copy(alpha = (if (colors.isDark) 0.95f else 0.97f) * opacityScale)
        )
    )

    return this
        .shadow(
            elevation = elevation,
            shape = shape,
            ambientColor = colors.backgroundDeep,
            spotColor = colors.backgroundDeep.copy(alpha = if (colors.isDark) 0.70f else 0.40f)
        )
        .clip(shape)
        .then(if (transparency > .001f) Modifier.sampleGlassBackdrop() else Modifier)
        .background(frostedGradient)
        .glassLighting(shape, lerp(Color.White, colors.glassSpecular, .06f).copy(alpha = 1f),
            strength = 1.12f, isDark = colors.isDark, sheen = true)
}

/**
 * Backward-compatible alias for liquidGlass.
 */
@Composable
fun Modifier.glassmorphism(
    cornerRadius: Int = 20,
    accentBorder: Color? = null,
    backgroundColor: Color? = null,
    borderAlpha: Float = 0.75f,
    innerSheen: Boolean = true,
    elevated: Boolean = false
): Modifier = liquidGlass(
    cornerRadius = cornerRadius,
    accentBorder = accentBorder,
    backgroundColor = backgroundColor,
    borderAlpha = borderAlpha,
    innerSheen = innerSheen,
    elevated = elevated
)

/**
 * Combined Liquid Glass with tactile spring press bounce and haptics.
 */
@Composable
fun Modifier.liquidGlassClickable(
    cornerRadius: Int = 20,
    accentBorder: Color? = null,
    backgroundColor: Color? = null,
    targetScale: Float = 0.97f,
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier =
    this
        .liquidGlass(
            cornerRadius = cornerRadius,
            accentBorder = accentBorder,
            backgroundColor = backgroundColor
        )
        .tactileClickable(
            targetScale = targetScale,
            enabled = enabled,
            onClick = onClick
        )

/**
 * Adds spring-based tactile press bounce and subtle haptic feedback to clickable components.
 */
@Composable
fun Modifier.tactileClickable(
    targetScale: Float = 0.96f,
    enabled: Boolean = true,
    hapticFeedbackType: HapticFeedbackType? = null,
    onClick: () -> Unit
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current
    val hapticManager = rememberHapticFeedbackManager()

    return this
        .tvFocusOutline(enabled)
        .elasticPressFeedback(interactionSource, enabled, targetScale.coerceIn(.94f, 1f))
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled
        ) {
            if (hapticFeedbackType != null) haptic.performHapticFeedback(hapticFeedbackType)
            else hapticManager.click()
            onClick()
        }
}
