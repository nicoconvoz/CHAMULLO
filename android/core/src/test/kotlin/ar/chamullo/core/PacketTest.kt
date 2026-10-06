package ar.chamullo.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
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

    @Test
    fun `a plaza message is signed by its author and anyone nearby can read it`() {
        val p = Packet.parse(Plaza.of(ana, "hola plaza", now = 7).encode()) as Plaza
        assertTrue(p.verify())
        assertEquals("hola plaza", p.text)
        assertEquals("Ana", p.name)
        val forged = Plaza(p.nodeId, p.ts, "otra cosa", p.name, p.nonce, p.sig)
        assertFalse(forged.verify())
    }

    @Test
    fun `a heard receipt names who heard which plaza message`() {
        val msg = Plaza.of(ana, "hola", now = 1)
        val h = Packet.parse(Heard.of(beto, msg.id, now = 2).encode()) as Heard
        assertTrue(h.verify())
        assertEquals(msg.id, h.plazaId)
        assertEquals("Beto", h.name)
    }

    @Test
    fun `a road key travels sealed to one neighbor and is signed by its owner`() {
        val beaconDani = Beacon.of(dani, coded = false, now = 1)
        val invite = Packet.parse(RoadInvite.to(ana, beaconDani.nodeId, beaconDani.boxPublic, "DIRECT-CH-ana", "clave-secreta-123", now = 2).encode()) as RoadInvite
        assertNull(invite.open(beto))
        val road = invite.open(dani)!!
        assertEquals("DIRECT-CH-ana", road.ssid)
        assertEquals("clave-secreta-123", road.passphrase)
        assertFalse(invite.encode().toHex().contains("clave-secreta-123".toByteArray().toHex()))
    }

    /* ---------- the compass fields (Discovery & Routing v0.2 §10) ---------- */

    private val barrio = Zone.of(-34.6037, -58.3816, Zone.BARRIO)
    private val elsewhere = Zone.of(-31.4201, -64.1888, Zone.BARRIO)
    private val caro = Identity.generate("Caro")

    @Test
    fun `a card carries its barrio signed, and an old card without zone still works`() {
        val c = dani.card(now = 5, zone = barrio)
        val back = Card.decode(c.encode())
        assertEquals(barrio, back.zone)
        assertTrue(back.verify())
        assertFalse(Card(c.nodeId, c.boxPublic, c.tagSecret, c.name, c.ts, c.sig, elsewhere).verify())
        val old = Card.decode(dani.card(now = 5).encode())
        assertNull(old.zone)
        assertTrue(old.verify())
    }

    @Test
    fun `a beacon tells its cell, signed`() {
        val cell = Zone.of(-34.6037, -58.3816)
        val b = Packet.parse(Beacon.of(beto, false, 1, cell).encode()) as Beacon
        assertEquals(cell, b.cell)
        assertTrue(b.verify())
        assertFalse(Beacon(b.nodeId, b.boxPublic, b.ts, b.name, b.coded, b.sig, Zone.of(-31.4201, -64.1888)).verify())
    }

    @Test
    fun `the destination zone is sealed by the origin, the transit fields are rewritten by each giver`() {
        val env = Envelope.letter(ana, dani.card(zone = barrio), Letter.Text("hola"), now = 1_000)
        val short = beto.nodeId.copyOf(8)
        val back = Packet.parse(env.routed(listOf(short), localTtl = 4, detour = 1).encode()) as Envelope
        assertEquals(barrio, back.destZone)
        assertTrue(back.verify())
        assertEquals(4, back.localTtl)
        assertEquals(1, back.detour)
        assertArrayEquals(short, back.receivers.single())
        val plain = Packet.parse(back.routed(emptyList(), null, 0).encode()) as Envelope
        assertTrue(plain.receivers.isEmpty() && plain.localTtl == null && plain.detour == 0 && plain.verify())
    }

    @Test
    fun `each hop names giver and taker, signed by both, and the recipient checks the chain`() {
        val sealed = Envelope.seal(ana, dani.card(), Letter.Text("x"), 1_000)
        var env = sealed.envelope
        val a0 = env.offer(env.src, beto.nodeId).accept(beto, 1_001)
        assertTrue(env.accepts(a0, env.src))
        env = env.withHop(sealed.signer(), 1_001, a0, alternatives = 2)
        val a1 = (Packet.parse(env.offer(beto.nodeId, caro.nodeId).encode()) as Offer).accept(caro, 1_002)
        env = env.withHop(beto.signer(), 1_002, Packet.parse(a1.encode()) as Accept, alternatives = 1)
        env = env.withHop(caro.signer(), 1_003) // a shout to everyone: no taker
        assertTrue(env.verify(), "hops never touch what the origin sealed")
        assertEquals(1_000, env.ts)
        val hops = env.journey(env.open(dani)!!.journeySecret!!)
        assertTrue(hops.all { it.valid })
        assertEquals(listOf(2, 1, 0), hops.map { it.alternatives })
        assertArrayEquals(env.src, hops[0].giver)
        assertArrayEquals(beto.nodeId, hops[0].taker)
        assertArrayEquals(beto.nodeId, hops[1].giver)
        assertEquals(0, hops[2].taker.size)
    }

    @Test
    fun `a hop whose giver is not the previous taker does not count, nor a forged acceptance`() {
        val sealed = Envelope.seal(ana, dani.card(), Letter.Text("x"), 1_000)
        var env = sealed.envelope
        env = env.withHop(sealed.signer(), 1_001, env.offer(env.src, beto.nodeId).accept(beto, 1_001))
        val real = env.offer(caro.nodeId, dani.nodeId).accept(caro, 1_002)
        val forged = Accept(dani.nodeId, real.giver, real.msgId, real.i, real.ts, real.sig) // names Dani, signed by Caro
        assertFalse(env.accepts(forged, caro.nodeId))
        env = env.withHop(caro.signer(), 1_002) // caro was never handed it
        val hops = env.journey(env.open(dani)!!.journeySecret!!)
        assertTrue(hops[0].valid)
        assertFalse(hops[1].valid)
    }

    @Test
    fun `every letter carries the sender's current card, and a moving letter carries only that`() {
        val card = ana.card(now = 9, zone = barrio)
        val env = Envelope.letter(ana, dani.card(), Letter.Text("hola"), now = 1_000, senderCard = card)
        assertEquals(barrio, env.open(dani)!!.senderCard!!.zone)
        val moved = Envelope.letter(ana, dani.card(), Letter.Moved(card), now = 1_000)
        val body = moved.open(dani)!!.body
        assertTrue(body is Letter.Moved)
        assertEquals(barrio, (body as Letter.Moved).card.zone)
    }

    @Test
    fun `a payment can be pointed at a zone without touching what the origin signed`() {
        val sealed = Envelope.seal(ana, dani.card(), Letter.Text("x"), 1_000)
        val pay = Payment.confirm(sealed, listOf(ByteArray(32)), ByteArray(32), byteArrayOf(1)).routed(barrio, listOf(beto.nodeId.copyOf(8)), 5)
        val back = Packet.parse(pay.encode()) as Payment
        assertEquals(barrio, back.zone)
        assertEquals(5, back.ttl)
        assertEquals(1, back.receivers.size)
        assertTrue(Identity.verify(back.src, "CONF", back.msgId + back.journeyHash(), back.confirm))
    }

    /* ---------- cobrar: the carrier keeps the key of its record (Proof of Relay §7) ---------- */

    private fun journeyOf(): Triple<Sealed, Envelope, List<HopKey>> {
        val sealed = Envelope.seal(ana, dani.card(), Letter.Text("x"), 1_000)
        var env = sealed.envelope
        val keys = ArrayList<HopKey>()
        env.withHopKept(sealed.signer(), 1_001, env.offer(env.src, beto.nodeId).accept(beto, 1_001), 3).let { (e, k) -> env = e; keys += k }
        env.withHopKept(beto.signer(), 1_002, env.offer(beto.nodeId, caro.nodeId).accept(caro, 1_002), 1).let { (e, k) -> env = e; keys += k }
        env.withHopKept(caro.signer(), 1_003).let { (e, k) -> env = e; keys += k }
        return Triple(sealed, env, keys)
    }

    @Test
    fun `the destination's receipt says which hops held, and the confirmation seals exactly that`() {
        val (sealed, env, _) = journeyOf()
        val opened = env.open(dani)!!
        val validity = Journey.validity(env.journey(opened.journeySecret!!))
        assertEquals(listOf(true, true, true), (0 until 3).map { Journey.bit(validity, it) })
        val j = Journey.confirm(sealed, env.blobs.map { Crypto.hash(it) }, opened.deliverySecret!!, validity)
        assertTrue(j.verify())
        assertFalse(Journey(j.origin, j.hashes, j.revealed, Journey.validity(listOf(Hop(ByteArray(0), false))), j.confirm).verify(), "another validity, another seal")
        assertFalse(Journey(j.origin, j.hashes, Crypto.randomBytes(32), j.validity, j.confirm).verify(), "only the reader knew r")
    }

    @Test
    fun `a carrier proves its hop with the key it kept, and nobody else can`() {
        val (sealed, env, keys) = journeyOf()
        val opened = env.open(dani)!!
        val j = Journey.confirm(sealed, env.blobs.map { Crypto.hash(it) }, opened.deliverySecret!!, Journey.validity(env.journey(opened.journeySecret!!)))
        val betoClaim = Claim(sealed.envelope.originSection(), keys[1])
        val ok = betoClaim.check(j)!!
        assertArrayEquals(beto.nodeId, ok.giver)
        assertArrayEquals(caro.nodeId, ok.taker)
        assertEquals(1, ok.alternatives)
        assertEquals(null, Claim(sealed.envelope.originSection(), keys[0]).check(j), "the origin does not charge for its own letter")
        val forged = HopKey(1, Crypto.randomBytes(32), keys[1].blob, keys[1].record)
        assertEquals(null, Claim(sealed.envelope.originSection(), forged).check(j), "without the key, no proof")
        val back = Packet.parse(betoClaim.encode()) as Claim
        assertArrayEquals(beto.nodeId, back.check(j)!!.giver)
    }
}
