// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-CHAMULLO-Commercial
// Copyright (C) 2026 Jesús Nicolás Astorga y RESOURCES OPEN DOORS S.A.S
package ar.chamullo.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** La Tienda de Lucas (Economy & Governance §13): a catalog anyone can read, purchases written in the ledger. */
class ShopTest {
    @Test
    fun `the catalog has data from megabytes to gigabytes, priority, badges and support`() {
        val cats = Shop.parse(Shop.DEFAULT)
        assertEquals(listOf("datos", "prioridad", "insignias", "apoyos"), cats.map { it.id })
        val data = cats.first { it.id == "datos" }.items
        assertEquals(50, data.first().mb)
        assertEquals(10 * 1024, data.last().mb)
        assertTrue(data.zipWithNext().all { (a, b) -> a.mb < b.mb && a.price < b.price }, "bigger packs cost more")
        assertTrue(cats.flatMap { it.items }.all { it.price > 0 && it.name.isNotBlank() })
    }

    @Test
    fun `a broken catalog from the page falls back to the built-in one`() {
        assertEquals(Shop.parse(Shop.DEFAULT).size, Shop.parseOrDefault("{esto no es json").size)
    }

    @Test
    fun `my purchases and my data come from the ledger, so every copy agrees`() {
        val pueblo = Zone.of(-34.59, -58.43, Zone.PUEBLO)
        val founder = Identity.generate("Fundador")
        val l = Ledger(Ledger.Params(pueblo, founder.nodeId, periodMs = 60_000, genesisContributors = 50))
        assertTrue(l.accept(l.propose(founder, emptyList(), 10)))
        assertTrue(l.accept(l.propose(founder, listOf(l.closePeriod(60_001)), 60_001))) // the founder earns its wage
        assertTrue(l.accept(l.propose(founder, listOf(l.closePeriod(120_001)), 120_001))) // twice: 40 Lucas
        val cats = Shop.parse(Shop.DEFAULT)
        val pack = cats.first { it.id == "datos" }.items.first()
        val spend = Shop.buy(founder, pack, Identity.generate("Tienda").nodeId, 120_002)
        assertTrue(l.accept(l.propose(founder, listOf(spend), 120_002)))
        val mine = Shop.purchases(l, founder.nodeId, cats)
        assertEquals(listOf(pack.id), mine.map { it.item.id })
        assertEquals(pack.mb, Shop.dataMb(mine))
    }
}
