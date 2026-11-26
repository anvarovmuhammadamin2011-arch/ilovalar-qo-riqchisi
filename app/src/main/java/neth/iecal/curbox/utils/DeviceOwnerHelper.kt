package neth.iecal.curbox.utils

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import neth.iecal.curbox.receivers.AdminReceiver

/**
 * Fully managed mode. Only a phone set up with "dpm set-device-owner" (a freshly reset
 * phone, done by an adult or IT person) makes Curbox the device owner. Curbox never turns
 * this on by itself, and on a normal phone every call here does nothing.
 */
object DeviceOwnerHelper {

    private const val TAG = "DeviceOwner"

    fun isDeviceOwner(context: Context): Boolean = try {
        dpm(context).isDeviceOwnerApp(context.packageName)
    } catch (e: Exception) {
        false
    }

    /** Stops or allows uninstalling Curbox. Returns true only when Android accepted it. */
    fun setUninstallBlocked(context: Context, blocked: Boolean): Boolean {
        if (!isDeviceOwner(context)) return false
        return try {
            dpm(context).setUninstallBlocked(admin(context), context.packageName, blocked)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Uninstall block not changed: ${e.message}")
            false
        }
    }

    /** After a release: allow uninstalling and give up device owner, so Curbox is an ordinary app again. */
    @Suppress("DEPRECATION")
    fun releaseManagement(context: Context) {
        if (!isDeviceOwner(context)) return
        setUninstallBlocked(context, false)
        try {
            dpm(context).clearDeviceOwnerApp(context.packageName)
        } catch (e: SecurityException) {
            Log.w(TAG, "Device owner not cleared: ${e.message}")
        }
    }

    /** The command that makes Curbox the device owner of a freshly reset phone. */
    fun provisioningCommand(context: Context): String =
        "adb shell dpm set-device-owner ${context.packageName}/${AdminReceiver::class.java.name}"

    private fun dpm(context: Context) =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private fun admin(context: Context) = ComponentName(context, AdminReceiver::class.java)
}
