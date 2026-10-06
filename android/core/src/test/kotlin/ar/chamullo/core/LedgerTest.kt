package ar.chamullo.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** La libreta del pueblo (Economy & Governance §8-§10, Proof of Relay §6.4-§7). */
class LedgerTest {
    private val pueblo = Zone.of(-34.59, -58.43, Zone.PUEBLO)
    private val founder = Identity.generate("Fundador")
    private val params = Ledger.Params(pueblo, founder.nodeId, budget = 1_000, periodMs = 60_000, maturityMs = 1, nobles = 5, genesisContributors = 6)

    /** A letter carried from [origin] by [carriers] in a line, delivered and confirmed: the journey and each carrier's claim. */
    private fun delivery(carriers: List<Identity>, alternatives: Int = 1): Pair<Journey, List<Claim>> {
        val origin = Identity.generate("Origen"); val dest = Identity.generate("Destino")
        val sealed = Envelope.seal(origin, dest.card(), Letter.Text("x"), 1_000)
        var env = sealed.envelope
        val keys = ArrayList<HopKey>()
        val givers = listOf(sealed.signer()) + carriers.map { it.signer() }
        val takers = carriers + dest
        for ((k, giver) in givers.withIndex()) {
            val accept = env.offer(giver.id, takers[k].nodeId).accept(takers[k], 1_001L + k)
            env.withHopKept(giver, 1_001L + k, accept, alternatives).let { (e, key) -> env = e; keys += key }
        }
        val opened = env.open(dest)!!
        val j = Journey.confirm(sealed, env.blobs.map { Crypto.hash(it) }, opened.deliverySecret!!, Journey.validity(env.journey(opened.journeySecret!!)))
        return j to keys.drop(1).map { Claim(sealed.envelope.originSection(), it) }
    }

    private fun Ledger.write(by: Identity, entries: List<Entry>, ts: Long, endorsers: List<Identity> = emptyList()): Page {
        val p = propose(by, entries, ts)
        return endorsers.fold(p) { page, n -> page.endorsedBy(n) }
    }

    @Test
    fun `genesis - only the founder writes, and a journey must come before its claims`() {
        val l = Ledger(params)
        val beto = Identity.generate("Beto")
        val (j, claims) = delivery(listOf(beto))
        assertFalse(l.accept(l.write(beto, listOf(Entry.journey(j)), 10)), "not the founder")
        assertFalse(l.accept(l.write(founder, listOf(Entry.claim(claims[0])), 10)), "a claim with no journey")
        assertTrue(l.accept(l.write(founder, listOf(Entry.journey(j), Entry.claim(claims[0])), 10)))
        assertFalse(l.accept(l.write(founder, listOf(Entry.claim(claims[0])), 20)), "the same claim twice")
        assertEquals(1, l.pages.size)
    }

    @Test
    fun `a forged page breaks the chain and is refused`() {
        val l = Ledger(params)
        val page = l.write(founder, emptyList(), 10)
        assertFalse(l.accept(Page(page.pueblo, page.index, Crypto.randomBytes(32), page.ts, page.entries, page.king, page.kingSig, page.endorsements)))
        assertTrue(l.accept(page))
        assertFalse(l.accept(page), "the same page again")
    }

    @Test
    fun `closing a period shares the bag by score - where paths are few a carry pays more - and pays the court its wage`() {
        val l = Ledger(params)
        val busy = Identity.generate("Busy"); val lonely = Identity.generate("Lonely")
        val entries = ArrayList<Entry>()
        repeat(3) { val (j, c) = delivery(listOf(busy), alternatives = 5); entries += Entry.journey(j); entries += Entry.claim(c[0]) }
        repeat(3) { val (j, c) = delivery(listOf(lonely), alternatives = 0); entries += Entry.journey(j); entries += Entry.claim(c[0]) }
        assertTrue(l.accept(l.write(founder, entries, 10)))
        val close = l.closePeriod(60_001)
        assertTrue(l.accept(l.write(founder, listOf(close), 60_001)))
        val wage = Ledger.KING_WAGE // genesis: the founder sealed the period
        assertEquals(wage, l.balance(founder.nodeId))
        assertTrue(l.balance(lonely.nodeId) > l.balance(busy.nodeId), "scarcity: ${l.balance(lonely.nodeId)} vs ${l.balance(busy.nodeId)}")
        assertTrue(l.balance(lonely.nodeId) + l.balance(busy.nodeId) + wage <= params.budget)
        val wrong = Entry.Close(0, mapOf(busy.nodeId.toHex() to 999L))
        val l2 = Ledger(params).also { it.accept(it.write(founder, entries, 10)) }
        assertFalse(l2.accept(l2.write(founder, listOf(wrong), 60_001)), "shares that do not follow the rule")
    }

