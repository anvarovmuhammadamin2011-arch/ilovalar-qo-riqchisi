package neth.iecal.curbox.ui.activity

import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicatorSpec
import com.google.android.material.progressindicator.IndeterminateDrawable
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.BuildConfig
import neth.iecal.curbox.R
import neth.iecal.curbox.blockers.AdminLinkBlocker
import neth.iecal.curbox.data.models.AdminLinkConfig
import neth.iecal.curbox.databinding.ActivityAdminLinkBinding
import neth.iecal.curbox.services.AppBlockerService
import neth.iecal.curbox.ui.components.ConnectionChip
import neth.iecal.curbox.ui.components.FixedBrandColors
import neth.iecal.curbox.utils.AdminLinkClient
import neth.iecal.curbox.utils.AntiUninstallManager
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.PermissionUtils
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/**
 * Signs this phone in to a user account on Curbox for PC.
 *
 * The first sign in goes to the computer and asks for consent before anything
 * is shared. Later sign ins check the password on the phone, so they also work
 * while the computer is off.
 */
class AdminLinkActivity : AppCompatActivity(), FixedBrandColors {

    companion object {
        private const val PREF_REMEMBER_USERNAME = "adminLinkRememberUsername"
        private const val PREF_REMEMBERED_USERNAME = "adminLinkUsername"
        private const val STATUS_CHECK_DELAY_MS = 600L
        private const val STATUS_CHECK_TIMEOUT_MS = 3000
    }

