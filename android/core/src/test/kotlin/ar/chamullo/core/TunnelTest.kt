package ar.chamullo.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** El túnel de datos (Discovery & Routing §11.3, Economy & Governance §13): Internet lent inside the island, paid with receipts. */
class TunnelTest {
    private val mb = 1024L * 1024

    /* ---------- the plan: how many Lucas a number of bytes costs ---------- */

    @Test
    fun `bytes are paid pack by pack, oldest first, at each pack's own price`() {
        val packs = listOf(DataPlan.Pack(50, 25), DataPlan.Pack(1024, 350))
        assertEquals(0, DataPlan.owed(packs, 0))
        assertEquals(25, DataPlan.owed(packs, 50 * mb), "the first pack, whole")
        assertEquals(25 + 175, DataPlan.owed(packs, 50 * mb + 512 * mb), "plus half of the second")
        assertEquals(375, DataPlan.owed(packs, 10_000 * mb), "never more than what was paid")
        assertEquals(1074 * mb, DataPlan.total(packs))
        assertEquals(1074 * mb - 60 * mb, DataPlan.left(packs, 60 * mb))
        assertEquals(0, DataPlan.left(packs, 2000 * mb))
    }

    /* ---------- the receipt: the buyer signs what it used ---------- */

    @Test
    fun `a receipt is signed by the buyer and travels intact`() {
        val buyer = Identity.generate("Compra"); val lender = Identity.generate("Presta")
        val r = DataReceipt.of(buyer, lender.nodeId, ByteArray(16) { 7 }, 3 * mb, 2, 1_000)
        val back = DataReceipt.decode(r.encode())
        assertTrue(back.verify())
        assertArrayEquals(lender.nodeId, back.lender)
        assertEquals(3 * mb, back.bytes); assertEquals(2, back.lucas)
        val forged = DataReceipt(back.buyer, back.lender, back.session, back.bytes, 200, back.ts, back.sig)
        assertFalse(forged.verify(), "nobody raises the amount")
    }

    /* ---------- the ledger: packs wait in escrow, receipts pay the lender ---------- */

    private fun bookWithWage(founder: Identity): Ledger {
        val l = Ledger(Ledger.Params(Zone.of(-34.59, -58.43, Zone.PUEBLO), founder.nodeId, periodMs = 60_000, genesisContributors = 50))
        assertTrue(l.accept(l.propose(founder, emptyList(), 10)))
        assertTrue(l.accept(l.propose(founder, listOf(l.closePeriod(60_001)), 60_001)))
        return l
    }

    @Test
    fun `a data pack is not burned nor given to the store - it waits for whoever lends the Internet`() {
        val buyer = Identity.generate("Fundador")
        val l = bookWithWage(buyer)
        val pack = Shop.Item("datos-test", "Test", "", 10, mb = 50)
        assertTrue(l.accept(l.propose(buyer, listOf(Shop.buy(buyer, pack, Identity.generate("Tienda").nodeId, 60_002)), 60_002)))
        assertEquals(Ledger.KING_WAGE - 10, l.balance(buyer.nodeId))
        assertEquals(10, l.dataEscrow(buyer.nodeId))
        assertEquals(Ledger.KING_WAGE, l.supply(), "nothing burned")
    }

    @Test
    fun `receipts pay the lender from the buyer's escrow, only the difference, never twice nor beyond the escrow`() {
        val buyer = Identity.generate("Fundador"); val lender = Identity.generate("Presta")
        val l = bookWithWage(buyer)
        val pack = Shop.Item("datos-test", "Test", "", 10, mb = 50)
        assertTrue(l.accept(l.propose(buyer, listOf(Shop.buy(buyer, pack, ByteArray(32), 60_002)), 60_002)))
        val session = ByteArray(16) { 1 }
        val first = DataReceipt.of(buyer, lender.nodeId, session, 10 * mb, 2, 60_003)
        assertTrue(l.accept(l.propose(buyer, listOf(Entry.data(first)), 60_003)))
        assertEquals(2, l.balance(lender.nodeId))
        assertFalse(l.accept(l.propose(buyer, listOf(Entry.data(first)), 60_004)), "the same receipt pays once")
        val later = DataReceipt.of(buyer, lender.nodeId, session, 25 * mb, 5, 60_005)
        assertTrue(l.accept(l.propose(buyer, listOf(Entry.data(later)), 60_005)))
        assertEquals(5, l.balance(lender.nodeId), "only the difference")
        val greedy = DataReceipt.of(buyer, lender.nodeId, ByteArray(16) { 2 }, 900 * mb, 900, 60_006)
        assertTrue(l.accept(l.propose(buyer, listOf(Entry.data(greedy)), 60_006)))
        assertEquals(10, l.balance(lender.nodeId), "never beyond what the buyer put in escrow")
        assertEquals(0, l.dataEscrow(buyer.nodeId))
        val empty = DataReceipt.of(buyer, lender.nodeId, ByteArray(16) { 3 }, mb, 1, 60_007)
        assertFalse(l.accept(l.propose(buyer, listOf(Entry.data(empty)), 60_007)), "an empty escrow pays nothing")
    }

