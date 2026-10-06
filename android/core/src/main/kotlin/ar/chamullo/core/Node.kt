package ar.chamullo.core

sealed interface NodeEvent {
    data class CardReceived(val card: Card) : NodeEvent
    /** [journey]: the valid givers, in order; [alternatives]: for each, how many neighbors could have carried it instead. */
    data class LetterReceived(
        val from: Card, val text: String, val msgId: String, val journey: List<String> = emptyList(), val service: String = "chat",
        val alternatives: List<Int> = emptyList()
    ) : NodeEvent
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
    val cell get() = beacon.cell
    val bridge get() = beacon.bridge
}

/**
 * The node logic, independent of the radio: it receives frames, decides what to shout and keeps letters in its pocket.
 * Routing is the compass of Discovery & Routing v0.2 §10 ([Compass]): each letter goes hand to hand (offer, accept,
 * then the copy with both signatures, Proof of Relay §5) toward its destination barrio, and spreads by local eco once
 * there. Letters without a destination zone keep the bounded eco of v0.1. A letter nobody can take yet stays in the
 * pocket and is decided again when someone new shows up.
 */
class Node(
    val identity: Identity,
    val store: Store,
    val maxFrame: Int = 240,
    var coded: Boolean = false,
    private val clock: () -> Long
) {
    /**
     * A letter I hold. [sealed] only for my own letters (I write the first hop with its one-time key); [from]: who handed
     * it to me, so the lake never sends it back; [refused]: neighbors that did not take it; [shout]: my eco copy, written once.
     */
    private class Pocket(
        val env: Envelope, val sealed: Sealed? = null, val from: ByteArray? = null, var reshouts: Int = 0, var nextTry: Long = 0,
        val refused: HashMap<String, Long> = HashMap(), var shout: Envelope? = null, var stuck: Boolean = false,
        var stuckSince: Long = Long.MAX_VALUE
    )

    /** An offer in flight: who I offered it to, who already took their copy, and until when I wait. */
    private class Handoff(
        val takers: List<String>, val done: HashSet<String>, val deadline: Long, val alternatives: Int, val detour: Int,
        val copied: HashSet<String> = HashSet() // copies sent, waiting for "la tengo"
    )

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
    private var lastReply = Long.MIN_VALUE / 2
    // Where I am (my cell), where a ferry is about to sail (its heading) and my barrio, for my card (Discovery & Routing §5).
    private var cell: Zone? = null
    private var heading: Zone? = null
    private var myCard: Card? = null
    private var routeDirty = false
    private val pending = HashMap<String, Handoff>()
    private class Promise(val giver: ByteArray, val at: Long)
    private val promised = HashMap<String, Promise>() // letters I accepted and wait for: msg id → giver
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
    // Payments I passed on: kept a while and repeated to new neighbors, so they board ferries like letters (§10.7).
    private class PayPocket(val p: Payment, val ttl: Int, val at: Long, var reshouts: Int = 0)
    private val payPockets = LinkedHashMap<String, PayPocket>()
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
    /** Why copies left my pocket without being handed on: for the twin's report. */
    val dropped = LinkedHashMap<String, Int>()
    private fun drop(id: String, why: String) { if (pockets.remove(id) != null) { pending.remove(id); dropped.merge(why, 1, Int::plus); savePockets() } }
    var framesHeard = 0L; private set

    /** The phone says where it is (from its GPS, or the twin's map). Changing barrio is a mudanza: my contacts hear it. */
    fun locate(here: Zone) {
        val before = cell?.up(Zone.BARRIO)
        cell = here
        routeDirty = true
        val barrio = here.up(Zone.BARRIO)
        if (barrio == before) return
        myCard = null
        if (before != null) for (c in store.contacts()) sendLetter(c, Letter.Moved(card()))
    }

    fun locate(lat: Double, lon: Double) = locate(Zone.of(lat, lon))

    fun cell(): Zone? = cell

    /** Where a letter I hold wants to go when nobody around gets it closer: the island layer may sail me there. */
    fun stuckZone(): Zone? = pockets.values.firstOrNull { it.stuck }?.env?.destZone

    /** My card, with my barrio when I know it. */
    fun card(): Card = myCard ?: Card.of(identity, clock(), cell?.up(Zone.BARRIO)).also { myCard = it }

    /**
     * A ferry about to sail (Camino y Carretera §6.1) announces where it goes, so the letters headed there board it;
     * while it has a heading it carries and does not hand out. Null: it arrived, it is where it is again.
     */
    fun setHeading(to: Zone?) {
        heading = to
        routeDirty = true
        lastBeacon = clock()
        enqueue(beacon())
    }

    private fun beacon() = Beacon.of(identity, coded, clock(), heading ?: cell, bridge != null).encode()

    /** Internet lent as a bridge (Discovery & Routing §11); null: no Internet, or not lending it. */
    var bridge: Bridge? = null
        set(value) { field = value; routeDirty = true }

    /** Bytes this phone moved over Internet as a bridge: what the economy pays for (Economy & Governance §13). */
    var internetBytes = 0L; private set

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
        if (now - lastBeacon >= BEACON_MS) { lastBeacon = now; enqueue(beacon()) }
        // Housekeeping once a second is plenty: lifetimes are tens of seconds, and it keeps an idle tick cheap.
        if (now - lastSweep >= SWEEP_MS) {
            lastSweep = now
            neighbors.values.removeAll { now - it.lastSeen > NEIGHBOR_TTL_MS }
            nearby.values.removeAll { now - it.lastSeen > NEIGHBOR_TTL_MS }
            if (pockets.values.removeAll { it.env.expired(now) }) savePockets()
            payPockets.values.removeAll { now - it.at > PAY_KEEP_MS }
            val late = promised.values.count { now - it.at > 2 * OFFER_TIMEOUT_MS }
            if (late > 0) { promised.values.removeAll { now - it.at > 2 * OFFER_TIMEOUT_MS }; dropped.merge("promesa sin carta", late, Int::plus) } // the copy never came
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
        // Offers that timed out: whoever took it, took it; whoever did not, is left out next time.
        if (pending.isNotEmpty()) for ((id, h) in pending.entries.toList()) if (now >= h.deadline) {
            pending.remove(id)
            val p = pockets[id] ?: continue
            if (h.done.isNotEmpty()) { pockets.remove(id); savePockets() }
            else { for (t in h.takers) p.refused[t] = now; p.nextTry = now }
        }
        val fresh = newNeighbor
        val all = newNeighbor || routeDirty
        newNeighbor = false; routeDirty = false
        if (fresh) for (k in payPockets.values) if (k.reshouts < MAX_RESHOUTS) { k.reshouts++; shoutPayment(k.p, k.ttl) }
        // Anyone new: an eco may shout again. A heading or a walk only re-plans the compass.
        if (pockets.isNotEmpty()) for ((id, p) in pockets.entries.toList()) if (all || now >= p.nextTry) decide(id, p, fresh)
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
        val frame = CardOffer.to(identity, nodeId, boxPublic, clock(), cell?.up(Zone.BARRIO)).encode()
        if (store.contact(nodeId) == null) cardRetries[key] = Retry(frame, CARD_RETRIES, clock() + RETRY_MS)
        enqueue(frame)
    }

    /** [maxHops]: null lets the compass size it to the distance (Discovery & Routing §10.3). */
    fun send(to: Card, text: String, maxHops: Int? = null, service: String = "chat"): String {
        store.saveContact(to)
        val sealed = sendLetter(to, Letter.Text(text, service), maxHops)
        val id = sealed.envelope.msgId.toHex()
        sent[id] = sealed
        while (sent.size > 500) sent.remove(sent.keys.first())
        store.saveMessage(Message(to.nodeId.toHex(), true, text, clock(), id, MessageState.SENT))
        return id
    }

    // Every letter of mine carries my current card; I keep it in my pocket and write its first hop (giver_1 = src).
    private fun sendLetter(to: Card, body: Letter, maxHops: Int? = null): Sealed {
        val sealed = Envelope.seal(identity, to, body, clock(), maxHops = maxHops ?: Compass.maxHops(cell, to.zone), senderCard = card())
        val id = sealed.envelope.msgId.toHex()
        remember(id)
        val p = Pocket(sealed.envelope, sealed)
        pockets[id] = p
        savePockets()
        decide(id, p, true)
        return sealed
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
            is Offer -> onOffer(p)
            is Accept -> onAccept(p)
            else -> emptyList()
        }
    }

    private fun onBeacon(b: Beacon): List<NodeEvent> {
        if (b.nodeId.contentEquals(identity.nodeId) || !b.verify()) return emptyList()
        val key = b.nodeId.toHex()
        val before = neighbors[key]
        val isNew = before == null
        neighbors[key] = Neighbor(b, clock(), (before?.beats ?: 0) + 1)
        if (isNew) newNeighbor = true
        if (before != null && before.cell != b.cell) routeDirty = true // a ferry announcing its heading, or someone walking
        // Someone new: I answer with my heartbeat, so a ferry that just arrived knows the island at once (§10.2).
        if (isNew && clock() - lastReply >= BEACON_REPLY_MS) { lastReply = clock(); lastBeacon = clock(); enqueue(beacon()) }
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
        // It arrived: no need to keep carrying my copy.
        if (pockets.remove(id) != null) { pending.remove(id); savePockets() }
        if (p.isReceiver(identity.nodeId) && p.ttl > 1) forwardPayment(p, p.ttl - 1)
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
        val promise = if (env.isReceiver(identity.nodeId)) promised.remove(id) else null
        if ((id in seen && promise == null) || env.expired(now) || !env.verify()) return emptyList()
        if (env.isFor(identity)) {
            if (id in seen) return emptyList()
            remember(id)
            return open(env, now)
        }
        // A copy handed to someone else: I heard it, but it is not mine to carry (§10.5).
        if (!env.isReceiver(identity.nodeId)) return emptyList()
        remember(id)
        if (env.hopCount >= env.maxHops || pockets.size >= MAX_POCKETS) return emptyList()
        // "La tengo": the giver may let go of its copy now that mine is here.
        promise?.let { enqueue(Accept(identity.nodeId, it.giver, env.msgId, 0, 0, ByteArray(0), already = true).encode()) }
        val p = Pocket(env, from = promise?.giver)
        pockets[id] = p
        savePockets()
        decide(id, p, true)
        return emptyList()
    }

    private fun open(env: Envelope, now: Long): List<NodeEvent> {
        val id = env.msgId.toHex()
        val opened = env.open(identity) ?: return emptyList()
        // Every letter brings the sender's current card: that is how I learn their barrio (Discovery & Routing §5).
        opened.senderCard?.let { fresh -> if ((store.contact(fresh.nodeId)?.ts ?: -1) <= fresh.ts) store.saveContact(fresh) }
        return when (val body = opened.body) {
            is Letter.Text -> {
                val from = store.contact(opened.sender) ?: Card(opened.sender, ByteArray(32), ByteArray(32), "Desconocido", 0, ByteArray(64))
                store.saveMessage(Message(opened.sender.toHex(), false, body.text, opened.ts, id, MessageState.RECEIVED))
                // The journey: who carried it, read with the key that came inside. The receipt carries only hashes.
                val hops = opened.journeySecret?.let { env.journey(it) } ?: emptyList()
                val receipt = Letter.Ack(env.msgId, env.blobs.map { Crypto.hash(it) }, opened.deliverySecret ?: ByteArray(0))
                store.contact(opened.sender)?.let { sendLetter(it, receipt) }
                val valid = hops.filter { it.valid && !it.giver.contentEquals(env.src) } // the carriers: the origin's own hop is not one
                listOf(NodeEvent.LetterReceived(from, body.text, id, valid.map { it.giver.toHex() }, body.service, valid.map { it.alternatives }))
            }
            is Letter.Ack -> {
                val acked = body.msgId.toHex()
                if (pockets.remove(acked) != null) { pending.remove(acked); savePockets() }
                store.setState(acked, MessageState.DELIVERED)
                // The origin checks the receipt with the secret only the reader could know, signs it and pays the carriers.
                sent.remove(acked)?.takeIf { it.deliverySecret.contentEquals(body.revealed) && body.journey.isNotEmpty() }?.let {
                    val pay = Payment.confirm(it, body.journey, body.revealed)
                    paymentsSeen += acked
                    forwardPayment(pay, it.envelope.maxHops)
                }
                listOf(NodeEvent.Delivered(acked))
            }
            is Letter.Moved -> emptyList() // the new card is already saved
        }
    }

    /* ---------- the compass at work (Discovery & Routing §10.4, §10.5) ---------- */

    private fun peers() = neighbors.values.map { Compass.Peer(it.nodeId, it.cell, it.stable) }

    private fun giverOf(p: Pocket) = p.sealed?.signer() ?: identity.signer()

    // What to do with a letter in my pocket. [fresh]: a new neighbor or a new letter, so an eco may shout again.
    private fun decide(id: String, p: Pocket, fresh: Boolean) {
        if (id in pending) return
        val now = clock()
        val env = p.env
        if (env.expired(now)) { drop(id, "venció"); return }
        p.nextTry = Long.MAX_VALUE
        if (heading != null) return // a sailing ferry carries
        val plan = Compass.plan(cell, env.destZone, env.localTtl, env.detour, peers(), p.from, p.sealed != null, p.refused, now)
        p.stuck = false
        if (plan != Compass.Plan.Stop && plan != Compass.Plan.Hold && env.hopCount >= env.maxHops) { drop(id, "sin saltos"); return }
        // The big jump (§11): beyond the next barrio the islands are too slow, so a bridge at hand takes it up.
        val dest = env.destZone; val here = cell
        if (dest != null && here != null && !dest.contains(here) && here.distanceTo(dest) > BIG_JUMP_M) {
            if (upload(id, p) || toBridge(id, p)) return
        }
        when (plan) {
            is Compass.Plan.Eco -> {
                if (!fresh || p.reshouts >= MAX_RESHOUTS) return
                val copy = p.shout ?: env.withHop(giverOf(p), now).routed(emptyList(), plan.localTtl, 0).also { p.shout = it; carriedBy(p, it) }
                p.reshouts++
                enqueue(copy.encode())
            }
            is Compass.Plan.River -> { p.stuckSince = Long.MAX_VALUE; offer(id, p, plan.takers, plan.alternatives, 0) }
            // Nobody gets it closer: a bridge takes it up now; anyone else waits for the ferry of need (§10.6), then
            // hands it to a neighbor who lends Internet, and only then tries the lake.
            is Compass.Plan.Lake -> {
                p.stuckSince = minOf(p.stuckSince, now)
                if (upload(id, p)) return
                val waited = now - p.stuckSince >= LAKE_WAIT_MS
                if (waited && toBridge(id, p)) return
                if (waited) offer(id, p, listOf(plan.taker), 0, plan.detour)
                else { p.nextTry = now + RETRY_MS; p.stuck = true }
            }
            Compass.Plan.Hold -> {
                p.stuckSince = minOf(p.stuckSince, now)
                if (upload(id, p)) return
                if (now - p.stuckSince >= LAKE_WAIT_MS && toBridge(id, p)) return
                p.nextTry = now + RETRY_MS; p.stuck = env.destZone != null
            }
            Compass.Plan.Stop -> drop(id, "eco local agotado")
        }
    }

    // The big jump (Discovery & Routing §11): up to Internet, down through three bridges of the destination barrio.
    private fun upload(id: String, p: Pocket): Boolean {
        val b = bridge ?: return false
        val zone = p.env.destZone ?: return false
        val here = cell ?: return false
        if (zone.contains(here)) return false
        val down = b.peersIn(zone).filter { !it.contentEquals(identity.nodeId) }.take(BRIDGE_PEERS)
        if (down.isEmpty()) return false
        val copy = p.env.withHop(giverOf(p), clock()).routed(emptyList(), null, 0)
        carriedBy(p, copy)
        val bytes = copy.encode()
        for (peer in down) { b.send(peer, bytes); internetBytes += bytes.size }
        pockets.remove(id); savePockets()
        return true
    }

    // No Internet here: hand it to a neighbor who lends it.
    private fun toBridge(id: String, p: Pocket): Boolean {
        if (bridge != null) return false // I am one, and I could not go up: no bridge down there
        val now = clock()
        val b = neighbors.values.filter { n ->
            n.bridge && (p.from == null || !n.nodeId.contentEquals(p.from)) && (p.refused[n.nodeId.toHex()]?.let { now - it < Compass.REFUSED_MS } != true)
        }.minByOrNull { it.nodeId.toHex() } ?: return false
        offer(id, p, listOf(Compass.Peer(b.nodeId, b.cell, b.stable)), 0, p.env.detour)
        return true
    }

    private fun offer(id: String, p: Pocket, takers: List<Compass.Peer>, alternatives: Int, detour: Int) {
        val giver = giverOf(p).id
        for (t in takers) enqueue(p.env.offer(giver, t.id).encode())
        pending[id] = Handoff(takers.map { it.id.toHex() }, HashSet(), clock() + OFFER_TIMEOUT_MS, alternatives, detour)
    }

    // The taker named me as its giver: I write my record with its signature and hand it its own copy.
    private fun onAccept(a: Accept): List<NodeEvent> {
        val id = a.msgId.toHex()
        val h = pending[id] ?: return emptyList()
        val p = pockets[id] ?: return emptyList()
        val taker = a.taker.toHex()
        if (taker !in h.takers || taker in h.done || !a.msgId.contentEquals(p.env.msgId)) return emptyList()
        if (a.already) {
            // "La tengo": my copy arrived, or another one is already there. Either way that side is covered.
            if (taker !in h.copied) dropped.merge("la tiene otro", 1, Int::plus)
            h.done += taker
        } else if (taker !in h.copied) {
            if (!p.env.accepts(a, giverOf(p).id)) return emptyList()
            val copy = p.env.withHop(giverOf(p), clock(), a, h.alternatives).routed(listOf(a.taker), null, h.detour)
            carriedBy(p, copy)
            enqueue(copy.encode())
            h.copied += taker // I let go when the taker says it got it
        }
        if (h.done.size == h.takers.size) { pending.remove(id); pockets.remove(id); savePockets() }
        return emptyList()
    }

    // Someone wants to hand me a letter: accepting is promising to sign (Proof of Relay §5).
    private fun onOffer(o: Offer): List<NodeEvent> {
        if (!o.taker.contentEquals(identity.nodeId)) return emptyList()
        val id = o.msgId.toHex()
        // "La tengo": another copy is in my pocket right now, so the one behind can go. Only if I hold it: having seen it
        // proves nothing (I may have passed it on, and that copy may be gone). Never for my own letters: it would tell my
        // neighbor I wrote it. In every other case I stay quiet, like any refusal.
        val holding = (pockets[id]?.let { it.sealed == null } ?: false) || id in promised
        if (holding) { enqueue(o.already(identity).encode()); return emptyList() }
        if (id in pockets || id in sent) return emptyList() // my own letter
        // Having seen it does not close the road: offered by hand, I take it again (§10.5). The letter cannot loop:
        // it never goes back to its giver, it waits before backing up, and max_hops is the hard limit.
        if (o.exp < clock() || pockets.size >= MAX_POCKETS) return emptyList()
        promised[id] = Promise(o.giver, clock())
        enqueue(o.accept(identity, clock()).encode())
        return emptyList()
    }

    // What I need to cash my candy later: the hash of the record I wrote (not for my own letters: the origin does not charge).
    private fun carriedBy(p: Pocket, copy: Envelope) {
        if (p.sealed != null) return
        val commit = copy.deliveryCommit ?: return
        carried[copy.msgId.toHex()] = Carried(copy.src, commit, Crypto.hash(copy.blobs.last()))
        while (carried.size > 2000) carried.remove(carried.keys.first())
    }

    // Payments go like letters toward the zone (§10.7): two streams, local eco on arrival, a short shout if nothing gets closer.
    private fun forwardPayment(p: Payment, ttl: Int) {
        payPockets[p.msgId.toHex()] = PayPocket(p, ttl, clock())
        while (payPockets.size > MAX_POCKETS) payPockets.remove(payPockets.keys.first())
        shoutPayment(p, ttl)
    }

    private fun shoutPayment(p: Payment, ttl: Int) {
        val zone = p.zone ?: return enqueue(p.routed(null, emptyList(), ttl).encode())
        // Over the big jump too: the carriers down there earn as well.
        val b = bridge; val here = cell
        if (b != null && here != null && !zone.contains(here)) {
            val bytes = p.routed(zone, emptyList(), minOf(ttl, Compass.LOCAL_TTL)).encode()
            for (peer in b.peersIn(zone).filter { !it.contentEquals(identity.nodeId) }.take(BRIDGE_PEERS)) { b.send(peer, bytes); internetBytes += bytes.size }
        }
        when (val plan = Compass.plan(cell, zone, null, 0, peers(), null, origin = true)) {
            is Compass.Plan.River -> enqueue(p.routed(zone, plan.takers.map { it.id }, ttl).encode())
            is Compass.Plan.Eco -> enqueue(p.routed(zone, emptyList(), minOf(ttl, Compass.LOCAL_TTL)).encode())
            else -> enqueue(p.routed(zone, emptyList(), minOf(ttl, 2)).encode())
        }
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
        const val MAX_POCKETS = 200
        const val OFFER_TIMEOUT_MS = 2_000L
        const val BEACON_REPLY_MS = 5_000L
        const val PAY_KEEP_MS = 30 * 60_000L
        const val LAKE_WAIT_MS = 2 * Islands.FERRY_TURN_MS
        // Three bridges bring a letter down: if one fails, the others still do (the Capitán's "diversificando").
        const val BRIDGE_PEERS = 3
        // A big jump: farther than the next barrio (~1.8 km wide), where islands would take half an hour or more.
        const val BIG_JUMP_M = 2_500.0
    }
}
