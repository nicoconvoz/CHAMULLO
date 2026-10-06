package ar.chamullo.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// The universal shout: every phone can send and hear classic 31-byte advertisements, so frames travel as micros,
// with parity micros ("comodines") that rebuild lost ones.
class MicroTest {
    private fun rebuild(micros: List<ByteArray>): ByteArray? {
        val r = MicroAssembler()
        var out: ByteArray? = null
        for (m in micros) out = r.accept(m) ?: out
        return out
    }

    @Test
    fun `every micro fits in a classic advertisement`() {
        val micros = Micro.split(ByteArray(300) { it.toByte() })
        assertTrue(micros.all { it.size <= Micro.MAX })
        assertTrue(micros.all { Micro.isMicro(it) })
    }

    @Test
    fun `micros rebuild the frame in any order, even with repeats`() {
        val frame = ByteArray(300) { (it * 7).toByte() }
        val micros = Micro.split(frame)
        assertArrayEquals(frame, rebuild((micros + micros.take(3)).reversed()))
    }

    @Test
    fun `parity costs about a quarter more micros`() {
        val frame = ByteArray(200)
        val data = (frame.size + 2 + Micro.DATA - 1) / Micro.DATA
        val total = Micro.split(frame).size
        assertTrue(total - data in 1..(data + 3) / 4, "data $data, total $total")
    }

    @Test
    fun `a lost micro is rebuilt from the parity micros, wherever it was`() {
        val frame = ByteArray(200) { (it * 31 + 5).toByte() }
        val micros = Micro.split(frame)
        for (lost in micros.indices) assertArrayEquals(frame, rebuild(micros.filterIndexed { i, _ -> i != lost }), "lost micro $lost")
    }

    @Test
    fun `one lost micro per parity group can be rebuilt at the same time`() {
        val frame = ByteArray(200) { (it * 13).toByte() }
        val micros = Micro.split(frame)
        val groups = micros.size - (frame.size + 2 + Micro.DATA - 1) / Micro.DATA
        val lost = (0 until groups).toSet() // micro i belongs to group i % groups: one loss in each group
        assertArrayEquals(frame, rebuild(micros.filterIndexed { i, _ -> i !in lost }))
    }

    @Test
    fun `two lost micros in the same group cannot be guessed, so nothing comes out half-built`() {
        val frame = ByteArray(200) { 9 }
        val micros = Micro.split(frame)
        val groups = micros.size - (frame.size + 2 + Micro.DATA - 1) / Micro.DATA
        assertNull(rebuild(micros.filterIndexed { i, _ -> i != 0 && i != groups }))
    }

    @Test
    fun `two frames shouted at the same time do not mix`() {
        val a = ByteArray(100) { 1 }
        val b = ByteArray(100) { 2 }
        val r = MicroAssembler()
        val got = mutableListOf<ByteArray>()
        for ((x, y) in Micro.split(a).zip(Micro.split(b))) { r.accept(x)?.let(got::add); r.accept(y)?.let(got::add) }
        assertEquals(2, got.size)
        assertTrue(got.any { it.contentEquals(a) } && got.any { it.contentEquals(b) })
    }

    @Test
    fun `a frame comes out only once`() {
        val micros = Micro.split(ByteArray(80) { 4 })
        val r = MicroAssembler()
        assertEquals(1, (micros + micros).mapNotNull { r.accept(it) }.size)
    }

    @Test
    fun `something that is not a micro is ignored`() {
        assertNull(MicroAssembler().accept(byteArrayOf(1, 2, 3)))
    }
}
