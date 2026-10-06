package ar.chamullo.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The compass inside real nodes (Discovery & Routing v0.2 §10): each node knows where it is, the air links neighbors. */
class NodeCompassTest {
    private class Air {
        var now = 0L
        val nodes = LinkedHashMap<String, Node>()
        val links = mutableSetOf<Pair<String, String>>()
        val events = mutableMapOf<String, MutableList<NodeEvent>>()
        val envelopesShouted = mutableMapOf<String, Int>()
        var drop: (to: String, frame: ByteArray) -> Boolean = { _, _ -> false }

        // Wi-Fi frames are big: nothing is cut in pieces, so a test can see (and drop) whole packets.
        fun add(name: String, lat: Double, lon: Double) = Node(Identity.generate(name), MemoryStore(), maxFrame = 4_096) { now }
            .also { it.locate(lat, lon); nodes[name] = it }

        fun link(a: String, b: String) { links += a to b; links += b to a }
        fun unlink(a: String, b: String) { links -= a to b; links -= b to a }
        fun node(n: String) = nodes.getValue(n)

        fun run(ms: Long) {
            val end = now + ms
            while (now < end) {
                now += 100
                for ((name, n) in nodes) {
                    n.tick()
                    for (frame in n.drainOutbox()) {
                        if (frame[3].toInt() == Packet.KIND_ENVELOPE || frame[3].toInt() == Packet.KIND_FRAGMENT) envelopesShouted.merge(name, 1, Int::plus)
                        for ((a, b) in links) if (a == name && !drop(b, frame)) events.getOrPut(b) { mutableListOf() } += nodes.getValue(b).onFrame(frame)
                    }
                }
            }
        }

        fun letters(n: String) = events[n].orEmpty().filterIsInstance<NodeEvent.LetterReceived>()
    }

    private val lat = -34.5900
    private val step = 0.0007 // ~64 m: neighbors over Wi-Fi

    @Test
    fun `the letter walks hand to hand toward the barrio, and nobody behind carries it`() {
        val air = Air()
        // A street going east from one barrio into the next; behind each house, someone who only listens.
        val n = 32
        for (i in 0 until n) air.add("c$i", lat, -58.4180 + i * step)
        for (i in 0 until n) { air.add("b$i", lat, -58.4180 + i * step - step / 2); air.link("c$i", "b$i") }
        // Wi-Fi reaches the next two houses (~130 m): a cell is ~100 m, so there is always someone in a cell ahead.
        for (i in 0 until n - 1) { air.link("c$i", "c${i + 1}"); if (i + 2 < n) air.link("c$i", "c${i + 2}") }
        air.run(2_000)
        val dest = air.node("c${n - 1}")
        assertTrue(dest.card().zone != air.node("c0").card().zone, "two different barrios")
        air.node("c0").send(dest.card(), "por la brújula")
        air.run(30_000)
        val got = air.letters("c${n - 1}").single()
        assertEquals("por la brújula", got.text)
        assertTrue(got.journey.size >= 5, "carried hand to hand: ${got.journey.size}")
        // Going east, the ones behind never carry it (on the way back, the receipt, they are the ones ahead).
        val behind = (0 until n).map { air.node("b$it").identity.nodeId.toHex() }.toSet()
        assertTrue(got.journey.none { it in behind }, "nobody behind carried the letter")
    }

    @Test
    fun `a ferry announcing its heading takes the letters that go that way, carries them and hands them out on arrival`() {
        val air = Air()
        air.add("ana", lat, -58.4190)
        air.add("fer", lat, -58.4190) // same cell as ana: no progress while it stays
        air.add("dani", lat, -58.3950)
        air.link("ana", "fer")
        air.run(2_000)
        air.node("ana").send(air.node("dani").card(), "en barco")
        air.run(3_000)
        assertEquals(0, air.node("fer").pocketCount(), "nobody gets it closer: ana keeps it")
        air.node("fer").setHeading(Zone.of(lat, -58.4175)) // the next island, a cell ahead
        air.run(3_000)
        assertEquals(1, air.node("fer").pocketCount(), "it boarded the ferry")
        assertEquals(0, air.node("ana").pocketCount(), "and ana let it go")
        val shoutsAtSea = air.envelopesShouted["fer"] ?: 0
        air.run(5_000)
        assertEquals(shoutsAtSea, air.envelopesShouted["fer"] ?: 0, "at sea the ferry carries, it does not hand out")
        air.unlink("ana", "fer"); air.link("fer", "dani")
        air.node("fer").locate(lat, -58.3956)
        air.node("fer").setHeading(null)
        air.run(10_000)
        assertEquals("en barco", air.letters("dani").single().text)
    }

