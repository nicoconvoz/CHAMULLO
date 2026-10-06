package ar.chamullo.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Identity §7: whoever opens a pipe on an island proves who they are with their key before anything else passes.
class HandshakeTest {
    private val ana = Identity.generate("Ana")
    private val beto = Identity.generate("Beto")

    @Test
    fun `two phones prove who they are to each other`() {
        val a = Handshake(ana); val b = Handshake(beto)
        val proofFromB = b.onHello(a.hello())!!
        val proofFromA = a.onHello(b.hello())!!
        assertTrue(a.onProof(proofFromB))
        assertTrue(b.onProof(proofFromA))
        assertArrayEquals(beto.nodeId, a.peer)
        assertArrayEquals(ana.nodeId, b.peer)
    }

    @Test
    fun `a proof signed by someone else is refused`() {
        val a = Handshake(ana); val b = Handshake(beto)
        a.onHello(b.hello())
        val impostor = Handshake(Identity.generate("Intruso"))
        impostor.onHello(a.hello())
        // The impostor claims to be Beto: its hello carried Beto's id, but it cannot sign as Beto.
        val forged = Handshake.proofFrame(Identity.generate("Intruso").sign("HS", ByteArray(0)))
        assertFalse(a.onProof(forged))
        assertNull(a.peer)
    }

    @Test
    fun `a proof from an old conversation cannot be replayed`() {
        val a1 = Handshake(ana); val b1 = Handshake(beto)
        a1.onHello(b1.hello())
        val oldProof = b1.onHello(a1.hello())!!
        val a2 = Handshake(ana) // new pipe, new nonce
        a2.onHello(Handshake(beto).hello())
        assertFalse(a2.onProof(oldProof))
    }

    @Test
    fun `handshake frames are recognized and nothing else is`() {
        val h = Handshake(ana)
        assertTrue(Handshake.isHandshake(h.hello()))
        assertFalse(Handshake.isHandshake(Beacon.of(ana, false, 1).encode()))
    }
}
