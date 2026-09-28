package io.github.brunovinicioslg.alumia.core.settings

import io.github.brunovinicioslg.alumia.core.detection.Sensitivity
import io.github.brunovinicioslg.alumia.core.detection.ShakeConfig

/** User preferences. Every value is always valid: [sanitized] clamps anything read from storage. */
data class Settings(
    val detectionEnabled: Boolean = true,
    val sensitivity: Sensitivity = Sensitivity.MEDIUM,
    val requiredStrokes: Int = ShakeConfig.DEFAULT_STROKES,
    val workWithScreenOff: Boolean = true,
    val ignoreInPocket: Boolean = true,
    val vibrate: Boolean = true,
    /** 0 = never turn off automatically. */
    val autoOffMinutes: Int = 0,
    /** Turn the light off when the battery drops to this percentage; 0 = never. */
    val lowBatteryPercent: Int = 10,
    /** Brightness for devices that support torch strength levels (Android 13+). */
    val torchLevelPercent: Int = 100,
) {
    fun sanitized(): Settings = copy(
        requiredStrokes = requiredStrokes.coerceIn(ShakeConfig.MIN_STROKES, ShakeConfig.MAX_STROKES),
        autoOffMinutes = autoOffMinutes.takeIf { it in AUTO_OFF_OPTIONS } ?: 0,
        lowBatteryPercent = lowBatteryPercent.takeIf { it in LOW_BATTERY_OPTIONS } ?: 0,
        torchLevelPercent = torchLevelPercent.coerceIn(1, 100),
    )

    fun shakeConfig(): ShakeConfig = ShakeConfig(sensitivity = sensitivity, requiredStrokes = requiredStrokes)

    companion object {
        val STROKE_OPTIONS = listOf(2, 3, 4)
        val AUTO_OFF_OPTIONS = listOf(0, 1, 5, 10, 30)
        val LOW_BATTERY_OPTIONS = listOf(0, 5, 10, 15, 20)
    }
}
