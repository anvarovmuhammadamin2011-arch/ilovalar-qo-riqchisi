package neth.iecal.curbox.ui.fragments.user

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.data.db.AppUsageEntity
import neth.iecal.curbox.data.db.FocusStatsEntity
import neth.iecal.curbox.data.sync.SYNCED_WEB_PACKAGE
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.TimeTools
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Reads the usage the app already records (Room: app usage, website stats, focus
 * sessions) for the Usage and Activity tabs. Nothing here writes or changes the schema.
 */
object UsageRepository {

    enum class Range(val days: Int) { TODAY(1), WEEK(7), MONTH(30) }

    data class UsageSummary(
        val range: Range,
        val totalMs: Long,
        /** Total of the same number of days just before, 0 when there is no data. */
        val previousTotalMs: Long,
        val dailyAverageMs: Long,
        val perApp: List<AppUsage>,
        /** Minutes per bar: per hour for TODAY, per day (oldest first) otherwise. */
        val bars: List<Float>,
        val barLabels: List<String>,
        val labelEvery: Int,
        val averageBar: Float,
        val websites: List<Pair<String, Long>>
    )

    enum class EventKind { APP_USE, FOCUS }

    data class ActivityEvent(
        val kind: EventKind,
        val timeMs: Long,
        val title: String,
        val packageName: String?,
        val icon: Drawable?,
        val usageMs: Long = 0L,
        val launches: Int = 0,
        val focusStatus: Int = FocusStatsEntity.STATUS_COMPLETED
    )

    data class ActivityDay(val date: LocalDate, val events: List<ActivityEvent>)

    /** Smallest use worth a line in the activity history. */
    private const val MIN_ACTIVITY_MS = 60_000L

    /** The tracker's key for a day: TimeTools.getCurrentDate() is the same as daysAgo = 0. */
    private fun dateKey(daysAgo: Long): String =
        if (daysAgo == 0L) TimeTools.getCurrentDate() else TimeTools.getPreviousDate(daysAgo)

    suspend fun loadUsage(context: Context, range: Range): UsageSummary = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(context)
        val days = range.days.toLong()
        val keys = (0 until days).map { dateKey(it) }
        val previousKeys = (days until days * 2).map { dateKey(it) }

        val rows = filtered(context, db.appUsageDao().getForDates(keys))
        val previousRows = filtered(context, db.appUsageDao().getForDates(previousKeys))
        val total = rows.sumOf { it.totalTime }
        val previousTotal = previousRows.sumOf { it.totalTime }

        val pm = context.packageManager
        val perApp = rows.groupBy { it.packageName }
            .map { (pkg, list) -> AppUsage(pkg, label(pm, pkg), icon(pm, pkg), list.sumOf { it.totalTime }) }
            .sortedByDescending { it.timeMs }

        val bars: List<Float>
        val labels: List<String>
        val labelEvery: Int
        if (range == Range.TODAY) {
            val hourly = LongArray(24)
            rows.forEach { row -> parseHourly(row.hourlyUsage).forEachIndexed { h, ms -> hourly[h] += ms } }
            bars = hourly.map { it / 60_000f }
            labels = (0 until 24).map { it.toString() }
            labelEvery = 6
        } else {
            val byKey = rows.groupBy { it.date }.mapValues { (_, list) -> list.sumOf { it.totalTime } }
            // Oldest day first, so the chart reads left to right.
            val ordered = (days - 1 downTo 0).toList()
            bars = ordered.map { (byKey[dateKey(it)] ?: 0L) / 60_000f }
            val today = LocalDate.now()
            labels = ordered.map { ago ->
                val date = today.minusDays(ago)
                if (range == Range.WEEK) date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                else date.dayOfMonth.toString()
            }
            labelEvery = if (range == Range.WEEK) 1 else 5
        }
        val averageBar = if (bars.isEmpty()) 0f else bars.sum() / bars.size

