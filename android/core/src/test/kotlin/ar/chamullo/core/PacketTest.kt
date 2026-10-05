package ar.chamullo.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PacketTest {
    private val ana = Identity.generate("Ana")
    private val dani = Identity.generate("Dani")
    private val beto = Identity.generate("Beto")

    @Test
    fun `a letter opens only for its recipient, who learns the real sender`() {
        val env = Envelope.letter(ana, dani.card(), Letter.Text("hola Dani"), now = 1_000)
        val parsed = Packet.parse(env.encode()) as Envelope
        assertTrue(parsed.verify())
        assertTrue(parsed.isFor(dani))
        assertFalse(parsed.isFor(beto))
        val opened = parsed.open(dani)!!
        assertEquals("hola Dani", (opened.body as Letter.Text).text)
        assertEquals(ana.nodeId.toHex(), opened.sender.toHex())
        assertNull(parsed.open(beto))
    }

    @Test
    fun `carriers see neither the real sender nor the recipient, and the tag changes with every letter`() {
        val e1 = Envelope.letter(ana, dani.card(), Letter.Text("1"), now = 1_000)
        val e2 = Envelope.letter(ana, dani.card(), Letter.Text("2"), now = 1_000)
        val wire = e1.encode().toHex()
        assertFalse(wire.contains(ana.nodeId.toHex()))
        assertFalse(wire.contains(dani.nodeId.toHex()))
        assertNotEquals(e1.dstTag.toHex(), e2.dstTag.toHex())
        assertNotEquals(e1.src.toHex(), e2.src.toHex())
    }

    @Test
    fun `touching any byte of the origin section breaks the seal`() {
        val bytes = Envelope.letter(ana, dani.card(), Letter.Text("hola"), now = 1_000).encode()
        bytes[40] = (bytes[40] + 1).toByte()
        assertFalse((Packet.parse(bytes) as Envelope).verify())
    }

    @Test
    fun `carriers may raise the hop count without breaking the seal`() {
        val env = Envelope.letter(ana, dani.card(), Letter.Text("hola"), now = 1_000)
        val hopped = Packet.parse(env.withHop().encode()) as Envelope
        assertEquals(1, hopped.hopCount)
        assertTrue(hopped.verify())
        assertEquals(env.msgId.toHex(), hopped.msgId.toHex())
    }

    @Test
    fun `a delivery receipt names the letter it confirms`() {
        val letter = Envelope.letter(ana, dani.card(), Letter.Text("hola"), now = 1_000)
        val ack = Envelope.letter(dani, ana.card(), Letter.Ack(letter.msgId), now = 2_000)
        val opened = (Packet.parse(ack.encode()) as Envelope).open(ana)!!
        assertEquals(letter.msgId.toHex(), (opened.body as Letter.Ack).msgId.toHex())
    }

    @Test
    fun `beacons are signed and carry the box key needed to hand over a card privately`() {
        val b = Packet.parse(Beacon.of(beto, coded = true, now = 5_000).encode()) as Beacon
        assertTrue(b.verify())
        assertEquals("Beto", b.name)
        assertEquals(beto.boxPublic.toHex(), b.boxPublic.toHex())
        assertTrue(b.coded)
    }

    @Test
    fun `a card travels sealed to its neighbor - only that neighbor can read it`() {
        val beaconDani = Beacon.of(dani, coded = false, now = 1)
        val offer = Packet.parse(CardOffer.to(ana, beaconDani, now = 2).encode()) as CardOffer
        assertNull(offer.open(beto))
        val card = offer.open(dani)!!
        assertTrue(card.verify())
        assertEquals("Ana", card.name)
        assertEquals(ana.tagSecret.toHex(), card.tagSecret.toHex())
        assertFalse(offer.encode().toHex().contains(ana.tagSecret.toHex()))
    }

    @Test
    fun `an expired letter is recognized as such`() {
        val env = Envelope.letter(ana, dani.card(), Letter.Text("hola"), now = 1_000, ttlMs = 10_000)
        assertFalse(env.expired(5_000))
        assertTrue(env.expired(11_001))
    }

    @Test
    fun `garbage is not a packet`() {
        assertNull(Packet.parseOrNull(byteArrayOf(1, 2, 3)))
        assertNotNull(Packet.parseOrNull(Beacon.of(ana, false, 1).encode()))
    }
}
