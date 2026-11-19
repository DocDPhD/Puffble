package dev.proxywatch.companion.update

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * "Check for updates": asks GitHub for the latest Puffble release. Runs only when the user taps
 * the button; nothing about the user or their device is sent beyond a normal web request.
 */
object Updates {
    const val LATEST_API = "https://api.github.com/repos/DocDPhD/Puffble/releases/latest"
    const val RELEASES_PAGE = "https://github.com/DocDPhD/Puffble/releases/latest"

    data class Release(val version: String, val apkUrl: String?, val pageUrl: String)

    /** Blocking; call off the main thread. */
    fun fetchLatest(): Release {
        val conn = URL(LATEST_API).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "Puffble-companion")
            if (conn.responseCode != 200) throw IOException("GitHub answered ${conn.responseCode}")
            return parse(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    fun parse(json: String): Release {
        val o = JSONObject(json)
        val assets = o.optJSONArray("assets")
        var apk: String? = null
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) apk = a.optString("browser_download_url").ifBlank { null }
            }
        }
        return Release(
            version = o.getString("tag_name").removePrefix("v"),
            apkUrl = apk,
            pageUrl = o.optString("html_url").ifBlank { RELEASES_PAGE },
        )
    }

    /** True if [candidate] is a newer dotted version than [installed] ("0.6.1" > "0.6.0"). */
    fun isNewer(candidate: String, installed: String): Boolean {
        val a = numbers(candidate)
        val b = numbers(installed)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun numbers(v: String): List<Int> =
        v.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
}
