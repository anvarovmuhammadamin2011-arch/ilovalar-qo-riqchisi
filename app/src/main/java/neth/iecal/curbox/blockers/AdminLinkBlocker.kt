package neth.iecal.curbox.blockers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.telecom.TelecomManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import neth.iecal.curbox.BuildConfig
import neth.iecal.curbox.Constants
import neth.iecal.curbox.CrashLogger
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AdminLinkConfig
import neth.iecal.curbox.data.models.AdminSchedule
import neth.iecal.curbox.services.AppBlockerService
import neth.iecal.curbox.services.BaseBlockingService
import neth.iecal.curbox.trackers.WebsiteObservation
import neth.iecal.curbox.ui.activity.WarningActivity
import neth.iecal.curbox.utils.AdminLinkClient
import neth.iecal.curbox.utils.AdminLinkClient.toStringList
import neth.iecal.curbox.utils.AntiUninstallManager
import neth.iecal.curbox.utils.DeviceOwnerHelper
import neth.iecal.curbox.utils.PermissionUtils
import neth.iecal.curbox.utils.ServiceProtectionManager
import neth.iecal.curbox.utils.getCurrentKeyboardPackageName
import neth.iecal.curbox.utils.getDefaultLauncherPackageName
import neth.iecal.curbox.ui.fragments.user.UsageRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Applies the apps and websites blocked by a linked Curbox Admin, and keeps the
 * admin up to date with this phone's app list.
 *
 * Blocks are cached in [AdminLinkConfig], so they keep working while the admin's
 * computer is unreachable. Removing the phone on the admin side lifts them.
 */
class AdminLinkBlocker : BaseBlocker() {

    companion object {
        const val INTENT_ACTION_REFRESH_ADMIN_LINK = "neth.iecal.curbox.refresh.admin_link"
        private const val TAG = "AdminLink"
        private const val SYNC_INTERVAL_MS = 10_000L
        private const val APPS_REPORT_INTERVAL_MS = 5 * 60_000L
        private const val SITE_BLOCK_COOLDOWN_MS = 1500L
        private const val SYNC_TIME_SAVE_MS = 60_000L
        private const val USAGE_REPORT_INTERVAL_MS = 5 * 60_000L
        private const val RULES_INTERVAL_MS = 30_000L
        private const val LIMIT_CHANNEL = "admin_daily_limit"
        private const val LIMIT_NOTIFICATION_ID = 4711

        /**
         * Still usable while the daily limit or a "block all apps" schedule is on: calls,
         * emergency, alarms, settings, and system screens Android needs.
         */
        private val OPEN_DURING_BLOCK_ALL = setOf(
            "android", "com.android.settings", "com.android.phone", "com.android.server.telecom",
            "com.android.incallui", "com.samsung.android.incallui", "com.google.android.dialer",
            "com.android.dialer", "com.samsung.android.dialer", "com.android.emergency",
            "com.google.android.apps.safetyhub", "com.android.permissioncontroller",
            "com.google.android.permissioncontroller", "com.google.android.deskclock",
            "com.android.deskclock", "com.sec.android.app.clockpackage"
        )

        /** Focus time is stricter: only calls, emergency screens and the clock. */
        val OPEN_DURING_FOCUS = setOf(
            "android", "com.android.phone", "com.android.server.telecom", "com.android.incallui",
            "com.samsung.android.incallui", "com.google.android.dialer", "com.android.dialer",
            "com.samsung.android.dialer", "com.android.emergency", "com.google.android.apps.safetyhub",
            "com.android.permissioncontroller", "com.google.android.permissioncontroller",
            "com.google.android.deskclock", "com.android.deskclock", "com.sec.android.app.clockpackage",
            "com.android.contacts", "com.google.android.contacts", "com.samsung.android.contacts",
            "com.miui.contacts", "com.android.mms"
        )
    }

    private enum class Kind(val extra: String) { APP("app"), SITE("site"), SCHEDULE("schedule"), LIMIT("limit"), FOCUS("focus") }

    private lateinit var service: AppBlockerService
    private lateinit var crashLogger: CrashLogger
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val syncNow = Channel<Unit>(Channel.CONFLATED)
    private val rulesNow = Channel<Unit>(Channel.CONFLATED)
    private var settingsJob: Job? = null
    private var syncJob: Job? = null
    private var rulesJob: Job? = null
    private var receiverRegistered = false

