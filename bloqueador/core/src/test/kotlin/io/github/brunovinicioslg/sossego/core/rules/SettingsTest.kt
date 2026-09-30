package io.github.brunovinicioslg.sossego.core.rules

import java.time.DayOfWeek
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsTest {

    // 2026-09-28 is a Monday.
    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 9, 28, hour, minute).plusDays(day.toLong())

    private val night = Schedule(enabled = true, startMinute = 22 * 60, endMinute = 7 * 60, mode = Mode.ALLOWLIST)

    @Test
    fun `a night window covers the evening and the next morning`() {
        assertTrue(night.isActive(at(0, 22)))
        assertTrue(night.isActive(at(0, 23, 59)))
        assertTrue(night.isActive(at(1, 3)))
        assertTrue(night.isActive(at(1, 6, 59)))
        assertFalse(night.isActive(at(1, 7)))
        assertFalse(night.isActive(at(1, 12)))
        assertFalse(night.isActive(at(0, 21, 59)))
    }

    @Test
    fun `the morning part belongs to the day the window started`() {
        val fridayNights = night.copy(days = setOf(DayOfWeek.FRIDAY))
        // Friday 2026-10-02 at 23:00 and Saturday 03:00 are in; Friday 03:00 is Thursday's night.
        assertTrue(fridayNights.isActive(at(4, 23)))
        assertTrue(fridayNights.isActive(at(5, 3)))
        assertFalse(fridayNights.isActive(at(4, 3)))
    }

    @Test
    fun `a daytime window stays within the day`() {
        val work = Schedule(enabled = true, startMinute = 9 * 60, endMinute = 18 * 60, days = setOf(DayOfWeek.MONDAY))
        assertTrue(work.isActive(at(0, 9)))
        assertFalse(work.isActive(at(0, 18)))
        assertFalse(work.isActive(at(1, 10))) // Tuesday
    }

    @Test
    fun `equal start and end means the whole day`() {
        val allDay = Schedule(enabled = true, startMinute = 0, endMinute = 0, days = setOf(DayOfWeek.SUNDAY))
        assertTrue(allDay.isActive(at(6, 0)))
        assertTrue(allDay.isActive(at(6, 23, 59)))
        assertFalse(allDay.isActive(at(5, 12)))
    }

    @Test
    fun `a window turned off or with no days never applies`() {
        assertFalse(night.copy(enabled = false).isActive(at(0, 23)))
        assertFalse(night.copy(days = emptySet()).isActive(at(0, 23)))
    }

    @Test
    fun `the schedule overrides the mode, and off overrides everything`() {
        val settings = Settings(mode = Mode.BLOCKLIST, schedule = night)
        assertEquals(Mode.ALLOWLIST, settings.effectiveMode(at(0, 23)))
        assertEquals(Mode.BLOCKLIST, settings.effectiveMode(at(0, 12)))
        assertEquals(Mode.OFF, settings.copy(enabled = false).effectiveMode(at(0, 23)))
    }

    @Test
    fun `stored nonsense is fixed`() {
        val fixed = Settings(mode = Mode.OFF, schedule = Schedule(startMinute = -5, endMinute = 99_999, mode = Mode.OFF)).sanitized()
        assertEquals(Mode.BLOCKLIST, fixed.mode)
        assertEquals(0, fixed.schedule.startMinute)
        assertEquals(Schedule.MINUTES_PER_DAY - 1, fixed.schedule.endMinute)
        assertEquals(Mode.ALLOWLIST, fixed.schedule.mode)
    }
}
