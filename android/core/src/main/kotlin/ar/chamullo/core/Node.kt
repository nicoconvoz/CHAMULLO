package ar.chamullo.core

sealed interface NodeEvent {
    data class CardReceived(val card: Card) : NodeEvent
    data class LetterReceived(val from: Card, val text: String, val msgId: String, val journey: List<String> = emptyList(), val service: String = "chat") : NodeEvent
    data class Delivered(val msgId: String) : NodeEvent
    data object NeighborsChanged : NodeEvent
    /** A letter I carried was confirmed: one more candy. */
    data class CandyEarned(val total: Int) : NodeEvent
    data class PlazaReceived(val id: String, val name: String, val text: String) : NodeEvent
    data class PlazaHeard(val id: String, val name: String) : NodeEvent
    /** A neighbor handed me the key of its Wi-Fi road (la carretera). */
    class RoadInvited(val from: ByteArray, val name: String, val ssid: String, val passphrase: String) : NodeEvent
}

class PlazaLine(val id: String, val name: String, val text: String, val ts: Long, val mine: Boolean, val heardBy: List<String>)

/** Someone heard saying hello: near, not verified. */
class Nearby(val shortId: String, val name: String, val coded: Boolean, val lastSeen: Long)

class Neighbor(val beacon: Beacon, val lastSeen: Long, val beats: Int = 1) {
    /** Heard more than once: the camino is stable enough to open a carretera. */
    val stable get() = beats >= Node.STABLE_BEATS
    val name get() = beacon.name
    val nodeId get() = beacon.nodeId
    val coded get() = beacon.coded
}

/**
 * The node logic of "Primer Grito" (v0.1), independent of the radio: it receives frames, decides what to shout and
 * keeps letters in its pocket. Routing in v0.1 is a bounded eco (every carrier re-shouts once, up to the letter's hop
 * limit) plus carry-and-forward: pocketed letters are shouted again when a new neighbor shows up.
 * The compass routing of Discovery & Routing §6 comes in a later version.
 */
