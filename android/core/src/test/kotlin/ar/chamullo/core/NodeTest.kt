package ar.chamullo.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A tiny village in the air: every node hears the shouts of the nodes listed as its neighbors.
 * The radio is replaced by this test double; everything else is the real node logic.
 */
class NodeTest {
    private class Air(vararg names: String) {
        var now = 0L
        val nodes = names.associateWith { Node(Identity.generate(it.replaceFirstChar(Char::uppercase)), MemoryStore(), maxFrame = 240) { now } }
        val links = mutableSetOf<Pair<String, String>>()
        val events = mutableMapOf<String, MutableList<NodeEvent>>()
        var shouts = 0

        fun link(a: String, b: String) { links += a to b; links += b to a }
        fun unlink(a: String, b: String) { links -= a to b; links -= b to a }
        fun node(n: String) = nodes.getValue(n)

        // Advance time in small steps: each node shouts what it has queued, its neighbors hear it.
        fun run(ms: Long, stepMs: Long = 100) {
            val end = now + ms
            while (now < end) {
                now += stepMs
                for ((name, n) in nodes) {
                    n.tick()
                    for (frame in n.drainOutbox()) {
                        shouts++
                        for ((a, b) in links) if (a == name) {
                            events.getOrPut(b) { mutableListOf() } += nodes.getValue(b).onFrame(frame)
                        }
                    }
                }
            }
        }

        fun befriend(a: String, b: String) {
            val na = node(a); val nb = node(b)
            na.store.saveContact(nb.identity.card())
            nb.store.saveContact(na.identity.card())
        }
    }

    @Test
    fun `neighbors hear each other's heartbeat`() {
        val air = Air("ana", "beto").apply { link("ana", "beto") }
        air.run(3_000)
        assertEquals(listOf("Beto"), air.node("ana").neighbors().map { it.name })
    }

    @Test
    fun `two neighbors exchange cards with one tap each and become contacts`() {
        val air = Air("ana", "beto").apply { link("ana", "beto") }
        air.run(3_000)
        air.node("ana").offerCard(air.node("ana").neighbors().single())
        air.run(1_000)
        val received = air.events.getValue("beto").filterIsInstance<NodeEvent.CardReceived>().single()
        air.node("beto").acceptCard(received.card)
        air.run(1_000)
        assertEquals(listOf("Ana"), air.node("beto").store.contacts().map { it.name })
        assertEquals(listOf("Beto"), air.node("ana").store.contacts().map { it.name })
    }

    @Test
    fun `a letter hops through a carrier and the sender gets the double check`() {
        val air = Air("ana", "beto", "caro").apply { link("ana", "beto"); link("beto", "caro"); befriend("ana", "caro") }
        val caroCard = air.node("caro").identity.card()
        val msgId = air.node("ana").send(caroCard, "hola Caro")
        air.run(5_000)
        val got = air.events.getValue("caro").filterIsInstance<NodeEvent.LetterReceived>().single()
        assertEquals("hola Caro", got.text)
        assertEquals("Ana", got.from.name)
        assertTrue(air.events.getValue("ana").filterIsInstance<NodeEvent.Delivered>().any { it.msgId == msgId })
        assertEquals(MessageState.DELIVERED, air.node("ana").store.messages(caroCard.nodeId).single().state)
    }

    @Test
    fun `nobody shouts the same letter twice and letters do not bounce forever`() {
        val air = Air("a", "b", "c", "d").apply { link("a", "b"); link("b", "c"); link("c", "d"); link("a", "c"); link("b", "d"); befriend("a", "d") }
        air.node("a").send(air.node("d").identity.card(), "una sola vez")
        air.run(5_000)
        assertEquals(1, air.events.getValue("d").filterIsInstance<NodeEvent.LetterReceived>().size)
        air.run(20_000)
        assertEquals(1, air.events.getValue("d").filterIsInstance<NodeEvent.LetterReceived>().size)
    }

    @Test
    fun `guardar y llevar - with no path the letter waits in a pocket and leaves when a new neighbor shows up`() {
        val air = Air("ana", "beto", "caro").apply { link("ana", "beto"); befriend("ana", "caro") }
        air.node("ana").send(air.node("caro").identity.card(), "te espero")
        air.run(5_000)
        assertTrue(air.events["caro"].orEmpty().none { it is NodeEvent.LetterReceived })
        assertTrue(air.node("beto").pocketCount() > 0, "beto carries it")
        air.unlink("ana", "beto"); air.link("beto", "caro") // beto walks to caro
        air.run(5_000)
        assertEquals("te espero", air.events.getValue("caro").filterIsInstance<NodeEvent.LetterReceived>().single().text)
    }

