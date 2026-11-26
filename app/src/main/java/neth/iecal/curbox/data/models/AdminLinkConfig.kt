package neth.iecal.curbox.data.models

/**
 * Link between this phone and a Curbox Admin running on a computer on the same WiFi.
 *
 * The admin decides [blockedApps] and [blockedSites]. They are cached here so the
 * blocks keep working while the computer is off or out of reach.
 */
data class AdminLinkConfig(
    val serverUrl: String = "",
    val deviceId: String = "",
    val deviceToken: String = "",
    val deviceName: String = "",
    /** The account on Curbox Admin this phone is signed in to. */
    val username: String = "",
    /** SHA-256 of that account's password, so later sign ins work without the computer. */
    val passwordHash: String = "",
    val blockedApps: List<String> = emptyList(),
    val blockedSites: List<String> = emptyList(),
    /** When the admin last answered a sync. Saved at most every few minutes. */
    val lastSyncMs: Long = 0L,
    /** Whether the admin set a six digit release code. The code itself never leaves the admin. */
    val releaseCodeSet: Boolean = false,
    /** Minutes of phone use allowed per day; 0 means no limit. */
    val dailyLimitMinutes: Int = 0,
    /** A notification fires once a day when this many minutes are left. */
    val warningMinutes: List<Int> = listOf(60, 30, 10),
    val schedules: List<AdminSchedule> = emptyList(),
    /** The rules version last received; sent back so the admin knows the phone has it. */
    val policyVersion: Long = 0L,
    /** ISO date and thresholds already warned about that day, so each warning fires once. */
    val warnedDate: String = "",
    val warnedMinutes: List<Int> = emptyList(),
    /** When not empty, every website except these is blocked (admin profiles, phones only). */
    val allowSites: List<String> = emptyList(),
    /** The admin has not approved this phone yet, so no rules apply. */
    val pendingApproval: Boolean = false,
    /** PBKDF2 of the admin's six digit code ("pbkdf2$iterations$salt$hash"), never the code. */
    val releaseVerifier: String = "",
    /** When new rules from the admin last arrived. Never set unless they really changed. */
    val lastRulesChangeMs: Long = 0L
) {
    fun isLinked(): Boolean = serverUrl.isNotEmpty() && deviceToken.isNotEmpty()
}