    @Test
    fun `once enough people contribute, the court comes from the rule - the founder steps down - and pages need over two thirds of the nobility`() {
        val l = Ledger(params)
        val carriers = (1..7).map { Identity.generate("C$it") }
        val entries = ArrayList<Entry>()
        for ((k, c) in carriers.withIndex()) repeat(k + 1) { val (j, cl) = delivery(listOf(c)); entries += Entry.journey(j); entries += Entry.claim(cl[0]) }
        assertTrue(l.accept(l.write(founder, entries, 10)))
        assertTrue(l.accept(l.write(founder, listOf(l.closePeriod(60_001)), 60_001)))
        val court = l.court()
        assertEquals(carriers.last().nodeId.toHex(), court.king!!.toHex(), "the one who carried most")
        assertEquals(5, court.nobles.size)
        assertFalse(l.accept(l.write(founder, emptyList(), 70_000)), "the founder is one more now")
        val king = carriers.last()
        val nobles = court.nobles.map { n -> carriers.single { it.nodeId.contentEquals(n) } }
        assertFalse(l.accept(l.write(king, emptyList(), 70_000, nobles.take(3))), "3 of 5 is not more than two thirds")
        assertTrue(l.accept(l.write(king, emptyList(), 70_000, nobles.take(4))))
    }

    @Test
    fun `over two thirds of the nobility depose the king, and the first noble writes until the next period`() {
        val l = Ledger(params)
        val carriers = (1..7).map { Identity.generate("C$it") }
        val entries = ArrayList<Entry>()
        for ((k, c) in carriers.withIndex()) repeat(k + 1) { val (j, cl) = delivery(listOf(c)); entries += Entry.journey(j); entries += Entry.claim(cl[0]) }
        l.accept(l.write(founder, entries, 10)); l.accept(l.write(founder, listOf(l.closePeriod(60_001)), 60_001))
        val court = l.court()
        val nobles = court.nobles.map { n -> carriers.single { it.nodeId.contentEquals(n) } }
        val depose = Entry.depose(court.king!!, 70_000, nobles.take(4))
        assertTrue(l.accept(l.write(nobles[0], listOf(depose), 70_000, nobles.take(4))))
        assertTrue(l.court().king!!.contentEquals(nobles[0].nodeId))
    }

    @Test
    fun `Lucas are spent from a balance - priority burns half and pays the carriers, the store pays the seller`() {
        val l = Ledger(params)
        val beto = Identity.generate("Beto")
        val (j, c) = delivery(listOf(beto), alternatives = 0)
        l.accept(l.write(founder, listOf(Entry.journey(j), Entry.claim(c[0])), 10)); l.accept(l.write(founder, listOf(l.closePeriod(60_001)), 60_001))
        val before = l.balance(beto.nodeId)
        val seller = Identity.generate("Tienda")
        assertFalse(l.accept(l.write(founder, listOf(Entry.spend(beto, seller.nodeId, before + 1, "demasiado", 70_000)), 70_000)), "no overdraft")
        assertTrue(l.accept(l.write(founder, listOf(Entry.spend(beto, seller.nodeId, 10, "sticker", 70_000)), 70_000)))
        assertEquals(before - 10, l.balance(beto.nodeId)); assertEquals(10, l.balance(seller.nodeId))
        val supply = l.supply()
        assertTrue(l.accept(l.write(founder, listOf(Entry.spend(beto, null, 10, "prioridad", 70_001)), 70_001)))
        assertEquals(supply - 5, l.supply(), "half of priority is burned")
        assertNull(Entry.parse(Entry.spend(beto, null, 10, "x", 1).encode().copyOf(5)))
        assertNotNull(Entry.parse(Entry.spend(beto, null, 10, "x", 1).encode()))
    }

    @Test
    fun `the clearing house nets what pueblos owe each other`() {
        val owed = mapOf(("Mendoza" to "Junín") to 500L, ("Junín" to "Mendoza") to 300L, ("Junín" to "Ushuaia") to 50L)
        assertEquals(setOf(Clearing.Transfer("Mendoza", "Junín", 200), Clearing.Transfer("Junín", "Ushuaia", 50)), Clearing.net(owed).toSet())
    }

    @Test
    fun `a journey is collectable for two periods, then it leaves the state - the book does not grow forever`() {
        val l = Ledger(params)
        val (j, c) = delivery(listOf(Identity.generate("Beto"), Identity.generate("Caro")))
        assertTrue(l.accept(l.write(founder, listOf(Entry.journey(j)), 10)))
        assertTrue(l.accept(l.write(founder, listOf(l.closePeriod(60_001)), 60_001)))
        assertTrue(l.accept(l.write(founder, listOf(Entry.claim(c[0])), 60_002)), "the next period it is still collectable")
        assertTrue(l.accept(l.write(founder, listOf(l.closePeriod(120_001)), 120_001)))
        assertFalse(l.accept(l.write(founder, listOf(Entry.claim(c[1])), 120_002)), "too late to collect")
        assertEquals(0, l.journeysKept())
    }

    @Test
    fun `in the twin, phones share the pages they decode - one copy in memory, not a thousand`() {
        Ledger.share(true)
        try {
            val page = Ledger(params).propose(founder, emptyList(), 10).encode()
            assertTrue(Page.decode(page) === Page.decode(page.copyOf()))
        } finally { Ledger.share(false) }
        val page = Ledger(params).propose(founder, emptyList(), 10).encode()
        assertFalse(Page.decode(page) === Page.decode(page))
    }
}
