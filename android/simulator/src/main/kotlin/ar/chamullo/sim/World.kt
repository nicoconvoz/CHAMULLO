package ar.chamullo.sim

import ar.chamullo.core.Cartel
import ar.chamullo.core.Identity
import ar.chamullo.core.IslandAction
import ar.chamullo.core.IslandState
import ar.chamullo.core.Islands
import ar.chamullo.core.MemoryStore
import ar.chamullo.core.Node
import ar.chamullo.core.NodeEvent
import ar.chamullo.core.Packet
import ar.chamullo.core.toHex
import kotlin.math.hypot
import kotlin.random.Random

/**
 * The digital twin: real CHAMULLO nodes (the exact core the app ships) on simulated Wi-Fi Direct islands.
 *
 * Simulated: positions, Wi-Fi reach, the time a phone needs to join a group, frame latency and walking.
 * Real: Node, envelopes, journeys, receipts, payments, candies and every island decision ([Islands.decide]).
 */
class World(
    seed: Long = 1,
    val wifiRangeM: Double = 80.0,
    val connectMs: Long = 3_000,
    val latencyMs: Long = 30,
    val tickMs: Long = 100,
    val areaM: Double = 400.0
) {
    private val random = Random(seed)
    var now = 0L; private set

    inner class Phone(val name: String, var x: Double, var y: Double, val village: String, val speed: Double) {
        val identity = Identity.generate(name)
        val id = identity.nodeId.toHex().take(16)
        val node = Node(identity, MemoryStore(), 60_000) { now }
        var island: String? = null
        var host = false
        var busyUntil = 0L
        var target: String? = null
        var targetIsFerry = false
        var ferryHome: String? = null
        var ferryBackAt: Long? = null
        var ferriedTurn = -1L
        var tx = x; var ty = y
        val connected get() = now >= busyUntil
    }

    private val phones = LinkedHashMap<String, Phone>()
    // Per tick: a grid sized to the Wi-Fi reach (neighbors without comparing everyone) and each island's members.
    private var grid = HashMap<Long, MutableList<Phone>>()
    private var membersByHost = HashMap<String, MutableList<Phone>>()
    private val samples = ArrayList<Sample>()
    class Sample(val t: Long, val sent: Int, val delivered: Int, val pockets: Int, val islands: Int, val ferry: Int, val candies: Int)
    private val byId = HashMap<String, Phone>()
    private class Delivery(val at: Long, val to: Phone, val from: Phone, val frame: ByteArray)
    private val inFlight = ArrayDeque<Delivery>() // every frame takes the same latency, so arrival order = send order

    init { ar.chamullo.core.Crypto.rememberSignatures(true) } // see Crypto: the same frame, many listeners, one check

    // What happened, for the report.
    private val sentAt = HashMap<String, Long>()
    private val deliveredAt = HashMap<String, Long>()
    private val crossIsland = HashSet<String>()
    var ferryTrips = 0; private set
    private val carries = ArrayList<Carry>()
    /** What is happening, newest last: for the live screen. */
    val log = ArrayDeque<String>()
    private fun say(msg: String) { log.addLast("${now / 1000}s · $msg"); while (log.size > 200) log.removeFirst() }
    private val letterFrom = HashMap<String, Pair<String, String>>()

    /** A confirmed carry, with what the economy needs: who, for whom, and how many alternatives there were. */
    class Carry(val carrier: String, val origin: String, val destination: String, val alternatives: Int, val village: String)

    fun addPhone(name: String, x: Double, y: Double, village: String, speed: Double = 0.0): String {
        val p = Phone(name, x, y, village, speed)
        phones[name] = p; byId[p.id] = p
        return name
    }

    fun befriendAll() {
        val cards = phones.values.associateWith { it.identity.card() } // sign each card once, not once per pair
        for (a in phones.values) for ((b, card) in cards) if (a !== b) a.node.store.saveContact(card)
    }

    fun send(from: String, to: String, text: String): String {
        val a = phones.getValue(from); val b = phones.getValue(to)
        val id = a.node.send(b.identity.card(), text)
        sentAt[id] = now
        letterFrom[id] = from to to
        say("✉ $from le escribe a $to")
        if (a.island != b.island) crossIsland += id
        return id
    }

    fun phonesOf(village: String) = phones.values.filter { it.village == village }.map { it.name }

    private fun dist(a: Phone, b: Phone) = hypot(a.x - b.x, a.y - b.y)
    private fun sees(a: Phone, b: Phone) = a !== b && dist(a, b) <= wifiRangeM

    private fun members(h: Phone): List<Phone> = membersByHost[h.id] ?: emptyList()

    private fun cell(x: Double, y: Double) = (Math.floorDiv(x.toLong(), wifiRangeM.toLong()) shl 32) + Math.floorDiv(y.toLong(), wifiRangeM.toLong())

    private fun near(p: Phone): List<Phone> {
        val cx = Math.floorDiv(p.x.toLong(), wifiRangeM.toLong()); val cy = Math.floorDiv(p.y.toLong(), wifiRangeM.toLong())
        val out = ArrayList<Phone>()
        for (dx in -1L..1L) for (dy in -1L..1L) grid[((cx + dx) shl 32) + (cy + dy)]?.let { for (q in it) if (sees(p, q)) out += q }
        return out
    }

    private fun reindex() {
        grid = HashMap()
        for (p in phones.values) grid.getOrPut(cell(p.x, p.y)) { ArrayList() } += p
        membersByHost = HashMap()
        for (p in phones.values) if (!p.host && p.connected) p.island?.let { id -> byId[id]?.takeIf { h -> h.host && sees(p, h) }?.let { membersByHost.getOrPut(id) { ArrayList() } += p } }
    }

    private fun hostOf(p: Phone): Phone? = p.island?.let { byId[it] }?.takeIf { it.host && it !== p }

    private fun cartel(p: Phone) = Cartel(p.id, p.name, p.island ?: "", if (p.host) "DIRECT-CH-${p.id.take(6)}" else "", if (p.host) "clave" else "",
        p.host, if (p.host) members(p).map { it.id.take(8) } else emptyList())

    /** Islands as they are now: island id → names of the host and its connected members. */
    fun islands(): Map<String, List<String>> = phones.values.filter { it.host }.associate { h -> h.id to (listOf(h.name) + members(h).map { it.name }) }

    fun membersOfSomeIsland(): List<String> = islands().values.maxBy { it.size }

    /** A picture of the world right now, as JSON, for the live screen. */
    fun snapshotJson(): String {
        fun q(x: String) = "\"" + x.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val r = report()
        val nat = Economy.national(r.carries, nobles = 5)
        val courts = Economy.courts(r.carries, nobles = 3)
        val ph = phones.values.joinToString(",") { p ->
            val hostName = p.island?.let { byId[it]?.name } ?: ""
            "{\"n\":${q(p.name)},\"x\":${p.x.toInt()},\"y\":${p.y.toInt()},\"i\":${q(hostName)},\"h\":${if (p.host) 1 else 0},\"c\":${if (p.connected) 1 else 0},\"f\":${if (p.ferryHome != null) 1 else 0},\"k\":${p.node.candies},\"b\":${p.node.pocketCount()}}"
        }
        val court = courts.entries.joinToString(",") { (v, c) -> "{\"v\":${q(v)},\"king\":${q(c.king ?: "")},\"nobles\":[${c.nobles.joinToString(",") { q(it) }}]}" }
        return "{\"t\":$now,\"range\":$wifiRangeM,\"phones\":[$ph],\"sent\":${r.sent},\"delivered\":${r.delivered},\"p50\":${r.latencyP50s},\"ferry\":${r.ferryTrips}," +
            "\"candies\":${r.candies.values.sum()},\"king\":${q(nat.king ?: "")},\"nobles\":[${nat.nobles.joinToString(",") { q(it) }}],\"courts\":[$court]," +
            "\"history\":[${samples.takeLast(240).joinToString(",") { "[${it.t / 1000},${it.sent},${it.delivered},${it.pockets},${it.islands},${it.ferry},${it.candies}]" }}]," +
            "\"log\":[${log.toList().takeLast(40).reversed().joinToString(",") { q(it) }}]}"
    }

    /** For probes: who each phone is, where it stands and which islands it can see. */
    fun debug(): List<String> = phones.values.map { p ->
        val seen = phones.values.filter { sees(p, it) }
        val hostsSeen = seen.filter { it.host && it.island != p.island }.map { it.name }
        val strangers = seen.filter { !it.host && it.island != null && it.island != p.island }.map { it.name }
        "${p.name} x=${p.x.toInt()} isla=${p.island?.let { byId[it]?.name }} host=${p.host} ve-anfitriones=$hostsSeen ve-extraños=$strangers"
    }

    fun run(ms: Long) {
        val end = now + ms
        while (now < end) { now += tickMs; tick() }
    }

    private fun tick() {
        walk()
        reindex()
        // Links break when phones walk apart or the host stops hosting.
        for (p in phones.values) if (!p.host && p.connected && p.island != null) {
            val h = hostOf(p)
            if (h == null || !sees(p, h)) p.island = null
        }
        for ((i, p) in phones.values.withIndex()) {
            finishConnect(p)
            if ((now / tickMs + i) % (Islands.FERRY_TURN_MS / 12 / tickMs) == 0L) decide(p) // every 5 s, staggered
            p.node.tick()
            for (f in p.node.drainOutbox()) emit(p, f)
            collect(p, emptyList())
        }
        deliver()
        if (now % 10_000 < tickMs) samples += Sample(now, sentAt.size, deliveredAt.size, phones.values.sumOf { it.node.pocketCount() },
            phones.values.count { it.host }, ferryTrips, phones.values.sumOf { it.node.candies })
    }

    /** One sample every 10 simulated seconds, for the charts. */
    fun history(): List<Sample> = samples.toList()

    private fun decide(p: Phone) {
        if (!p.connected || p.ferryBackAt != null) return
        val visible = near(p).map { cartel(it) }
        val roster = if (p.host) members(p).map { it.id.take(8) } else hostOf(p)?.let { h -> members(h).map { it.id.take(8) } } ?: emptyList()
        when (val a = Islands.decide(p.id, IslandState(p.island, p.host, roster, p.ferriedTurn), visible, now)) {
            IslandAction.Stay -> Unit
            IslandAction.Host -> { p.host = true; p.island = p.id; say("🏝 ${p.name} funda una isla") }
            is IslandAction.Join -> connect(p, a.island, ferry = false)
            is IslandAction.Ferry -> { p.ferryHome = p.island; p.ferriedTurn = Islands.turnOf(now); ferryTrips++; say("🚢 ${p.name} sale de ferry a la isla de ${byId[a.island]?.name}"); connect(p, a.island, ferry = true) }
        }
    }

    private fun connect(p: Phone, island: String, ferry: Boolean) {
        p.host = false; p.island = null; p.target = island; p.targetIsFerry = ferry; p.busyUntil = now + connectMs
    }

    private fun finishConnect(p: Phone) {
        val t = p.target
        if (t != null && p.connected) {
            p.target = null
            val h = byId[t]
            // The host keeps the door: the last seat is only for ferries.
            val seats = if (p.targetIsFerry) Islands.MAX_MEMBERS else Islands.MAX_MEMBERS - Islands.FERRY_SEATS
            p.island = if (h != null && h.host && sees(p, h) && members(h).size < seats) t else null
            if (p.island != null) membersByHost.getOrPut(t) { ArrayList() } += p
            if (p.ferryHome != null && p.ferryBackAt == null) p.ferryBackAt = now + 20_000
        }
        val back = p.ferryBackAt
        if (back != null && now >= back && p.connected) {
            val home = p.ferryHome
            p.ferryHome = null; p.ferryBackAt = null
            if (home != null) connect(p, home, ferry = false)
        }
    }

    // A frame goes over the island links: a member to its host, a host to all its members.
    private fun emit(p: Phone, frame: ByteArray, except: Phone? = null) {
        if (!p.connected) return
        val targets = if (p.host) members(p) else listOfNotNull(hostOf(p)?.takeIf { sees(p, it) })
        for (t in targets) if (t !== except) inFlight += Delivery(now + latencyMs, t, p, frame)
    }

    private fun deliver() {
        while (inFlight.isNotEmpty() && inFlight.first().at <= now) {
            val d = inFlight.removeFirst()
            if (!d.to.connected) continue
            // The host repeats island messages (heartbeats, plaza, cards) to everyone; letters go through its node,
            // which re-shouts them with its own hop record, so the host earns its candy like any carrier.
            if (d.to.host && d.frame.size > 3 && d.frame[3].toInt() == Packet.KIND_LINK) emit(d.to, d.frame, except = d.from)
            collect(d.to, d.to.node.onFrame(d.frame))
        }
    }

    private fun collect(p: Phone, events: List<NodeEvent>) {
        for (e in events) when (e) {
            is NodeEvent.CandyEarned -> say("🍬 ${p.name} cobró un caramelo (tiene ${e.total})")
            is NodeEvent.LetterReceived -> {
                if (deliveredAt.putIfAbsent(e.msgId, now) == null) {
                    val via = e.journey.mapNotNull { byId[it.take(16)]?.name }
                    say("📬 llegó a ${p.name} la carta de ${e.from.name}" + if (via.isEmpty()) " (directo)" else " · la llevaron ${via.joinToString(" → ")}")
                }
                val origin = e.from.nodeId.toHex().take(16)
                for (carrier in e.journey) {
                    val c = byId[carrier.take(16)] ?: continue
                    val island = c.island
                    val alternatives = island?.let { (membersByHost[it]?.size ?: 0) } ?: 0 // others in the island who could have carried it
                    carries += Carry(c.name, byId[origin]?.name ?: "?", p.name, alternatives, c.village)
                }
            }
            else -> Unit
        }
    }

    private fun walk() {
        for (p in phones.values) {
            if (p.speed <= 0) continue
            if (hypot(p.tx - p.x, p.ty - p.y) < 2) { p.tx = random.nextDouble() * areaM; p.ty = random.nextDouble() * 60 }
            val d = hypot(p.tx - p.x, p.ty - p.y); val s = minOf(d, p.speed * tickMs / 1000.0)
            p.x += (p.tx - p.x) / d * s; p.y += (p.ty - p.y) / d * s
        }
    }

    class Report(
        val sent: Int, val delivered: Int, val deliveryRatio: Double,
        val crossSent: Int, val crossDelivered: Int,
        val latencyP50s: Double, val latencyP95s: Double,
        val ferryTrips: Int, val candies: Map<String, Int>, val islands: Map<String, List<String>>, val carries: List<Carry>
    )

    fun report(): Report {
        val lat = sentAt.keys.mapNotNull { id -> deliveredAt[id]?.let { (it - sentAt.getValue(id)) / 1000.0 } }.sorted()
        fun pct(q: Double) = if (lat.isEmpty()) 0.0 else lat[minOf(lat.size - 1, (q * lat.size).toInt())]
        return Report(
            sentAt.size, sentAt.keys.count { it in deliveredAt }, if (sentAt.isEmpty()) 0.0 else sentAt.keys.count { it in deliveredAt }.toDouble() / sentAt.size,
            crossIsland.size, crossIsland.count { it in deliveredAt }, pct(0.5), pct(0.95), ferryTrips,
            phones.values.associate { it.name to it.node.candies }, islands().mapKeys { (k, _) -> byId[k]?.name ?: k }, carries.toList()
        )
    }
}
