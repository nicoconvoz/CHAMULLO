// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-CHAMULLO-Commercial
// Copyright (C) 2026 Jesús Nicolás Astorga y RESOURCES OPEN DOORS S.A.S
package ar.chamullo.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.sin

/** La voz que no se corta (Camino y Carretera §7.3): μ-law, each piece three times, numbered. */
class VoiceTest {
    private fun tone(seed: Int) = ByteArray(Voice.PCM_PIECE).also { b ->
        for (i in 0 until Voice.PCM_PIECE / 2) {
            val v = (sin((i + seed * 160) * 0.05) * 12_000).toInt()
            b[2 * i] = v.toByte(); b[2 * i + 1] = (v shr 8).toByte()
        }
    }

    private fun sample(b: ByteArray, i: Int) = ((b[2 * i + 1].toInt() shl 8) or (b[2 * i].toInt() and 0xff)).toShort().toInt()

    @Test
    fun `mu-law halves the voice and keeps it close to the original`() {
        val pcm = tone(1)
        val packed = Voice.muLaw(pcm)
        assertEquals(Voice.PCM_PIECE / 2, packed.size)
        val back = Voice.linear(packed)
        for (i in 0 until Voice.PCM_PIECE / 2) {
            val a = sample(pcm, i); val b = sample(back, i)
            assertTrue(abs(a - b) <= maxOf(64, abs(a) / 16), "sample $i: $a vs $b")
        }
    }

    @Test
    fun `each piece travels three times, so a lost packet costs nothing`() {
        val packer = Voice.Packer()
        val packets = (0 until 10).map { packer.next(tone(it)) }
        val unpacker = Voice.Unpacker()
        val got = HashSet<Long>()
        for ((k, p) in packets.withIndex()) if (k != 4 && k != 7) unpacker.accept(p).forEach { got += it.first }
        assertEquals((0L until 10L).toSet(), got, "pieces 4 and 7 came again in the packets after them")
    }

    @Test
    fun `a piece that arrives again or late is not played twice`() {
        val packer = Voice.Packer()
        val a = packer.next(tone(0)); val b = packer.next(tone(1))
        val u = Voice.Unpacker()
        assertEquals(listOf(0L), u.accept(a).map { it.first })
        assertEquals(listOf(1L), u.accept(b).map { it.first }, "piece 0 inside b was already there")
        assertTrue(u.accept(b).isEmpty(), "the same packet twice (by the pipe and by UDP)")
    }

    @Test
    fun `the jitter buffer plays in order, waits a little, and conceals what never came`() {
        val buf = Voice.Playout(delay = 3)
        assertNull(buf.next(), "gathering")
        buf.put(0, tone(0)); buf.put(2, tone(2)); buf.put(1, tone(1))
        assertNull(buf.next(), "still gathering: three pieces of margin")
        buf.put(3, tone(3))
        assertEquals(0L, buf.next()!!.first)
        assertEquals(1L, buf.next()!!.first)
        assertEquals(2L, buf.next()!!.first)
        buf.put(5, tone(5)); buf.put(6, tone(6)); buf.put(7, tone(7))
        assertEquals(3L, buf.next()!!.first)
        val gap = buf.next()!!
        assertEquals(4L, gap.first); assertTrue(gap.second == null, "piece 4 never came: conceal it")
        assertEquals(5L, buf.next()!!.first)
    }

    @Test
    fun `a phone before 0_9_9 still sounds - its raw piece is taken as is`() {
        val raw = tone(3)
        assertTrue(Voice.isLegacy(raw))
        assertTrue(!Voice.isLegacy(Voice.Packer().next(raw)))
    }
}