    @Volatile private var config = AdminLinkConfig()
    @Volatile private var blockedApps: Set<String> = emptySet()
    @Volatile private var blockedSites: List<String> = emptyList()
    @Volatile private var essentialPackages: Set<String> = emptySet()
    @Volatile private var openDuringBlockAll: Set<String> = emptySet()
    // Rules that depend on the clock, worked out every 30 seconds by rulesLoop().
    @Volatile private var scheduleApps: Map<String, AdminSchedule> = emptyMap()
    @Volatile private var scheduleBlockAll: AdminSchedule? = null
    @Volatile private var activeSites: List<String> = emptyList()
    @Volatile private var siteSchedules: Map<String, AdminSchedule> = emptyMap()
    @Volatile private var limitReached = false
    @Volatile private var allowSites: List<String> = emptyList()
    @Volatile private var lastAppsReportMs = 0L
    @Volatile private var lastUsageReportMs = 0L
    @Volatile private var lastSiteBlockMs = 0L
    @Volatile private var ownerChecked = false
    // Sync tries that failed in a row; the admin is told when there were many.
    @Volatile private var failedSyncs = 0
    // Message ids already shown (so each opens once) and ids to confirm to the server.
    private val messagePrefs by lazy { service.getSharedPreferences("admin_messages", Context.MODE_PRIVATE) }
    @Volatile private var lastPackage = ""

    fun setup(service: BaseBlockingService) {
        if (service !is AppBlockerService) return
        this.service = service
        crashLogger = CrashLogger(service)

        val essential = mutableSetOf(service.packageName, "com.android.systemui")
        getDefaultLauncherPackageName(service.packageManager)?.let { essential.add(it) }
        getCurrentKeyboardPackageName(service)?.let { essential.add(it) }
        essentialPackages = essential
        val open = essential + OPEN_DURING_BLOCK_ALL
        val dialer = runCatching { service.getSystemService(TelecomManager::class.java)?.defaultDialerPackage }.getOrNull()
        openDuringBlockAll = if (dialer != null) open + dialer else open

        settingsJob?.cancel()
        settingsJob = scope.launchSafely {
            service.dataStoreManager.settings.collectLatest { applyConfig(it.adminLinkConfig) }
        }
        syncJob?.cancel()
        syncJob = scope.launchSafely { syncLoop() }
        rulesJob?.cancel()
        rulesJob = scope.launchSafely { rulesLoop() }
    }

    private fun applyConfig(newConfig: AdminLinkConfig) {
        val tokenChanged = newConfig.deviceToken != config.deviceToken
        config = newConfig
        // Essential apps stay usable so a block can never trap the phone.
        blockedApps = newConfig.blockedApps.toSet() - essentialPackages
        blockedSites = newConfig.blockedSites.map(AdminLinkClient::normalizeSite).filter { it.isNotEmpty() }
        allowSites = newConfig.allowSites.map(AdminLinkClient::normalizeSite).filter { it.isNotEmpty() }
        if (tokenChanged) {
            lastAppsReportMs = 0L
            lastUsageReportMs = 0L
            syncNow.trySend(Unit)
        }
        rulesNow.trySend(Unit)
    }

    /** Cheap check, runs inline in onAccessibilityEvent. */
    fun doAppCheck(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val packageName = event.packageName?.toString() ?: return
        if (packageName == lastPackage) return
        lastPackage = packageName
        checkPackage(packageName)
    }

    /** Shows the admin block screen when [packageName] is blocked right now. */
    private fun checkPackage(packageName: String) {
        if (packageName in essentialPackages) return
        if (config.focusActive() && packageName !in OPEN_DURING_FOCUS && !isDialerApp(packageName)) {
            showBlock(Kind.FOCUS, packageName)
            return
        }
        val open = packageName in openDuringBlockAll
        val allSchedule = scheduleBlockAll
        val schedule = scheduleApps[packageName]
        when {
            limitReached && !open -> showBlock(Kind.LIMIT, packageName)
            packageName in blockedApps -> showBlock(Kind.APP, packageName)
            schedule != null -> showBlock(Kind.SCHEDULE, packageName, schedule = schedule)
            allSchedule != null && !open -> showBlock(Kind.SCHEDULE, packageName, schedule = allSchedule)
        }
    }

