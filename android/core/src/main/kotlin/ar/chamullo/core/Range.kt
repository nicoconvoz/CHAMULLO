// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-CHAMULLO-Commercial
// Copyright (C) 2026 Jesús Nicolás Astorga y RESOURCES OPEN DOORS S.A.S
package ar.chamullo.core

/**
 * La prueba de largo alcance (Air Interface §12): Bluetooth LE Coded PHY (S=8) is the phone's own cousin of LoRa: each
 * bit coded and repeated, about four times the reach of plain Bluetooth, at a crawl. To know how far it really goes on
 * real phones, one shouts a tiny numbered shout every second and the other counts how many arrive while walking away.
 */
object Range {
    private val MAGIC = byteArrayOf(0x43, 0x48, 0x52) // "CHR"

    /** The first six hex digits of an id: who is shouting, as the islands name their hosts. */
    fun idOf(nodeId: ByteArray) = nodeId.toHex().take(6)

    /** "CHR" + 3 bytes of id + the number: 14 bytes, small enough for the longest-range mode. */
    fun shout(nodeId: ByteArray, seq: Long): ByteArray = Writer().raw(MAGIC).raw(nodeId.copyOf(3)).u64(seq).bytes()

    fun read(b: ByteArray): Pair<String, Long>? {
        if (b.size != 14 || !b.copyOf(3).contentEquals(MAGIC)) return null
        return b.copyOfRange(3, 6).toHex() to Reader(b, 6).u64()
    }

    class Result(val received: Int, val expected: Int, val lastRssi: Int, val coded: Boolean, val lastAt: Long) {
        val percent get() = if (expected == 0) 0 else received * 100 / expected
    }

    /** What arrived from each shouter: the numbers seen, out of those shouted since the first one heard. */
    class Stats {
        private class Track(val first: Long, var last: Long, val seen: HashSet<Long> = HashSet(), var rssi: Int = 0, var coded: Boolean = false, var at: Long = 0)
        private val tracks = HashMap<String, Track>()

        @Synchronized fun heard(id: String, seq: Long, rssi: Int, coded: Boolean, now: Long) {
            var t = tracks[id]
            if (t == null || seq < t.first || seq + 10 < t.last) t = Track(seq, seq).also { tracks[id] = it } // a new run
            t.seen += seq
            if (seq > t.last) t.last = seq
            t.rssi = rssi; t.coded = coded; t.at = now
        }

        @Synchronized fun of(id: String): Result? = tracks[id]?.let { Result(it.seen.size, (it.last - it.first + 1).toInt(), it.rssi, it.coded, it.at) }

        @Synchronized fun all(): Map<String, Result> = tracks.keys.associateWith { of(it)!! }

        @Synchronized fun clear() = tracks.clear()
    }
}