    @Test
    fun `the carrier earns its candy, the payment goes toward the letter's barrio`() {
        val air = Air()
        air.add("ana", lat, -58.4005) // just west of a barrio line
        air.add("beto", lat, -58.3998)
        air.add("caro", lat, -58.3985) // a cell ahead of beto, so the receipt can come back
        air.link("ana", "beto"); air.link("beto", "caro")
        air.run(2_000)
        air.node("ana").send(air.node("caro").card(), "pagale a Beto")
        air.run(15_000)
        assertEquals(1, air.letters("caro").size)
        assertTrue(air.events["ana"].orEmpty().any { it is NodeEvent.Delivered })
        assertEquals(1, air.node("beto").candies)
        assertEquals(0, air.node("caro").candies)
    }

    @Test
    fun `moving to another barrio tells every contact, and the next letter points to the new one`() {
        val air = Air()
        air.add("ana", lat, -58.4005)
        air.add("beto", lat, -58.3998)
        air.link("ana", "beto")
        air.run(2_000)
        air.node("ana").send(air.node("beto").card(), "hola")
        air.run(5_000)
        val old = air.node("beto").store.contact(air.node("ana").identity.nodeId)!!.zone
        air.node("ana").locate(lat, -58.3995) // ana crosses into beto's barrio
        air.run(5_000)
        val now = air.node("beto").store.contact(air.node("ana").identity.nodeId)!!.zone
        assertTrue(old != now)
        assertEquals(Zone.of(lat, -58.3995, Zone.BARRIO), now)
    }

    @Test
    fun `a payment is kept and repeated to new neighbors, so a carrier who was away still gets paid`() {
        val air = Air()
        air.add("ana", lat, -58.4005)
        air.add("beto", lat, -58.3998)
        air.add("caro", lat, -58.3985)
        air.link("ana", "beto"); air.link("beto", "caro")
        air.run(2_000)
        air.drop = { to, frame -> to == "beto" && Packet.parseOrNull(frame) is Payment } // beto is away when the payment passes
        air.node("ana").send(air.node("caro").card(), "pagale a Beto cuando vuelva")
        air.run(10_000)
        assertTrue(air.events["ana"].orEmpty().any { it is NodeEvent.Delivered })
        assertEquals(0, air.node("beto").candies)
        air.unlink("ana", "beto"); air.run(Node.NEIGHBOR_TTL_MS + 2_000) // beto walks away and is forgotten
        air.drop = { _, _ -> false }
        air.link("ana", "beto"); air.run(Node.BEACON_MS + 5_000) // and comes back: a new neighbor
        assertEquals(1, air.node("beto").candies)
    }

    @Test
    fun `an eco repeats only to someone new, not every time a neighbor changes its heading`() {
        val air = Air()
        air.add("ana", lat, -58.4300); air.add("beto", lat, -58.4295)
        air.link("ana", "beto")
        air.run(2_000)
        air.node("ana").send(Identity.generate("Caro").card(), "sin zona y lejos: eco, y queda en el bolsillo")
        air.run(1_000)
        val before = air.envelopesShouted["ana"] ?: 0
        repeat(6) { k -> air.node("beto").setHeading(Zone.of(lat, -58.4290 + k * 0.0005)); air.run(1_000) }
        assertEquals(before, air.envelopesShouted["ana"] ?: 0, "a heading is not a new neighbor")
    }

    @Test
    fun `with no progress the letter first waits for the ferry of need, and only then tries the lake`() {
        val air = Air()
        air.add("ana", lat, -58.4300)
        air.add("beto", lat, -58.4310) // behind ana: only the lake would hand it to him
        air.link("ana", "beto")
        air.run(2_000)
        air.node("ana").send(Identity.generate("Dani").card(zone = Zone.of(lat, -58.3700, Zone.BARRIO)), "hacia el este")
        air.run(Node.LAKE_WAIT_MS - 5_000)
        assertEquals(0, air.node("beto").pocketCount(), "waiting for a ferry")
        assertEquals(Zone.of(lat, -58.3700, Zone.BARRIO), air.node("ana").stuckZone())
        air.run(Node.RETRY_MS + 10_000)
        assertEquals(1, air.node("beto").pocketCount(), "then the lake")
    }