    /** Leaves the blocked app and opens WarningActivity in its admin mode. Call on the main thread. */
    private fun showBlock(kind: Kind, packageName: String, site: String? = null, schedule: AdminSchedule? = null) {
        service.pressHome()
        val intent = Intent(service, WarningActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("mode", Constants.WARNING_SCREEN_MODE_ADMIN_BLOCK)
            putExtra("admin_kind", kind.extra)
            putExtra("launch_package", packageName)
            if (site != null) putExtra("admin_site", site)
            if (schedule != null) {
                putExtra("admin_schedule_name", schedule.name)
                putExtra("admin_schedule_end", schedule.end)
            }
            if (kind == Kind.FOCUS) putExtra("admin_focus_until", config.focusUntilMs)
        }
        mainHandler.postDelayed({
            try {
                service.startActivity(intent)
            } catch (e: Exception) {
                crashLogger.logNonFatalError(e)
            }
        }, 100)
    }

    private var dialerPackage: String? = null

    private fun isDialerApp(packageName: String): Boolean {
        val dialer = dialerPackage ?: runCatching {
            (service.getSystemService(Context.TELECOM_SERVICE) as TelecomManager).defaultDialerPackage
        }.getOrNull().orEmpty().also { dialerPackage = it }
        return dialer.isNotEmpty() && packageName == dialer
    }

    /** Runs on the website observation worker, never on the main thread. */
    fun onWebsiteObserved(observation: WebsiteObservation?) {
        observation ?: return
        val sites = activeSites
        val allowed = allowSites
        if (sites.isEmpty() && allowed.isEmpty()) return
        // A blocked site wins over the allow list.
        val site = matchingSite(observation, sites)
            ?: if (allowed.isNotEmpty() && matchingSite(observation, allowed) == null) siteHost(observation) else null
        if (site.isNullOrEmpty()) return

        val now = SystemClock.uptimeMillis()
        if (now - lastSiteBlockMs < SITE_BLOCK_COOLDOWN_MS) return
        lastSiteBlockMs = now
        val schedule = siteSchedules[site]
        mainHandler.post {
            try {
                showBlock(if (schedule != null) Kind.SCHEDULE else Kind.SITE, observation.packageName, site = site, schedule = schedule)
            } catch (e: Exception) {
                crashLogger.logNonFatalError(e)
            }
        }
    }

    private fun siteHost(observation: WebsiteObservation): String {
        val url = AdminLinkClient.normalizeSite(observation.urlIdentifier)
        return AdminLinkClient.normalizeSite(observation.domain).ifEmpty { url.substringBefore('/') }
    }

    private fun matchingSite(observation: WebsiteObservation, sites: List<String>): String? {
        val url = AdminLinkClient.normalizeSite(observation.urlIdentifier)
        val host = AdminLinkClient.normalizeSite(observation.domain).ifEmpty { url.substringBefore('/') }
        return sites.firstOrNull { site ->
            if (site.contains('/')) {
                url.startsWith(site)
            } else {
                host == site || host.endsWith(".$site")
            }
        }
    }

    private suspend fun syncLoop() {
        while (currentCoroutineContext().isActive) {
            val current = config
            if (current.isLinked()) {
                try {
                    syncOnce(current)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    // The computer is off or on another network. Cached blocks keep working.
                    failedSyncs++
                    Log.w(TAG, "Admin not reachable: ${e.message}")
                } catch (t: Throwable) {
                    Log.e(TAG, "Admin sync failed", t)
                    crashLogger.logNonFatalError(Exception(t))
                }
            }
            withTimeoutOrNull(SYNC_INTERVAL_MS) { syncNow.receive() }
        }
    }

