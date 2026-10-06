// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-CHAMULLO-Commercial
// Copyright (C) 2026 Jesús Nicolás Astorga y RESOURCES OPEN DOORS S.A.S
package ar.chamullo.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** GRITO+ (Air Interface §4.1): Reed-Solomon spare pieces - any n of the n + r pieces rebuild the frame. */
class ErasureTest {
    private fun pieces(n: Int, rnd: Random) = List(n) { ByteArray(16).also(rnd::nextBytes) }

    @Test
    fun `any n of the n + r pieces rebuild the data, whichever were lost`() {
        val rnd = Random(7)
        repeat(200) {
            val n = rnd.nextInt(1, 40); val r = rnd.nextInt(1, 30)
            val data = pieces(n, rnd)
            val all = data + Erasure.parity(data, r)
            val kept = all.indices.shuffled(rnd).take(n).sorted()
            val back = Erasure.recover(n, kept.associateWith { all[it] })!!
            for (i in 0 until n) assertArrayEquals(data[i], back[i], "n=$n r=$r kept=$kept")
        }
    }

    @Test
    fun `with fewer than n pieces it does not invent anything`() {
        val rnd = Random(3)
        val data = pieces(6, rnd)
        val all = data + Erasure.parity(data, 6)
        assertNull(Erasure.recover(6, (0 until 5).associateWith { all[it + 4] }))
    }

    @Test
    fun `a grito+ frame survives losing a third of its shouts, where a single spare per group did not`() {
        val rnd = Random(11)
        val frame = ByteArray(240).also(rnd::nextBytes)
        val micros = Micro.split(frame)
        val lose = micros.size / 3
        var ok = 0
        repeat(50) {
            val gone = micros.indices.shuffled(rnd).take(lose).toSet()
            val a = MicroAssembler()
            val got = micros.filterIndexed { i, _ -> i !in gone }.firstNotNullOfOrNull { a.accept(it) }
            if (got != null && got.contentEquals(frame)) ok++
        }
        assertTrue(ok == 50, "rebuilt $ok of 50 with a third of the shouts lost")
    }

    @Test
    fun `old micros with a single spare per group still assemble`() {
        val frame = ByteArray(60) { it.toByte() }
        val old = Micro.splitXor(frame)
        val a = MicroAssembler()
        assertArrayEquals(frame, old.firstNotNullOfOrNull { a.accept(it) })
    }
}
