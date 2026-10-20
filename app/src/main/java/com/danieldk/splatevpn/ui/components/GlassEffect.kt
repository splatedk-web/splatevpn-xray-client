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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.danieldk.splatevpn.ui.theme.LocalAppThemeColors

/**
 * State-of-the-art Liquid Glass Modifier.
 * Provides authentic translucent glass refraction, directional dual-tone rim highlight,
 * and an inner specular sheen that mimics curved liquid glass surfaces.
 */
@Composable
fun Modifier.liquidGlass(
    cornerRadius: Int = 20,
    accentBorder: Color? = null,
    backgroundColor: Color? = null,
    borderAlpha: Float = 0.75f,
    innerSheen: Boolean = true,
    elevated: Boolean = false,
    borderBrush: Brush? = null,
    specularSheen: Boolean = innerSheen
): Modifier {
    val colors = LocalAppThemeColors.current
    val transparency = LocalGlassTransparency.current
    val opacityScale = if (android.os.Build.VERSION.SDK_INT >= 31) 1f - transparency * .92f else 1f - transparency * .63f

    // Полупрозрачные «стеклянные» поверхности: фон темы (сетка, звёзды, сияние) просвечивает сквозь карточки.
    val originalSurface = backgroundColor ?: if (elevated) colors.glassSurfaceElevated else colors.glassSurface
    val baseSurface = if (backgroundColor == null) originalSurface.copy(alpha = originalSurface.alpha * opacityScale) else originalSurface
    val shape = RoundedCornerShape(cornerRadius.dp)

    val strokeModifier = when {
        borderBrush != null -> Modifier.border(width = 1.dp, brush = borderBrush, shape = shape)
        accentBorder != null -> {
            val gradient = Brush.linearGradient(
                colors = listOf(
                    accentBorder.copy(alpha = borderAlpha),
                    accentBorder.copy(alpha = (borderAlpha * 0.35f).coerceAtLeast(0.12f))
                ),
                start = Offset.Zero,
                end = Offset.Infinite
            )
            Modifier.border(width = 1.dp, brush = gradient, shape = shape)
        }
        else -> {
            // Рамка окрашена в акценты темы: у каждой темы заметно свой «ободок»
            val defaultRimGradient = Brush.linearGradient(
                colors = listOf(
                    lerp(colors.glassBorderLight, colors.accentCyan, 0.24f)
                        .copy(alpha = if (colors.isDark) 0.30f else 0.40f),
                    colors.borderSubtle.copy(alpha = (borderAlpha * 0.45f).coerceAtLeast(0.18f)),
                    colors.accentSecondary.copy(alpha = if (colors.isDark) 0.14f else 0.16f)
                ),
                start = Offset.Zero,
                end = Offset.Infinite
            )
            Modifier.border(width = 1.dp, brush = defaultRimGradient, shape = shape)
        }
    }

    // Цветная «заливка-подсветка» внутри карточки (диагональный градиент акцентов темы)
    val washEnabled = backgroundColor == null
    val washAlpha = ((if (colors.isDark) 0.045f else 0.025f) + if (elevated) 0.02f else 0f) * opacityScale

    val sheenModifier = Modifier.drawWithCache {
        val washBrush = if (washEnabled) {
            Brush.linearGradient(
                colors = listOf(
                    colors.accentCyan.copy(alpha = washAlpha),
                    Color.Transparent,
                    colors.accentSecondary.copy(alpha = washAlpha * 0.65f)
                ),
                start = Offset.Zero,
                end = Offset(size.width, size.height)
            )
        } else null

        val specularBrush = if (specularSheen) {
            Brush.verticalGradient(
                colors = listOf(
                    colors.glassSpecular.copy(alpha = (if (colors.isDark) 0.06f else 0.28f) * opacityScale),
                    colors.glassSpecular.copy(alpha = (if (colors.isDark) 0.03f else 0.07f) * opacityScale),
                    Color.Transparent
                ),
                startY = 0f,
                endY = size.height * 0.50f
            )
        } else null

        val cornerPx = CornerRadius(cornerRadius.dp.toPx())

        onDrawWithContent {
            if (washBrush != null) {
                drawRect(brush = washBrush)
            }
            if (specularBrush != null) {
                drawRoundRect(
                    brush = specularBrush,
                    cornerRadius = cornerPx
                )
            }
            drawContent()
        }
    }

    return this
        .clip(shape)
        .then(if (transparency > .001f && backgroundColor == null) Modifier.sampleGlassBackdrop() else Modifier)
        .background(baseSurface)
        .then(sheenModifier)
        .then(strokeModifier)
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

/**
 * Frosted Glass Modifier designed for floating docks and overlays.
 * Combines high-density translucency, subtle multi-tone frosted gradient,
 * specular edge illumination, and ambient diffusion to ensure optimal readability
 * over scrollable content while maintaining a luxury glassmorphism feel.
 */
@Composable
fun Modifier.frostedGlass(
    cornerRadius: Dp = 24.dp,
    elevation: Dp = 12.dp
): Modifier {
    val colors = LocalAppThemeColors.current
    val transparency = LocalGlassTransparency.current
    val opacityScale = if (android.os.Build.VERSION.SDK_INT >= 31) 1f - transparency * .92f else 1f - transparency * .63f
    val shape = RoundedCornerShape(cornerRadius)

    val frostedGradient = Brush.verticalGradient(
        colors = listOf(
            lerp(colors.surfaceElevated, Color.White, if (colors.isDark) 0.035f else 0.12f)
                .copy(alpha = (if (colors.isDark) 0.90f else 0.93f) * opacityScale),
            colors.surface.copy(alpha = (if (colors.isDark) 0.95f else 0.97f) * opacityScale)
        )
    )

    val rimGradient = Brush.verticalGradient(
        colors = listOf(
            lerp(colors.glassBorderLight, colors.accentCyan, 0.40f)
                .copy(alpha = if (colors.isDark) 0.46f else 0.55f),
            colors.borderSubtle.copy(alpha = if (colors.isDark) 0.45f else 0.35f),
            colors.accentSecondary.copy(alpha = if (colors.isDark) 0.20f else 0.18f)
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
        .border(width = 1.dp, brush = rimGradient, shape = shape)
        .drawWithContent {
            // Specular top highlight line for authentic physical glass refraction
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        colors.glassSpecular.copy(alpha = (if (colors.isDark) 0.16f else 0.28f) * opacityScale),
                        Color.Transparent
                    ),
                    startY = 0f,
                    endY = size.height * 0.45f
                ),
                cornerRadius = CornerRadius(cornerRadius.toPx())
            )
            drawContent()
        }
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
    val isPressed by interactionSource.collectIsPressedAsState()
    val haptic = LocalHapticFeedback.current
    val hapticManager = rememberHapticFeedbackManager()

    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) targetScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "tactile_scale"
    )

    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled
        ) {
            hapticManager.click()
            hapticFeedbackType?.let { haptic.performHapticFeedback(it) }
            onClick()
        }
}
