package neth.iecal.curbox.ui.fragments.user

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.data.models.AdminLinkConfig
import neth.iecal.curbox.data.sync.SYNCED_WEB_PACKAGE
import neth.iecal.curbox.utils.AdminLinkClient
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.TimeTools
import org.json.JSONObject

data class AppUsage(val packageName: String, val label: String, val icon: Drawable?, val timeMs: Long)

data class AppInfo(val packageName: String, val label: String, val icon: Drawable?)

/** Data the user tabs share. Everything here runs off the main thread. */
object UserData {

    enum class Link { CONNECTED, OFFLINE, REMOVED, NOT_LINKED }

    /** Today's app use, longest first, without ignored apps, synced website rows or Curbox. */
    suspend fun todayUsage(context: Context): List<AppUsage> = withContext(Dispatchers.IO) {
        val ignored = DataStoreManager(context).settings.first().usageTrackerIgnoredApps.toSet()
        val rows = AppDatabase.getInstance(context).appUsageDao().getForDate(TimeTools.getCurrentDate())
        val pm = context.packageManager
        rows.asSequence()
            .filter { it.totalTime > 0 }
            .filter { it.packageName != SYNCED_WEB_PACKAGE && it.packageName != context.packageName }
            .filter { it.packageName !in ignored }
            .sortedByDescending { it.totalTime }
            .map { AppUsage(it.packageName, label(pm, it.packageName), icon(pm, it.packageName), it.totalTime) }
            .toList()
    }

    /** The blocked packages that are installed on this phone, with names and icons, by name. */
    suspend fun installedApps(context: Context, packages: Collection<String>): List<AppInfo> =
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            packages.filter { isInstalled(pm, it) }
                .map { AppInfo(it, label(pm, it), icon(pm, it)) }
                .sortedBy { it.label.lowercase() }
        }

    /**
     * Asks Curbox for PC with this phone's token, the same call the blocker makes, so
     * "Connected" means the admin really answers for this phone right now.
     */
    suspend fun checkLink(config: AdminLinkConfig): Link = withContext(Dispatchers.IO) {
        if (!config.isLinked()) return@withContext Link.NOT_LINKED
        val code = runCatching {
            AdminLinkClient.post(config.serverUrl, "/api/device/sync", JSONObject(), config.deviceToken).code
        }.getOrNull()
        when (code) {
            200 -> Link.CONNECTED
            401 -> Link.REMOVED
            else -> Link.OFFLINE
        }
    }

    fun formatDuration(ms: Long): String = if (ms <= 0L) "0m" else TimeTools.formatDuration(ms)

    private fun isInstalled(pm: PackageManager, pkg: String): Boolean = try {
        pm.getApplicationInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun label(pm: PackageManager, pkg: String): String = try {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }

    private fun icon(pm: PackageManager, pkg: String): Drawable? = try {
        pm.getApplicationIcon(pkg)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }
}
