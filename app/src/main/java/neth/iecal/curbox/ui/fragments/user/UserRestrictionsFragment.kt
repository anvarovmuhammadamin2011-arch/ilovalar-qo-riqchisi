package neth.iecal.curbox.ui.fragments.user

import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.util.TypedValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AdminLinkConfig
import neth.iecal.curbox.data.models.AdminSchedule
import neth.iecal.curbox.databinding.FragmentUserRestrictionsBinding
import neth.iecal.curbox.databinding.SheetRestrictionDetailBinding
import neth.iecal.curbox.ui.components.AppRowView
import neth.iecal.curbox.ui.components.StatusPill
import neth.iecal.curbox.utils.DataStoreManager
import java.time.LocalDateTime
import java.util.Date

/**
 * Everything the admin set for this phone: blocked apps, schedules, the daily limit and
 * websites, plus focus mode when it runs. Read only: nothing here can be changed.
 */
class UserRestrictionsFragment : Fragment() {

    private var _binding: FragmentUserRestrictionsBinding? = null
    private val binding get() = _binding!!

    private enum class Status { BLOCKED, SCHEDULED, ALLOWED }

    private data class AppStatus(val app: AppInfo, val status: Status, val schedule: AdminSchedule?)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentUserRestrictionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toggle.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            binding.cardApps.visibility = if (id == R.id.btn_apps) View.VISIBLE else View.GONE
            binding.cardSites.visibility = if (id == R.id.btn_sites) View.VISIBLE else View.GONE
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                DataStoreManager(requireContext()).settings
                    .map { it.adminLinkConfig to UserRules.activeFocus(it) }
                    .distinctUntilChanged()
                    .collect { (config, focus) -> render(config, focus) }
            }
        }
    }

    private suspend fun render(config: AdminLinkConfig, focus: UserRules.Focus?) {
        val context = context ?: return
        val now = LocalDateTime.now()
        val schedules = config.schedules.filter { it.enabled }
        val blockAllSchedule = schedules.firstOrNull { it.blockAllApps }
        val apps = UserData.launcherApps(context).map { app ->
            val schedule = schedules.firstOrNull { app.packageName in it.blockApps } ?: blockAllSchedule
            when {
                app.packageName in config.blockedApps -> AppStatus(app, Status.BLOCKED, null)
                schedule != null -> AppStatus(app, Status.SCHEDULED, schedule)
                else -> AppStatus(app, Status.ALLOWED, null)
            }
        }.sortedWith(compareBy<AppStatus> { it.status.ordinal }.thenBy { it.app.label.lowercase() })
        val b = _binding ?: return

        val blocked = apps.count { it.status == Status.BLOCKED }
        val scheduled = apps.count { it.status == Status.SCHEDULED }
        val parts = mutableListOf<String>()
        if (blocked > 0) parts += resources.getQuantityString(R.plurals.user_restrictions_blocked_count, blocked, blocked)
        if (scheduled > 0) parts += resources.getQuantityString(R.plurals.user_restrictions_scheduled_count, scheduled, scheduled)
        if (config.dailyLimitMinutes > 0) parts += getString(R.string.user_restrictions_limited, limitText(config.dailyLimitMinutes))
        b.tvSummary.text = if (parts.isEmpty()) getString(R.string.user_restrictions_nothing) else parts.joinToString(", ")

        renderFocus(focus)
        renderRules(config, schedules, now)

        b.appsList.removeAllViews()
        val ripple = TypedValue().also { context.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true) }
        val padding = resources.getDimensionPixelSize(R.dimen.curbox_space_l)
        apps.forEach { item ->
            b.appsList.addView(AppRowView(context).apply {
                setPadding(padding, paddingTop, padding, paddingBottom)
                setBackgroundResource(ripple.resourceId)
                setAppIcon(item.app.icon)
                title = item.app.label
                when (item.status) {
                    Status.BLOCKED -> {
                        subtitle = getString(R.string.user_restrictions_set_by_admin)
                        showPill(StatusPill.State.BLOCKED)
                    }
                    Status.SCHEDULED -> {
                        subtitle = item.schedule?.let { UserRules.scheduleText(context, it) }.orEmpty()
                        showPill(StatusPill.State.SCHEDULED)
                    }
                    Status.ALLOWED -> {
                        // Allowed apps stay quiet, so the blocked and scheduled ones stand out.
                        subtitle = ""
                        clearTrailing()
                    }
                }
                setOnClickListener { showDetails(item, config) }
            })
        }
        b.appsEmpty.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE

        val sites = config.blockedSites
        val scheduledSites = schedules.flatMap { s -> s.blockSites.map { it to s } }.filter { it.first !in sites }
        b.sitesList.removeAllViews()
        sites.forEach { site ->
            b.sitesList.addView(AppRowView(context).apply {
                setSymbol(R.drawable.ic_curbox_globe)
                title = site
                subtitle = getString(R.string.user_restrictions_set_by_admin)
                showPill(StatusPill.State.BLOCKED)
            })
        }
        scheduledSites.forEach { (site, schedule) ->
            b.sitesList.addView(AppRowView(context).apply {
                setSymbol(R.drawable.ic_curbox_globe)
                title = site
                subtitle = UserRules.scheduleText(context, schedule)
                showPill(StatusPill.State.SCHEDULED)
            })
        }
        b.sitesEmpty.visibility = if (sites.isEmpty() && scheduledSites.isEmpty()) View.VISIBLE else View.GONE
        b.tvAllowNote.visibility = if (config.allowSites.isEmpty()) View.GONE else View.VISIBLE
        b.tvAllowNote.text = getString(R.string.user_restrictions_allow_only, config.allowSites.joinToString(", "))
    }

    private fun renderFocus(focus: UserRules.Focus?) {
        val b = _binding ?: return
        b.cardFocus.visibility = if (focus == null) View.GONE else View.VISIBLE
        if (focus == null) return
        val ends = DateFormat.getTimeFormat(requireContext()).format(Date(focus.endsAtMs))
        b.tvFocusTitle.text = getString(R.string.user_focus_on, focus.name.ifEmpty { getString(R.string.user_focus_default_name) }, ends)
        val labels = focus.packages.mapNotNull { pkg ->
            runCatching { requireContext().packageManager.getApplicationLabel(requireContext().packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
        }.sorted()
        b.tvFocusBody.text = getString(
            if (focus.blockSelected) R.string.user_focus_blocks else R.string.user_focus_allows,
            labels.joinToString(", ").ifEmpty { getString(R.string.user_home_none) }
        )
    }

    private fun renderRules(config: AdminLinkConfig, schedules: List<AdminSchedule>, now: LocalDateTime) {
        val b = _binding ?: return
        val context = requireContext()
        b.rulesList.removeAllViews()
        if (config.dailyLimitMinutes > 0) {
            b.rulesList.addView(AppRowView(context).apply {
                setSymbol(R.drawable.ic_curbox_clock, ContextCompat.getColor(context, R.color.curbox_warning))
                title = getString(R.string.user_restrictions_daily_limit)
                subtitle = getString(R.string.user_restrictions_daily_limit_detail, limitText(config.dailyLimitMinutes))
                showPill(StatusPill.State.TIME_LIMITED, limitText(config.dailyLimitMinutes))
            })
        }
        schedules.forEach { s ->
            b.rulesList.addView(AppRowView(context).apply {
                setSymbol(R.drawable.ic_curbox_event, ContextCompat.getColor(context, R.color.curbox_secondary))
                title = s.name
                subtitle = UserRules.scheduleText(context, s) + if (s.blockAllApps) "\n" + getString(R.string.user_restrictions_all_apps) else ""
                showPill(StatusPill.State.SCHEDULED, getString(if (s.isActive(now)) R.string.user_restrictions_now else R.string.pill_scheduled))
            })
        }
        b.cardRules.visibility = if (b.rulesList.childCount == 0) View.GONE else View.VISIBLE
    }

    private fun showDetails(item: AppStatus, config: AdminLinkConfig) {
        val context = context ?: return
        val sheet = BottomSheetDialog(context)
        val view = SheetRestrictionDetailBinding.inflate(layoutInflater)
        view.ivIcon.setImageDrawable(item.app.icon)
        view.tvName.text = item.app.label
        when (item.status) {
            Status.BLOCKED -> view.pill.setState(StatusPill.State.BLOCKED)
            Status.SCHEDULED -> view.pill.setState(StatusPill.State.SCHEDULED)
            Status.ALLOWED -> view.pill.setState(StatusPill.State.ALLOWED)
        }
        view.tvReason.setText(
            when (item.status) {
                Status.BLOCKED -> R.string.user_restrictions_reason_blocked
                Status.SCHEDULED -> R.string.user_restrictions_reason_scheduled
                Status.ALLOWED -> R.string.user_restrictions_reason_allowed
            }
        )
        if (config.dailyLimitMinutes > 0) {
            view.tvLimit.visibility = View.VISIBLE
            view.tvLimit.text = getString(R.string.user_restrictions_daily_limit_detail, limitText(config.dailyLimitMinutes))
        }
        item.schedule?.let {
            view.tvSchedule.visibility = View.VISIBLE
            view.tvSchedule.text = getString(R.string.user_restrictions_schedule_detail, it.name, UserRules.scheduleText(context, it))
        }
        sheet.setContentView(view.root)
        sheet.show()
    }

    private fun limitText(minutes: Int): String = UserData.formatDuration(minutes * 60_000L)

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