    private suspend fun syncOnce(current: AdminLinkConfig) {
        val now = System.currentTimeMillis()
        val includeApps = now - lastAppsReportMs >= APPS_REPORT_INTERVAL_MS
        val body = JSONObject()
            .put("deviceName", current.deviceName)
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("osVersion", AdminLinkClient.osVersion())
            .put("policyVersion", current.policyVersion)
        if (failedSyncs > 0) body.put("failedSyncs", failedSyncs)
        val acks = messagePrefs.getStringSet("ack", emptySet()).orEmpty().toSet()
        if (acks.isNotEmpty()) body.put("seenMessages", JSONArray(acks.toList()))
        if (includeApps) {
            body.put("apps", AdminLinkClient.installedApps(service))
            body.put("protection", protectionReport())
        }
        val includeUsage = now - lastUsageReportMs >= USAGE_REPORT_INTERVAL_MS
        if (includeUsage) body.put("usage", usageReport())

        val response = AdminLinkClient.post(current.serverUrl, "/api/device/sync", body, current.deviceToken)
        when (response.code) {
            200 -> {
                failedSyncs = 0
                if (includeApps) lastAppsReportMs = now
                if (includeUsage) lastUsageReportMs = now
                val json = JSONObject(response.body)
                val apps = json.optJSONArray("blockedApps").toStringList()
                val sites = json.optJSONArray("blockedSites").toStringList()
                val codeSet = json.optBoolean("releaseCodeSet", false)
                val limit = json.optInt("dailyLimitMinutes", 0).coerceIn(0, 1440)
                val warnings = json.optJSONArray("warningMinutes")?.let { arr ->
                    (0 until arr.length()).map { arr.optInt(it) }.filter { it > 0 }
                } ?: listOf(60, 30, 10)
                val schedules = parseSchedules(json.optJSONArray("schedules"))
                val version = json.optLong("policyVersion", current.policyVersion)
                val pending = json.optBoolean("pending", false)
                val verifier = json.optString("releaseVerifier", "")
                val allow = json.optJSONArray("allowSites").toStringList()
                // The server sends time left; small differences between syncs are not a change.
                val focusLeft = json.optLong("focusLeftMs", 0L)
                val focusUntil = if (focusLeft > 0) now + focusLeft else 0L
                val focusChanged = (focusUntil == 0L) != (current.focusUntilMs == 0L) ||
                    kotlin.math.abs(focusUntil - current.focusUntilMs) > 60_000L
                // The sync time is only for the Protection screen, so it is not saved every 10 seconds.
                val saveTime = now - current.lastSyncMs >= SYNC_TIME_SAVE_MS
                val rulesChanged = apps != current.blockedApps || sites != current.blockedSites ||
                    limit != current.dailyLimitMinutes || warnings != current.warningMinutes ||
                    schedules != current.schedules || version != current.policyVersion ||
                    pending != current.pendingApproval || allow != current.allowSites ||
                    verifier != current.releaseVerifier || focusChanged
                if (rulesChanged || codeSet != current.releaseCodeSet || saveTime) {
                    service.dataStoreManager.updateAdminLinkConfig {
                        if (it.deviceToken == current.deviceToken) {
                            it.copy(
                                blockedApps = apps, blockedSites = sites, releaseCodeSet = codeSet, lastSyncMs = now,
                                dailyLimitMinutes = limit, warningMinutes = warnings, schedules = schedules,
                                policyVersion = version, pendingApproval = pending, allowSites = allow,
                                releaseVerifier = verifier,
                                focusUntilMs = if (focusChanged) focusUntil else it.focusUntilMs,
                                lastRulesChangeMs = if (rulesChanged) now else it.lastRulesChangeMs
                            )
                        } else {
                            it
                        }
                    }
                }
                if (verifier.isNotEmpty()) {
                    // Uninstall protection turned on for the admin follows a changed code.
                    service.dataStoreManager.updateAntiUninstallConfig {
                        if (it.adminManaged && it.passwordHash != verifier) it.copy(passwordHash = verifier) else it
                    }
                }
                if (acks.isNotEmpty()) {
                    val left = messagePrefs.getStringSet("ack", emptySet()).orEmpty() - acks
                    messagePrefs.edit().putStringSet("ack", left).apply()
                }
                handleMessages(current, json.optJSONArray("messages"))
                if (!ownerChecked) {
                    // Only a device owner can stop uninstalls; on a normal phone this does nothing.
                    ownerChecked = true
                    DeviceOwnerHelper.setUninstallBlocked(service, true)
                }
            }
            401 -> {
                // The admin removed this phone, so its blocks are lifted too.
                service.dataStoreManager.updateAdminLinkConfig {
                    if (it.deviceToken == current.deviceToken) AdminLinkConfig() else it
                }
                DeviceOwnerHelper.releaseManagement(service)
                releaseAdminUninstallProtection()
                ownerChecked = false
            }
            else -> Log.w(TAG, "Admin answered ${response.code}")
        }
    }

