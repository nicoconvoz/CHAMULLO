package ar.chamullo.core

import kotlin.math.ln

/**
 * An entry of a page (Economy & Governance §9.1): a confirmed journey, a carrier's claim, a spend, the close of a period
 * with its shares, or the deposition of a king.
 */
sealed interface Entry {
    fun encode(): ByteArray

    class OfJourney(val journey: Journey) : Entry {
        override fun encode() = byteArrayOf(J) + journey.encode()
    }

    class OfClaim(val claim: Claim) : Entry {
        override fun encode() = byteArrayOf(C) + claim.encode()
    }

    /** Lucas from [from] to [to]; [to] null is priority: half burned, half for the carriers of letter [what] (§6.1). */
    class Spend(val from: ByteArray, val to: ByteArray?, val amount: Long, val what: String, val ts: Long, val sig: ByteArray) : Entry {
        fun body() = from + (to ?: ByteArray(0)) + Tlv.u64(amount) + what.toByteArray() + Tlv.u64(ts)
        override fun encode() = byteArrayOf(S) + Tlv.encode(listOfNotNull(2L to from, to?.let { 4L to it }, 6L to Tlv.u64(amount), 8L to what.toByteArray(), 10L to Tlv.u64(ts), 12L to sig))
    }

    /** The close of [period]: what each one gets, wages of the court first, the rest by score (§4.2, §8.2). */
    class Close(val period: Long, val shares: Map<String, Long>) : Entry {
        override fun encode() = byteArrayOf(K) + Tlv.encode(listOf(2L to Tlv.u64(period),
            4L to Writer().apply { for ((id, v) in shares.toSortedMap()) raw(hex(id)).u64(v) }.bytes()))
    }

    /** More than two thirds of the nobility depose [king] (§8.3). */
    class Depose(val king: ByteArray, val ts: Long, val sigs: List<Pair<ByteArray, ByteArray>>) : Entry {
        override fun encode() = byteArrayOf(D) + Tlv.encode(listOf(2L to king, 4L to Tlv.u64(ts),
            6L to Writer().apply { for ((id, s) in sigs) raw(id).raw(s) }.bytes()))
    }

    companion object {
        private const val J: Byte = 1
        private const val C: Byte = 2
        private const val S: Byte = 3
        private const val K: Byte = 4
        private const val D: Byte = 5

        fun journey(j: Journey): Entry = OfJourney(j)
        fun claim(c: Claim): Entry = OfClaim(c)

        fun spend(from: Identity, to: ByteArray?, amount: Long, what: String, ts: Long): Spend {
            val unsigned = Spend(from.nodeId, to, amount, what, ts, ByteArray(0))
            return Spend(from.nodeId, to, amount, what, ts, from.sign("SPEND", unsigned.body()))
        }

        fun deposeBody(king: ByteArray, ts: Long) = king + Tlv.u64(ts)

        fun depose(king: ByteArray, ts: Long, nobles: List<Identity>) =
            Depose(king, ts, nobles.map { it.nodeId to it.sign("DEPOSE", deposeBody(king, ts)) })

        fun parse(bytes: ByteArray): Entry? = runCatching {
            val rest = bytes.copyOfRange(1, bytes.size)
            when (bytes[0]) {
                J -> OfJourney(Journey.decode(rest))
                C -> OfClaim(Packet.parse(rest) as Claim)
                S -> Tlv.decode(rest).let { t -> Spend(t.getValue(2), t[4], Tlv.readU64(t.getValue(6)), String(t.getValue(8)), Tlv.readU64(t.getValue(10)), t.getValue(12)) }
                K -> Tlv.decode(rest).let { t ->
                    val r = Reader(t.getValue(4))
                    Close(Tlv.readU64(t.getValue(2)), buildMap { while (r.remaining > 0) put(r.take(32).toHex(), r.u64()) })
                }
                D -> Tlv.decode(rest).let { t ->
                    val r = Reader(t.getValue(6))
                    Depose(t.getValue(2), Tlv.readU64(t.getValue(4)), buildList { while (r.remaining > 0) add(r.take(32) to r.take(64)) })
                }
                else -> null
            }
        }.getOrNull()
    }
}

