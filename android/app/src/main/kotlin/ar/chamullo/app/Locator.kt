package ar.chamullo.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper

/**
 * Where the phone is, for the compass (Discovery & Routing §2). The exact position never leaves the phone: direct
 * neighbors, already within Wi-Fi reach, learn the ~50 m cell; contacts learn the manzana or the barrio.
 */
@SuppressLint("MissingPermission")
class Locator(private val context: Context, private val onPlace: (lat: Double, lon: Double) -> Unit) {
    private val lm = context.getSystemService(LocationManager::class.java)
    private val listener = LocationListener { onPlace(it.latitude, it.longitude) }
    var started = false; private set

    fun start() {
        if (lm == null || context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (!runCatching { lm.isProviderEnabled(provider) }.getOrDefault(false)) continue
            runCatching {
                lm.getLastKnownLocation(provider)?.let { onPlace(it.latitude, it.longitude) }
                lm.requestLocationUpdates(provider, EVERY_MS, EVERY_M, listener, Looper.getMainLooper())
                started = true
            }.onFailure { FieldLog.add("LUGAR", "no pude pedir la ubicación a $provider: ${it.message}") }
        }
        FieldLog.add("LUGAR", if (started) "brújula encendida: sé en qué celda estoy" else "sin ubicación: las cartas van por eco, sin brújula")
    }

    fun stop() { if (started) runCatching { lm?.removeUpdates(listener) }; started = false }

    companion object {
        // A cell is ~50 m: asking every 15 s or 20 m is enough, and keeps the GPS from eating the battery.
        const val EVERY_MS = 15_000L
        const val EVERY_M = 20f
    }
}
