package neth.iecal.curbox.data.models

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class AdminScheduleTest {

    // 2026-11-23 is a Monday.
    private fun at(day: Int, hour: Int, minute: Int) = LocalDateTime.of(2026, 11, 22 + day, hour, minute)

    private val study = AdminSchedule(name = "Study", days = listOf(1, 2, 3, 4, 5), start = "08:00", end = "14:00")
    private val sleep = AdminSchedule(name = "Sleep", days = listOf(5), start = "22:00", end = "07:00")

    @Test
    fun sameDayWindowIncludesStartAndExcludesEnd() {
        assertTrue(study.isActive(at(1, 8, 0)))
        assertTrue(study.isActive(at(5, 13, 59)))
        assertFalse(study.isActive(at(1, 7, 59)))
        assertFalse(study.isActive(at(1, 14, 0)))
        assertFalse(study.isActive(at(6, 10, 0)))
    }

    @Test
    fun overnightWindowRunsIntoTheNextDay() {
        assertTrue(sleep.isActive(at(5, 23, 30)))
        // Saturday morning belongs to Friday night's schedule.
        assertTrue(sleep.isActive(at(6, 6, 59)))
        assertFalse(sleep.isActive(at(6, 7, 0)))
        assertFalse(sleep.isActive(at(6, 23, 0)))
        assertFalse(sleep.isActive(at(5, 6, 0)))
    }

    @Test
    fun sundayNightRunsIntoMonday() {
        val sunday = AdminSchedule(days = listOf(7), start = "21:00", end = "06:00")
        assertTrue(sunday.isActive(at(1, 5, 0)))
        assertFalse(sunday.isActive(at(2, 5, 0)))
    }

    @Test
    fun disabledOrBrokenSchedulesNeverRun() {
        assertFalse(study.copy(enabled = false).isActive(at(1, 9, 0)))
        assertFalse(study.copy(start = "8am").isActive(at(1, 9, 0)))
        assertFalse(study.copy(end = "08:00").isActive(at(1, 8, 0)))
    }
}