/** A page of the ledger: chained to the one before, written by the king, endorsed by the nobility (§9.1). */
class Page(
    val pueblo: Zone, val index: Int, val prev: ByteArray, val ts: Long, val entries: List<ByteArray>,
    val king: ByteArray, val kingSig: ByteArray, val endorsements: List<Pair<ByteArray, ByteArray>>
) {
    fun hash(): ByteArray = Crypto.hash("CHAMULLO/1/PAGE".toByteArray() + 0.toByte() + pueblo.encode() + Tlv.u64(index.toLong()) + prev +
        Tlv.u64(ts) + Writer().apply { for (e in entries) varint(e.size.toLong()).raw(e) }.bytes() + king)

    fun endorsedBy(noble: Identity) = Page(pueblo, index, prev, ts, entries, king, kingSig, endorsements + (noble.nodeId to noble.sign("PAGE", hash())))

    fun encode(): ByteArray = Tlv.encode(listOf(
        2L to pueblo.encode(), 4L to Tlv.u64(index.toLong()), 6L to prev, 8L to Tlv.u64(ts),
        10L to Writer().apply { for (e in entries) varint(e.size.toLong()).raw(e) }.bytes(),
        12L to king, 14L to kingSig, 16L to Writer().apply { for ((id, s) in endorsements) raw(id).raw(s) }.bytes()
    ))

    companion object {
        fun decode(bytes: ByteArray): Page {
            val t = Tlv.decode(bytes)
            val e = Reader(t.getValue(10)); val n = Reader(t.getValue(16))
            return Page(Zone.decode(t.getValue(2)), Tlv.readU64(t.getValue(4)).toInt(), t.getValue(6), Tlv.readU64(t.getValue(8)),
                buildList { while (e.remaining > 0) add(e.take(e.varint().toInt())) }, t.getValue(12), t.getValue(14),
                buildList { while (n.remaining > 0) add(n.take(32) to n.take(64)) })
        }
    }
}

/**
 * La libreta del pueblo (Economy & Governance §8-§10). Everyone in the pueblo keeps a copy and anyone can audit it: every
 * page is checked against the same deterministic rules, so all copies agree on balances, scores and the court.
 *
 * - **Genesis:** the founder writes alone until a closed period has [Params.genesisContributors] contributors (§10).
 * - **Court:** king and nobles by score of the last closed period; a page needs the king's signature and more than two
 *   thirds of the nobility (§8.3). More than two thirds can depose the king; the first noble writes until the next period.
 * - **Money:** journeys and claims are checked like the spec says (Proof of Relay §6.4, §7); at each close the bag is
 *   shared: wages of the court first (§8.2), the rest by score (§4.2, §5). Spends need a signed balance.
 */
class Ledger(val params: Params) {
    class Params(
        val pueblo: Zone, val founder: ByteArray, val budget: Long = 1_000, val periodMs: Long = 24 * 3600_000L,
        val maturityMs: Long = 30L * 24 * 3600_000L, val nobles: Int = 20, val genesisContributors: Int = 21
    )

    class Court(val king: ByteArray?, val nobles: List<ByteArray>)

    /** A verified claim: who carried, next to whom, how scarce, and its part of the delivery (§5.1-§5.3). */
    private class Carry(val carrier: String, val neighbor: String, val scarcity: Double, val share: Double, val msgId: String)

    private class State {
        val balances = HashMap<String, Long>()
        val journeys = HashMap<String, Journey>()
        val claimed = HashSet<String>()
        val carries = ArrayList<Carry>()            // of the open period
        val claimants = HashMap<String, HashSet<String>>() // msg id → who claimed it, for priority escrow
        val firstSeen = HashMap<String, Long>()
        val escrow = HashMap<String, Long>()        // msg id → Lucas offered for priority, waiting for its carriers
        var period = 0L
        var lastScores: Map<String, Double> = emptyMap()
        var deposed: String? = null

        fun copy() = State().also { s ->
            s.balances += balances; s.journeys += journeys; s.claimed += claimed; s.carries += carries
            claimants.forEach { (k, v) -> s.claimants[k] = HashSet(v) }; s.firstSeen += firstSeen; s.escrow += escrow
            s.period = period; s.lastScores = lastScores; s.deposed = deposed
        }
    }

    private var state = State()
    private val book = ArrayList<Page>()
    val pages: List<Page> get() = book

    fun balance(id: ByteArray): Long = state.balances[id.toHex()] ?: 0
    fun balances(): Map<String, Long> = state.balances.toMap()

    /** Every Lucas in the pueblo: balances plus what waits in priority escrow. */
    fun supply(): Long = state.balances.values.sum() + state.escrow.values.sum()

    fun court(): Court = courtOf(state)

    fun lastHash(): ByteArray = book.lastOrNull()?.hash() ?: ByteArray(32)