        val websites = db.websiteStatsDao().getStatsForDates(keys)
            .groupBy { it.domain }
            .map { (domain, list) -> domain to list.sumOf { it.totalTime } }
            .filter { it.first.isNotBlank() && it.second > 0 }
            .sortedByDescending { it.second }
            .take(5)

        UsageSummary(
            range = range,
            totalMs = total,
            previousTotalMs = previousTotal,
            dailyAverageMs = total / range.days,
            perApp = perApp,
            bars = bars,
            barLabels = labels,
            labelEvery = labelEvery,
            averageBar = averageBar,
            websites = websites
        )
    }

    /** App use and focus sessions per day, newest day first, newest event first. */
    suspend fun loadActivity(context: Context, daysAgo: LongRange): List<ActivityDay> = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(context)
        val pm = context.packageManager
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val settings = DataStoreManager(context).settings.first()
        val focusNames = settings.manualFocusGroups.associate { it.groupId to it.groupName }

        val keyToDate = daysAgo.associate { dateKey(it) to today.minusDays(it) }
        val usageByDate = filtered(context, db.appUsageDao().getForDates(keyToDate.keys.toList()))
            .filter { it.totalTime >= MIN_ACTIVITY_MS }
            .groupBy { keyToDate.getValue(it.date) }

        val firstDay = today.minusDays(daysAgo.last)
        val rangeStart = firstDay.atStartOfDay(zone).toInstant().toEpochMilli()
        val rangeEnd = today.minusDays(daysAgo.first).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val focusByDate = db.focusStatsDao().getAllSessionsFlow().first()
            .filter { it.startTimeInMillis in rangeStart until rangeEnd }
            .groupBy { java.time.Instant.ofEpochMilli(it.startTimeInMillis).atZone(zone).toLocalDate() }

        daysAgo.map { today.minusDays(it) }.mapNotNull { date ->
            val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val uses = usageByDate[date].orEmpty().map { row ->
                ActivityEvent(
                    kind = EventKind.APP_USE,
                    timeMs = if (row.lastUsed > 0) row.lastUsed else dayStart,
                    title = label(pm, row.packageName),
                    packageName = row.packageName,
                    icon = icon(pm, row.packageName),
                    usageMs = row.totalTime,
                    launches = row.launchCount
                )
            }
            val focus = focusByDate[date].orEmpty().map { session ->
                val end = if (session.status == FocusStatsEntity.STATUS_RUNNING) System.currentTimeMillis()
                else session.actualEndTimeInMillis
                ActivityEvent(
                    kind = EventKind.FOCUS,
                    timeMs = session.startTimeInMillis,
                    title = focusNames[session.groupId].orEmpty(),
                    packageName = null,
                    icon = null,
                    usageMs = (end - session.startTimeInMillis).coerceAtLeast(0L),
                    focusStatus = session.status
                )
            }
            val events = (uses + focus).sortedByDescending { it.timeMs }
            if (events.isEmpty()) null else ActivityDay(date, events)
        }
    }

    fun dayTitle(date: LocalDate): String =
        date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault()))

    private suspend fun filtered(context: Context, rows: List<AppUsageEntity>): List<AppUsageEntity> {
        val ignored = DataStoreManager(context).settings.first().usageTrackerIgnoredApps.toSet()
        return rows.filter {
            it.totalTime > 0 &&
                it.packageName != SYNCED_WEB_PACKAGE &&
                it.packageName != context.packageName &&
                it.packageName !in ignored
        }
    }

    /** Same format the tracker writes: 24 comma separated millisecond values. */
    private fun parseHourly(serialized: String): LongArray {
        val result = LongArray(24)
        if (serialized.isEmpty()) return result
        serialized.split(',').take(24).forEachIndexed { i, part -> result[i] = part.toLongOrNull() ?: 0L }
        return result
    }

    private fun label(pm: PackageManager, pkg: String): String = try {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }

    private fun icon(pm: PackageManager, pkg: String): Drawable? = try {
        pm.getApplicationIcon(pkg)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }
}