    /**
     * Shows the newest message from the admin on the whole screen, once. Every message in the
     * list is confirmed on the next sync, so the admin sees it was shown.
     */
    private fun handleMessages(current: AdminLinkConfig, array: JSONArray?) {
        if (array == null || array.length() == 0) return
        val messages = (0 until array.length()).mapNotNull { array.optJSONObject(it) }.filter { it.optString("id").isNotEmpty() }
        val ids = messages.map { it.optString("id") }.toSet()
        val shown = messagePrefs.getStringSet("shown", emptySet()).orEmpty()
        val newest = messages.filter { it.optString("id") !in shown }.maxByOrNull { it.optLong("sentAt") }
        val acks = messagePrefs.getStringSet("ack", emptySet()).orEmpty()
        var keep = (shown + ids).toSet()
        if (keep.size > 100) keep = ids
        messagePrefs.edit().putStringSet("shown", keep).putStringSet("ack", acks + ids).apply()
        newest ?: return

        val id = newest.optString("id")
        var imagePath = ""
        if (newest.optBoolean("image")) {
            val file = java.io.File(service.filesDir, "admin_message_$id.img")
            val saved = runCatching {
                AdminLinkClient.download(current.serverUrl, "/api/device/message-image?id=$id", current.deviceToken, file)
            }.getOrDefault(false)
            if (saved) imagePath = file.absolutePath
        }
        // Only the picture on screen now is kept.
        service.filesDir.listFiles { f -> f.name.startsWith("admin_message_") && f.absolutePath != imagePath }?.forEach { it.delete() }

        val intent = Intent(service, WarningActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("mode", Constants.WARNING_SCREEN_MODE_ADMIN_BLOCK)
            putExtra("admin_kind", "message")
            putExtra("message_text", newest.optString("text"))
            putExtra("message_image", imagePath)
            putExtra("message_sent_at", newest.optLong("sentAt"))
        }
        mainHandler.post {
            try {
                service.startActivity(intent)
            } catch (e: Exception) {
                crashLogger.logNonFatalError(e)
            }
        }
    }

    /** What protects this phone right now, for the admin's Protection tab. Read from Android, not assumed. */
    private suspend fun protectionReport(): JSONObject {
        val anti = service.dataStoreManager.settings.first().antiUninstallConfig2
        return JSONObject()
            // This code runs inside the accessibility service, so the service is on.
            .put("accessibility", true)
            .put("overlay", PermissionUtils.hasOverlayPermission(service))
            .put("notifications", NotificationManagerCompat.from(service).areNotificationsEnabled())
            .put("battery", ServiceProtectionManager.isIgnoringBatteryOptimizations(service))
            .put("uninstallProtection", anti.isEnabled && AntiUninstallManager.isAdminActive(service))
            .put("deviceOwner", DeviceOwnerHelper.isDeviceOwner(service))
    }

    /** The admin removed this phone: uninstall protection that was on for the admin goes too. */
    private suspend fun releaseAdminUninstallProtection() {
        var wasAdmin = false
        service.dataStoreManager.updateAntiUninstallConfig {
            wasAdmin = it.adminManaged
            if (it.adminManaged) it.copy(isEnabled = false, adminManaged = false, passwordHash = "", unlockRequestedAtMs = 0L) else it
        }
        if (wasAdmin) runCatching { AntiUninstallManager.removeProtection(service) }
    }

