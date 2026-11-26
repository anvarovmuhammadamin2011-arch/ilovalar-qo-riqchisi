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
import neth.iecal.curbox.BuildConfig
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.FragmentUserProfileBinding
import neth.iecal.curbox.services.AppBlockerService
import neth.iecal.curbox.ui.activity.ProtectionActivity
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.PermissionUtils
import java.util.Locale

/** Who is signed in on this phone and how it is managed. More settings come in a later part. */
class UserProfileFragment : Fragment() {

    private var _binding: FragmentUserProfileBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentUserProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.rowVersion.subtitle = BuildConfig.VERSION_NAME
        binding.rowPrivacy.setOnClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.user_profile_privacy)
                .setMessage(R.string.user_profile_privacy_body)
                .setPositiveButton(R.string.protection_close, null)
                .show()
        }
        binding.rowSignOut.setOnClickListener { confirmSignOut() }
        // The Protection screen is declared only where the admin link exists.
        if (BuildConfig.SUPPORTS_ADMIN_LINK) {
            binding.rowProtection.setOnClickListener {
                startActivity(Intent(requireContext(), ProtectionActivity::class.java))
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                DataStoreManager(requireContext()).settings
                    .map { it.adminLinkConfig }
                    .distinctUntilChanged()
                    .collect { config ->
                        val b = _binding ?: return@collect
                        b.tvUsername.text = config.username
                        b.tvAvatar.text = config.username.take(1).uppercase(Locale.getDefault())
                        b.rowComputer.subtitle = config.serverUrl.removePrefix("http://")
                        b.rowDevice.subtitle = config.deviceName
                        b.tvLastSync.text = if (config.lastSyncMs > 0) {
                            getString(
                                R.string.protection_last_sync,
                                android.text.format.DateUtils.getRelativeTimeSpanString(config.lastSyncMs)
                            )
                        } else {
                            getString(R.string.protection_last_sync_never)
                        }
                        checkLink(config)
                    }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshProtection()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && isResumed) refreshProtection()
    }

    private fun checkLink(config: neth.iecal.curbox.data.models.AdminLinkConfig) {
        viewLifecycleOwner.lifecycleScope.launch {
            val link = UserData.checkLink(config)
            val b = _binding ?: return@launch
            UserData.showLink(b.profileChip, link, null, config)
        }
    }

    /** Signs out on this phone only: the admin link and every block stay. */
    private fun confirmSignOut() {
        viewLifecycleOwner.lifecycleScope.launch {
            val anti = DataStoreManager(requireContext()).settings.first().antiUninstallConfig2
            var message = getString(R.string.user_profile_sign_out_body)
            if (anti.isEnabled) message += "\n\n" + getString(R.string.user_profile_sign_out_protection)
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.user_profile_sign_out)
                .setMessage(message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.user_profile_sign_out) { _, _ ->
                    startActivity(
                        Intent(requireContext(), neth.iecal.curbox.ui.activity.RoleSelectActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    )
                }
                .show()
        }
    }

    private fun refreshProtection() {
        val on = PermissionUtils.isAccessibilityServiceEnabled(requireContext(), AppBlockerService::class.java)
        binding.rowProtection.subtitle = getString(if (on) R.string.user_home_on else R.string.user_home_off)
        if (BuildConfig.SUPPORTS_ADMIN_LINK) binding.rowProtection.showChevron()
        binding.rowProtection.setSymbol(
            R.drawable.ic_admin_shield,
            ContextCompat.getColor(requireContext(), if (on) R.color.curbox_success else R.color.curbox_error)
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
