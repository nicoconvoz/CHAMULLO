package ar.chamullo.app

import android.content.Context

/** Small switches the user can flip from Diagnóstico. */
object Settings {
    private fun prefs(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Bluetooth el grito as a fallback for phones without Wi-Fi Direct. Off by default since 0.3.0. */
    fun bluetooth(c: Context) = prefs(c).getBoolean("bluetooth", false)

    fun setBluetooth(c: Context, on: Boolean) = prefs(c).edit().putBoolean("bluetooth", on).apply()
}
