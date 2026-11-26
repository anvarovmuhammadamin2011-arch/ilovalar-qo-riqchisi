package neth.iecal.curbox.ui.activity

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.BuildConfig
import neth.iecal.curbox.R
import neth.iecal.curbox.blockers.AdminLinkBlocker
import neth.iecal.curbox.data.models.AdminLinkConfig
import neth.iecal.curbox.databinding.ActivityProtectionBinding
import neth.iecal.curbox.databinding.DialogReleaseCodeBinding
import neth.iecal.curbox.services.AppBlockerService
import neth.iecal.curbox.ui.components.AppRowView
import neth.iecal.curbox.ui.components.FixedBrandColors
import neth.iecal.curbox.ui.components.StatusPill
import neth.iecal.curbox.ui.components.withAlpha
import neth.iecal.curbox.utils.AdminLinkClient
import neth.iecal.curbox.utils.AntiUninstallManager
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.DeviceOwnerHelper
import neth.iecal.curbox.utils.PermissionUtils
import neth.iecal.curbox.utils.ServiceProtectionManager
import org.json.JSONObject

/**
 * One place that shows what Curbox needs, what actually works right now, the protection
 * mode Android reports, and the six digit release. Every status here is read from
 * Android or from the admin's computer; nothing is assumed.
 */
class ProtectionActivity : AppCompatActivity(), FixedBrandColors {

    private enum class State { FULL, STANDARD, PARTIAL, OFF, OFFLINE }

    private class Item(
        @DrawableRes val icon: Int,
        val title: String,
        val summary: String,
        val explanation: String,
        val ready: Boolean,
        val required: Boolean,
        val checking: Boolean = false,
        /** The admin computer row: offline is its own state, not a missing permission. */
        val isAdminLink: Boolean = false,
        @StringRes val fixLabel: Int = R.string.protection_open_settings,
        val fix: (() -> Unit)? = null
    )

    private lateinit var binding: ActivityProtectionBinding
    private lateinit var dataStore: DataStoreManager
    private var config = AdminLinkConfig()
    private var configLoaded = false
    /** Null while the admin's computer is being asked, then whether it answered. */
    private var reachable: Boolean? = null
    private var checkJob: Job? = null

    private var antiUninstall = neth.iecal.curbox.data.models.AntiUninstallConfig()

