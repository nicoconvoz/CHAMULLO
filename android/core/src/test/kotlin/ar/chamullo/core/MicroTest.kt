package ar.chamullo.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// The universal shout: every phone can send and hear classic 31-byte advertisements, so frames travel as micros.
class MicroTest {
    @Test
    fun `every micro fits in a classic advertisement`() {
        val frame = ByteArray(300) { it.toByte() }
        val micros = Micro.split(frame)
        assertTrue(micros.all { it.size <= Micro.MAX })
        assertTrue(micros.all { Micro.isMicro(it) })
    }

    @Test
    fun `micros rebuild the frame in any order, even with repeats`() {
        val frame = ByteArray(300) { (it * 7).toByte() }
        val micros = Micro.split(frame)
        val r = MicroAssembler()
        var out: ByteArray? = null
        for (m in (micros + micros.take(3)).reversed()) out = r.accept(m) ?: out
        assertArrayEquals(frame, out)
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
    fun `a frame missing a micro never comes out half-built`() {
        val micros = Micro.split(ByteArray(100) { 3 })
        val r = MicroAssembler()
        for (m in micros.drop(1)) assertNull(r.accept(m))
    }

    @Test
    fun `something that is not a micro is ignored`() {
        assertNull(MicroAssembler().accept(byteArrayOf(1, 2, 3)))
    }
}
