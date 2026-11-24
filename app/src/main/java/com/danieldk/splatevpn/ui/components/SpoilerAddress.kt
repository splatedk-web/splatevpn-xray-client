package com.danieldk.splatevpn.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.danieldk.splatevpn.ui.theme.LocalAppStrings
import com.danieldk.splatevpn.ui.theme.LocalAppThemeColors
import com.danieldk.splatevpn.ui.theme.LocalUiPerformanceSettings
import com.danieldk.splatevpn.ui.theme.localized
import kotlinx.coroutines.delay
import kotlin.random.Random
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.cos

/** Hidden text is absent from both drawing and accessibility until explicitly revealed. */
@Composable
internal fun SpoilerAddress(address: String, modifier: Modifier = Modifier, revealDurationMs: Long = 15_000L,
    tag: String = "serverAddressSpoiler", contentPadding: PaddingValues = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
    onReveal: () -> Unit = {}) {
    val colors = LocalAppThemeColors.current
    val strings = LocalAppStrings.current
    val addressLineHeight = 16.sp
    val addressMinHeight = with(LocalDensity.current) { addressLineHeight.toDp() } +
        contentPadding.calculateTopPadding() + contentPadding.calculateBottomPadding()
    var revealed by remember(address) { mutableStateOf(false) }
    val revealAlpha = animateFloatAsState(if (revealed) 1f else 0f,
        tween(if (LocalUiPerformanceSettings.current.animationsEnabled) 180 else 0), label = "addressReveal")
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var inForeground by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle, address) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) revealed = false
            inForeground = event == Lifecycle.Event.ON_RESUME || lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(revealed, address) {
        if (revealed) { delay(revealDurationMs); revealed = false }
    }
    Box(modifier.heightIn(min = maxOf(32.dp, addressMinHeight)).clip(RoundedCornerShape(7.dp))
        .semantics {
            role = Role.Button
            if (!revealed) contentDescription = strings.localized("Адрес сервера скрыт. Показать на 15 секунд", "Server address hidden. Reveal for 15 seconds")
        }
        .tactileClickable(targetScale = .98f) { revealed = !revealed; if (revealed) onReveal() }
        .testTag(tag)
        .padding(contentPadding), contentAlignment = Alignment.CenterStart) {
        if (revealed) {
            Text(address, color = colors.textSecondary, fontSize = 11.sp, lineHeight = addressLineHeight, fontFamily = FontFamily.Monospace,
                modifier = Modifier.graphicsLayer { alpha = revealAlpha.value },
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else {
            SpoilerDust(colors.textSecondary, animate = inForeground && LocalUiPerformanceSettings.current.animationsEnabled)
        }
    }
}

/** Local displacement in dp, independent of the spoiler's width and height. */
internal data class SpoilerParticleMotion(
    val phaseX: Float, val phaseY: Float, val speedX: Int, val speedY: Int,
    val amplitudeX: Float, val amplitudeY: Float
) {
    fun displacementAt(progress: Float): Offset {
        val angle = progress * 2f * PI.toFloat()
        return Offset(
            amplitudeX * (sin(angle * speedX + phaseX) + .25f * sin(angle * (speedX + 1) + phaseY)),
            amplitudeY * (sin(angle * speedY + phaseY) + .20f * sin(angle * (speedY + 2) + phaseX))
        )
    }
}

private data class SpoilerParticle(val x: Float, val y: Float, val radius: Float, val motion: SpoilerParticleMotion)

/** Draw-only particle motion: positions and opacity vary independently while hidden text stays absent. */
@Composable
private fun SpoilerDust(tint: Color, animate: Boolean) {
    val particles = remember {
        val random = Random(150)
        List(112) { SpoilerParticle(random.nextFloat(), random.nextFloat(), .35f + random.nextFloat() * .55f,
            SpoilerParticleMotion(
                phaseX = random.nextFloat() * 2f * PI.toFloat(),
                phaseY = random.nextFloat() * 2f * PI.toFloat(),
                speedX = 1 + random.nextInt(4), speedY = 1 + random.nextInt(4),
                amplitudeX = .7f + random.nextFloat() * 1.7f,
                amplitudeY = .5f + random.nextFloat() * 1.5f
            )) }
    }
    val progress = if (animate) {
        rememberInfiniteTransition(label = "spoilerDust").animateFloat(0f, 1f,
            infiniteRepeatable(tween(3600, easing = LinearEasing)), label = "spoilerParticleDrift")
    } else rememberUpdatedState(0f)
    Canvas(Modifier.fillMaxWidth().height(15.dp).testTag("spoilerDust")) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        drawRoundRect(tint.copy(alpha = .055f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
        val phase = progress.value
        val a = phase * 2f * PI.toFloat()
        val fadeX = 2.dp.toPx()
        val fadeY = 1.5.dp.toPx()
        val count = (size.width / 1.45.dp.toPx()).toInt().coerceIn(28, particles.size)
        for (i in 0 until count) {
            val particle = particles[i]
            val drift = particle.motion.displacementAt(phase)
            val x = particle.x * size.width + drift.x.dp.toPx()
            val y = particle.y * size.height + drift.y.dp.toPx()
            val edge = (x / fadeX).coerceIn(0f, 1f) * ((size.width - x) / fadeX).coerceIn(0f, 1f) *
                (y / fadeY).coerceIn(0f, 1f) * ((size.height - y) / fadeY).coerceIn(0f, 1f)
            val twinkle = .35f + .65f * (.5f + .5f * cos(a * (1 + i % 3) + particle.motion.phaseY))
            val radius = particle.radius.dp.toPx() * (.78f + twinkle * .30f)
            drawCircle(tint.copy(alpha = (.14f + twinkle * .62f) * edge), radius, Offset(x, y))
        }
    }
}