    @Test
    fun `a receipt signed by someone else does not move the buyer's Lucas`() {
        val buyer = Identity.generate("Fundador"); val thief = Identity.generate("Ladrón")
        val l = bookWithWage(buyer)
        assertTrue(l.accept(l.propose(buyer, listOf(Shop.buy(buyer, Shop.Item("datos-test", "Test", "", 10, mb = 50), ByteArray(32), 60_002)), 60_002)))
        val fake = DataReceipt.of(thief, thief.nodeId, ByteArray(16), mb, 5, 60_003)
        val swapped = DataReceipt(buyer.nodeId, thief.nodeId, fake.session, fake.bytes, fake.lucas, fake.ts, fake.sig)
        assertFalse(l.accept(l.propose(buyer, listOf(Entry.data(swapped)), 60_003)))
        assertEquals(10, l.dataEscrow(buyer.nodeId))
    }

    /* ---------- the lender: serves on a short credit, and only to the outside ---------- */

    @Test
    fun `the lender serves on a short credit and stops until the buyer signs`() {
        val buyer = Identity.generate("Compra"); val lender = Identity.generate("Presta")
        val s = LendSession(lender.nodeId, buyer.nodeId, ByteArray(16) { 4 })
        assertTrue(s.mayServe())
        s.served(Tunnel.CREDIT + 1)
        assertFalse(s.mayServe(), "a megabyte without a receipt is the limit")
        assertFalse(s.onReceipt(DataReceipt.of(Identity.generate("Otro"), lender.nodeId, s.session, Tunnel.CREDIT, 1, 1)), "only the buyer signs")
        assertTrue(s.onReceipt(DataReceipt.of(buyer, lender.nodeId, s.session, Tunnel.CREDIT, 1, 2)))
        assertTrue(s.mayServe())
        assertEquals(1, s.best!!.lucas)
        assertFalse(s.onReceipt(DataReceipt.of(buyer, lender.nodeId, s.session, 10, 0, 3)), "an older receipt does not replace a newer one")
    }

    @Test
    fun `the buyer signs a receipt every quarter megabyte with what it owes for this session`() {
        val buyer = Identity.generate("Compra"); val lender = Identity.generate("Presta")
        val packs = listOf(DataPlan.Pack(1024, 350))
        val u = Usage(buyer, lender.nodeId, ByteArray(16) { 5 }, packs, usedBefore = 0)
        assertNull(u.used(Tunnel.RECEIPT_EVERY - 1, 1))
        val r = u.used(1, 2)
        assertNotNull(r)
        assertEquals(Tunnel.RECEIPT_EVERY, r!!.bytes)
        assertEquals(DataPlan.owed(packs, Tunnel.RECEIPT_EVERY), r.lucas)
        assertTrue(u.left() < DataPlan.total(packs))
    }

    @Test
    fun `nobody reaches the lender's home network or its own phone through the tunnel`() {
        assertFalse(Tunnel.allowed("127.0.0.1", 443))
        assertFalse(Tunnel.allowed("192.168.0.1", 80))
        assertFalse(Tunnel.allowed("10.1.2.3", 443))
        assertFalse(Tunnel.allowed("172.16.5.5", 443))
        assertFalse(Tunnel.allowed("169.254.1.1", 80))
        assertFalse(Tunnel.allowed("8.8.8.8", 25), "no mail servers: only web ports")
        assertTrue(Tunnel.allowed("8.8.8.8", 443))
        assertTrue(Tunnel.allowed("142.250.1.1", 80))
    }

    /* ---------- the wire: LINK 18, sealed end to end ---------- */

    @Test
    fun `tunnel frames are sealed for the other end - the island's host sees who, not what`() {
        val a = Identity.generate("Compra"); val b = Identity.generate("Presta"); val host = Identity.generate("Anfitrión")
        val m = TunnelMsg.seal(a, b.nodeId, b.boxPublic, TunnelMsg.DATA, 7, "GET / HTTP/1.1".toByteArray())
        val back = Packet.parse(m.encode()) as TunnelMsg
        assertEquals(TunnelMsg.DATA, back.op); assertEquals(7, back.stream)
        assertArrayEquals("GET / HTTP/1.1".toByteArray(), back.open(b, a.boxPublic))
        assertNull(back.open(host, a.boxPublic))
    }

    @Test
    fun `who lends Internet answers with a signed box key, so a host in the middle cannot swap it`() {
        val buyer = Identity.generate("Compra"); val lender = Identity.generate("Presta")
        val ask = TunnelMsg.ask(buyer, 100)
        assertNotNull((Packet.parse(ask.encode()) as TunnelMsg).boxKey())
        val lend = TunnelMsg.lend(lender, buyer.nodeId, 101)
        val got = Packet.parse(lend.encode()) as TunnelMsg
        assertArrayEquals(lender.boxPublic, got.boxKey())
        val evil = Identity.generate("Malo")
        val swapped = TunnelMsg(got.from, got.to, got.op, got.stream, got.nonce, evil.boxPublic + got.payload.copyOfRange(32, got.payload.size))
        assertNull(swapped.boxKey())
    }
}