    private lateinit var binding: ActivityAdminLinkBinding
    private val dataStoreManager by lazy { DataStoreManager(applicationContext) }
    private val prefs by lazy { getSharedPreferences(AdminPanelActivity.PREFS, MODE_PRIVATE) }
    private var current = AdminLinkConfig()
    private var returningUser = false
    private var statusJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.SUPPORTS_ADMIN_LINK) {
            finish()
            return
        }
        enableEdgeToEdge()
        binding = ActivityAdminLinkBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.linkRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = maxOf(bars.bottom, ime.bottom)
            )
            insets
        }

        binding.btnConnect.setOnClickListener { onSignInClicked() }
        if (savedInstanceState == null) {
            // The logo and the form ease in, so the screen does not just pop up.
            for ((i, v) in listOf(binding.logoCard, binding.formCard).withIndex()) {
                v.alpha = 0f
                v.translationY = 24f * resources.displayMetrics.density
                v.animate().alpha(1f).translationY(0f).setStartDelay(80L * i).setDuration(320L).start()
            }
        }
        binding.btnOtherServer.setOnClickListener { showAddressField() }
        binding.inputPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                onSignInClicked()
                true
            } else {
                false
            }
        }
        binding.inputAddress.doAfterTextChanged {
            binding.inputLayoutAddress.error = null
            if (!returningUser) checkComputer(STATUS_CHECK_DELAY_MS)
        }
        binding.inputUsername.doAfterTextChanged { binding.inputLayoutUsername.error = null }
        binding.inputPassword.doAfterTextChanged { binding.inputLayoutPassword.error = null }

        lifecycleScope.launch {
            current = dataStoreManager.settings.first().adminLinkConfig
            // A phone linked to the old school computer signs in again on the internet server.
            returningUser = current.isLinked() && current.username.isNotEmpty() && current.passwordHash.isNotEmpty() &&
                current.serverUrl == AdminLinkClient.DEFAULT_SERVER_URL
            if (returningUser) {
                showReturningUser()
            } else if (savedInstanceState == null) {
                // Everyone uses the internet server; an address is typed only after "another server".
                checkComputer(0L)
                if (prefs.getBoolean(PREF_REMEMBER_USERNAME, false)) {
                    binding.checkRemember.isChecked = true
                    binding.inputUsername.setText(prefs.getString(PREF_REMEMBERED_USERNAME, "").orEmpty())
                }
            }
        }
    }

    // Only the password is needed; the address and login come from the saved link.
    private fun showReturningUser() {
        binding.tvTitle.text = getString(R.string.admin_link_welcome_back, current.username)
        binding.tvDesc.setText(R.string.admin_link_desc_again)
        binding.inputLayoutAddress.visibility = View.GONE
        binding.btnOtherServer.visibility = View.GONE
        binding.inputLayoutUsername.visibility = View.GONE
        binding.checkRemember.visibility = View.GONE
        binding.inputPassword.requestFocus()
        checkComputer(0L)
    }

    /**
     * The same id after Curbox is reinstalled, so the admin keeps one entry per phone (with its
     * rules) instead of a new copy each time. ANDROID_ID stays the same for this app on this phone.
     */
    private fun stableDeviceId(): String {
        val androidId = runCatching {
            android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID)
        }.getOrNull()
        return if (androidId.isNullOrBlank() || androidId == "9774d56d682e549c") UUID.randomUUID().toString() else "a-$androidId"
    }

    private fun showAddressField() {
        binding.inputLayoutAddress.visibility = View.VISIBLE
        binding.btnOtherServer.visibility = View.GONE
    }

    private fun serverUrl(): String {
        if (returningUser) return current.serverUrl
        val typed = binding.inputAddress.text?.toString().orEmpty()
        if (binding.inputLayoutAddress.visibility != View.VISIBLE || typed.isBlank()) return AdminLinkClient.DEFAULT_SERVER_URL
        return AdminLinkClient.normalizeServerUrl(typed)
    }

    /** Shows whether Curbox for PC answers at the address, checked a moment after typing stops. */
    private fun checkComputer(delayMs: Long) {
        statusJob?.cancel()
        val url = serverUrl()
        if (url.isEmpty()) {
            binding.connectionChip.visibility = View.GONE
            binding.inputLayoutAddress.helperText = null
            return
        }
        statusJob = lifecycleScope.launch {
            delay(delayMs)
            binding.connectionChip.visibility = View.VISIBLE
            binding.connectionChip.setState(ConnectionChip.State.SYNCING, getString(R.string.admin_link_checking))
            val found = withContext(Dispatchers.IO) {
                runCatching {
                    AdminLinkClient.get(url, "/api/admin/state", STATUS_CHECK_TIMEOUT_MS).code == 200
                }.getOrDefault(false)
            }
            if (found) {
                binding.connectionChip.setState(ConnectionChip.State.CONNECTED, getString(R.string.admin_link_found))
                binding.inputLayoutAddress.helperText = null
            } else {
                binding.connectionChip.setState(ConnectionChip.State.ERROR, getString(R.string.admin_link_not_found))
                binding.inputLayoutAddress.helperText = getString(R.string.admin_link_not_found_help)
            }
        }
    }

    private fun onSignInClicked() {
        val url = serverUrl()
        val username = if (returningUser) {
            current.username
        } else {
            binding.inputUsername.text?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
        }
        val password = binding.inputPassword.text?.toString().orEmpty()

        val missing = mutableListOf<TextInputLayout>()
        if (url.isEmpty()) missing += binding.inputLayoutAddress.withError(R.string.admin_link_bad_address)
        if (username.isEmpty()) missing += binding.inputLayoutUsername.withError(R.string.admin_link_need_username)
        if (password.isEmpty()) missing += binding.inputLayoutPassword.withError(R.string.admin_link_need_password)
        if (missing.isNotEmpty()) {
            missing.first().editText?.requestFocus()
            return
        }

        if (returningUser && AntiUninstallManager.hashPassword(password) == current.passwordHash) {
            openApp()
            return
        }
        if (returningUser) {
            // The admin may have changed the password, so ask the computer.
            signInOnServer(url, username, password)
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.admin_link_consent_title)
            .setMessage(R.string.admin_link_consent_message)
            .setPositiveButton(R.string.admin_link_consent_yes) { _, _ -> signInOnServer(url, username, password) }
            .setNegativeButton(R.string.admin_link_consent_no, null)
            .show()
    }

    private fun TextInputLayout.withError(message: Int): TextInputLayout {
        error = getString(message)
        return this
    }

    private fun signInOnServer(url: String, username: String, password: String) {
        setBusy(true)
        lifecycleScope.launch {
            val deviceId = current.deviceId.ifEmpty { stableDeviceId() }
            val body = JSONObject()
                .put("username", username)
                .put("password", password)
                .put("deviceId", deviceId)
                .put("deviceName", Build.MODEL)
                .put("platform", "android")
                .put("osVersion", AdminLinkClient.osVersion())
            val response = withContext(Dispatchers.IO) {
                runCatching { AdminLinkClient.post(url, "/api/device/login", body) }.getOrNull()
            }
            val token = response
                ?.takeIf { it.code == 200 }
                ?.let { runCatching { JSONObject(it.body).getString("token") }.getOrNull() }

            if (token.isNullOrEmpty()) {
                setBusy(false)
                if (response?.code == 403) {
                    binding.inputLayoutPassword.error = getString(R.string.admin_link_wrong_login)
                    shakeForm()
                } else if (response?.code == 429) {
                    binding.inputLayoutPassword.error = getString(R.string.admin_link_locked)
                } else {
                    // Without an address field on screen, the message goes under the password.
                    val addressShown = binding.inputLayoutAddress.visibility == View.VISIBLE
                    val field = if (returningUser || !addressShown) binding.inputLayoutPassword else binding.inputLayoutAddress
                    field.error = getString(R.string.admin_link_unreachable)
                }
                return@launch
            }

            // With approval on, the admin has to say yes before any rule applies.
            val pending = runCatching { JSONObject(response.body).optBoolean("pending") }.getOrDefault(false)
            if (!returningUser) {
                val remember = binding.checkRemember.isChecked
                prefs.edit()
                    .putBoolean(PREF_REMEMBER_USERNAME, remember)
                    .putString(PREF_REMEMBERED_USERNAME, if (remember) username else "")
                    .apply()
            }

            val keepBlocks = username == current.username
            dataStoreManager.updateAdminLinkConfig { old ->
                AdminLinkConfig(
                    serverUrl = url,
                    deviceId = deviceId,
                    deviceToken = token,
                    deviceName = Build.MODEL,
                    username = username,
                    passwordHash = AntiUninstallManager.hashPassword(password),
                    blockedApps = if (keepBlocks) old.blockedApps else emptyList(),
                    blockedSites = if (keepBlocks) old.blockedSites else emptyList(),
                    pendingApproval = pending
                )
            }
            prefs.edit().putString(AdminPanelActivity.PREF_ADMIN_URL, url).apply()
            sendBroadcast(Intent(AdminLinkBlocker.INTENT_ACTION_REFRESH_ADMIN_LINK).setPackage(packageName))
            Toast.makeText(
                this@AdminLinkActivity,
                if (pending) R.string.admin_link_pending else R.string.admin_link_connected,
                Toast.LENGTH_LONG
            ).show()
            openApp()
        }
    }

    /**
     * Opens the user app. First time users go through Curbox onboarding (it grants the
     * permissions and then opens the user app). Blocks only work once the accessibility
     * service runs, so its guide opens on top when it is off.
     */
    private fun openApp() {
        val clearTask = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        val userMain = Intent(this, UserMainActivity::class.java).addFlags(clearTask)
        when {
            !prefs.getBoolean("isFirstLaunchComplete", false) ->
                startActivity(
                    if (prefs.getBoolean(IntroActivity.PREF_INTRO_SEEN, false)) {
                        ProtectionActivity.setupIntent(this)
                    } else {
                        Intent(this, IntroActivity::class.java)
                    }.addFlags(clearTask)
                )
            // Blocks need the service; the Protection screen explains it and opens the setting.
            !PermissionUtils.isAccessibilityServiceEnabled(this, AppBlockerService::class.java) ->
                startActivities(arrayOf(userMain, Intent(this, ProtectionActivity::class.java)))
            else -> startActivity(userMain)
        }
        finish()
    }

    /** Spinner inside the button while signing in; the button cannot be pressed twice. */
    private fun setBusy(busy: Boolean) {
        val button = binding.btnConnect
        button.isEnabled = !busy
        button.setText(if (busy) R.string.admin_link_signing_in else R.string.admin_link_connect)
        if (busy) {
            val spec = CircularProgressIndicatorSpec(
                this,
                null,
                0,
                com.google.android.material.R.style.Widget_Material3_CircularProgressIndicator_ExtraSmall
            )
            spec.indicatorColors = intArrayOf(button.currentTextColor)
            button.icon = IndeterminateDrawable.createCircularDrawable(this, spec)
            button.iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
        } else {
            button.icon = null
        }
    }

    private fun shakeForm() {
        val step = resources.getDimension(R.dimen.curbox_space_s)
        ObjectAnimator.ofFloat(binding.formCard, View.TRANSLATION_X, 0f, step, -step, step * 0.6f, -step * 0.6f, 0f)
            .setDuration(360L)
            .start()
    }
}