    /** Android's own "activate device admin" screen; Curbox cannot turn it on by itself. */
    private val deviceAdminRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val verifier = config.releaseVerifier
        if (AntiUninstallManager.isAdminActive(this) && verifier.isNotEmpty()) {
            lifecycleScope.launch {
                // Stricter than before, so it applies right away.
                dataStore.updateAntiUninstallConfig {
                    it.copy(
                        isEnabled = true, mode = neth.iecal.curbox.data.models.AntiUninstallMode.PASSWORD,
                        passwordHash = verifier, adminManaged = true, unlockRequestedAtMs = 0L
                    )
                }
            }
        }
        render()
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityProtectionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.protectionRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right)
            binding.scroll.updatePadding(bottom = bars.bottom)
            insets
        }
        dataStore = DataStoreManager(this)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnCheck.setOnClickListener { checkAdmin() }
        binding.btnModeHelp.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.protection_mode_full_title)
                .setMessage(getString(R.string.protection_mode_help_body, DeviceOwnerHelper.provisioningCommand(this)))
                .setPositiveButton(R.string.protection_close, null)
                .show()
        }
        binding.btnRelease.setOnClickListener { showReleaseDialog() }
        binding.btnUninstall.setOnClickListener {
            deviceAdminRequest.launch(
                Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, AntiUninstallManager.adminComponent(this))
                    .putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.protection_uninstall_explain))
            )
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                dataStore.settings.map { it.adminLinkConfig to it.antiUninstallConfig2 }.distinctUntilChanged().collect { (link, anti) ->
                    val firstLoad = !configLoaded
                    config = link
                    antiUninstall = anti
                    configLoaded = true
                    if (firstLoad) checkAdmin() else render()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Coming back from Android settings: show what changed there.
        if (configLoaded) render()
    }

    // ------------------------------------------------------------ status

    private fun items(): List<Item> {
        val serviceOn = PermissionUtils.isAccessibilityServiceEnabled(this, AppBlockerService::class.java)
        val openService = { PermissionUtils.openAccessibilityServiceScreen(this, AppBlockerService::class.java) }
        val server = config.serverUrl.removePrefix("http://").removePrefix("https://")
        val adminSummary = when (reachable) {
            null -> getString(R.string.protection_item_admin_checking, server)
            true -> getString(R.string.protection_item_admin_connected, server)
            false -> getString(R.string.protection_item_admin_offline, server)
        }
        return listOf(
            Item(
                R.drawable.ic_curbox_accessibility, getString(R.string.protection_item_service),
                getString(R.string.protection_item_service_short), getString(R.string.protection_item_service_long),
                ready = serviceOn, required = true, fix = openService
            ),
            Item(
                R.drawable.ic_curbox_layers, getString(R.string.protection_item_overlay),
                getString(R.string.protection_item_overlay_short), getString(R.string.protection_item_overlay_long),
                ready = PermissionUtils.hasOverlayPermission(this), required = true,
                fix = { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
            ),
            Item(
                R.drawable.ic_curbox_bar_chart, getString(R.string.protection_item_usage),
                getString(R.string.protection_item_usage_short), getString(R.string.protection_item_usage_long),
                ready = serviceOn, required = true, fix = openService
            ),
            Item(
                R.drawable.ic_curbox_globe, getString(R.string.protection_item_sites),
                getString(R.string.protection_item_sites_short), getString(R.string.protection_item_sites_long),
                ready = serviceOn, required = true, fix = openService
            ),
            Item(
                R.drawable.ic_curbox_apps, getString(R.string.protection_item_apps),
                getString(R.string.protection_item_apps_short), getString(R.string.protection_item_apps_long),
                ready = canSeeApps(), required = true
            ),
            Item(
                R.drawable.ic_notifications_outline, getString(R.string.protection_item_notifications),
                getString(R.string.protection_item_notifications_short), getString(R.string.protection_item_notifications_long),
                ready = NotificationManagerCompat.from(this).areNotificationsEnabled(), required = false,
                fix = ::askForNotifications
            ),
            Item(
                R.drawable.ic_curbox_battery, getString(R.string.protection_item_battery),
                getString(R.string.protection_item_battery_short), getString(R.string.protection_item_battery_long),
                ready = ServiceProtectionManager.isIgnoringBatteryOptimizations(this), required = false,
                fix = { startActivity(ServiceProtectionManager.ignoreBatteryOptimizationsIntent(this)) }
            ),
            Item(
                R.drawable.ic_curbox_wifi, getString(R.string.protection_item_admin), adminSummary,
                getString(R.string.protection_item_admin_long),
                ready = reachable == true, required = true, checking = reachable == null, isAdminLink = true,
                fixLabel = R.string.protection_check_again, fix = ::checkAdmin
            )
        )
    }

    /** Android 11 and newer hide other apps unless Curbox may list them; it is granted at install. */
    private fun canSeeApps(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.QUERY_ALL_PACKAGES) == PackageManager.PERMISSION_GRANTED

    private fun render() {
        val items = items()
        val list = binding.listChecks
        list.removeAllViews()
        val ripple = TypedValue().also { theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true) }
        val padding = resources.getDimensionPixelSize(R.dimen.curbox_space_l)
        for (item in items) {
            val row = AppRowView(this)
            row.setPadding(padding, row.paddingTop, padding, row.paddingBottom)
            row.setBackgroundResource(ripple.resourceId)
            row.title = item.title
            row.subtitle = item.summary
            val tint = when {
                item.ready -> R.color.curbox_success
                item.checking -> R.color.curbox_primary
                item.required -> R.color.curbox_error
                else -> R.color.curbox_warning
            }
            row.setSymbol(item.icon, ContextCompat.getColor(this, tint))
            when {
                item.ready -> row.showPill(StatusPill.State.ALLOWED, getString(R.string.protection_pill_ready))
                item.checking -> row.showPill(StatusPill.State.SCHEDULED, getString(R.string.protection_pill_checking))
                item.required -> row.showPill(StatusPill.State.BLOCKED, getString(R.string.protection_pill_needed))
                else -> row.showPill(StatusPill.State.TIME_LIMITED, getString(R.string.protection_pill_optional))
            }
            row.setOnClickListener { explain(item) }
            list.addView(row)
        }

        // Required items that are missing keep this below 100 percent.
        val ready = items.count { it.ready }
        binding.tvProgress.text = getString(R.string.protection_progress, ready, items.size)
        binding.progress.max = items.size
        binding.progress.setProgressCompat(ready, true)

        val owner = DeviceOwnerHelper.isDeviceOwner(this)
        val serviceOn = items.first().ready
        val state = when {
            !serviceOn -> State.OFF
            reachable == false -> State.OFFLINE
            items.any { it.required && !it.ready && !it.isAdminLink } -> State.PARTIAL
            owner -> State.FULL
            else -> State.STANDARD
        }
        showState(state)

        binding.tvLastSync.text = if (config.lastSyncMs > 0) {
            getString(
                R.string.protection_last_sync,
                DateUtils.getRelativeTimeSpanString(config.lastSyncMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            )
        } else {
            getString(R.string.protection_last_sync_never)
        }

        binding.tvModeTitle.setText(if (owner) R.string.protection_mode_full_title else R.string.protection_mode_standard_title)
        binding.tvModeBody.setText(if (owner) R.string.protection_mode_full_body else R.string.protection_mode_standard_body)
        binding.releaseSection.visibility = if (config.isLinked()) View.VISIBLE else View.GONE
        renderUninstall()
    }

    /** Uninstall protection with the admin's code: only where the flavor has it and a code is set. */
    private fun renderUninstall() {
        val show = BuildConfig.SUPPORTS_ANTI_UNINSTALL && config.isLinked()
        binding.uninstallSection.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        val active = AntiUninstallManager.isAdminActive(this) && antiUninstall.isEnabled
        val (text, canTurnOn) = when {
            active && antiUninstall.adminManaged -> R.string.protection_uninstall_on to false
            active -> R.string.protection_uninstall_own to false
            config.releaseVerifier.isEmpty() -> R.string.protection_uninstall_no_code to false
            else -> R.string.protection_uninstall_off to true
        }
        binding.tvUninstallState.setText(text)
        binding.btnUninstall.visibility = if (canTurnOn) View.VISIBLE else View.GONE
    }

    private fun showState(state: State) {
        val (title, body, color) = when (state) {
            State.FULL -> Triple(R.string.protection_state_full_title, R.string.protection_state_full_body, R.color.curbox_success)
            State.STANDARD -> Triple(R.string.protection_state_standard_title, R.string.protection_state_standard_body, R.color.curbox_success)
            State.PARTIAL -> Triple(R.string.protection_state_partial_title, R.string.protection_state_partial_body, R.color.curbox_warning)
            State.OFF -> Triple(R.string.protection_state_off_title, R.string.protection_state_off_body, R.color.curbox_error)
            State.OFFLINE -> Triple(R.string.protection_state_offline_title, R.string.protection_state_offline_body, R.color.curbox_warning)
        }
        val tint = ContextCompat.getColor(this, color)
        binding.tvStatusTitle.setText(title)
        binding.tvStatusBody.setText(body)
        binding.statusIcon.imageTintList = ColorStateList.valueOf(tint)
        binding.statusIconBg.backgroundTintList = ColorStateList.valueOf(tint.withAlpha(0.14f))
        binding.progress.setIndicatorColor(tint)
    }

    private fun explain(item: Item) {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(item.title)
            .setMessage(item.explanation)
            .setNegativeButton(R.string.protection_close, null)
        val fix = item.fix
        if (!item.ready && fix != null) {
            dialog.setPositiveButton(item.fixLabel) { _, _ -> runCatching { fix() } }
        }
        dialog.show()
    }

    private fun askForNotifications() {
        val canAsk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !PermissionUtils.isNotificationPermissionGiven(this)
        if (canAsk) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
    }

    /** Asks the admin's computer if this phone is still linked. Same request the service makes. */
    private fun checkAdmin() {
        val current = config
        checkJob?.cancel()
        if (!current.isLinked()) {
            reachable = false
            render()
            return
        }
        reachable = null
        render()
        checkJob = lifecycleScope.launch {
            val body = JSONObject().put("deviceName", current.deviceName).put("osVersion", AdminLinkClient.osVersion())
            val response = withContext(Dispatchers.IO) {
                runCatching { AdminLinkClient.post(current.serverUrl, "/api/device/sync", body, current.deviceToken) }.getOrNull()
            }
            when (response?.code) {
                200 -> {
                    reachable = true
                    val codeSet = runCatching { JSONObject(response.body).optBoolean("releaseCodeSet") }.getOrDefault(false)
                    val now = System.currentTimeMillis()
                    dataStore.updateAdminLinkConfig {
                        if (it.deviceToken == current.deviceToken) it.copy(lastSyncMs = now, releaseCodeSet = codeSet) else it
                    }
                }
                401 -> {
                    onReleased()
                    return@launch
                }
                else -> reachable = false
            }
            render()
        }
    }

    // ------------------------------------------------------------ release

    private fun showReleaseDialog() {
        val current = config
        if (!current.isLinked()) return
        val view = DialogReleaseCodeBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.protection_release_title)
            .setView(view.root)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.protection_release_confirm, null)
            .create()
        dialog.setOnShowListener {
            view.inputCode.requestFocus()
            val confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            confirm.setOnClickListener {
                val code = view.inputCode.text?.toString().orEmpty()
                if (!code.matches(Regex("[0-9]{6}"))) {
                    view.layoutCode.error = getString(R.string.protection_release_need_six)
                    return@setOnClickListener
                }
                view.layoutCode.error = null
                confirm.isEnabled = false
                view.progress.visibility = View.VISIBLE
                lifecycleScope.launch {
                    val response = withContext(Dispatchers.IO) {
                        runCatching {
                            AdminLinkClient.post(current.serverUrl, "/api/device/release", JSONObject().put("code", code), current.deviceToken)
                        }.getOrNull()
                    }
                    confirm.isEnabled = true
                    view.progress.visibility = View.INVISIBLE
                    val json = runCatching { JSONObject(response?.body.orEmpty()) }.getOrNull()
                    binding.tvReleaseState.visibility = View.GONE
                    when (response?.code) {
                        // 401: the admin already removed this phone, which is a release too.
                        200, 401 -> {
                            dialog.dismiss()
                            onReleased()
                        }
                        403 -> {
                            view.inputCode.text?.clear()
                            view.layoutCode.error = getString(R.string.protection_release_wrong, json?.optInt("attemptsLeft", 0) ?: 0)
                        }
                        429 -> {
                            val minutes = (((json?.optLong("retryAfter", 300L) ?: 300L) + 59) / 60).toInt()
                            view.layoutCode.error = getString(R.string.protection_release_locked, minutes)
                        }
                        409 -> view.layoutCode.error = getString(R.string.protection_release_no_code)
                        null -> {
                            // Offline: nothing is released until the admin's computer confirms the code.
                            view.layoutCode.error = getString(R.string.protection_release_pending)
                            binding.tvReleaseState.setText(R.string.protection_release_pending)
                            binding.tvReleaseState.visibility = View.VISIBLE
                        }
                        else -> view.layoutCode.error = getString(R.string.protection_release_failed)
                    }
                }
            }
        }
        dialog.show()
    }

    /** The admin's computer confirmed the release (or had already removed this phone). */
    private fun onReleased() {
        lifecycleScope.launch {
            val wasOwner = DeviceOwnerHelper.isDeviceOwner(this@ProtectionActivity)
            withContext(Dispatchers.IO) { DeviceOwnerHelper.releaseManagement(this@ProtectionActivity) }
            val token = config.deviceToken
            dataStore.updateAdminLinkConfig { if (it.deviceToken == token) AdminLinkConfig() else it }
            if (antiUninstall.adminManaged) {
                dataStore.updateAntiUninstallConfig {
                    it.copy(isEnabled = false, adminManaged = false, passwordHash = "", unlockRequestedAtMs = 0L)
                }
                withContext(Dispatchers.IO) { runCatching { AntiUninstallManager.removeProtection(this@ProtectionActivity) } }
            }
            sendBroadcast(Intent(AdminLinkBlocker.INTENT_ACTION_REFRESH_ADMIN_LINK).setPackage(packageName))

            var message = getString(R.string.protection_release_done_body)
            if (wasOwner) message += "\n\n" + getString(R.string.protection_release_done_owner)
            MaterialAlertDialogBuilder(this@ProtectionActivity)
                .setTitle(R.string.protection_release_done_title)
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    startActivity(
                        Intent(this@ProtectionActivity, RoleSelectActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    )
                    finish()
                }
                .show()
        }
    }
}
