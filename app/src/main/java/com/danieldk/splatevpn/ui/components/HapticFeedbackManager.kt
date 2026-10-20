package com.danieldk.splatevpn.ui.components

import android.content.Context
import android.os.Build
import android.os.CombinedVibration
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * State-of-the-art Haptic Feedback Engine.
 * Specifically tuned for high-precision linear resonant actuators (O-Haptics on OnePlus / Oppo,
 * Taptic on Pixel/Samsung), with graceful backward compatibility for all Android devices.
 */
class HapticFeedbackManager(private val context: Context) {

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    /**
     * Crisp mechanical click — ideal for buttons, chips, and cards.
     */
    fun click() {
        if (vibrator?.hasVibrator() != true) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(12, 120))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(12)
            }
        } catch (e: Exception) {
            // Ignore in silent mode or restricted permissions
        }
    }

    /**
     * Ultra-subtle crisp tick — ideal for switches, sliders, and micro-interactions.
     */
    fun tick() {
        if (vibrator?.hasVibrator() != true) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(8, 70))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(8)
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    /**
     * Double heartbeat pulse — fired when VPN successfully connects or verifies health.
     */
    fun success() {
        if (vibrator?.hasVibrator() != true) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val timings = longArrayOf(0, 16, 45, 24)
                val amplitudes = intArrayOf(0, 140, 0, 200)
                vibrator?.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(longArrayOf(0, 20, 50, 25), -1)
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    /**
     * Heavy tactical confirmation — fired on disconnect, kill switch or critical toggles.
     */
    fun heavy() {
        if (vibrator?.hasVibrator() != true) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(28, 220))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(30)
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    /**
     * Subtle pulse on Dynamic Island expansion / collapse.
     */
    fun pop() {
        if (vibrator?.hasVibrator() != true) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(18, 160))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(20)
            }
        } catch (e: Exception) {
            // Ignore
        }
    }
}

@Composable
fun rememberHapticFeedbackManager(): HapticFeedbackManager {
    val context = LocalContext.current
    return remember(context) { HapticFeedbackManager(context) }
}
