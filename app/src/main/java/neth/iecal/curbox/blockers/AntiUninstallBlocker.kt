package neth.iecal.curbox.blockers

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.CrashLogger
import neth.iecal.curbox.R
import neth.iecal.curbox.blockers.uihider.NodeFinder
import neth.iecal.curbox.data.models.AntiUninstallConfig
import neth.iecal.curbox.services.AppBlockerService
import neth.iecal.curbox.services.BaseBlockingService
import neth.iecal.curbox.utils.AntiUninstallManager
import neth.iecal.curbox.utils.ServiceProtectionManager
import java.util.Locale


/**
 * Keeps Curbox installed and running by bouncing the user away from the screens that could undo
 * protection while it is locked: the device admin deactivation screen and the accessibility service
 * disable screens. When a timed or cooldown unlock finishes, the admin is removed so the user can
 * leave freely.
 */
class AntiUninstallBlocker : BaseBlocker() {

    private companion object {
        // The AOSP activity that activates or deactivates a device admin.
        const val DEVICE_ADMIN_SCREEN_CLASS = "DeviceAdminAdd"
        const val SETTINGS_PACKAGE = "com.android.settings"
        const val ACCESSIBILITY_SHORTCUT_TITLE = "accessibility_shortcut_title"
        const val SCREEN_SCAN_INTERVAL_MS = 300L
        const val CODE_SCREEN_COOLDOWN_MS = 3000L
        // Screens where an uninstall can be confirmed: the system installers, Xiaomi's security app.
        val UNINSTALL_PACKAGES = setOf(
            "com.android.packageinstaller", "com.google.android.packageinstaller", "com.miui.packageinstaller",
            "com.miui.securitycenter", "com.samsung.android.packageinstaller"
        )
        // Words of an uninstall button or question, in English, Russian and Uzbek.
        val UNINSTALL_WORDS = listOf("uninstall", "удал", "chirish", "chirilsin")
        val SERVICE_LABELS = listOf("App Blocker")
    }

    @Volatile private var config: AntiUninstallConfig = AntiUninstallConfig()
    // A phone the admin manages, with a release code set: removing Curbox always needs that code.
    @Volatile private var adminCodeRequired = false
    private var lastScreenScan = 0L
    private var lastCodeScreen = 0L
    private lateinit var service: AppBlockerService
    private var settingsJob: kotlinx.coroutines.Job? = null

    // Resolving these needs binder calls, so build the label once per blocker instance.
    private val shortcutTitle by lazy { buildShortcutTitle() }

    private var foundCount = 0
    fun setupBlocker(service: BaseBlockingService) {
        if (service !is AppBlockerService) return
        this.service = service
        settingsJob?.cancel()
        settingsJob = CoroutineScope(Dispatchers.IO).launch {
            service.dataStoreManager.settings.collectLatest {
                config = it.antiUninstallConfig2
                adminCodeRequired = it.adminLinkConfig.isLinked() && it.adminLinkConfig.releaseVerifier.isNotEmpty()
            }
        }
    }

    fun onDestroy() {
        settingsJob?.cancel()
    }

    fun doAntiUninstallCheck(event: AccessibilityEvent?) {
        event ?: return
        val current = config
        val managed = adminCodeRequired
        if (!current.isEnabled && !managed) return

        // A finished timed or cooldown unlock lifts protection for good.
        if (current.isEnabled && AntiUninstallManager.isUnlockComplete(current)) {
            finishUnlock()
            return
        }

        val pkg = event.packageName?.toString().orEmpty()
        if (managed && isUninstallScreen(event, pkg)) {
            bounce()
            return
        }

        // isAdminActive is a binder call, so only pay for it on the screens we may bounce from.
        val onAdminScreen = event.className?.toString()?.contains(DEVICE_ADMIN_SCREEN_CLASS) == true
        val inSettings = pkg == SETTINGS_PACKAGE
        if (!onAdminScreen && !inSettings) return
        val adminActive = AntiUninstallManager.isAdminActive(service)
        // Without device admin, a managed phone still guards its accessibility service.
        if (!adminActive && !managed) return

        if (onAdminScreen) {
            // The same screen also turns device admin on during setup; only guard turning it off.
            if (adminActive) bounce()
            return
        }

        val appNameMatch = service.rootInActiveWindow.findAccessibilityNodeInfosByText(service.getString(R.string.app_name))
        if(appNameMatch!=null){
            //Device admin check
            val admMatch = service.rootInActiveWindow.findAccessibilityNodeInfosByText("device admin app")
            if(!admMatch.isNullOrEmpty()) bounce()
        }

        //Accessibility Service Block
        val matches = service.rootInActiveWindow.findAccessibilityNodeInfosByText(shortcutTitle)
        if(!matches.isNullOrEmpty()){
            bounce()
            return
        }



        // Bounce the moment the user taps Curbox's row in accessibility settings.
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && clickHitsOurService(event.source)) {
            bounce()
            return
        }

        val now = SystemClock.uptimeMillis()
        if (now - lastScreenScan < SCREEN_SCAN_INTERVAL_MS) return
        lastScreenScan = now

