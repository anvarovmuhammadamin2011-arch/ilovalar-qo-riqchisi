package neth.iecal.curbox.ui.fragments.user

import android.content.Context
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AdminSchedule
import neth.iecal.curbox.data.models.FocusBlockMode
import neth.iecal.curbox.data.models.Settings
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/** Words for the admin's rules and focus mode, shared by Home and Restrictions. */
object UserRules {

    data class Focus(val name: String, val endsAtMs: Long, val blockSelected: Boolean, val packages: Set<String>)

    /** The running focus session, or null. Read only: users see it, they cannot change it here. */
    fun activeFocus(settings: Settings, now: Long = System.currentTimeMillis()): Focus? {
        val (groupId, endsAt) = settings.activeManualFocusGroupId
        if (groupId == null || endsAt <= now) return null
        val group = settings.manualFocusGroups.firstOrNull { it.groupId == groupId } ?: return null
        return Focus(group.groupName, endsAt, group.blockMode == FocusBlockMode.BLOCK_SELECTED, group.packages)
    }

    /** "Mon to Fri", "Every day", or "Mon, Wed, Fri" in the phone's language. */
    fun daysText(context: Context, days: List<Int>): String {
        val sorted = days.distinct().sorted()
        fun name(d: Int) = DayOfWeek.of(d).getDisplayName(TextStyle.SHORT, Locale.getDefault())
        return when {
            sorted.size == 7 -> context.getString(R.string.user_rules_every_day)
            sorted.size > 2 && sorted.zipWithNext().all { (a, b) -> b == a + 1 } ->
                context.getString(R.string.user_rules_day_range, name(sorted.first()), name(sorted.last()))
            else -> sorted.joinToString(", ") { name(it) }
        }
    }

    fun scheduleText(context: Context, schedule: AdminSchedule): String =
        context.getString(R.string.user_rules_schedule_time, daysText(context, schedule.days), schedule.start, schedule.end)
}
