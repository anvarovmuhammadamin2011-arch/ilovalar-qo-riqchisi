package neth.iecal.curbox.utils

import android.content.Context
import android.os.Build
import neth.iecal.curbox.BuildConfig

/**
 * Review mode for the Android emulator: the setup's permission steps can be marked as granted
 * without opening Android's screens, so the whole flow can be walked through. It works only in
 * a debug build on an emulator, never on a real phone, so it cannot be used to skip protection.
 */
object DemoMode {

    private const val PREFS = "demo_mode"

    fun isOn(): Boolean = BuildConfig.DEBUG && isEmulator()

    private fun isEmulator(): Boolean =
        Build.HARDWARE.contains("goldfish") || Build.HARDWARE.contains("ranchu") ||
            Build.FINGERPRINT.startsWith("generic") || Build.PRODUCT.contains("sdk_gphone") ||
            Build.MODEL.contains("Android SDK built for")

    fun isGranted(context: Context, step: String): Boolean =
        isOn() && prefs(context).getBoolean(step, false)

    fun grant(context: Context, step: String) {
        if (isOn()) prefs(context).edit().putBoolean(step, true).apply()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
