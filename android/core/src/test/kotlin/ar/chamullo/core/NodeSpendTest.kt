// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-CHAMULLO-Commercial
// Copyright (C) 2026 Jesús Nicolás Astorga y RESOURCES OPEN DOORS S.A.S
package ar.chamullo.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Spending Lucas (Economy & Governance §6): priority with a free lane, and the store. */
class NodeSpendTest {
    private val lat = -34.59

    /** A founder alone: when the first period closes it earns the king's wage, so it has Lucas to spend. */
    private fun founderWithWage(): Pair<Node, () -> Unit> {
        var now = 0L
        val me = Identity.generate("Fundador")
        val n = Node(me, MemoryStore(), maxFrame = 65_536) { now }.apply {
            locate(lat, -58.43)
            ledger = Ledger(Ledger.Params(Zone.of(lat, -58.43, Zone.PUEBLO), me.nodeId, periodMs = 60_000, maturityMs = 1, genesisContributors = 50))
        }
        while (now < 60_000 + Node.LEDGER_PAGE_MS * 2) { now += 100; n.tick() }
        n.drainOutbox()
        return n to { now += 100; n.tick() }
    }

    @Test
    fun `paid priority goes first, but one free letter in three always goes out`() {
        val (ana, _) = founderWithWage()
        assertEquals(Ledger.KING_WAGE, ana.ledger!!.balance(ana.identity.nodeId))
        repeat(6) { ana.send(Identity.generate("Libre$it").card(), "gratis $it") }
        repeat(6) { ana.send(Identity.generate("Paga$it").card(), "con prioridad $it", priority = 2) }
        val letters = ana.drainOutbox().mapNotNull { Packet.parseOrNull(it) as? Envelope }
        val firstNine = letters.take(9).map { it.priority > 0 }
        assertEquals(6, firstNine.count { it }, "priority first")
        assertEquals(3, firstNine.count { !it }, "but the free lane keeps its third: $firstNine")
        assertEquals(12, letters.size, "nobody is left out")
    }

    @Test
    fun `without Lucas there is no priority - nobody gets it for free`() {
        var now = 0L
        val poor = Node(Identity.generate("Pobre"), MemoryStore(), maxFrame = 65_536) { now }
        poor.send(Identity.generate("Dani").card(), "quiero pasar primero", priority = 5)
        assertTrue(poor.drainOutbox().mapNotNull { Packet.parseOrNull(it) as? Envelope }.all { it.priority == 0L })
    }

    @Test
    fun `a priority letter opens three streams instead of two`() {
        val me = Zone.of(lat, -58.4300)
        val east = Zone.of(lat, -58.3700, Zone.BARRIO)
        val peers = (1..4).map { Compass.Peer(Identity.generate("p$it").nodeId, Zone.of(lat, -58.4300 + it * 0.0005), true) }
        assertEquals(2, (Compass.plan(me, east, null, 0, peers, null, origin = true) as Compass.Plan.River).takers.size)
        assertEquals(3, (Compass.plan(me, east, null, 0, peers, null, origin = true, priority = true) as Compass.Plan.River).takers.size)
    }

    @Test
    fun `whoever has Lucas spends them in the store, the book pays the seller, and nobody spends twice what they have`() {
        val (fund, tick) = founderWithWage()
        val seller = Identity.generate("Tienda")
        assertTrue(fund.spend(seller.nodeId, 15, "sticker de CHAMULLO"))
        assertTrue(!fund.spend(seller.nodeId, 15, "otro sticker"), "only 5 left until the page is written")
        repeat((Node.LEDGER_PAGE_MS * 2 / 100).toInt()) { tick() }
        assertEquals(Ledger.KING_WAGE - 15, fund.ledger!!.balance(fund.identity.nodeId))
        assertEquals(15, fund.ledger!!.balance(seller.nodeId))
    }
}
