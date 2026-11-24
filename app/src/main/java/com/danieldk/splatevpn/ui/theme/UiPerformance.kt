package com.danieldk.splatevpn.ui.theme

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.MotionDurationScale
import com.danieldk.splatevpn.data.UiPerformanceSettings

val LocalUiPerformanceSettings = compositionLocalOf { UiPerformanceSettings() }

/** Application opt-out composes with Android's accessibility/developer animation scale. */
@OptIn(ExperimentalComposeUiApi::class)
internal class UiMotionDurationScale(enabled: Boolean, systemScale: Float) : MotionDurationScale {
    private var enabled by mutableStateOf(enabled)
    private var systemDurationScale by mutableFloatStateOf(validSystemScale(systemScale))
    override val scaleFactor: Float get() = if (enabled) systemDurationScale else 0f

    fun setAnimationsEnabled(value: Boolean) { enabled = value }
    fun setSystemScale(value: Float) { systemDurationScale = validSystemScale(value) }

    private fun validSystemScale(value: Float) = value.takeIf { it.isFinite() && it >= 0f } ?: 1f
}
