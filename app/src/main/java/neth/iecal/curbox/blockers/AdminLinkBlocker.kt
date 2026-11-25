package neth.iecal.curbox.blockers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import neth.iecal.curbox.BuildConfig
import neth.iecal.curbox.CrashLogger
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AdminLinkConfig
import neth.iecal.curbox.services.AppBlockerService
import neth.iecal.curbox.services.BaseBlockingService
import neth.iecal.curbox.trackers.WebsiteObservation
import neth.iecal.curbox.utils.AdminLinkClient
import neth.iecal.curbox.utils.AdminLinkClient.toStringList
import neth.iecal.curbox.utils.getCurrentKeyboardPackageName
import neth.iecal.curbox.utils.getDefaultLauncherPackageName
import org.json.JSONObject
import java.io.IOException

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
    }

    private lateinit var service: AppBlockerService
    private lateinit var crashLogger: CrashLogger
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val syncNow = Channel<Unit>(Channel.CONFLATED)
    private var settingsJob: Job? = null
    private var syncJob: Job? = null
    private var receiverRegistered = false

    @Volatile private var config = AdminLinkConfig()
    @Volatile private var blockedApps: Set<String> = emptySet()
    @Volatile private var blockedSites: List<String> = emptyList()
    @Volatile private var essentialPackages: Set<String> = emptySet()
    @Volatile private var lastAppsReportMs = 0L
    @Volatile private var lastSiteBlockMs = 0L
    private var lastPackage = ""

    fun setup(service: BaseBlockingService) {
        if (service !is AppBlockerService) return
        this.service = service
        crashLogger = CrashLogger(service)

        val essential = mutableSetOf(service.packageName, "com.android.systemui")
        getDefaultLauncherPackageName(service.packageManager)?.let { essential.add(it) }
        getCurrentKeyboardPackageName(service)?.let { essential.add(it) }
        essentialPackages = essential

        settingsJob?.cancel()
        settingsJob = scope.launchSafely {
            service.dataStoreManager.settings.collectLatest { applyConfig(it.adminLinkConfig) }
        }
        syncJob?.cancel()
        syncJob = scope.launchSafely { syncLoop() }
    }

    private fun applyConfig(newConfig: AdminLinkConfig) {
        val tokenChanged = newConfig.deviceToken != config.deviceToken
        config = newConfig
        // Essential apps stay usable so a block can never trap the phone.
        blockedApps = newConfig.blockedApps.toSet() - essentialPackages
        blockedSites = newConfig.blockedSites.map(AdminLinkClient::normalizeSite).filter { it.isNotEmpty() }
        if (tokenChanged) {
            lastAppsReportMs = 0L
            syncNow.trySend(Unit)
        }
    }

    /** Cheap check, runs inline in onAccessibilityEvent. */
    fun doAppCheck(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val blocked = blockedApps
        if (blocked.isEmpty()) return
        val packageName = event.packageName?.toString() ?: return
        if (packageName == lastPackage) return
        lastPackage = packageName
        if (packageName !in blocked) return

        service.pressHome()
        Toast.makeText(service, R.string.admin_link_blocked_app, Toast.LENGTH_SHORT).show()
    }

    /** Runs on the website observation worker, never on the main thread. */
    fun onWebsiteObserved(observation: WebsiteObservation?) {
        observation ?: return
        val sites = blockedSites
        if (sites.isEmpty() || !matchesBlockedSite(observation, sites)) return

        val now = SystemClock.uptimeMillis()
        if (now - lastSiteBlockMs < SITE_BLOCK_COOLDOWN_MS) return
        lastSiteBlockMs = now
        mainHandler.post {
            service.pressHome()
            Toast.makeText(service, R.string.admin_link_blocked_site, Toast.LENGTH_SHORT).show()
        }
    }

    private fun matchesBlockedSite(observation: WebsiteObservation, sites: List<String>): Boolean {
        val url = AdminLinkClient.normalizeSite(observation.urlIdentifier)
        val host = AdminLinkClient.normalizeSite(observation.domain).ifEmpty { url.substringBefore('/') }
        return sites.any { site ->
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
        if (includeApps) body.put("apps", AdminLinkClient.installedApps(service))

        val response = AdminLinkClient.post(current.serverUrl, "/api/device/sync", body, current.deviceToken)
        when (response.code) {
            200 -> {
                if (includeApps) lastAppsReportMs = now
                val json = JSONObject(response.body)
                val apps = json.optJSONArray("blockedApps").toStringList()
                val sites = json.optJSONArray("blockedSites").toStringList()
                if (apps != current.blockedApps || sites != current.blockedSites) {
                    service.dataStoreManager.updateAdminLinkConfig {
                        if (it.deviceToken == current.deviceToken) it.copy(blockedApps = apps, blockedSites = sites) else it
                    }
                }
            }
            401 -> {
                // The admin removed this phone, so its blocks are lifted too.
                service.dataStoreManager.updateAdminLinkConfig {
                    if (it.deviceToken == current.deviceToken) AdminLinkConfig() else it
                }
            }
            else -> Log.w(TAG, "Admin answered ${response.code}")
        }
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
        scope.cancel()
        syncNow.close()
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
