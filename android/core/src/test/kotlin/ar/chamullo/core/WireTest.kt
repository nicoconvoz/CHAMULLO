// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-CHAMULLO-Commercial
// Copyright (C) 2026 Jesús Nicolás Astorga y RESOURCES OPEN DOORS S.A.S
package ar.chamullo.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class WireTest {
    @Test
    fun `varints use the shortest QUIC encoding and round-trip`() {
        for ((v, bytes) in listOf(0L to "00", 63L to "3f", 64L to "4040", 16383L to "7fff", 16384L to "80004000", 1_073_741_824L to "c000000040000000")) {
            val w = Writer().varint(v).bytes()
            assertEquals(bytes, w.toHex(), "encoding of $v")
            assertEquals(v, Reader(w).varint())
        }
    }

    @Test
    fun `a non-minimal varint is rejected`() {
        assertThrows<WireException> { Reader(hex("4001")).varint() }
    }

    @Test
    fun `tlv streams are canonical - strictly increasing types, no duplicates`() {
        val stream = Tlv.encode(listOf(2L to byteArrayOf(1), 7L to byteArrayOf(2, 3)))
        assertArrayEquals(byteArrayOf(2, 3), Tlv.decode(stream).getValue(7))
        assertThrows<WireException> { Tlv.encode(listOf(7L to byteArrayOf(), 2L to byteArrayOf())) }
        assertThrows<WireException> { Tlv.decode(hex("070102" + "0201" + "01")) }
        assertThrows<WireException> { Tlv.decode(hex("0201" + "01" + "0201" + "02")) }
    }

    @Test
    fun `even-odd rule - an unknown odd field is ignored, an unknown even field is refused`() {
        val known = setOf(2L)
        Tlv.requireKnown(Tlv.decode(Tlv.encode(listOf(2L to byteArrayOf(1), 9L to byteArrayOf(1)))), known)
        assertThrows<WireException> { Tlv.requireKnown(Tlv.decode(Tlv.encode(listOf(2L to byteArrayOf(1), 8L to byteArrayOf(1)))), known) }
    }

    @Test
    fun `fragments split a frame bigger than the radio limit and reassemble it in any order`() {
        val frame = ByteArray(1000) { it.toByte() }
        val parts = Fragments.split(frame, 300)
        assertEquals(true, parts.all { it.size <= 300 })
        val r = Reassembler()
        var out: ByteArray? = null
        for (p in parts.reversed()) out = r.accept(p) ?: out
        assertArrayEquals(frame, out)
    }

    @Test
    fun `a frame that already fits is not fragmented`() {
        val frame = ByteArray(100)
        assertEquals(listOf(frame.toHex()), Fragments.split(frame, 300).map { it.toHex() })
    }
}
