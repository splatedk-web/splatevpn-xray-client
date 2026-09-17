package com.danieldk.splatevpn.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.danieldk.splatevpn.ui.theme.BorderSubtle
import com.danieldk.splatevpn.ui.theme.SurfaceDark

fun Modifier.glassmorphism(
    cornerRadius: Int = 20,
    accentBorder: Color? = null,
    backgroundColor: Color = SurfaceDark.copy(alpha = 0.82f)
): Modifier {
    return this
        .clip(RoundedCornerShape(cornerRadius.dp))
        .background(backgroundColor)
        .border(
            width = 1.dp,
            brush = if (accentBorder != null) {
                Brush.linearGradient(
                    listOf(
                        accentBorder.copy(alpha = 0.85f),
                        accentBorder.copy(alpha = 0.25f)
                    )
                )
            } else {
                Brush.linearGradient(
                    listOf(
                        BorderSubtle.copy(alpha = 0.9f),
                        BorderSubtle.copy(alpha = 0.4f)
                    )
                )
            },
            shape = RoundedCornerShape(cornerRadius.dp)
        )
}