    /** The king writes a page with [entries]; nobles add their endorsement with [Page.endorsedBy]. */
    fun propose(by: Identity, entries: List<Entry>, ts: Long): Page {
        val unsigned = Page(params.pueblo, book.size, lastHash(), ts, entries.map { it.encode() }, by.nodeId, ByteArray(0), emptyList())
        return Page(unsigned.pueblo, unsigned.index, unsigned.prev, ts, unsigned.entries, by.nodeId, by.sign("PAGE", unsigned.hash()), emptyList())
    }

    /** Whether [page] is the next one by the rules; if so it goes into the book. */
    fun accept(page: Page): Boolean {
        val next = check(page) ?: return false
        state = next
        book += page
        return true
    }

    /** What a noble checks before endorsing: everything except the endorsements themselves. */
    fun wouldAccept(page: Page): Boolean = check(page, endorsementsNeeded = false) != null

    /** The close of the open period, by the rule, as the page sealing it at [ts] will check it. */
    fun closePeriod(ts: Long): Entry.Close = Entry.Close(state.period, sharesOf(state, ts))

    /** Whether the open period can be closed in a page at [ts]. */
    fun canClose(ts: Long) = ts >= (state.period + 1) * params.periodMs

    private fun check(page: Page, endorsementsNeeded: Boolean = true): State? {
        if (page.index != book.size || !page.prev.contentEquals(lastHash()) || page.pueblo != params.pueblo) return null
        if (book.isNotEmpty() && page.ts < book.last().ts) return null
        val h = page.hash()
        val s = state.copy()
        val entries = page.entries.map { Entry.parse(it) ?: return null }
        // Who may write: the king, or the first noble with a valid deposition as the first entry.
        val court = courtOf(s)
        val deposing = (entries.firstOrNull() as? Entry.Depose)?.takeIf { validDepose(it, court) }
        val writer = if (deposing != null) court.nobles.firstOrNull() else court.king
        if (writer == null || !page.king.contentEquals(writer) || !Identity.verify(page.king, "PAGE", h, page.kingSig)) return null
        if (endorsementsNeeded) {
            val nobles = court.nobles.map { it.toHex() }.toSet()
            val good = page.endorsements.filter { (id, sig) -> id.toHex() in nobles && Identity.verify(id, "PAGE", h, sig) }.map { it.first.toHex() }.toSet()
            if (good.size * 3 <= nobles.size * 2 && nobles.isNotEmpty()) return null
        }
        for (e in entries) if (!apply(s, e, page.ts)) return null
        return s
    }

    private fun apply(s: State, e: Entry, ts: Long): Boolean = when (e) {
        is Entry.OfJourney -> {
            val id = e.journey.msgId.toHex()
            if (id in s.journeys || !e.journey.verify()) false else { s.journeys[id] = e.journey; true }
        }
        is Entry.OfClaim -> {
            val id = runCatching { e.claim.msgId().toHex() }.getOrNull()
            val j = id?.let { s.journeys[it] }
            val proof = j?.let { e.claim.check(it) }
            val key = "$id:${e.claim.key.i}"
            if (proof == null || key in s.claimed) false else {
                s.claimed += key
                val carrier = proof.giver.toHex()
                val neighbor = if (proof.taker.isEmpty()) ECO else proof.taker.toHex()
                for (who in listOf(carrier, neighbor)) s.firstSeen.putIfAbsent(who, ts)
                s.carries += Carry(carrier, neighbor, scarcity(proof.alternatives), 1.0 / (proof.hops - 1).coerceAtLeast(1), id!!)
                s.claimants.getOrPut(id) { HashSet() } += carrier
                true
            }
        }
        is Entry.Spend -> {
            val from = e.from.toHex()
            val ok = e.amount > 0 && (s.balances[from] ?: 0) >= e.amount && Identity.verify(e.from, "SPEND", e.body(), e.sig)
            if (ok) {
                s.balances[from] = (s.balances[from] ?: 0) - e.amount
                if (e.to != null) s.balances.merge(e.to.toHex(), e.amount, Long::plus)
                else s.escrow.merge(e.what, e.amount - e.amount / 2, Long::plus) // the other half is burned
            }
            ok
        }
        is Entry.Close -> {
            if (e.period != s.period || ts < (s.period + 1) * params.periodMs || e.shares != sharesOf(s, ts)) false else {
                for ((id, v) in e.shares) s.balances.merge(id, v, Long::plus)
                // Priority escrow goes to the carriers of that letter, equally (§6.1).
                for ((msg, amount) in s.escrow.entries.toList()) {
                    val who = s.claimants[msg] ?: continue
                    val each = amount / who.size
                    for (c in who) s.balances.merge(c, each, Long::plus)
                    s.escrow.remove(msg)
                }
                s.lastScores = scoresOf(s, ts)
                s.carries.clear()
                s.deposed = null
                s.period++
                true
            }
        }
        is Entry.Depose -> {
            val court = courtOf(s)
            if (!validDepose(e, court)) false else { s.deposed = e.king.toHex(); true }
        }
    }

