package neth.iecal.curbox.data.models

import java.time.LocalDateTime

/**
 * A weekly time window set by Curbox Admin, in this phone's local time. [days] are
 * 1 (Monday) to 7 (Sunday). An [end] before [start] means it runs past midnight,
 * for example "Sleep" from 22:00 to 07:00.
 */
data class AdminSchedule(
    val id: String = "",
    val name: String = "",
    val days: List<Int> = emptyList(),
    val start: String = "00:00",
    val end: String = "00:00",
    val blockApps: List<String> = emptyList(),
    val blockSites: List<String> = emptyList(),
    val blockAllApps: Boolean = false,
    val enabled: Boolean = true
) {
    fun isActive(at: LocalDateTime): Boolean {
        val from = minuteOfDay(start)
        val to = minuteOfDay(end)
        if (!enabled || from < 0 || to < 0 || from == to) return false
        val day = at.dayOfWeek.value
        val previous = if (day == 1) 7 else day - 1
        val minute = at.hour * 60 + at.minute
        return if (from < to) {
            day in days && minute >= from && minute < to
        } else {
            (day in days && minute >= from) || (previous in days && minute < to)
        }
    }

    private fun minuteOfDay(hhmm: String): Int {
        val parts = hhmm.split(':')
        if (parts.size != 2) return -1
        val h = parts[0].toIntOrNull() ?: return -1
        val m = parts[1].toIntOrNull() ?: return -1
        return if (h in 0..23 && m in 0..59) h * 60 + m else -1
    }
}
