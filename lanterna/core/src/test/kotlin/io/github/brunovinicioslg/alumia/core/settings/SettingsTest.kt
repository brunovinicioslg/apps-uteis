package io.github.brunovinicioslg.alumia.core.settings

import io.github.brunovinicioslg.alumia.core.detection.ShakeConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsTest {

    @Test
    fun `defaults are already sanitized`() {
        assertEquals(Settings(), Settings().sanitized())
    }

    @Test
    fun `out of range values from storage are clamped`() {
        val broken = Settings(requiredStrokes = 99, autoOffMinutes = 7, lowBatteryPercent = -3, torchLevelPercent = 0)
        val fixed = broken.sanitized()
        assertEquals(ShakeConfig.MAX_STROKES, fixed.requiredStrokes)
        assertEquals(0, fixed.autoOffMinutes)
        assertEquals(0, fixed.lowBatteryPercent)
        assertEquals(1, fixed.torchLevelPercent)
        // The result is always a valid detector configuration.
        fixed.shakeConfig()
    }

    @Test
    fun `every offered option survives sanitizing`() {
        Settings.STROKE_OPTIONS.forEach { assertEquals(it, Settings(requiredStrokes = it).sanitized().requiredStrokes) }
        Settings.AUTO_OFF_OPTIONS.forEach { assertEquals(it, Settings(autoOffMinutes = it).sanitized().autoOffMinutes) }
        Settings.LOW_BATTERY_OPTIONS.forEach { assertEquals(it, Settings(lowBatteryPercent = it).sanitized().lowBatteryPercent) }
    }
}
