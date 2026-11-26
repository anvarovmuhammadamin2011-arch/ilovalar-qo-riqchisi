package neth.iecal.curbox.ui.fragments.user

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AdminLinkConfig
import neth.iecal.curbox.databinding.FragmentUserHomeBinding
import neth.iecal.curbox.services.AppBlockerService
import neth.iecal.curbox.ui.activity.FragmentActivity
import neth.iecal.curbox.ui.activity.UserMainActivity
import neth.iecal.curbox.ui.components.AppRowView
import neth.iecal.curbox.ui.components.ConnectionChip
import neth.iecal.curbox.ui.components.StatusPill
import neth.iecal.curbox.ui.fragments.installation.AccessibilityGuide
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.PermissionUtils
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The user's dashboard: today's phone time, quick status, top apps and the admin's blocks. */
class UserHomeFragment : Fragment() {

    private companion object {
        const val TOP_APPS = 5
        const val RESTRICTION_ROWS = 4
    }

    private var _binding: FragmentUserHomeBinding? = null
    private val binding get() = _binding!!
    private var config = AdminLinkConfig()
    private var focus: UserRules.Focus? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentUserHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val toRestrictions = View.OnClickListener { openTab(R.id.nav_user_restrictions) }
        binding.tileBlockedApps.setOnClickListener(toRestrictions)
        binding.tileBlockedSites.setOnClickListener(toRestrictions)
        binding.tileSchedules.setOnClickListener(toRestrictions)
        binding.tileProtection.setOnClickListener { onProtectionTapped() }
        binding.sectionTopApps.setOnActionClickListener { openTab(R.id.nav_user_usage) }
        binding.sectionRestrictions.setOnActionClickListener { openTab(R.id.nav_user_restrictions) }

        binding.ring.caption = getString(R.string.user_home_no_limit)
        binding.tvDate.text = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault()))

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                DataStoreManager(requireContext()).settings
                    .map { it.adminLinkConfig to UserRules.activeFocus(it) }
                    .distinctUntilChanged()
                    .collect { (newConfig, newFocus) ->
                        config = newConfig
                        focus = newFocus
                        renderConfig()
                    }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
    }

    // Tabs are hidden, not destroyed, so showing one again counts as coming back.
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && isResumed) refreshAll()
    }

    private fun refreshAll() {
        refreshUsage()
        refreshConnection()
        refreshProtection()
        refreshReset()
    }

    private fun openTab(itemId: Int) {
        (activity as? UserMainActivity)?.openTab(itemId)
    }

    private fun renderConfig() {
        binding.tvGreeting.text = if (config.username.isEmpty()) getString(R.string.user_home_greeting_plain)
            else getString(R.string.user_home_greeting, config.username)
        viewLifecycleOwner.lifecycleScope.launch {
            val apps = UserData.installedApps(requireContext(), config.blockedApps)
            val sites = config.blockedSites
            val b = _binding ?: return@launch
            b.tileBlockedApps.value = apps.size.toString()
            b.tileBlockedSites.value = sites.size.toString()
            // Focus mode wins the tile while it runs; otherwise the schedules on right now.
            val activeSchedules = config.schedules.count { it.isActive(java.time.LocalDateTime.now()) }
            b.tileSchedules.value = when {
                focus != null -> getString(R.string.user_home_focus_on)
                activeSchedules > 0 -> activeSchedules.toString()
                else -> getString(R.string.user_home_none)
            }

            b.restrictionsList.removeAllViews()
            val rows = apps.take(RESTRICTION_ROWS).map { app ->
                AppRowView(requireContext()).apply {
                    setAppIcon(app.icon)
                    title = app.label
                    showPill(StatusPill.State.BLOCKED)
                }
            } + sites.take((RESTRICTION_ROWS - apps.size).coerceAtLeast(0)).map { site ->
                AppRowView(requireContext()).apply {
                    setSymbol(R.drawable.ic_curbox_globe)
                    title = site
                    showPill(StatusPill.State.BLOCKED)
                }
            }
            rows.forEach { b.restrictionsList.addView(it) }
            b.restrictionsEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun refreshUsage() {
        viewLifecycleOwner.lifecycleScope.launch {
            val usage = UserData.todayUsage(requireContext())
            val b = _binding ?: return@launch
            val used = usage.sumOf { it.timeMs }
            b.ring.centerText = UserData.formatDuration(used)
            val limitMs = config.dailyLimitMinutes * 60_000L
            if (limitMs > 0) {
                b.ring.progress = (used.toFloat() / limitMs).coerceIn(0f, 1f)
                b.ring.caption = if (used >= limitMs) {
                    getString(R.string.user_home_limit_reached)
                } else {
                    getString(R.string.user_home_limit_caption, UserData.formatDuration(limitMs - used), UserData.formatDuration(limitMs))
                }
            } else {
                b.ring.progress = null
                b.ring.caption = getString(R.string.user_home_no_limit)
            }

            b.topAppsList.removeAllViews()
            val top = usage.take(TOP_APPS)
            val longest = top.firstOrNull()?.timeMs ?: 0L
            top.forEach { app ->
                b.topAppsList.addView(AppRowView(requireContext()).apply {
                    setAppIcon(app.icon)
                    title = app.label
                    setBar(if (longest > 0) app.timeMs.toFloat() / longest else 0f)
                    showValue(UserData.formatDuration(app.timeMs))
                })
            }
            b.topAppsEmpty.visibility = if (top.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun refreshConnection() {
        binding.connectionChip.setState(ConnectionChip.State.SYNCING, getString(R.string.user_home_checking))
        viewLifecycleOwner.lifecycleScope.launch {
            val current = DataStoreManager(requireContext()).settings.first().adminLinkConfig
            val link = UserData.checkLink(current)
            val b = _binding ?: return@launch
            UserData.showLink(b.connectionChip, link, b.tvSyncNote, current)
        }
    }

    private fun refreshProtection() {
        val on = PermissionUtils.isAccessibilityServiceEnabled(requireContext(), AppBlockerService::class.java)
        binding.tileProtection.value = getString(if (on) R.string.user_home_on else R.string.user_home_off)
        binding.tileProtection.tint = ContextCompat.getColor(
            requireContext(),
            if (on) R.color.curbox_success else R.color.curbox_error
        )
    }

    private fun onProtectionTapped() {
        val on = PermissionUtils.isAccessibilityServiceEnabled(requireContext(), AppBlockerService::class.java)
        if (on) {
            openTab(R.id.nav_user_profile)
        } else {
            startActivity(Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", AccessibilityGuide.FRAGMENT_ID)
            })
        }
    }

    private fun refreshReset() {
        val now = LocalDateTime.now()
        val untilMidnight = Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay()).toMillis()
        binding.rowReset.subtitle = getString(R.string.user_home_reset_in, UserData.formatDuration(untilMidnight))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