    @Test
    fun `a letter dies when it reaches its hop limit`() {
        val names = (1..10).map { "n$it" }.toTypedArray()
        val air = Air(*names)
        for (i in 0 until names.size - 1) air.link(names[i], names[i + 1])
        air.befriend("n1", "n10")
        air.node("n1").send(air.node("n10").identity.card(), "lejos", maxHops = 4)
        air.run(10_000)
        assertTrue(air.events["n10"].orEmpty().none { it is NodeEvent.LetterReceived })
    }

    @Test
    fun `a long letter is split into several shouts and arrives whole`() {
        val air = Air("ana", "beto").apply { link("ana", "beto"); befriend("ana", "beto") }
        val text = "chamullo ".repeat(30)
        air.node("ana").send(air.node("beto").identity.card(), text)
        air.run(5_000)
        assertEquals(text, air.events.getValue("beto").filterIsInstance<NodeEvent.LetterReceived>().single().text)
    }

    @Test
    fun `plaza - neighbors read the message and the author sees who heard it, one hop only`() {
        val air = Air("ana", "beto", "caro").apply { link("ana", "beto"); link("beto", "caro") }
        air.run(1_000)
        val id = air.node("ana").sendPlaza("¿me escuchan?")
        air.run(3_000)
        assertEquals("¿me escuchan?", air.events.getValue("beto").filterIsInstance<NodeEvent.PlazaReceived>().single().text)
        assertTrue(air.events["caro"].orEmpty().none { it is NodeEvent.PlazaReceived }, "the plaza is only for those within earshot")
        assertEquals(listOf("Beto"), air.node("ana").plaza().single { it.id == id }.heardBy)
    }

    @Test
    fun `plaza - the history survives an app restart`() {
        val store = MemoryStore()
        var now = 0L
        val ana = Identity.generate("Ana")
        val beto = Node(Identity.generate("Beto"), MemoryStore()) { now }
        val first = Node(ana, store) { now }
        val id = first.sendPlaza("¿me escuchan?")
        for (f in first.drainOutbox()) beto.onFrame(f)
        for (f in beto.drainOutbox()) first.onFrame(f)
        val again = Node(ana, store) { now } // the app was closed and opened again
        val line = again.plaza().single { it.id == id }
        assertEquals("¿me escuchan?", line.text)
        assertEquals(listOf("Beto"), line.heardBy)
    }

    @Test
    fun `cards - accepting sends my card back even if I never heard the other phone's heartbeat`() {
        var now = 0L
        val ana = Node(Identity.generate("Ana"), MemoryStore()) { now }
        val beto = Node(Identity.generate("Beto"), MemoryStore()) { now }
        ana.tick(); for (f in ana.drainOutbox()) beto.onFrame(f) // beto hears ana; ana never hears beto
        ana.offerCard(beto.neighbors().single().let { Neighbor(Beacon.of(beto.identity, false, now), now) })
        val card = ana.drainOutbox().flatMap { beto.onFrame(it) }.filterIsInstance<NodeEvent.CardReceived>().single().card
        beto.acceptCard(card)
        val back = beto.drainOutbox().flatMap { ana.onFrame(it) }
        assertTrue(back.any { it is NodeEvent.CardReceived }, "ana gets beto's card")
        assertEquals(listOf("Beto"), ana.store.contacts().map { it.name })
    }

    @Test
    fun `plaza - a message nobody heard is shouted again, at most three times`() {
        var now = 0L
        val ana = Node(Identity.generate("Ana"), MemoryStore()) { now }
        ana.sendPlaza("¿hay alguien?")
        var shouts = ana.drainOutbox().size
        repeat(30) { now += 1_000; ana.tick(); shouts += ana.drainOutbox().count { Packet.parseOrNull(it) is Plaza } }
        assertEquals(1 + Node.PLAZA_RETRIES, shouts)
    }

    @Test
    fun `plaza - once someone heard it, it is not shouted again`() {
        val air = Air("ana", "beto").apply { link("ana", "beto") }
        air.node("ana").sendPlaza("hola")
        air.run(1_000)
        val before = air.shouts
        air.run(20_000)
        val plazasAfter = air.shouts - before
        assertTrue(plazasAfter < 10, "only heartbeats keep going: $plazasAfter shouts")
    }

    @Test
    fun `cards - an offer is repeated until the other card arrives`() {
        var now = 0L
        val ana = Node(Identity.generate("Ana"), MemoryStore()) { now }
        val beto = Identity.generate("Beto")
        val r = Reassembler()
        val offersIn = { frames: List<ByteArray> -> frames.mapNotNull { r.accept(it) }.count { Packet.parseOrNull(it) is CardOffer } } // a card travels in fragments
        ana.offerCard(Neighbor(Beacon.of(beto, false, now), now))
        var offers = offersIn(ana.drainOutbox())
        repeat(60) { now += 1_000; ana.tick(); offers += offersIn(ana.drainOutbox()) }
        assertEquals(1 + Node.CARD_RETRIES, offers)
    }
}
