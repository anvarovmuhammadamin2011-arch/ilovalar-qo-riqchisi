package neth.iecal.curbox.ui.fragments.user

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.FragmentUserUsageBinding
import neth.iecal.curbox.ui.components.AppRowView
import neth.iecal.curbox.ui.fragments.user.UsageRepository.Range
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Phone time for today, 7 or 30 days: totals, a bar chart, top apps and websites. */
class UserUsageFragment : Fragment() {

    private companion object {
        const val PIE_SLICES = 5
        /** Smaller changes than this read as "about the same". */
        const val SAME_PERCENT = 5
    }

    private var _binding: FragmentUserUsageBinding? = null
    private val binding get() = _binding!!
    private var range = Range.TODAY
    private var summary: UsageRepository.UsageSummary? = null
    private var pieAnimated = false
    private var loadJob: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentUserUsageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpPie()
        binding.rangeChips.setOnCheckedStateChangeListener { _, checkedIds ->
            range = when (checkedIds.firstOrNull()) {
                R.id.chip_week -> Range.WEEK
                R.id.chip_month -> Range.MONTH
                else -> Range.TODAY
            }
            load()
        }
        binding.barChart.setOnBarSelectedListener { showSelectedBar(it) }
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
            val result = UsageRepository.loadUsage(requireContext(), range)
            if (_binding == null) return@launch
            summary = result
            render(result)
        }
    }

    private fun render(s: UsageRepository.UsageSummary) {
        val b = binding
        b.tvTotalLabel.text = if (s.range == Range.TODAY) getString(R.string.user_usage_total)
        else getString(R.string.user_usage_total_last, s.range.days)
        b.tvTotal.text = UserData.formatDuration(s.totalMs)

        b.tvAverage.visibility = if (s.range == Range.TODAY) View.GONE else View.VISIBLE
        b.tvAverage.text = getString(R.string.user_usage_daily_average, UserData.formatDuration(s.dailyAverageMs))
        renderChange(s)

        b.tvChartTitle.setText(if (s.range == Range.TODAY) R.string.user_usage_chart_hours else R.string.user_usage_chart_days)
        b.tvChartSelected.visibility = View.GONE
        b.barChart.setData(s.bars, s.barLabels, s.labelEvery, selected = -1, average = s.averageBar)

        renderBreakdown(s)
        renderApps(s)
        renderWebsites(s)
    }

    // Only compare when both periods have data; a change from nothing says nothing.
    private fun renderChange(s: UsageRepository.UsageSummary) {
        val tv = binding.tvChange
        if (s.totalMs <= 0 || s.previousTotalMs <= 0) {
            tv.visibility = View.GONE
            return
        }
        tv.visibility = View.VISIBLE
        val percent = (abs(s.totalMs - s.previousTotalMs) * 100.0 / s.previousTotalMs).roundToInt()
        val up = s.totalMs > s.previousTotalMs
        tv.text = when {
            percent < SAME_PERCENT -> getString(R.string.user_usage_same)
            s.range == Range.TODAY ->
                getString(if (up) R.string.user_usage_up_yesterday else R.string.user_usage_down_yesterday, percent)
            else ->
                getString(if (up) R.string.user_usage_up_before else R.string.user_usage_down_before, percent, s.range.days)
        }
    }

    private fun showSelectedBar(index: Int) {
        val s = summary ?: return
        val minutes = s.bars.getOrNull(index) ?: return
        val label = if (s.range == Range.TODAY) {
            getString(R.string.user_usage_hour_label, index)
        } else {
            LocalDate.now().minusDays((s.range.days - 1 - index).toLong())
                .format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()))
        }
        binding.tvChartSelected.visibility = View.VISIBLE
        binding.tvChartSelected.text = getString(
            R.string.user_usage_chart_selected,
            label,
            UserData.formatDuration((minutes * 60_000L).toLong())
        )
    }

    private fun setUpPie() {
        val inter = try {
            ResourcesCompat.getFont(requireContext(), R.font.inter)
        } catch (e: Exception) {
            null
        }
        binding.pieChart.apply {
            description.isEnabled = false
            legend.isEnabled = false
            setDrawEntryLabels(false)
            isRotationEnabled = false
            setTouchEnabled(false)
            setHoleColor(Color.TRANSPARENT)
            holeRadius = 74f
            transparentCircleRadius = 0f
            setDrawCenterText(true)
            setCenterTextColor(ContextCompat.getColor(requireContext(), R.color.curbox_text))
            setCenterTextSize(18f)
            setCenterTextTypeface(Typeface.create(inter ?: Typeface.DEFAULT, Typeface.BOLD))
            setExtraOffsets(0f, 0f, 0f, 0f)
            setNoDataText("")
        }
    }

    private fun sliceColors(): List<Int> = listOf(
        R.color.curbox_primary,
        R.color.curbox_secondary,
        R.color.curbox_success,
        R.color.curbox_warning,
        R.color.curbox_error,
        R.color.curbox_text_secondary
    ).map { ContextCompat.getColor(requireContext(), it) }

    private fun renderBreakdown(s: UsageRepository.UsageSummary) {
        val b = binding
        val visible = s.perApp.isNotEmpty()
        b.sectionBreakdown.visibility = if (visible) View.VISIBLE else View.GONE
        b.breakdownCard.visibility = if (visible) View.VISIBLE else View.GONE
        b.breakdownList.removeAllViews()
        if (!visible) return

        val top = s.perApp.take(PIE_SLICES)
        val otherMs = s.perApp.drop(PIE_SLICES).sumOf { it.timeMs }
        val slices = top.map { it.label to it.timeMs } +
            (if (otherMs > 0) listOf(getString(R.string.user_usage_other) to otherMs) else emptyList())
        val colors = sliceColors()

        val dataSet = PieDataSet(slices.map { PieEntry(it.second.toFloat()) }, "").apply {
            this.colors = slices.indices.map { i -> if (i < top.size) colors[i] else colors.last() }
            setDrawValues(false)
            sliceSpace = 2f
            selectionShift = 0f
        }
        b.pieChart.data = PieData(dataSet)
        b.pieChart.centerText = UserData.formatDuration(s.totalMs)
        if (!pieAnimated) {
            pieAnimated = true
            b.pieChart.animateY(600)
        } else {
            b.pieChart.invalidate()
        }

        slices.forEachIndexed { i, (label, ms) ->
            val color = if (i < top.size) colors[i] else colors.last()
            val percent = if (s.totalMs > 0) (ms * 100.0 / s.totalMs).roundToInt() else 0
            b.breakdownList.addView(AppRowView(requireContext()).apply {
                setSymbol(R.drawable.bg_curbox_circle, color)
                title = label
                subtitle = "$percent%"
                showValue(UserData.formatDuration(ms))
            })
        }
    }

    private fun renderApps(s: UsageRepository.UsageSummary) {
        val b = binding
        b.appsList.removeAllViews()
        val longest = s.perApp.firstOrNull()?.timeMs ?: 0L
        s.perApp.forEach { app ->
            b.appsList.addView(AppRowView(requireContext()).apply {
                setAppIcon(app.icon)
                title = app.label
                setBar(if (longest > 0) app.timeMs.toFloat() / longest else 0f)
                showValue(UserData.formatDuration(app.timeMs))
            })
        }
        b.appsEmpty.visibility = if (s.perApp.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun renderWebsites(s: UsageRepository.UsageSummary) {
        val b = binding
        b.websitesBlock.visibility = if (s.websites.isEmpty()) View.GONE else View.VISIBLE
        b.websitesList.removeAllViews()
        val longest = s.websites.firstOrNull()?.second ?: 0L
        s.websites.forEach { (domain, ms) ->
            b.websitesList.addView(AppRowView(requireContext()).apply {
                setSymbol(R.drawable.ic_curbox_globe)
                title = domain
                setBar(if (longest > 0) ms.toFloat() / longest else 0f)
                showValue(UserData.formatDuration(ms))
            })
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        loadJob?.cancel()
        _binding = null
    }
}
