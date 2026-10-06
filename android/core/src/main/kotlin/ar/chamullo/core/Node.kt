package ar.chamullo.core

sealed interface NodeEvent {
    data class CardReceived(val card: Card) : NodeEvent
    data class LetterReceived(val from: Card, val text: String, val msgId: String) : NodeEvent
    data class Delivered(val msgId: String) : NodeEvent
    data object NeighborsChanged : NodeEvent
    data class PlazaReceived(val id: String, val name: String, val text: String) : NodeEvent
    data class PlazaHeard(val id: String, val name: String) : NodeEvent
}

class PlazaLine(val id: String, val name: String, val text: String, val ts: Long, val mine: Boolean, val heardBy: List<String>)

class Neighbor(val beacon: Beacon, val lastSeen: Long) {
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
    private val pockets = LinkedHashMap<String, Pocket>()
    private val offeredTo = mutableSetOf<String>()
    private var lastBeacon = Long.MIN_VALUE / 2
    private var newNeighbor = false
    private class PlazaEntry(val id: String, val name: String, val text: String, val ts: Long, val mine: Boolean, val heardBy: LinkedHashSet<String> = LinkedHashSet())
    private val plaza = ArrayList<PlazaEntry>()

    var shoutsSent = 0L; private set
    var framesHeard = 0L; private set

    fun pocketCount() = pockets.size

    fun neighbors(): List<Neighbor> = neighbors.values.toList()

    fun drainOutbox(): List<ByteArray> = buildList { while (outbox.isNotEmpty()) add(outbox.removeFirst()) }.also { shoutsSent += it.size }

    fun tick() {
        val now = clock()
        if (now - lastBeacon >= BEACON_MS) { lastBeacon = now; enqueue(Beacon.of(identity, coded, now).encode()) }
        neighbors.values.removeAll { now - it.lastSeen > NEIGHBOR_TTL_MS }
        pockets.values.removeAll { it.env.expired(now) }
        if (newNeighbor) {
            newNeighbor = false
            for (p in pockets.values) if (p.reshouts < MAX_RESHOUTS) { p.reshouts++; enqueue(p.env.encode()) }
        }
    }

    fun offerCard(neighbor: Neighbor) {
        offeredTo += neighbor.nodeId.toHex()
        enqueue(CardOffer.to(identity, neighbor.beacon, clock()).encode())
    }

    fun acceptCard(card: Card) {
        store.saveContact(card)
        val key = card.nodeId.toHex()
        if (key !in offeredTo) neighbors[key]?.let { offerCard(it) }
    }

    fun send(to: Card, text: String, maxHops: Int = 8): String {
        val env = Envelope.letter(identity, to, Letter.Text(text), clock(), maxHops = maxHops)
        val id = env.msgId.toHex()
        seen += id
        store.saveMessage(Message(to.nodeId.toHex(), true, text, clock(), id, MessageState.SENT))
        pockets[id] = Pocket(env) // the sender keeps its own letter until it is confirmed
        enqueue(env.encode())
        return id
    }

    fun plaza(): List<PlazaLine> = plaza.map { PlazaLine(it.id, it.name, it.text, it.ts, it.mine, it.heardBy.toList()) }

    /** La plaza: an open message for whoever is within earshot; it is not carried further. */
    fun sendPlaza(text: String): String {
        val p = Plaza.of(identity, text, clock())
        keepPlaza(PlazaEntry(p.id, identity.name, text, p.ts, true))
        enqueue(p.encode())
        return p.id
    }

    private fun keepPlaza(e: PlazaEntry) { plaza += e; while (plaza.size > 200) plaza.removeAt(0) }

    fun onFrame(bytes: ByteArray): List<NodeEvent> {
        framesHeard++
        val frame = reassembler.accept(bytes) ?: return emptyList()
        return when (val p = Packet.parseOrNull(frame)) {
            is Beacon -> onBeacon(p)
            is CardOffer -> onCardOffer(p)
            is Envelope -> onEnvelope(p)
            is Plaza -> onPlaza(p)
            is Heard -> onHeard(p)
            else -> emptyList()
        }
    }

    private fun onBeacon(b: Beacon): List<NodeEvent> {
        if (b.nodeId.contentEquals(identity.nodeId) || !b.verify()) return emptyList()
        val key = b.nodeId.toHex()
        val isNew = key !in neighbors
        neighbors[key] = Neighbor(b, clock())
        if (isNew) newNeighbor = true
        return if (isNew) listOf(NodeEvent.NeighborsChanged) else emptyList()
    }

    private fun onPlaza(p: Plaza): List<NodeEvent> {
        if (p.nodeId.contentEquals(identity.nodeId) || !p.verify() || plaza.any { it.id == p.id }) return emptyList()
        keepPlaza(PlazaEntry(p.id, p.name, p.text, p.ts, false))
        enqueue(Heard.of(identity, p.id, clock()).encode())
        return listOf(NodeEvent.PlazaReceived(p.id, p.name, p.text))
    }

    private fun onHeard(h: Heard): List<NodeEvent> {
        val mine = plaza.firstOrNull { it.mine && it.id == h.plazaId } ?: return emptyList()
        if (!h.verify() || !mine.heardBy.add(h.name.ifBlank { h.nodeId.toHex().take(8) })) return emptyList()
        return listOf(NodeEvent.PlazaHeard(h.plazaId, h.name))
    }

    private fun onCardOffer(o: CardOffer): List<NodeEvent> {
        val card = o.open(identity) ?: return emptyList()
        val key = card.nodeId.toHex()
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
                    store.contact(opened.sender)?.let { enqueue(Envelope.letter(identity, it, Letter.Ack(env.msgId), now).encode()) }
                    listOf(NodeEvent.LetterReceived(from, body.text, id))
                }
                is Letter.Ack -> {
                    val acked = body.msgId.toHex()
                    pockets.remove(acked)
                    store.setState(acked, MessageState.DELIVERED)
                    listOf(NodeEvent.Delivered(acked))
                }
            }
        }
        if (env.hopCount < env.maxHops) {
            val next = env.withHop()
            pockets[id] = Pocket(next)
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
        // A signed heartbeat is ~150 bytes: about 9 classic micros on the universal shout, so it goes out every 8 s.
        const val BEACON_MS = 8_000L
        const val NEIGHBOR_TTL_MS = 30_000L
        const val MAX_RESHOUTS = 5
        const val MAX_SEEN = 4_000
        const val MAX_OUTBOX = 400
    }
}
