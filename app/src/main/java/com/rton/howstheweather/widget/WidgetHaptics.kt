package com.rton.howstheweather.widget

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

object WidgetHaptics {

    private fun vibrator(context: Context): Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }.getOrNull()

    /** Crisp tap feedback when clicking widget controls (Search bar, Volume bar, etc.). */
    fun performClick(context: Context, view: View? = null) {
        view?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        vibratePredefinedOrOneShot(
            context = context,
            predefinedId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                VibrationEffect.EFFECT_CLICK
            } else {
                -1
            },
            durationMs = 12L,
            amplitude = 150,
        )
    }

    /** Detented "tik-tak" step feedback when scrubbing the volume slider. */
    fun performTick(context: Context, view: View? = null) {
        val performedByView = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            view?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) ?: false
        } else {
            false
        }
        if (!performedByView) {
            vibratePredefinedOrOneShot(
                context = context,
                predefinedId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    VibrationEffect.EFFECT_TICK
                } else {
                    -1
                },
                durationMs = 7L,
                amplitude = 100,
            )
        }
    }

    /** Tactile mechanical button press feedback for the Mute toggle and 0%/100% bounds. */
    fun performButtonPress(context: Context, view: View? = null) {
        view?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        vibratePredefinedOrOneShot(
            context = context,
            predefinedId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                VibrationEffect.EFFECT_HEAVY_CLICK
            } else {
                -1
            },
            durationMs = 18L,
            amplitude = 220,
        )
    }

    private fun vibratePredefinedOrOneShot(
        context: Context,
        predefinedId: Int,
        durationMs: Long,
        amplitude: Int,
    ) {
        runCatching {
            val vib = vibrator(context)?.takeIf { it.hasVibrator() } ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && predefinedId >= 0) {
                vib.vibrate(VibrationEffect.createPredefined(predefinedId))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(durationMs)
            }
        }
    }
}
