package ar.chamullo.app

import android.content.Context

/** Small switches the user can flip from Diagnóstico. */
object Settings {
    private fun prefs(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Bluetooth el grito as a fallback for phones without Wi-Fi Direct. Off by default since 0.3.0. */
    fun bluetooth(c: Context) = prefs(c).getBoolean("bluetooth", false)

    fun setBluetooth(c: Context, on: Boolean) = prefs(c).edit().putBoolean("bluetooth", on).apply()

    /** The relé of the Internet bridge (Discovery & Routing §11.1): anyone can host one. Empty: no bridge. */
    fun relay(c: Context) = prefs(c).getString("relay", "") ?: ""

    fun setRelay(c: Context, url: String) = prefs(c).edit().putString("relay", url.trim()).apply()

    /** Lend my Internet as a bridge for big jumps. Off by default: it is the owner's data plan. */
    fun lendInternet(c: Context) = prefs(c).getBoolean("lendInternet", false)

    fun setLendInternet(c: Context, on: Boolean) = prefs(c).edit().putBoolean("lendInternet", on).apply()

    /** The founder's id (Economy & Governance §10): with it, this phone keeps the ledger of its pueblo. */
    fun founder(c: Context) = prefs(c).getString("founder", "") ?: ""

    fun setFounder(c: Context, id: String) = prefs(c).edit().putString("founder", id.trim().lowercase()).apply()
}
