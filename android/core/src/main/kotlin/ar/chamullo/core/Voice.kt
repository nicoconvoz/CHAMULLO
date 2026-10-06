// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-CHAMULLO-Commercial
// Copyright (C) 2026 Jesús Nicolás Astorga y RESOURCES OPEN DOORS S.A.S
package ar.chamullo.core

/**
 * La voz que no se corta (Camino y Carretera §7.3). A call's voice goes in 20 ms pieces; over a weak link some packets
 * are lost and each loss was a hole in the voice. Now each piece is numbered, squeezed to half with μ-law (G.711, the
 * telephone's own), and travels three times: in its packet and again in the next two. A piece is lost only if three
 * packets in a row are. The receiver orders them, drops repeats and plays them with a margin of a few pieces.
 */
object Voice {
    /** 20 ms of 16 kHz mono 16-bit PCM, as the microphone gives it. */
    const val PCM_PIECE = 640
    /** The same piece in μ-law: one byte per sample. */
    const val LAW_PIECE = PCM_PIECE / 2
    /** Each packet carries its piece and the two before it. */
    const val COPIES = 3
    private const val MAGIC: Byte = 0x56 // 'V'

    /** A packet from a phone before 0.9.9: one raw PCM piece, no number. */
    fun isLegacy(payload: ByteArray) = payload.size == PCM_PIECE

    fun muLaw(pcm: ByteArray): ByteArray = ByteArray(pcm.size / 2) { i ->
        encode(((pcm[2 * i + 1].toInt() shl 8) or (pcm[2 * i].toInt() and 0xff)).toShort().toInt())
    }

    fun linear(law: ByteArray): ByteArray {
        val out = ByteArray(law.size * 2)
        for (i in law.indices) { val v = decode(law[i]); out[2 * i] = v.toByte(); out[2 * i + 1] = (v shr 8).toByte() }
        return out
    }

    // G.711 μ-law, as in the ITU recommendation.
    private fun encode(sample: Int): Byte {
        var s = sample
        val sign = if (s < 0) { s = -s; 0x80 } else 0
        if (s > 32_635) s = 32_635
        s += 0x84
        var exponent = 7
        var mask = 0x4000
        while (exponent > 0 && s and mask == 0) { exponent--; mask = mask shr 1 }
        val mantissa = (s shr (exponent + 3)) and 0x0f
        return (sign or (exponent shl 4) or mantissa).inv().toByte()
    }

    private fun decode(b: Byte): Int {
        val u = b.toInt().inv() and 0xff
        val exponent = (u shr 4) and 0x07
        val mantissa = u and 0x0f
        val magnitude = (((mantissa shl 3) + 0x84) shl exponent) - 0x84
        return if (u and 0x80 != 0) -magnitude else magnitude
    }

    /** The sender: numbers each piece and packs it with the previous [COPIES] - 1. */
    class Packer {
        private var seq = 0L
        private val recent = ArrayDeque<ByteArray>()

        fun next(pcm: ByteArray): ByteArray {
            recent.addFirst(muLaw(pcm))
            while (recent.size > COPIES) recent.removeLast()
            val w = Writer().u8(MAGIC.toInt()).u64(seq).u8(recent.size)
            for (p in recent) w.raw(p)
            seq++
            return w.bytes()
        }
    }

    /** The receiver: the pieces of a packet it did not have yet, oldest first, back in PCM. */
    class Unpacker {
        private val seen = LinkedHashSet<Long>()

        fun accept(payload: ByteArray): List<Pair<Long, ByteArray>> {
            val r = runCatching { Reader(payload) }.getOrNull() ?: return emptyList()
            return runCatching {
                if (r.u8() != MAGIC.toInt()) return emptyList()
                val seq = r.u64(); val n = r.u8()
                val pieces = (0 until n).map { seq - it to r.take(LAW_PIECE) }
                pieces.filter { (s, _) -> s >= 0 && seen.add(s) }.sortedBy { it.first }.map { (s, law) -> s to linear(law) }
                    .also { while (seen.size > 512) seen.remove(seen.first()) }
            }.getOrDefault(emptyList())
        }
    }

    /**
     * The jitter buffer: starts once [delay] pieces of margin have gathered, then gives one piece per 20 ms in order.
     * A missing piece with later ones already here is given as null (conceal it); with nothing here, null overall
     * (fill with silence without moving on). Too far behind, it jumps ahead to keep the voice live.
     */
    class Playout(private val delay: Int = 5, private val maxLag: Int = 15) {
        private val pieces = HashMap<Long, ByteArray>()
        private var head = -1L

        @Synchronized fun put(seq: Long, pcm: ByteArray) { if (head < 0 || seq >= head) pieces[seq] = pcm }

        @Synchronized fun next(): Pair<Long, ByteArray?>? {
            if (pieces.isEmpty()) return null
            val newest = pieces.keys.max()
            if (head < 0) {
                val oldest = pieces.keys.min()
                if (newest - oldest < delay) return null
                head = oldest
            }
            if (newest - head > maxLag) { head = newest - delay; pieces.keys.removeAll { it < head } }
            pieces.remove(head)?.let { return head++ to it }
            return if (newest > head) head++ to null else null
        }

        /** Forget everything (a long silence): gather again before sounding. */
        @Synchronized fun reset() { pieces.clear(); head = -1 }
    }
}