        val root = service.rootInActiveWindow ?: return
        val nodes = root.findAccessibilityNodeInfosByText(
            service.getString(R.string.accessibility_permission_app_blocker)
        )
        if (!nodes.isNullOrEmpty()) {
            nodes.forEach { NodeFinder.recycle(it) }
            bounce()
        }
    }

    private fun bounce() {
        service.pressBack()
        service.pressHome()
        if (config.adminManaged || adminCodeRequired) askForAdminCode()
    }

    /**
     * An uninstall question or button for Curbox: in a system installer, Settings, Xiaomi's security
     * app or the home screen's long press menu. Checked only when a window opens, so it stays cheap.
     */
    private fun isUninstallScreen(event: AccessibilityEvent, pkg: String): Boolean {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return false
        val watched = pkg in UNINSTALL_PACKAGES || pkg == SETTINGS_PACKAGE ||
            pkg == service.getDefaultLauncherPackageNameSafe()
        if (!watched) return false
        val root = service.rootInActiveWindow ?: return false
        val label = root.findAccessibilityNodeInfosByText(service.getString(R.string.app_name))
        if (label.isNullOrEmpty()) return false
        label.forEach { NodeFinder.recycle(it) }
        for (word in UNINSTALL_WORDS) {
            val hits = root.findAccessibilityNodeInfosByText(word)
            if (!hits.isNullOrEmpty()) {
                hits.forEach { NodeFinder.recycle(it) }
                return true
            }
        }
        return false
    }

    private var launcherPackage: String? = null

    private fun AppBlockerService.getDefaultLauncherPackageNameSafe(): String {
        launcherPackage?.let { return it }
        val found = runCatching {
            packageManager.resolveActivity(
                android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME), 0
            )?.activityInfo?.packageName
        }.getOrNull().orEmpty()
        launcherPackage = found
        return found
    }

    /** Opens the code screen; after the right code, Curbox can be removed like any app. */
    private fun askForAdminCode() {
        val now = SystemClock.uptimeMillis()
        if (now - lastCodeScreen < CODE_SCREEN_COOLDOWN_MS) return
        lastCodeScreen = now
        try {
            service.startActivity(
                android.content.Intent(service, neth.iecal.curbox.ui.activity.WarningActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    .putExtra("mode", neth.iecal.curbox.Constants.WARNING_SCREEN_MODE_ADMIN_BLOCK)
                    .putExtra("admin_kind", "uninstall")
            )
        } catch (e: Exception) {
            CrashLogger(service).logNonFatalError(e)
        }
    }

    /** True if the clicked node, or anything under it, is one of Curbox's accessibility services. */
    private fun clickHitsOurService(source: AccessibilityNodeInfo?): Boolean {
        source ?: return false
        try {
            return SERVICE_LABELS.any { label ->
                val matches = source.findAccessibilityNodeInfosByText(label)
                val found = !matches.isNullOrEmpty()
                matches?.forEach { NodeFinder.recycle(it) }
                found
            }
        } finally {
            NodeFinder.recycle(source)
        }
    }
    /**
     * The title Settings shows for the accessibility shortcut. The format string lives in the
     * Settings app, so read it from there to match the device language and Android version.
     */
    private fun buildShortcutTitle(): String {
        val serviceLabel = accessibilityServiceLabel()
        val settingsResources = try {
            service.packageManager.getResourcesForApplication(SETTINGS_PACKAGE)
        } catch (_: Exception) {
            null
        }
        val titleId = settingsResources?.getIdentifier(
            ACCESSIBILITY_SHORTCUT_TITLE,
            "string",
            SETTINGS_PACKAGE
        ) ?: 0
        if (settingsResources != null && titleId != 0) {
            try {
                return settingsResources.getString(titleId, serviceLabel)
            } catch (_: Exception) {
            }
        }
        // Fallback for a Settings build that does not expose the string.
        return "$serviceLabel shortcut"
    }

    /** The label Settings lists our accessibility service under. */
    private fun accessibilityServiceLabel(): String {
        return try {
            val component = ComponentName(service, AppBlockerService::class.java)
            service.packageManager.getServiceInfo(component, 0)
                .loadLabel(service.packageManager)
                .toString()
        } catch (_: Exception) {
            // The label declared on AppBlockerService in the manifest.
            "Curbox App Blocker"
        }
    }

    private fun traverseNodesForKeywords(node: AccessibilityNodeInfo?) {
        if (node == null) {
            return
        }

        if (node.getClassName() != null && node.getClassName() == "android.widget.TextView") {
            val nodeText = node.text.toString() + node.contentDescription.toString()
            val textContent = nodeText.toString().lowercase(Locale.getDefault())
            if(textContent.contains("curbox")){
                foundCount++
            }
        }

        for (i in 0..<node.getChildCount()) {
            val childNode = node.getChild(i)
            traverseNodesForKeywords(childNode)
        }
    }

    private fun finishUnlock() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                service.dataStoreManager.updateAntiUninstallConfig {
                    it.copy(isEnabled = false, unlockRequestedAtMs = 0L)
                }
                ServiceProtectionManager.removeDeviceAdmin(service)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                CrashLogger(service).logNonFatalError(e)
            }
        }
    }
}
