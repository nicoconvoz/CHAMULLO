package ar.chamullo.app

import ar.chamullo.core.Relay
import java.net.HttpURLConnection
import java.net.URL

/**
 * The relays CHAMULLO publishes on its page, read on their own like the version: nobody types an address. If the
 * project moves its relay, it changes relays.txt and every phone follows, without a new app. Call it off the main thread.
 */
object RelayList {
    const val URL_LIST = "https://nicoconvoz.github.io/chamullo-web/relays.txt"

    /** The first published relay that answers right now, or null if none is up yet. */
    fun pick(): String? = fetch().firstOrNull { answers(it) }

    private fun fetch(): List<String> = runCatching {
        val conn = URL("$URL_LIST?t=${System.currentTimeMillis()}").openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000; conn.readTimeout = 8_000
        try { if (conn.responseCode == 200) Relay.parseList(conn.inputStream.bufferedReader().readText()) else emptyList() } finally { conn.disconnect() }
    }.getOrDefault(emptyList())

    // A relay is up if its public directory answers (an empty zone is fine: we only want the answer).
    private fun answers(base: String): Boolean = runCatching {
        val conn = URL("$base/peers?zone=0000").openConnection() as HttpURLConnection
        conn.connectTimeout = 5_000; conn.readTimeout = 5_000
        try { conn.responseCode in 200..499 } finally { conn.disconnect() }
    }.getOrDefault(false)
}