    private fun validDepose(d: Entry.Depose, court: Court): Boolean {
        val king = court.king ?: return false
        if (!d.king.contentEquals(king) || court.nobles.isEmpty()) return false
        val nobles = court.nobles.map { it.toHex() }.toSet()
        val good = d.sigs.filter { (id, sig) -> id.toHex() in nobles && Identity.verify(id, "DEPOSE", Entry.deposeBody(d.king, d.ts), sig) }.map { it.first.toHex() }.toSet()
        return good.size * 3 > nobles.size * 2
    }

    private fun courtOf(s: State): Court {
        val ranked = s.lastScores.filter { it.value > 0 }.entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key }).map { it.key }
        if (ranked.size < params.genesisContributors) return Court(params.founder, emptyList()) // genesis (§10)
        val king = ranked.first()
        val nobles = ranked.drop(1).take(params.nobles)
        return if (s.deposed == king) Court(hex(nobles.first()), nobles.drop(1).map { hex(it) }) else Court(hex(king), nobles.map { hex(it) })
    }

    /**
     * Score of each carrier in the open period (§5): for each distinct neighbor it worked with, the neighbor's weight
     * (age, from 0 to 1 over [Params.maturityMs]) times f(n) = 1 + log2(n), times the average of scarcity × its part of
     * the delivery. A thousand paths with the same disguises count as one.
     */
    private fun scoresOf(s: State, ts: Long): Map<String, Double> =
        s.carries.groupBy { it.carrier }.mapValues { (_, mine) ->
            mine.groupBy { it.neighbor }.entries.sumOf { (neighbor, with) ->
                val weight = if (neighbor == ECO) ECO_WEIGHT else ((ts - (s.firstSeen[neighbor] ?: ts)).toDouble() / params.maturityMs).coerceIn(0.0, 1.0)
                weight * (1 + ln(with.size.toDouble()) / ln(2.0)) * with.map { it.scarcity * it.share }.average()
            }
        }

    private fun sharesOf(s: State, ts: Long): Map<String, Long> {
        val out = HashMap<String, Long>()
        val court = courtOf(s)
        var bag = params.budget
        court.king?.let { out.merge(it.toHex(), KING_WAGE, Long::plus); bag -= KING_WAGE }
        for (n in court.nobles) { out.merge(n.toHex(), NOBLE_WAGE, Long::plus); bag -= NOBLE_WAGE }
        val scores = scoresOf(s, ts).filter { it.value > 0 }
        val total = scores.values.sum()
        if (total > 0) for ((id, v) in scores) (bag * v / total).toLong().takeIf { it > 0 }?.let { out.merge(id, it, Long::plus) }
        return out
    }

    companion object {
        const val KING_WAGE = 20L
        const val NOBLE_WAGE = 2L
        private const val ECO = "eco"
        // A shout to everyone names no taker: it counts, but less than a hand-to-hand delivery to a known neighbor.
        private const val ECO_WEIGHT = 0.5

        /** Where paths are few, a carry pays more: 3 / (1 + alternatives), between 0.5 and 3 (§5.2). */
        fun scarcity(alternatives: Int): Double = (3.0 / (1 + alternatives)).coerceIn(0.5, 3.0)
    }
}

/** La cámara compensadora (§9.3): what pueblos owe each other, netted pair by pair. */
object Clearing {
    data class Transfer(val from: String, val to: String, val amount: Long)

    fun net(owed: Map<Pair<String, String>, Long>): List<Transfer> {
        val pairs = owed.keys.map { (a, b) -> if (a < b) a to b else b to a }.toSet()
        return pairs.mapNotNull { (a, b) ->
            val d = (owed[a to b] ?: 0) - (owed[b to a] ?: 0)
            when { d > 0 -> Transfer(a, b, d); d < 0 -> Transfer(b, a, -d); else -> null }
        }
    }
}