class Node(
    val identity: Identity,
    val store: Store,
    val maxFrame: Int = 240,
    var coded: Boolean = false,
    private val clock: () -> Long
) {
    private class Pocket(val env: Envelope, var reshouts: Int = 0)

    private val outbox = ArrayDeque<ByteArray>()
    private val reassembler = Reassembler()
    private val neighbors = LinkedHashMap<String, Neighbor>()
    private val seen = LinkedHashSet<String>()
    private val pockets = LinkedHashMap<String, Pocket>().apply {
        // Letters being carried survive a restart: they come back from the store.
        for (b in store.loadPockets()) (Packet.parseOrNull(b) as? Envelope)?.let { put(it.msgId.toHex(), Pocket(it)) }
    }

    private fun savePockets() = store.savePockets(pockets.values.map { it.env.encode() })
    private val offeredTo = mutableSetOf<String>()
    private var lastBeacon = Long.MIN_VALUE / 2
    private var newNeighbor = false
    private var lastSweep = Long.MIN_VALUE / 2
    private class PlazaEntry(val id: String, val name: String, val text: String, val ts: Long, val mine: Boolean, val heardBy: LinkedHashSet<String> = LinkedHashSet())
    // Letters that matter are repeated until confirmed: a plaza message until someone hears it, a card until theirs arrives.
    private class Retry(val frame: ByteArray, var left: Int, var nextAt: Long)
    private val plazaRetries = LinkedHashMap<String, Retry>()
    private val cardRetries = LinkedHashMap<String, Retry>()
    private var road: Road? = null
    // Receipts and candies (Proof of Relay): what I carried, what I sent, and the payments already seen.
    private class Carried(val src: ByteArray, val commit: ByteArray, val myRecord: ByteArray)
    private val carried = LinkedHashMap<String, Carried>()
    private val sent = LinkedHashMap<String, Sealed>()
    private val paymentsSeen = LinkedHashSet<String>()
    var candies = store.loadCandies(); private set
    private val nearby = LinkedHashMap<String, Nearby>()
    private val roadInvitedAt = HashMap<String, Long>()
    private var riding = false
    // Invites I could not take because I already ride a road: I open mine for them instead.
    private val inviteBack = LinkedHashMap<String, Pair<ByteArray, ByteArray>>()
    private val plaza = ArrayList<PlazaEntry>().apply {
        for (l in store.loadPlaza()) add(PlazaEntry(l.id, l.name, l.text, l.ts, l.mine, LinkedHashSet(l.heardBy)))
    }

    var shoutsSent = 0L; private set
    var framesHeard = 0L; private set

    fun pocketCount() = pockets.size

    fun neighbors(): List<Neighbor> = neighbors.values.toList()

    /** Everyone heard saying hello lately, verified or not. */
    fun nearby(): List<Nearby> = nearby.values.toList()

    fun hello(): ByteArray = Hello.of(identity, coded)

    fun onHello(bytes: ByteArray) {
        val h = Hello.parse(bytes) ?: return
        if (h.shortId == identity.nodeId.toHex().take(16)) return
        nearby[h.shortId] = Nearby(h.shortId, h.name, h.coded, clock())
    }

    fun drainOutbox(): List<ByteArray> = buildList { while (outbox.isNotEmpty()) add(outbox.removeFirst()) }.also { shoutsSent += it.size }

    fun tick() {
        val now = clock()
        if (now - lastBeacon >= BEACON_MS) { lastBeacon = now; enqueue(Beacon.of(identity, coded, now).encode()) }
        // Housekeeping once a second is plenty: lifetimes are tens of seconds, and it keeps an idle tick cheap.
        if (now - lastSweep >= SWEEP_MS) {
            lastSweep = now
            neighbors.values.removeAll { now - it.lastSeen > NEIGHBOR_TTL_MS }
            nearby.values.removeAll { now - it.lastSeen > NEIGHBOR_TTL_MS }
            if (pockets.values.removeAll { it.env.expired(now) }) savePockets()
        }
        if (plazaRetries.isNotEmpty() || cardRetries.isNotEmpty()) {
            for (retries in listOf(plazaRetries.values, cardRetries.values)) {
                for (r in retries) if (r.left > 0 && now >= r.nextAt) { r.left--; r.nextAt = now + RETRY_MS; enqueue(r.frame) }
                retries.removeAll { it.left == 0 }
            }
        }
        road?.let { r ->
            val targets = neighbors.values.filter { it.stable && hosts(it) }.map { it.nodeId to it.beacon.boxPublic } + inviteBack.values
            for ((nodeId, box) in targets) {
                val key = nodeId.toHex()
                if (now - (roadInvitedAt[key] ?: Long.MIN_VALUE / 2) < ROAD_INVITE_MS) continue
                roadInvitedAt[key] = now
                enqueue(RoadInvite.to(identity, nodeId, box, r.ssid, r.passphrase, now).encode())
            }
        }
        if (newNeighbor) {
            newNeighbor = false
            for (p in pockets.values) if (p.reshouts < MAX_RESHOUTS) { p.reshouts++; enqueue(p.env.encode()) }
        }
    }

    /** My Wi-Fi road is up: its key goes, sealed, to every stable neighbor (Camino y Carretera). */
    fun setRoad(ssid: String, passphrase: String) { road = Road(ssid, passphrase); roadInvitedAt.clear() }

    fun closeRoad() { road = null }

    /** Whether I am riding someone's road right now (the app knows; one ride at a time). */
    fun setRiding(on: Boolean) { riding = on }

    // Between two stable neighbors only one opens a road: the one with the smaller id. The other rides it.
    private fun hosts(n: Neighbor) = identity.nodeId.toHex() < n.nodeId.toHex()

    /** Do I need my own road? Only if a stable neighbor expects to ride mine, or someone I could not ride waits. */
    fun wantsRoad(): Boolean = inviteBack.isNotEmpty() || neighbors.values.any { it.stable && hosts(it) }

    fun offerCard(neighbor: Neighbor) = offerTo(neighbor.nodeId, neighbor.beacon.boxPublic)

    // Accepting answers with my card, built from the card just received: it does not matter whether I hear them now.
    fun acceptCard(card: Card) {
        store.saveContact(card)
        if (card.nodeId.toHex() !in offeredTo) offerTo(card.nodeId, card.boxPublic)
    }

    private fun offerTo(nodeId: ByteArray, boxPublic: ByteArray) {
        val key = nodeId.toHex()
        offeredTo += key
        val frame = CardOffer.to(identity, nodeId, boxPublic, clock()).encode()
        if (store.contact(nodeId) == null) cardRetries[key] = Retry(frame, CARD_RETRIES, clock() + RETRY_MS)
        enqueue(frame)
    }

    fun send(to: Card, text: String, maxHops: Int = 8, service: String = "chat"): String {
        val sealed = Envelope.seal(identity, to, Letter.Text(text, service), clock(), maxHops = maxHops)
        val env = sealed.envelope
        val id = env.msgId.toHex()
        sent[id] = sealed
        while (sent.size > 500) sent.remove(sent.keys.first())
        seen += id
        store.saveMessage(Message(to.nodeId.toHex(), true, text, clock(), id, MessageState.SENT))
        pockets[id] = Pocket(env) // the sender keeps its own letter until it is confirmed
        savePockets()
        enqueue(env.encode())
        return id
    }

    fun plaza(): List<PlazaLine> = plaza.map { PlazaLine(it.id, it.name, it.text, it.ts, it.mine, it.heardBy.toList()) }

    /** La plaza: an open message for whoever is within earshot; it is not carried further. */
    fun sendPlaza(text: String): String {
        val p = Plaza.of(identity, text, clock())
        keepPlaza(PlazaEntry(p.id, identity.name, text, p.ts, true))
        val frame = p.encode()
        plazaRetries[p.id] = Retry(frame, PLAZA_RETRIES, clock() + RETRY_MS)
        enqueue(frame)
        return p.id
    }

    private fun keepPlaza(e: PlazaEntry) { plaza += e; while (plaza.size > 200) plaza.removeAt(0); store.savePlaza(plaza()) }

    fun onFrame(bytes: ByteArray): List<NodeEvent> {
        framesHeard++
        val frame = reassembler.accept(bytes) ?: return emptyList()
        return when (val p = Packet.parseOrNull(frame)) {
            is Beacon -> onBeacon(p)
            is CardOffer -> onCardOffer(p)
            is Envelope -> onEnvelope(p)
            is Plaza -> onPlaza(p)
            is Heard -> onHeard(p)
            is RoadInvite -> onRoadInvite(p)
            is Payment -> onPayment(p)
            else -> emptyList()
        }
    }

    private fun onBeacon(b: Beacon): List<NodeEvent> {
        if (b.nodeId.contentEquals(identity.nodeId) || !b.verify()) return emptyList()
        val key = b.nodeId.toHex()
        val isNew = key !in neighbors
        neighbors[key] = Neighbor(b, clock(), (neighbors[key]?.beats ?: 0) + 1)
        if (isNew) newNeighbor = true
        return if (isNew) listOf(NodeEvent.NeighborsChanged) else emptyList()
    }

    private fun onPlaza(p: Plaza): List<NodeEvent> {
        if (p.nodeId.contentEquals(identity.nodeId) || !p.verify()) return emptyList()
        if (plaza.any { it.id == p.id }) { enqueue(Heard.of(identity, p.id, clock()).encode()); return emptyList() } // my "heard" got lost: say it again
        keepPlaza(PlazaEntry(p.id, p.name, p.text, p.ts, false))
        enqueue(Heard.of(identity, p.id, clock()).encode())
        return listOf(NodeEvent.PlazaReceived(p.id, p.name, p.text))
    }

    // A payment: if my record is in the journey the origin confirmed, I earn a candy. Then it keeps spreading.
    private fun onPayment(p: Payment): List<NodeEvent> {
        val id = p.msgId.toHex()
        if (!paymentsSeen.add(id)) return emptyList()
        while (paymentsSeen.size > 4000) paymentsSeen.remove(paymentsSeen.first())
        if (p.ttl > 1) enqueue(p.withTtl(p.ttl - 1).encode())
        val c = carried.remove(id) ?: return emptyList()
        if (!c.src.contentEquals(p.src) || !p.proves(c.commit) || p.journey.none { it.contentEquals(c.myRecord) }) return emptyList()
        candies++
        store.saveCandies(candies)
        return listOf(NodeEvent.CandyEarned(candies))
    }

    private fun onRoadInvite(i: RoadInvite): List<NodeEvent> {
        val r = i.open(identity) ?: return emptyList()
        if (riding) { inviteBack[i.from.toHex()] = i.from to i.fromBox; return emptyList() } // busy rider: I open mine for them
        val name = neighbors[i.from.toHex()]?.name ?: store.contact(i.from)?.name ?: ""
        return listOf(NodeEvent.RoadInvited(i.from, name, r.ssid, r.passphrase))
    }

    private fun onHeard(h: Heard): List<NodeEvent> {
        val mine = plaza.firstOrNull { it.mine && it.id == h.plazaId } ?: return emptyList()
        if (!h.verify()) return emptyList()
        plazaRetries.remove(h.plazaId)
        if (!mine.heardBy.add(h.name.ifBlank { h.nodeId.toHex().take(8) })) return emptyList()
        store.savePlaza(plaza())
        return listOf(NodeEvent.PlazaHeard(h.plazaId, h.name))
    }

    private fun onCardOffer(o: CardOffer): List<NodeEvent> {
        val card = o.open(identity) ?: return emptyList()
        val key = card.nodeId.toHex()
        cardRetries.remove(key)
        if (key in offeredTo) { store.saveContact(card); return listOf(NodeEvent.CardReceived(card)) } // mutual tap: saved right away
        return listOf(NodeEvent.CardReceived(card))
    }

    private fun onEnvelope(env: Envelope): List<NodeEvent> {
        val now = clock()
        val id = env.msgId.toHex()
        if (id in seen || env.expired(now) || !env.verify()) return emptyList()
        remember(id)
        if (env.isFor(identity)) {
            val opened = env.open(identity) ?: return emptyList()
            return when (val body = opened.body) {
                is Letter.Text -> {
                    val from = store.contact(opened.sender) ?: Card(opened.sender, ByteArray(32), ByteArray(32), "Desconocido", 0, ByteArray(64))
                    store.saveMessage(Message(opened.sender.toHex(), false, body.text, opened.ts, id, MessageState.RECEIVED))
                    // The journey: who carried it, read with the key that came inside. The receipt carries only hashes.
                    val hops = opened.journeySecret?.let { env.journey(it) } ?: emptyList()
                    val receipt = Letter.Ack(env.msgId, env.blobs.map { Crypto.hash(it) }, opened.deliverySecret ?: ByteArray(0))
                    store.contact(opened.sender)?.let { enqueue(Envelope.letter(identity, it, receipt, now).encode()) }
                    listOf(NodeEvent.LetterReceived(from, body.text, id, hops.filter { it.valid }.map { it.giver.toHex() }, body.service))
                }
                is Letter.Ack -> {
                    val acked = body.msgId.toHex()
                    if (pockets.remove(acked) != null) savePockets()
                    store.setState(acked, MessageState.DELIVERED)
                    // The origin checks the receipt with the secret only the reader could know, signs it and pays the carriers.
                    sent.remove(acked)?.takeIf { it.deliverySecret.contentEquals(body.revealed) && body.journey.isNotEmpty() }?.let {
                        val pay = Payment.confirm(it, body.journey, body.revealed)
                        paymentsSeen += acked
                        enqueue(pay.encode())
                    }
                    listOf(NodeEvent.Delivered(acked))
                }
            }
        }
        if (env.hopCount < env.maxHops) {
            val next = env.withHop(identity, now)
            next.deliveryCommit?.let { carried[id] = Carried(env.src, it, Crypto.hash(next.blobs.last())); while (carried.size > 2000) carried.remove(carried.keys.first()) }
            pockets[id] = Pocket(next)
            savePockets()
            enqueue(next.encode())
        }
        return emptyList()
    }

    private fun remember(id: String) {
        seen += id
        if (seen.size > MAX_SEEN) seen.remove(seen.first())
    }

    private fun enqueue(frame: ByteArray) {
        for (part in Fragments.split(frame, maxFrame)) outbox.addLast(part)
        while (outbox.size > MAX_OUTBOX) outbox.removeFirst()
    }

    companion object {
        // A signed heartbeat is ~150 bytes: about 12 classic micros with parity on the universal shout, so it goes out every 15 s.
        const val BEACON_MS = 15_000L
        const val NEIGHBOR_TTL_MS = 30_000L
        const val SWEEP_MS = 1_000L
        const val MAX_RESHOUTS = 5
        const val MAX_SEEN = 4_000
        const val MAX_OUTBOX = 400
        const val RETRY_MS = 10_000L
        const val STABLE_BEATS = 2
        const val ROAD_INVITE_MS = 5 * 60_000L
        const val PLAZA_RETRIES = 3
        const val CARD_RETRIES = 5
    }
}
