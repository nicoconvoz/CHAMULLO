package ar.chamullo.app

import ar.chamullo.core.Relay
import java.net.HttpURLConnection
import java.net.URL

/**
 * The Nostr relays of the bridge: built-in public ones, plus any CHAMULLO adds on its page (relays.txt), read on their own
 * like the version: nobody types an address, and a new relay reaches every phone without a new app. Call it off the main thread.
 */
object RelayList {
    const val URL_LIST = "https://nicoconvoz.github.io/chamullo-web/relays.txt"

    /** The Nostr relays for the bridge: the built-in public ones plus any the page adds (wss://). */
    fun nostr(): List<String> = (ar.chamullo.core.NostrBridge.DEFAULT_RELAYS + fetch().filter { it.startsWith("ws") }).distinct()

    private fun fetch(): List<String> = runCatching {
        val conn = URL("$URL_LIST?t=${System.currentTimeMillis()}").openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000; conn.readTimeout = 8_000
        try { if (conn.responseCode == 200) Relay.parseList(conn.inputStream.bufferedReader().readText()) else emptyList() } finally { conn.disconnect() }
    }.getOrDefault(emptyList())
}
