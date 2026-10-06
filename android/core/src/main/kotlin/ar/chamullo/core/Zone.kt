// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-CHAMULLO-Commercial
// Copyright (C) 2026 Jesús Nicolás Astorga y RESOURCES OPEN DOORS S.A.S
package ar.chamullo.core

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A square of the fixed grid of Discovery & Routing §2 and §10.1: a cell (~50 m), a manzana (~400 m), a barrio (~2 km),
 * a pueblo (~20 km) or a region (~150 km). Higher levels are computed from the cell by integer division, so they nest exactly.
 */
data class Zone(val level: Int, val latIndex: Long, val lonIndex: Long) {
    val centerLat get() = (latIndex + 0.5) * size(level)
    val centerLon get() = (lonIndex + 0.5) * size(level)

    /** The zone of a higher (or the same) level that contains this one. */
    fun up(to: Int): Zone {
        require(to >= level) { "a zone only goes up" }
        val k = (level until to).fold(1L) { a, l -> a * RATIOS[l] }
        return Zone(to, Math.floorDiv(latIndex, k), Math.floorDiv(lonIndex, k))
    }

    fun contains(other: Zone) = other.level <= level && other.up(level) == this

    /** Meters between centers, haversine on a 6 371 km sphere. */
    fun distanceTo(o: Zone): Double {
        val p1 = rad(centerLat); val p2 = rad(o.centerLat)
        val dp = p2 - p1; val dl = rad(o.centerLon - centerLon)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_M * asin(sqrt(h))
    }

    /** Initial great-circle bearing toward [o], degrees from north (ICEBREAK's Hunt.bearing). */
    fun bearingTo(o: Zone): Double {
        val p1 = rad(centerLat); val p2 = rad(o.centerLat); val dl = rad(o.centerLon - centerLon)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    fun encode(): ByteArray = Writer().u8(level).varint(zigzag(latIndex)).varint(zigzag(lonIndex)).bytes()

    companion object {
        const val CELL = 0
        const val MANZANA = 1
        const val BARRIO = 2
        const val PUEBLO = 3
        const val REGION = 4
        private val RATIOS = longArrayOf(8, 5, 10, 7) // cell→manzana, manzana→barrio, barrio→pueblo, pueblo→region
        // Finer than a Wi-Fi Direct reach: whoever learns my cell is already within earshot (§10.1).
        private const val CELL_DEG = 0.0005
        private const val EARTH_M = 6_371_000.0

        /** Side of a square of [level], in degrees: 0.0005, 0.004, 0.02, 0.2 and 1.4. */
        fun size(level: Int): Double = (0 until level).fold(CELL_DEG) { a, l -> a * RATIOS[l] }

        fun of(lat: Double, lon: Double, level: Int = CELL): Zone =
            Zone(CELL, floor(lat / CELL_DEG + 1e-9).toLong(), floor(lon / CELL_DEG + 1e-9).toLong()).up(level)

        fun decode(bytes: ByteArray): Zone {
            val r = Reader(bytes)
            val level = r.u8()
            require(level in CELL..REGION) { "unknown zone level" }
            return Zone(level, unzigzag(r.varint()), unzigzag(r.varint()))
        }

        fun decodeOrNull(bytes: ByteArray?): Zone? = bytes?.let { runCatching { decode(it) }.getOrNull() }

        private fun zigzag(n: Long) = (n shl 1) xor (n shr 63)
        private fun unzigzag(n: Long) = (n ushr 1) xor -(n and 1)
        private fun rad(d: Double) = Math.toRadians(d)
    }
}
