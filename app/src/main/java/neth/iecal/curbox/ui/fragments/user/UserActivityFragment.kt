package neth.iecal.curbox.ui.fragments.user

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.data.db.FocusStatsEntity
import neth.iecal.curbox.databinding.FragmentUserActivityBinding
import neth.iecal.curbox.ui.components.AppRowView
import neth.iecal.curbox.ui.components.SectionHeader
import neth.iecal.curbox.ui.fragments.user.UsageRepository.ActivityEvent
import neth.iecal.curbox.ui.fragments.user.UsageRepository.EventKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * What happened on this phone, per day: app use and focus sessions. It is built from
 * data Curbox already keeps, so admin events (blocks, rule updates, syncs) are not
 * listed; storing those would need a new table, which was declined.
 */
class UserActivityFragment : Fragment() {

    private var _binding: FragmentUserActivityBinding? = null
    private val binding get() = _binding!!
    private var daysAgo: LongRange = 0L..0L
    private var loadJob: Job? = null
    private val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentUserActivityBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.filterChips.setOnCheckedStateChangeListener { _, checkedIds ->
            daysAgo = when (checkedIds.firstOrNull()) {
                R.id.chip_yesterday -> 1L..1L
                R.id.chip_week -> 0L..6L
                else -> 0L..0L
            }
            load()
        }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    // Tabs are hidden, not destroyed, so showing one again counts as coming back.
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && isResumed) load()
    }

    private fun load() {
        loadJob?.cancel()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val days = UsageRepository.loadActivity(requireContext(), daysAgo)
            val b = _binding ?: return@launch
            b.timeline.removeAllViews()
            days.forEach { day -> addDay(b.timeline, day) }
            b.emptyBlock.visibility = if (days.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun addDay(parent: LinearLayout, day: UsageRepository.ActivityDay) {
        val context = requireContext()
        parent.addView(SectionHeader(context).apply {
            title = dayTitle(day.date)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = resources.getDimensionPixelSize(R.dimen.curbox_space_xl) }
        })
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = resources.getDimensionPixelSize(R.dimen.curbox_space_l)
            setPadding(pad, 0, pad, 0)
        }
        day.events.forEach { list.addView(eventRow(it)) }
        parent.addView(MaterialCardView(context).apply { addView(list) })
    }

    private fun dayTitle(date: LocalDate): String {
        val today = LocalDate.now()
        return when (date) {
            today -> getString(R.string.user_range_today)
            today.minusDays(1) -> getString(R.string.user_activity_yesterday)
            else -> UsageRepository.dayTitle(date)
        }
    }

    private fun eventRow(event: ActivityEvent): AppRowView = AppRowView(requireContext()).apply {
        val duration = UserData.formatDuration(event.usageMs)
        when (event.kind) {
            EventKind.APP_USE -> {
                setAppIcon(event.icon)
                title = getString(R.string.user_activity_used, event.title)
                subtitle = resources.getQuantityString(
                    R.plurals.user_activity_used_detail,
                    event.launches,
                    duration,
                    event.launches
                )
            }
            EventKind.FOCUS -> {
                setSymbol(
                    R.drawable.ic_curbox_clock,
                    ContextCompat.getColor(requireContext(), R.color.curbox_success)
                )
                title = if (event.title.isEmpty()) getString(R.string.user_activity_focus)
                else getString(R.string.user_activity_focus_named, event.title)
                subtitle = when (event.focusStatus) {
                    FocusStatsEntity.STATUS_RUNNING -> getString(R.string.user_activity_focus_running)
                    FocusStatsEntity.STATUS_STOPPED_EARLY -> getString(R.string.user_activity_focus_stopped, duration)
                    else -> getString(R.string.user_activity_focus_done, duration)
                }
            }
        }
        showValue(Instant.ofEpochMilli(event.timeMs).atZone(ZoneId.systemDefault()).toLocalTime().format(timeFormat))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        loadJob?.cancel()
        _binding = null
    }
}
