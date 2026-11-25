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
    val blockedSites: List<String> = emptyList()
) {
    fun isLinked(): Boolean = serverUrl.isNotEmpty() && deviceToken.isNotEmpty()
}
