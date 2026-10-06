// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-CHAMULLO-Commercial
// Copyright (C) 2026 Jesús Nicolás Astorga y RESOURCES OPEN DOORS S.A.S
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

    /** Favorite contacts (a star, first in the lists), like ICEBREAK's chat favorites. */
    fun favorites(c: Context): Set<String> = prefs(c).getStringSet("favorites", emptySet()) ?: emptySet()

    fun toggleFavorite(c: Context, id: String) {
        val now = favorites(c).toMutableSet()
        if (!now.remove(id)) now += id
        prefs(c).edit().putStringSet("favorites", now).apply()
    }

    /** When I last looked at a chat: newer letters from that contact are unread. */
    fun seenAt(c: Context, id: String) = prefs(c).getLong("seen-$id", 0)

    fun markSeen(c: Context, id: String) = prefs(c).edit().putLong("seen-$id", System.currentTimeMillis()).apply()

    /** Bytes of bought Internet used so far (Discovery & Routing §11.3), kept in memory and saved every 256 KB. */
    private var used = -1L
    private var savedUsed = 0L

    @Synchronized fun dataUsed(c: Context): Long {
        if (used < 0) { used = prefs(c).getLong("dataUsed", 0); savedUsed = used }
        return used
    }

    @Synchronized fun addDataUsed(c: Context, n: Long) {
        used = dataUsed(c) + n
        if (used - savedUsed >= 256 * 1024) { savedUsed = used; prefs(c).edit().putLong("dataUsed", used).apply() }
    }

    /** The store's catalog as last read from the page, so a phone without Internet still knows its packs. */
    fun catalog(c: Context): String? = prefs(c).getString("catalog", null)

    fun setCatalog(c: Context, json: String) = prefs(c).edit().putString("catalog", json).apply()

    /** The tab the home screen shows. */
    fun tab(c: Context) = prefs(c).getInt("tab", 0)

    fun setTab(c: Context, i: Int) = prefs(c).edit().putInt("tab", i).apply()
}