    private fun parseSchedules(array: JSONArray?): List<AdminSchedule> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val days = o.optJSONArray("days")
            AdminSchedule(
                id = o.optString("id"),
                name = o.optString("name"),
                days = if (days == null) emptyList() else (0 until days.length()).map { days.optInt(it) }.filter { it in 1..7 },
                start = o.optString("start", "00:00"),
                end = o.optString("end", "00:00"),
                blockApps = o.optJSONArray("blockApps").toStringList(),
                blockSites = o.optJSONArray("blockSites").toStringList(),
                blockAllApps = o.optBoolean("blockAllApps", false),
                enabled = o.optBoolean("enabled", true)
            )
        }
    }

    /** Works out schedules and the daily limit now, then again every 30 seconds or after a rule change. */
    private suspend fun rulesLoop() {
        while (currentCoroutineContext().isActive) {
            try {
                refreshRules()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "Admin rules check failed", t)
                crashLogger.logNonFatalError(Exception(t))
            }
            withTimeoutOrNull(RULES_INTERVAL_MS) { rulesNow.receive() }
        }
    }

    private suspend fun refreshRules() {
        val current = config
        val now = LocalDateTime.now()
        val active = if (current.isLinked()) current.schedules.filter { it.isActive(now) } else emptyList()

        val apps = HashMap<String, AdminSchedule>()
        active.forEach { s -> s.blockApps.forEach { if (it !in essentialPackages) apps.putIfAbsent(it, s) } }
        val sites = LinkedHashMap<String, AdminSchedule?>()
        blockedSites.forEach { sites[it] = null }
        active.forEach { s ->
            s.blockSites.map(AdminLinkClient::normalizeSite).filter { it.isNotEmpty() }.forEach { sites.putIfAbsent(it, s) }
        }
        val newBlockAll = active.firstOrNull { it.blockAllApps }

        var reached = false
        if (current.isLinked() && current.dailyLimitMinutes > 0) {
            val used = UsageRepository.dayReport(service, 0).first
            val limitMs = current.dailyLimitMinutes * 60_000L
            reached = used >= limitMs
            warnIfNeeded(current, ((limitMs - used).coerceAtLeast(0L) / 60_000L).toInt(), reached)
        }

        val tightened = (reached && !limitReached) || (newBlockAll != null && scheduleBlockAll == null) ||
            !scheduleApps.keys.containsAll(apps.keys)
        scheduleApps = apps
        scheduleBlockAll = newBlockAll
        activeSites = sites.keys.toList()
        @Suppress("UNCHECKED_CAST")
        siteSchedules = sites.filterValues { it != null } as Map<String, AdminSchedule>
        limitReached = reached

        // A rule that starts while an app is open blocks it now, not at the next app switch.
        if (tightened) {
            val foreground = runCatching { service.rootInActiveWindow?.packageName?.toString() }.getOrNull()
            mainHandler.post {
                lastPackage = ""
                if (foreground != null) {
                    lastPackage = foreground
                    try {
                        checkPackage(foreground)
                    } catch (e: Exception) {
                        crashLogger.logNonFatalError(e)
                    }
                }
            }
        }
    }

    /** One notification per threshold per day; only the smallest one crossed is shown. */
    private suspend fun warnIfNeeded(current: AdminLinkConfig, minutesLeft: Int, reached: Boolean) {
        val today = LocalDate.now().toString()
        val warned = if (current.warnedDate == today) current.warnedMinutes.toSet() else emptySet()
        val crossed = current.warningMinutes.filter { minutesLeft <= it && it !in warned }
        if (crossed.isEmpty()) return
        if (!reached) notifyLimit(minutesLeft)
        service.dataStoreManager.updateAdminLinkConfig {
            if (it.deviceToken == current.deviceToken) it.copy(warnedDate = today, warnedMinutes = (warned + crossed).toList()) else it
        }
    }

    private fun notifyLimit(minutesLeft: Int) {
        val manager = service.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(LIMIT_CHANNEL, service.getString(R.string.admin_limit_channel), NotificationManager.IMPORTANCE_HIGH)
                .apply { description = service.getString(R.string.admin_limit_channel_description) }
        )
        val text = service.getString(
            if (minutesLeft <= 10) R.string.admin_limit_warning_last else R.string.admin_limit_warning,
            minutesLeft
        )
        val notification = NotificationCompat.Builder(service, LIMIT_CHANNEL)
            .setSmallIcon(R.drawable.ic_admin_shield)
            .setContentTitle(service.getString(R.string.admin_limit_warning_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(LIMIT_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Notifications are off for Curbox; the block at the limit still works.
            Log.w(TAG, "Limit warning not shown: ${e.message}")
        }
    }

    /** Today and yesterday, as whole day totals: [{"date", "totalMs", "apps": [{"p", "ms"}]}]. */
    private suspend fun usageReport(): JSONArray {
        val days = JSONArray()
        for (daysAgo in 0L..1L) {
            val (total, perApp) = UsageRepository.dayReport(service, daysAgo)
            val apps = JSONArray()
            perApp.entries.sortedByDescending { it.value }.take(300).forEach { (pkg, ms) ->
                apps.put(JSONObject().put("p", pkg).put("ms", ms))
            }
            days.put(
                JSONObject()
                    .put("date", LocalDate.now().minusDays(daysAgo).toString())
                    .put("totalMs", total)
                    .put("apps", apps)
            )
        }
        return days
    }

    fun setupReceivers() {
        ContextCompat.registerReceiver(
            service,
            refreshReceiver,
            IntentFilter(INTENT_ACTION_REFRESH_ADMIN_LINK),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
    }

    fun removeReceivers() {
        if (!receiverRegistered) return
        service.unregisterReceiver(refreshReceiver)
        receiverRegistered = false
    }

    fun onDestroy() {
        settingsJob?.cancel()
        syncJob?.cancel()
        rulesJob?.cancel()
        scope.cancel()
        syncNow.close()
        rulesNow.close()
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun CoroutineScope.launchSafely(block: suspend () -> Unit): Job = launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "Admin link worker failed", t)
            crashLogger.logNonFatalError(Exception(t))
        }
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            syncNow.trySend(Unit)
        }
    }
}
