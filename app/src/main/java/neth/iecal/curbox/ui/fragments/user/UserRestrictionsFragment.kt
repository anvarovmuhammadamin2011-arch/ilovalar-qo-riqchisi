package neth.iecal.curbox.ui.fragments.user

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AdminLinkConfig
import neth.iecal.curbox.databinding.FragmentUserRestrictionsBinding
import neth.iecal.curbox.ui.components.AppRowView
import neth.iecal.curbox.ui.components.StatusPill
import neth.iecal.curbox.utils.DataStoreManager

/** What the admin blocked on this phone. Read only: the user cannot change it here. */
class UserRestrictionsFragment : Fragment() {

    private var _binding: FragmentUserRestrictionsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentUserRestrictionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                DataStoreManager(requireContext()).settings
                    .map { it.adminLinkConfig }
                    .distinctUntilChanged()
                    .collect { render(it) }
            }
        }
    }

    private suspend fun render(config: AdminLinkConfig) {
        val apps = UserData.installedApps(requireContext(), config.blockedApps)
        val sites = config.blockedSites
        val b = _binding ?: return

        b.tvSummary.text = listOf(
            resources.getQuantityString(R.plurals.user_restrictions_apps_count, apps.size, apps.size),
            resources.getQuantityString(R.plurals.user_restrictions_sites_count, sites.size, sites.size)
        ).joinToString(", ")

        b.appsList.removeAllViews()
        apps.forEach { app ->
            b.appsList.addView(AppRowView(requireContext()).apply {
                setAppIcon(app.icon)
                title = app.label
                subtitle = getString(R.string.user_restrictions_set_by_admin)
                showPill(StatusPill.State.BLOCKED)
            })
        }
        b.appsEmpty.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE

        b.sitesList.removeAllViews()
        sites.forEach { site ->
            b.sitesList.addView(AppRowView(requireContext()).apply {
                setSymbol(R.drawable.ic_curbox_globe)
                title = site
                subtitle = getString(R.string.user_restrictions_set_by_admin)
                showPill(StatusPill.State.BLOCKED)
            })
        }
        b.sitesEmpty.visibility = if (sites.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
