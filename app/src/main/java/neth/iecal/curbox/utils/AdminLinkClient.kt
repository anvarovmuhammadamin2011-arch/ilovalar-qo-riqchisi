package neth.iecal.curbox.utils

import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Talks to Curbox Admin (tools/pc-admin) over the local WiFi.
 *
 * Calls block the calling thread, so use them from a background dispatcher.
 */
object AdminLinkClient {

    const val DEFAULT_PORT = 8787

    /** The MBSI server on the internet. Phones use it unless someone types another address. */
    const val DEFAULT_SERVER_URL = "https://mbsi-server.onrender.com"
    private const val TIMEOUT_MS = 6000

    data class Response(val code: Int, val body: String)

    /** Shown in the admin's device list, like "Android 14". */
    fun osVersion(): String = "Android ${Build.VERSION.RELEASE}"

    /**
     * "192.168.1.5" or "192.168.1.5:8787" becomes "http://192.168.1.5:8787": a computer on the WiFi.
     * A domain such as "mbsi.example.uz" is a server on the internet and becomes "https://mbsi.example.uz".
     * Returns "" when unusable.
     */
    fun normalizeServerUrl(input: String): String {
        val text = input.trim()
        if (text.isEmpty()) return ""
        val hasScheme = text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true)
        return try {
            val url = URL(if (hasScheme) text else "http://$text")
            val host = url.host
            if (host.isNullOrEmpty()) return ""
            val https = if (hasScheme) url.protocol.equals("https", ignoreCase = true) else !isLocalHost(host)
            when {
                url.port != -1 -> "${if (https) "https" else "http"}://$host:${url.port}"
                https -> "https://$host"
                else -> "http://$host:$DEFAULT_PORT"
            }
        } catch (e: Exception) {
            ""
        }
    }

    /** An IP address, "localhost" or a name without dots: the admin computer on the same network. */
    private fun isLocalHost(host: String): Boolean =
        host.equals("localhost", ignoreCase = true) || !host.contains('.') ||
            host.endsWith(".local", ignoreCase = true) || host.all { it.isDigit() || it == '.' }

    /** Matches how the admin stores websites: lowercase, no scheme, no "www.", no trailing slash. */
    fun normalizeSite(input: String): String {
        var s = input.trim().lowercase(Locale.ROOT)
        val scheme = s.indexOf("://")
        if (scheme >= 0) s = s.substring(scheme + 3)
        s = s.removePrefix("www.")
        return s.trimEnd('/')
    }

    fun post(serverUrl: String, path: String, body: JSONObject, deviceToken: String? = null): Response {
        val connection = URL(serverUrl + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (!deviceToken.isNullOrEmpty()) {
                connection.setRequestProperty("X-Device-Token", deviceToken)
            }
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return Response(code, text)
        } finally {
            connection.disconnect()
        }
    }

    /** Saves a file meant for this device only, like a message picture. False when there is none. */
    fun download(serverUrl: String, path: String, deviceToken: String, dest: java.io.File, maxBytes: Int = 3_000_000): Boolean {
        val connection = URL(serverUrl + path).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = 15_000
            connection.setRequestProperty("X-Device-Token", deviceToken)
            if (connection.responseCode != 200) return false
            val tmp = java.io.File(dest.path + ".part")
            var total = 0
            connection.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) {
                            tmp.delete()
                            return false
                        }
                        output.write(buffer, 0, n)
                    }
                }
            }
            return tmp.renameTo(dest)
        } finally {
            connection.disconnect()
        }
    }

    /** A quick GET, used to check that Curbox for PC answers at an address. */
    fun get(serverUrl: String, path: String, timeoutMs: Int = TIMEOUT_MS): Response {
        val connection = URL(serverUrl + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return Response(code, text)
        } finally {
            connection.disconnect()
        }
    }

    /** Apps the user can open from the launcher, as [{"p": package, "l": label}]. */
    fun installedApps(context: Context): JSONArray {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcherIntent, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != context.packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase(Locale.ROOT) }

        val array = JSONArray()
        for ((pkg, label) in apps) {
            array.put(JSONObject().put("p", pkg).put("l", label))
        }
        return array
    }

    fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        val out = ArrayList<String>(length())
        for (i in 0 until length()) {
            val value = optString(i).trim()
            if (value.isNotEmpty()) out.add(value)
        }
        return out
    }
}
