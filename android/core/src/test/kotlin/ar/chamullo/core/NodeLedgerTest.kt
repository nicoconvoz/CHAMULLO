package ar.chamullo.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The ledger living in the phones: entries spread, the king writes, everyone keeps the same book (Economy §9). */
class NodeLedgerTest {
    private class Air {
        var now = 0L
        val nodes = LinkedHashMap<String, Node>()
        val links = mutableSetOf<Pair<String, String>>()
        fun add(name: String, id: Identity = Identity.generate(name)) = Node(id, MemoryStore(), maxFrame = 65_536) { now }.also { nodes[name] = it }
        fun link(a: String, b: String) { links += a to b; links += b to a }
        fun run(ms: Long) {
            val end = now + ms
            while (now < end) {
                now += 100
                for ((name, n) in nodes) { n.tick(); for (f in n.drainOutbox()) for ((a, b) in links) if (a == name) nodes.getValue(b).onFrame(f) }
            }
        }
    }

    private val lat = -34.59

    @Test
    fun `the founder writes what happened, everyone keeps the same book, and the carrier gets its Lucas at the close`() {
        val air = Air()
        val founder = Identity.generate("Fundador")
        val params = { Ledger.Params(Zone.of(lat, -58.43, Zone.PUEBLO), founder.nodeId, budget = 1_000, periodMs = 120_000, maturityMs = 1, nobles = 3, genesisContributors = 50) }
        air.add("fundador", founder); air.add("ana"); air.add("beto"); air.add("caro")
        air.nodes.values.forEachIndexed { k, n -> n.locate(lat, -58.4300 + k * 0.0006); n.ledger = Ledger(params()) }
        air.link("fundador", "ana"); air.link("ana", "beto"); air.link("beto", "caro")
        air.run(3_000)
        air.node("ana").send(air.node("caro").card(), "hola")
        air.run(Node.LEDGER_PAGE_MS * 2)
        val books = air.nodes.values.map { it.ledger!!.pages.size }
        assertTrue(books.all { it == books.first() && it >= 1 }, "everyone keeps the same book: $books")
        air.run(120_000 + Node.LEDGER_PAGE_MS * 2) // the period closes
        val beto = air.node("beto").identity.nodeId
        val balances = air.nodes.values.map { it.ledger!!.balance(beto) }
        assertTrue(balances.first() > 0, "beto carried and was paid: $balances")
        assertTrue(balances.all { it == balances.first() }, "the same balance in every copy: $balances")
    }

    @Test
    fun `someone who arrives late gets the pages it missed from its neighbors`() {
        val air = Air()
        val founder = Identity.generate("Fundador")
        val params = { Ledger.Params(Zone.of(lat, -58.43, Zone.PUEBLO), founder.nodeId, periodMs = 60_000, maturityMs = 1, genesisContributors = 50) }
        air.add("fundador", founder); air.add("ana")
        air.nodes.values.forEach { it.locate(lat, -58.43); it.ledger = Ledger(params()) }
        air.link("fundador", "ana")
        air.run(60_000 + Node.LEDGER_PAGE_MS * 2) // an empty period closes: a page
        val late = air.add("tarde").also { it.locate(lat, -58.43); it.ledger = Ledger(params()) }
        air.link("ana", "tarde")
        air.run(Node.BEACON_MS + 5_000)
        assertEquals(air.node("fundador").ledger!!.pages.size, late.ledger!!.pages.size)
    }

    private fun Air.node(n: String) = nodes.getValue(n)
}
