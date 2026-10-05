package ar.chamullo.app

import android.content.Context
import ar.chamullo.core.Version
import java.net.HttpURLConnection
import java.net.URL

/**
 * The latest version published on the download page. Optional: without internet or with the page down nothing
 * changes; it only lights the "new version" notice that leads to the download page. Call it off the main thread.
 */
object WebVersion {
    const val URL_VERSION = "https://nicoconvoz.github.io/chamullo-web/version.txt"
    const val DOWNLOAD_PAGE = "https://nicoconvoz.github.io/chamullo-web/"
    const val CHECK_EVERY_MS = 6 * 3600_000L

    @Volatile var latest: String? = null; private set

    fun installed(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"

    fun fetch(): String? = runCatching {
        // The time in the query skips stale caches between the phone and the page.
        val conn = URL("$URL_VERSION?t=${System.currentTimeMillis()}").openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000; conn.readTimeout = 8_000
        try { if (conn.responseCode == 200) Version.parse(conn.inputStream.bufferedReader().readText()) else null } finally { conn.disconnect() }
    }.getOrNull()?.also { latest = it }

    fun needsUpdate(context: Context) = Version.needsUpdate(latest, installed(context))
}
