// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-CHAMULLO-Commercial
// Copyright (C) 2026 Jesús Nicolás Astorga y RESOURCES OPEN DOORS S.A.S
package ar.chamullo.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** La prueba de largo alcance (Air Interface §12): a numbered shout per second, and how many of them arrive. */
class RangeTest {
    @Test
    fun `a range shout carries who and which number, and nothing else reads as one`() {
        val id = ByteArray(32) { 7 }
        val p = Range.shout(id, 42)
        val back = Range.read(p)!!
        assertEquals(Range.idOf(id), back.first); assertEquals(42L, back.second)
        assertTrue(p.size <= 20, "small: it has to fit the longest-range mode")
        assertNull(Range.read(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `it counts what arrived out of what was shouted, with the last signal and the mode`() {
        val s = Range.Stats()
        s.heard("ab12cd", 10, rssi = -60, coded = true, now = 0)
        s.heard("ab12cd", 11, rssi = -70, coded = true, now = 1_000)
        s.heard("ab12cd", 11, rssi = -70, coded = true, now = 1_000) // the same shout heard twice
        s.heard("ab12cd", 14, rssi = -90, coded = true, now = 4_000)
        val r = s.of("ab12cd")!!
        assertEquals(3, r.received); assertEquals(5, r.expected)
        assertEquals(60, r.percent)
        assertEquals(-90, r.lastRssi)
        assertTrue(r.coded)
    }

    @Test
    fun `a shouter that started again counts from zero`() {
        val s = Range.Stats()
        s.heard("ab12cd", 50, -60, false, 0); s.heard("ab12cd", 51, -60, false, 1_000)
        s.heard("ab12cd", 0, -60, false, 2_000)
        assertEquals(1, s.of("ab12cd")!!.received)
        assertEquals(1, s.of("ab12cd")!!.expected)
    }
}
