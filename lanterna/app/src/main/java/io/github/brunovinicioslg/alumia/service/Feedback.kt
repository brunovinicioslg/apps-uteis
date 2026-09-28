package io.github.brunovinicioslg.alumia.service

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Short vibration patterns that confirm a gesture when the screen is off. */
class Feedback(context: Context) {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        context.getSystemService(Vibrator::class.java)
    }

    fun turnedOn() = play(longArrayOf(0, 40))

    fun turnedOff() = play(longArrayOf(0, 30, 90, 30))

    fun failed() = play(longArrayOf(0, 250))

    private fun play(timings: LongArray) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val effect = VibrationEffect.createWaveform(timings, -1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK))
        } else {
            v.vibrate(effect)
        }
    }
}