    @Test
    fun `a neighbor that already saw the letter says so, and the copy behind is let go`() {
        var now = 0L
        val ana = Node(Identity.generate("Ana"), MemoryStore(), maxFrame = 4_096) { now }.apply { locate(lat, -58.4300) }
        val beto = Node(Identity.generate("Beto"), MemoryStore(), maxFrame = 4_096) { now }.apply { locate(lat, -58.4290) }
        val env = Envelope.letter(Identity.generate("Origen"), Identity.generate("Dani").card(zone = Zone.of(lat, -58.37, Zone.BARRIO)), Letter.Text("x"), now, maxHops = 50)
        beto.onFrame(env.encode()) // the other copy already passed through beto
        ana.onFrame(Beacon.of(beto.identity, false, now, beto.cell()).encode())
        ana.onFrame(env.encode()) // ana carries the copy behind, and offers it to beto
        beto.drainOutbox()
        for (f in ana.drainOutbox()) beto.onFrame(f)
        for (f in beto.drainOutbox()) ana.onFrame(f)
        now += 100; ana.tick()
        assertEquals(0, ana.pocketCount(), "the copy ahead is enough")
    }

    @Test
    fun `having seen a letter does not close the road - offered again by hand, it is taken again`() {
        var now = 0L
        val ana = Node(Identity.generate("Ana"), MemoryStore(), maxFrame = 4_096) { now }.apply { locate(lat, -58.4300) }
        val beto = Node(Identity.generate("Beto"), MemoryStore(), maxFrame = 4_096) { now }.apply { locate(lat, -58.4290) }
        val env = Envelope.letter(Identity.generate("Origen"), Identity.generate("Dani").card(zone = Zone.of(lat, -58.37, Zone.BARRIO)), Letter.Text("x"), now, maxHops = 50)
        beto.onFrame(env.encode()) // beto saw it once...
        val behind = Node(Identity.generate("Caro"), MemoryStore(), maxFrame = 4_096) { now }.apply { locate(lat, -58.4320) }
        beto.tick()
        now += Node.LAKE_WAIT_MS + 1
        beto.onFrame(Beacon.of(behind.identity, false, now, behind.cell()).encode())
        beto.tick() // ...and, stuck, let it go back by the lake
        for (f in beto.drainOutbox()) behind.onFrame(f)
        for (f in behind.drainOutbox()) beto.onFrame(f)
        for (f in beto.drainOutbox()) behind.onFrame(f)
        for (f in behind.drainOutbox()) beto.onFrame(f) // "la tengo"
        now += 100; beto.tick()
        assertEquals(0, beto.pocketCount()); assertEquals(1, behind.pocketCount())
        ana.onFrame(Beacon.of(beto.identity, false, now, beto.cell()).encode())
        ana.onFrame(env.encode()) // now ana offers it to beto again
        for (f in ana.drainOutbox()) beto.onFrame(f)
        for (f in beto.drainOutbox()) ana.onFrame(f)
        for (f in ana.drainOutbox()) beto.onFrame(f)
        assertEquals(1, beto.pocketCount(), "beto takes it again")
    }

    @Test
    fun `the giver lets go only when the taker says it got the copy - a copy lost on the way is not a letter lost`() {
        var now = 0L
        val ana = Node(Identity.generate("Ana"), MemoryStore(), maxFrame = 4_096) { now }.apply { locate(lat, -58.4300) }
        val beto = Node(Identity.generate("Beto"), MemoryStore(), maxFrame = 4_096) { now }.apply { locate(lat, -58.4290) }
        val env = Envelope.letter(Identity.generate("Origen"), Identity.generate("Dani").card(zone = Zone.of(lat, -58.37, Zone.BARRIO)), Letter.Text("x"), now, maxHops = 50)
        ana.onFrame(Beacon.of(beto.identity, false, now, beto.cell()).encode())
        ana.onFrame(env.encode())
        for (f in ana.drainOutbox()) beto.onFrame(f) // the offer
        for (f in beto.drainOutbox()) ana.onFrame(f) // the acceptance
        ana.drainOutbox() // the copy never reaches beto: he left
        now += Node.OFFER_TIMEOUT_MS + 1; ana.tick()
        assertEquals(1, ana.pocketCount(), "ana still has it")
    }

