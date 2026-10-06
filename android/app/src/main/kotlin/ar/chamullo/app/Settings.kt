package ar.chamullo.app

import android.content.Context

/** Small switches the user can flip from Diagnóstico. */
object Settings {
    private fun prefs(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Bluetooth el grito as a fallback for phones without Wi-Fi Direct. Off by default since 0.3.0. */
    fun bluetooth(c: Context) = prefs(c).getBoolean("bluetooth", false)

    fun setBluetooth(c: Context, on: Boolean) = prefs(c).edit().putBoolean("bluetooth", on).apply()


    /** Lend my Internet as a bridge for big jumps. Off by default: it is the owner's data plan. */
    fun lendInternet(c: Context) = prefs(c).getBoolean("lendInternet", false)

    fun setLendInternet(c: Context, on: Boolean) = prefs(c).edit().putBoolean("lendInternet", on).apply()

    /** Flip lending Internet and restart the grito so it takes effect at once. */
    fun toggleLendInternet(c: Context) {
        setLendInternet(c, !lendInternet(c))
        c.stopService(android.content.Intent(c, GritoService::class.java))
        c.startForegroundService(android.content.Intent(c, GritoService::class.java))
    }

    /**
     * The founder of the network (Economy & Governance §10): written in the protocol, not configured, so every CHAMULLO
     * follows one book per pueblo. Only the phone holding the founder's 16 words can write the genesis.
     */
    const val FOUNDER = "6e63751b4968bab08674c09598c2cf800808838e5c505103880f616cc509490a"

    @Suppress("UNUSED_PARAMETER") fun founder(c: Context) = FOUNDER
}
