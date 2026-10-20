package com.danieldk.splatevpn.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.danieldk.splatevpn.R
import com.danieldk.splatevpn.ui.theme.AppThemeColors
import com.danieldk.splatevpn.ui.theme.BackdropStyle
import kotlin.math.PI
import kotlin.math.sin

@Composable
internal fun ThemeCharacterArtwork(colors: AppThemeColors, modifier: Modifier = Modifier, phase: () -> Float = { 0f }) {
    val resource = when (colors.backdrop) {
        BackdropStyle.PONY -> R.drawable.theme_pony_characters
        BackdropStyle.BLOOM -> R.drawable.theme_bloom_character
        else -> return
    }
    BoxWithConstraints(modifier) {
        val preview = maxHeight < maxWidth
        val pony = colors.backdrop == BackdropStyle.PONY
        val alignment = if (pony && !preview) Alignment.BottomCenter else Alignment.BottomEnd
        Image(painterResource(resource), null,
            Modifier.align(alignment)
                .padding(bottom = if (preview) 0.dp else 62.dp)
                .width(maxWidth * if (preview || pony) .98f else .83f)
                .height(maxHeight * if (preview) .96f else if (pony) .40f else .48f)
                .graphicsLayer {
                    if (!preview) {
                        translationY = sin(phase() * 2f * PI.toFloat() * 2f) * 4.dp.toPx()
                        rotationZ = sin(phase() * 2f * PI.toFloat()) * .35f
                    }
                },
            contentScale = ContentScale.Fit, alignment = alignment,
            alpha = if (preview) .95f else .82f)
    }
}