    /* ---------- el puente por Internet (Discovery & Routing §11) ---------- */

    private class Cloud(val nodes: Map<String, Node>) : Bridge {
        val sent = mutableListOf<Pair<String, ByteArray>>()
        override fun peersIn(zone: Zone) = nodes.values.filter { it.bridge != null && zone.contains(it.cell()!!) }.map { it.identity.nodeId }
        override fun send(to: ByteArray, frame: ByteArray) { sent += to.toHex() to frame }
        fun deliver() { val now = sent.toList(); sent.clear(); for ((to, f) in now) nodes.values.single { it.identity.nodeId.toHex() == to }.onFrame(f) }
    }

    @Test
    fun `a bridge with no way forward uploads the letter, and three bridges in the barrio bring it down`() {
        val air = Air()
        air.add("ana", lat, -58.4300)
        val far = (1..4).map { air.add("sur$it", lat, -58.3600 + it * 0.0005) }
        val cloud = Cloud(air.nodes)
        air.node("ana").bridge = cloud
        far.forEach { it.bridge = cloud }
        air.run(1_000)
        air.node("ana").send(Identity.generate("Dani").card(zone = Zone.of(lat, -58.3590, Zone.BARRIO)), "por arriba")
        air.run(1_000)
        assertEquals(0, air.node("ana").pocketCount(), "it went up")
        assertEquals(3, cloud.sent.size, "three bridges bring it down: diversity")
        cloud.deliver()
        assertEquals(3, far.count { it.pocketCount() == 1 })
    }

    @Test
    fun `without Internet, a letter stuck for two ferry turns goes to the neighbor who has it`() {
        val air = Air()
        air.add("ana", lat, -58.4300)
        air.add("beto", lat, -58.4300) // same cell: no progress, but Internet
        air.link("ana", "beto")
        val cloud = Cloud(air.nodes)
        air.node("beto").bridge = cloud
        air.run(2_000)
        assertTrue(air.node("ana").neighbors().single().bridge, "the heartbeat says it")
        air.node("ana").send(Identity.generate("Dani").card(zone = Zone.of(lat, -58.4190, Zone.BARRIO)), "al barrio de al lado: no es un salto grande")
        air.run(Node.LAKE_WAIT_MS - 5_000)
        assertEquals(1, air.node("ana").pocketCount(), "first it waits for the islands")
        air.run(Node.RETRY_MS + 5_000)
        assertEquals(0, air.node("ana").pocketCount())
        assertEquals(1, air.node("beto").pocketCount(), "beto has it: no bridge down there yet, so he keeps it")
    }

    @Test
    fun `a big jump takes the bridge at hand even if the road moves, a short one stays on the islands`() {
        val air = Air()
        air.add("ana", lat, -58.4300)
        air.add("road", lat, -58.4290) // gets it closer, slowly
        air.add("net", lat, -58.4300)  // same cell, lends Internet
        air.link("ana", "road"); air.link("ana", "net")
        val cloud = Cloud(air.nodes)
        air.node("net").bridge = cloud
        val down = air.add("sur", lat, -58.3650).also { it.bridge = cloud }
        air.run(2_000)
        air.node("ana").send(Identity.generate("Dani").card(zone = Zone.of(lat, -58.3650, Zone.BARRIO)), "lejos: 5 km")
        air.run(2_000)
        assertEquals(0, air.node("road").pocketCount(), "not by the road")
        assertEquals(1, cloud.sent.size, "up through net, down through the only bridge there")
        cloud.deliver(); assertEquals(1, down.pocketCount())
        air.node("ana").send(Identity.generate("Eva").card(zone = Zone.of(lat, -58.4190, Zone.BARRIO)), "cerca: 1 km")
        air.run(2_000)
        assertEquals(1, air.node("road").pocketCount(), "a short jump stays on the islands")
    }
}
