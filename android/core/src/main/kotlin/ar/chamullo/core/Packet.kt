package ar.chamullo.core

/** Every CHAMULLO packet starts with the frozen universal prefix "CH" + version + kind (Packet Format §3). */
sealed interface Packet {
    fun encode(): ByteArray

    companion object {
        val MAGIC = byteArrayOf(0x43, 0x48)
        const val VERSION = 1
        const val KIND_LINK = 0x01
        const val KIND_ENVELOPE = 0x02
        const val KIND_FRAGMENT = 0x03
        const val LINK_BEACON = 10L
        const val LINK_CARD_OFFER = 11L
        const val LINK_PLAZA = 12L
        const val LINK_HEARD = 13L
        const val LINK_ROAD_INVITE = 14L
        const val LINK_PAYMENT = 15L
        const val LINK_OFFER = 8L
        const val LINK_ACCEPT = 9L
        const val LINK_CLAIM = 16L
        const val LINK_LEDGER = 17L

        fun isChamullo(b: ByteArray) = b.size >= 4 && b[0] == MAGIC[0] && b[1] == MAGIC[1] && b[2].toInt() == VERSION

        fun parse(bytes: ByteArray): Packet {
            if (!isChamullo(bytes)) throw WireException("not a CHAMULLO packet")
            val r = Reader(bytes, 4)
            return when (bytes[3].toInt()) {
                KIND_ENVELOPE -> Envelope.decode(r)
                KIND_LINK -> {
                    val type = r.varint()
                    val tlv = Tlv.decode(r.take(r.varint().toInt()))
                    when (type) {
                        LINK_BEACON -> Beacon.decode(tlv)
                        LINK_CARD_OFFER -> CardOffer.decode(tlv)
                        LINK_PLAZA -> Plaza.decode(tlv)
                        LINK_HEARD -> Heard.decode(tlv)
                        LINK_ROAD_INVITE -> RoadInvite.decode(tlv)
                        LINK_PAYMENT -> Payment.decode(tlv)
                        LINK_OFFER -> Offer.decode(tlv)
                        LINK_ACCEPT -> Accept.decode(tlv)
                        LINK_CLAIM -> Claim.decode(tlv)
                        LINK_LEDGER -> LedgerMsg.decode(tlv)
                        else -> throw WireException("unknown link message $type")
                    }
                }
                else -> throw WireException("unknown kind")
            }
        }

        fun parseOrNull(bytes: ByteArray): Packet? = try { parse(bytes) } catch (_: Exception) { null }

        internal fun link(type: Long, records: List<Pair<Long, ByteArray>>): ByteArray {
            val tlv = Tlv.encode(records)
            return Writer().raw(MAGIC).u8(VERSION).u8(KIND_LINK).varint(type).varint(tlv.size.toLong()).raw(tlv).bytes()
        }
    }
}

/* ======================= contact card (Discovery & Routing §4) ======================= */

/** [zone]: the barrio where its owner lives (Discovery & Routing §4.1), signed; null on cards from before v0.2. */
class Card(val nodeId: ByteArray, val boxPublic: ByteArray, val tagSecret: ByteArray, val name: String, val ts: Long, val sig: ByteArray, val zone: Zone? = null) {
    private fun body() = nodeId + boxPublic + tagSecret + Tlv.u64(ts) + name.toByteArray() + (zone?.encode() ?: ByteArray(0))

    fun verify() = Identity.verify(nodeId, "CARD", body(), sig)

    fun encode(): ByteArray = Tlv.encode(listOfNotNull(2L to nodeId, 4L to boxPublic, 6L to tagSecret, 7L to name.toByteArray(), 8L to Tlv.u64(ts), 10L to sig,
        zone?.let { 11L to it.encode() }))

    companion object {
        fun of(id: Identity, now: Long, zone: Zone? = null): Card {
            val unsigned = Card(id.nodeId, id.boxPublic, id.tagSecret, id.name, now, ByteArray(64), zone)
            return Card(id.nodeId, id.boxPublic, id.tagSecret, id.name, now, id.sign("CARD", unsigned.body()), zone)
        }

        fun decode(bytes: ByteArray): Card {
            val t = Tlv.decode(bytes).also { Tlv.requireKnown(it, setOf(2, 4, 6, 8, 10)) }
            return Card(t.getValue(2), t.getValue(4), t.getValue(6), String(t[7] ?: ByteArray(0)), Tlv.readU64(t.getValue(8)), t.getValue(10),
                Zone.decodeOrNull(t[11]))
        }
    }
}

/* ======================= heartbeat: who is around (Air Interface §5.3) ======================= */

/** [cell]: where its owner is, to the ~100 m cell (Discovery & Routing §3, §10.2); a ferry about to sail puts its heading. */
/** [bridge]: its owner lends Internet (Discovery & Routing §11). */
class Beacon(
    val nodeId: ByteArray, val boxPublic: ByteArray, val ts: Long, val name: String, val coded: Boolean, val sig: ByteArray,
    val cell: Zone? = null, val bridge: Boolean = false
) : Packet {
    private fun body() = nodeId + boxPublic + Tlv.u64(ts) + name.toByteArray() + (if (coded) 1 else 0).toByte() + (cell?.encode() ?: ByteArray(0)) +
        (if (bridge) byteArrayOf(1) else ByteArray(0))

    fun verify() = Identity.verify(nodeId, "BEACON", body(), sig)

    override fun encode() = Packet.link(
        Packet.LINK_BEACON,
        listOfNotNull(2L to nodeId, 4L to boxPublic, 6L to Tlv.u64(ts), 7L to name.toByteArray(), 9L to byteArrayOf(if (coded) 1 else 0), 10L to sig,
            cell?.let { 11L to it.encode() }, if (bridge) 13L to byteArrayOf(1) else null)
    )

    companion object {
        fun of(id: Identity, coded: Boolean, now: Long, cell: Zone? = null, bridge: Boolean = false): Beacon {
            val unsigned = Beacon(id.nodeId, id.boxPublic, now, id.name, coded, ByteArray(64), cell, bridge)
            return Beacon(id.nodeId, id.boxPublic, now, id.name, coded, id.sign("BEACON", unsigned.body()), cell, bridge)
        }

        fun decode(t: Map<Long, ByteArray>): Beacon {
            Tlv.requireKnown(t, setOf(2, 4, 6, 10))
            return Beacon(t.getValue(2), t.getValue(4), Tlv.readU64(t.getValue(6)), String(t[7] ?: ByteArray(0)), (t[9]?.firstOrNull() ?: 0).toInt() == 1, t.getValue(10),
                Zone.decodeOrNull(t[11]), (t[13]?.firstOrNull()?.toInt() ?: 0) == 1)
        }
    }
}

/* ======================= a card handed to one neighbor, sealed so only it can read it ======================= */

class CardOffer(val from: ByteArray, val to: ByteArray, val fromBox: ByteArray, val nonce: ByteArray, val sealed: ByteArray) : Packet {
    override fun encode() = Packet.link(Packet.LINK_CARD_OFFER, listOf(2L to from, 4L to to, 6L to fromBox, 8L to nonce, 10L to sealed))

    fun open(me: Identity): Card? {
        if (!to.contentEquals(me.nodeId)) return null
        val plain = Crypto.boxOpen(sealed, nonce, fromBox, me.boxSecret) ?: return null
        val card = runCatching { Card.decode(plain) }.getOrNull() ?: return null
        return card.takeIf { it.verify() && it.nodeId.contentEquals(from) && it.boxPublic.contentEquals(fromBox) }
    }

    companion object {
        fun to(me: Identity, neighbor: Beacon, now: Long): CardOffer = to(me, neighbor.nodeId, neighbor.boxPublic, now)

        /** Seals my card for whoever owns [nodeId] / [boxPublic]: a neighbor's heartbeat or a card already received. */
        fun to(me: Identity, nodeId: ByteArray, boxPublic: ByteArray, now: Long, zone: Zone? = null): CardOffer {
            val nonce = Crypto.randomBytes(24)
            return CardOffer(me.nodeId, nodeId, me.boxPublic, nonce, Crypto.box(me.card(now, zone).encode(), nonce, boxPublic, me.boxSecret))
        }

        fun decode(t: Map<Long, ByteArray>): CardOffer {
            Tlv.requireKnown(t, setOf(2, 4, 6, 8, 10))
            return CardOffer(t.getValue(2), t.getValue(4), t.getValue(6), t.getValue(8), t.getValue(10))
        }
    }
}

/* ======================= the letter inside the envelope ======================= */

sealed interface Letter {
    /** [service] says which service on the network the letter belongs to: "chat" for now, ICEBREAK and others later. */
    data class Text(val text: String, val service: String = "chat") : Letter
    /** The receipt (Proof of Relay §6): which letter, the hashes of its journey and the secret only its reader knew. */
    /** [validity]: which hops held, one bit each (Proof of Relay §6.2). */
    class Ack(val msgId: ByteArray, val journey: List<ByteArray> = emptyList(), val revealed: ByteArray = ByteArray(0), val validity: ByteArray = ByteArray(0)) : Letter
    /** La mudanza (Discovery & Routing §5): only my new card, so my contacts know my new barrio. */
    class Moved(val card: Card) : Letter

    /** An attachment (Packet Format §5.6): [kind] from [ar.chamullo.core.Media], its file name, its type and its bytes. */
    class Media(val kind: Int, val name: String, val mime: String, val data: ByteArray) : Letter {
        fun encode(): ByteArray = Tlv.encode(listOf(2L to byteArrayOf(kind.toByte()), 4L to name.toByteArray(), 6L to mime.toByteArray(), 8L to data))

        /** For a location: latitude and longitude. */
        fun latLon(): Pair<Double, Double>? = runCatching { String(data).split(',').let { it[0].toDouble() to it[1].toDouble() } }.getOrNull()

        /** For a shared contact: the card, if it is a good one. */
        fun card(): Card? = runCatching { Card.decode(data) }.getOrNull()?.takeIf { it.verify() }

        companion object {
            fun location(lat: Double, lon: Double) = Media(ar.chamullo.core.Media.LOCATION, "Ubicación", "geo", "$lat,$lon".toByteArray())
            fun contact(card: Card) = Media(ar.chamullo.core.Media.CONTACT, card.name, "chamullo/card", card.encode())
            fun decode(b: ByteArray): Media = Tlv.decode(b).let { t ->
                Media(t.getValue(2)[0].toInt(), String(t.getValue(4)), String(t.getValue(6)), t.getValue(8))
            }
        }
    }
}

/** What the recipient finds inside: the real sender, the letter, and the keys to read its journey and prove the delivery. */
class Opened(val sender: ByteArray, val ts: Long, val body: Letter, val journeySecret: ByteArray?, val deliverySecret: ByteArray?, val senderCard: Card? = null)

/** A letter just sealed, with the secrets only its origin keeps: to sign the confirmation and check the receipt. */
class Sealed(val envelope: Envelope, val ephemeralSeed: ByteArray, val deliverySecret: ByteArray) {
    /** The origin writes the first hop with the same one-time key that sealed the letter: giver_1 = src. */
    fun signer() = Signer(envelope.src, ephemeralSeed)
}

/** Whoever writes a hop: a node, or the origin with its one-time key. */
class Signer(val id: ByteArray, private val seed: ByteArray) {
    fun sign(tag: String, body: ByteArray): ByteArray = Crypto.sign(seed, Identity.signingInput(tag, body))
}

/** One carrier in the journey, as the recipient reads it. */
class Hop(val giver: ByteArray, val valid: Boolean, val taker: ByteArray = ByteArray(0), val alternatives: Int = 0)

/* ======================= the envelope (Packet Format §5, Discovery & Routing §7) ======================= */

class Envelope(
    val src: ByteArray, val dstTag: ByteArray, val nonce: ByteArray, val ts: Long, val exp: Long,
    val originTlv: ByteArray, val sig: ByteArray, val hopCount: Int, val transitTlv: ByteArray = ByteArray(0)
) : Packet {
    val msgId: ByteArray get() = Crypto.hash(src + nonce)
    private val origin: Map<Long, ByteArray> by lazy { Tlv.decode(originTlv) }
    val maxHops: Int get() = (origin[4]?.firstOrNull()?.toInt() ?: 0) and 0xff
    val journeyKey: ByteArray? get() = origin[8]
    val deliveryCommit: ByteArray? get() = origin[10]
    /** Where the compass points (Discovery & Routing §6): the recipient's barrio, sealed by the origin. */
    val destZone: Zone? by lazy { Zone.decodeOrNull(origin[12]) }
    /** Lucas the origin offers for priority (Economy & Governance §6.1), sealed by it; 0 for the free lane. */
    val priority: Long get() = origin[13]?.let { runCatching { Tlv.readU64(it) }.getOrNull() } ?: 0

    // Transit fields each giver rewrites (Packet Format §5.4): who this copy is for, the local eco budget, the lake detour.
    private val transit: Map<Long, ByteArray> by lazy { Tlv.decode(transitTlv) }
    val receivers: List<ByteArray> by lazy { (transit[5] ?: ByteArray(0)).toList().chunked(SHORT_ID) { it.toByteArray() } }
    val localTtl: Int? get() = transit[7]?.firstOrNull()?.toInt()?.and(0xff)
    val detour: Int get() = (transit[9]?.firstOrNull()?.toInt() ?: 0) and 0xff

    fun isReceiver(nodeId: ByteArray) = receivers.isEmpty() || receivers.any { it.contentEquals(nodeId.copyOf(SHORT_ID)) }

    /** The same letter, pointed at [receivers] (empty: everyone), with its local eco budget and lake detour. */
    fun routed(receivers: List<ByteArray>, localTtl: Int?, detour: Int): Envelope =
        Envelope(src, dstTag, nonce, ts, exp, originTlv, sig, hopCount, transitOf(transit[2], receivers, localTtl, detour))

    /** The sealed hop records carriers appended in transit (Proof of Relay §4.3). */
    val blobs: List<ByteArray> by lazy {
        val raw = Tlv.decode(transitTlv)[2] ?: return@lazy emptyList()
        val r = Reader(raw); buildList { while (r.remaining > 0) add(r.take(r.varint().toInt())) }
    }

    // The seed (Capitán's idea): it changes with every carrier's sealed record.
    private fun seedAfter(n: Int): ByteArray {
        var s = Crypto.hash("CHAMULLO/1/SEED".toByteArray() + 0.toByte() + msgId + sig)
        for (b in blobs.take(n)) s = Crypto.hash(s + Crypto.hash(b))
        return s
    }

    private fun body() = Writer().u8(Packet.VERSION).u8(Packet.KIND_ENVELOPE).raw(src).raw(dstTag).raw(nonce).u64(ts).u64(exp)
        .varint(originTlv.size.toLong()).raw(originTlv).bytes()

    fun verify(): Boolean = runCatching { Tlv.requireKnown(origin, setOf(2, 4, 6, 8, 10, 12)) }.isSuccess && Identity.verify(src, "ENV", body(), sig)

    fun expired(now: Long) = now > exp

    fun isFor(me: Identity) = dstTag.contentEquals(tagFor(me.tagSecret, nonce))

    fun withHop() = Envelope(src, dstTag, nonce, ts, exp, originTlv, sig, hopCount + 1, transitTlv)

    /** Proof of Relay §5.2: what the giver offers before handing it over. */
    fun offer(giver: ByteArray, taker: ByteArray) = Offer(giver, taker, msgId, blobs.size, seedAfter(blobs.size), exp)

    /** The giver checks the "papelito": the taker named in [a] signed for exactly this letter, this hop and this giver. */
    fun accepts(a: Accept, giver: ByteArray) = a.msgId.contentEquals(msgId) && a.i == blobs.size && a.giver.contentEquals(giver) &&
        Identity.verify(a.taker, "ACPT", acceptBody(msgId, a.i, seedAfter(a.i), giver, a.taker, a.ts), a.sig)

    /**
     * A giver passes the letter on and leaves its record (Proof of Relay §4.5), sealed with the journey key: only origin
     * and recipient read it. With [accept] the record names the taker and carries its signature; without, it was a shout
     * to everyone. [alternatives]: how many stable neighbors offered progress (Discovery & Routing §8).
     */
    fun withHop(giver: Signer, now: Long, accept: Accept? = null, alternatives: Int = 0): Envelope =
        withHopKept(giver, now, accept, alternatives).first

    /** The same, keeping what the giver needs to cash its hop later (Proof of Relay §7). */
    fun withHopKept(giver: Signer, now: Long, accept: Accept? = null, alternatives: Int = 0): Pair<Envelope, HopKey> {
        val jPub = journeyKey ?: return withHop() to HopKey(blobs.size, ByteArray(0), ByteArray(0), ByteArray(0))
        val i = blobs.size
        val taker = accept?.taker ?: ByteArray(0); val acceptSig = accept?.sig ?: ByteArray(0); val hopTs = accept?.ts ?: now
        val hopSig = giver.sign("HOP", acceptBody(msgId, i, seedAfter(i), giver.id, taker, hopTs) + acceptSig)
        val record = Tlv.encode(listOf(2L to giver.id, 4L to Tlv.u64(i.toLong()), 6L to Tlv.u64(hopTs), 8L to hopSig,
            10L to taker, 12L to acceptSig, 14L to Tlv.u64(alternatives.toLong())))
        val e = Crypto.randomBytes(32); val n = Crypto.randomBytes(24)
        val blob = Crypto.boxPublicKey(e) + n + Crypto.box(record, n, jPub, e)
        val list = Writer().apply { for (b in blobs + listOf(blob)) varint(b.size.toLong()).raw(b) }.bytes()
        return Envelope(src, dstTag, nonce, ts, exp, originTlv, sig, hopCount + 1, transitOf(list, receivers, localTtl, detour)) to HopKey(i, e, blob, record)
    }

    /** The part the origin signed, and nothing a carrier added: what a ledger needs to check a journey or a claim. */
    fun originSection(): ByteArray = Envelope(src, dstTag, nonce, ts, exp, originTlv, sig, 0).encode()

    /**
     * Reads the journey with the key found inside the letter (Proof of Relay §6.1): every hop in order, valid if both
     * signatures hold, its giver is the previous taker (unless that hop was a shout to everyone) and nobody gives twice.
     */
    fun journey(journeySecret: ByteArray): List<Hop> {
        val givers = HashSet<String>()
        var expected: ByteArray? = src
        return blobs.mapIndexed { i, blob ->
            runCatching {
                val rec = Tlv.decode(Crypto.boxOpen(blob.copyOfRange(56, blob.size), blob.copyOfRange(32, 56), blob.copyOfRange(0, 32), journeySecret)!!)
                val giver = rec.getValue(2); val hopTs = Tlv.readU64(rec.getValue(6))
                val taker = rec[10] ?: ByteArray(0); val acceptSig = rec[12] ?: ByteArray(0)
                val body = acceptBody(msgId, i, seedAfter(i), giver, taker, hopTs)
                val ok = Tlv.readU64(rec.getValue(4)) == i.toLong() &&
                    Identity.verify(giver, "HOP", body + acceptSig, rec.getValue(8)) &&
                    (taker.isEmpty() || Identity.verify(taker, "ACPT", body, acceptSig)) &&
                    (expected?.contentEquals(giver) ?: true) && givers.add(giver.toHex())
                expected = taker.takeIf { it.isNotEmpty() }
                Hop(giver, ok, taker, (rec[14]?.let { Tlv.readU64(it) } ?: 0L).toInt())
            }.getOrElse { expected = null; Hop(ByteArray(0), false) }
        }
    }

    override fun encode(): ByteArray = Writer().raw(Packet.MAGIC).u8(Packet.VERSION).u8(Packet.KIND_ENVELOPE)
        .raw(src).raw(dstTag).raw(nonce).u64(ts).u64(exp).varint(originTlv.size.toLong()).raw(originTlv).raw(sig)
        .u8(hopCount).varint(transitTlv.size.toLong()).raw(transitTlv).bytes()

    /** Opens the sealed payload; null if it is not for [me] or anything does not verify. */
    fun open(me: Identity): Opened? = runCatching {
        val r = Reader(origin.getValue(6))
        val ephBox = r.take(32); val boxNonce = r.take(24)
        val inner = Tlv.decode(Crypto.boxOpen(r.rest(), boxNonce, ephBox, me.boxSecret) ?: return null)
        Tlv.requireKnown(inner, setOf(2, 4, 6, 8, 12, 14, 16, 18, 20, 24))
        val sender = inner.getValue(2); val sentTs = Tlv.readU64(inner.getValue(4))
        val service = inner[19]?.let { String(it) } ?: "chat"
        // The sender's current card, only if it is really theirs (Discovery & Routing §5).
        val card = inner[21]?.let { runCatching { Card.decode(it) }.getOrNull() }?.takeIf { it.nodeId.contentEquals(sender) && it.verify() }
        val body: Letter = when {
            inner[6] != null -> Letter.Text(String(inner.getValue(6)), service)
            inner[24] != null -> Letter.Media.decode(inner.getValue(24))
            inner[12] != null -> Letter.Ack(inner.getValue(12), (inner[18] ?: ByteArray(0)).toList().chunked(32) { it.toByteArray() }, inner[20] ?: ByteArray(0), inner[23] ?: ByteArray(0))
            else -> Letter.Moved(card ?: return null)
        }
        if (!Identity.verify(sender, "MSG", msgId + Tlv.u64(sentTs) + content(body), inner.getValue(8))) return null
        Opened(sender, sentTs, body, inner[14], inner[16], card)
    }.getOrNull()

    companion object {
        private const val SEALED = 1L
        const val SHORT_ID = 8

        // What a taker signs to accept hop [i], and what the giver signs together with that acceptance.
        fun acceptBody(msgId: ByteArray, i: Int, seed: ByteArray, giver: ByteArray, taker: ByteArray, ts: Long) =
            msgId + Tlv.u64(i.toLong()) + seed + giver + taker + Tlv.u64(ts)

        private fun transitOf(list: ByteArray?, receivers: List<ByteArray>, localTtl: Int?, detour: Int): ByteArray = Tlv.encode(listOfNotNull(
            list?.let { 2L to it },
            receivers.takeIf { it.isNotEmpty() }?.let { r -> 5L to r.fold(ByteArray(0)) { a, b -> a + b.copyOf(SHORT_ID) } },
            localTtl?.let { 7L to byteArrayOf(it.toByte()) },
            detour.takeIf { it > 0 }?.let { 9L to byteArrayOf(it.toByte()) }
        ))

        fun tagFor(tagSecret: ByteArray, nonce: ByteArray) = Crypto.hash("CHAMULLO/1/TAG".toByteArray() + 0.toByte() + tagSecret + nonce)

        // What the sender signs: the text and service, or the whole receipt.
        internal fun content(body: Letter): ByteArray = when (body) {
            is Letter.Text -> body.text.toByteArray() + 0.toByte() + body.service.toByteArray()
            is Letter.Ack -> body.msgId + body.journey.fold(ByteArray(0)) { a, h -> a + h } + body.revealed + body.validity
            is Letter.Moved -> body.card.encode()
            is Letter.Media -> body.encode()
        }

        fun letter(sender: Identity, to: Card, body: Letter, now: Long, ttlMs: Long = 6 * 3600_000L, maxHops: Int = 8, senderCard: Card? = null): Envelope =
            seal(sender, to, body, now, ttlMs, maxHops, senderCard).envelope

        /** Seals a letter with a journey key and a delivery secret (Proof of Relay §4.1); the origin keeps the secrets. */
        /** The compass points at [to]'s barrio, from its card; [senderCard] travels inside so the reply knows where I am. */
        fun seal(sender: Identity, to: Card, body: Letter, now: Long, ttlMs: Long = 6 * 3600_000L, maxHops: Int = 8, senderCard: Card? = null, priority: Long = 0): Sealed {
            val eph = Crypto.randomBytes(32)
            val src = Crypto.signPublicKey(eph)
            val nonce = Crypto.randomBytes(16)
            val msgId = Crypto.hash(src + nonce)
            val journeySecret = Crypto.randomBytes(32)
            val deliverySecret = Crypto.randomBytes(32)
            val inner = Tlv.encode(
                listOfNotNull(
                    2L to sender.nodeId, 4L to Tlv.u64(now),
                    (body as? Letter.Text)?.let { 6L to it.text.toByteArray() },
                    8L to sender.sign("MSG", msgId + Tlv.u64(now) + content(body)),
                    (body as? Letter.Ack)?.let { 12L to it.msgId },
                    14L to journeySecret, 16L to deliverySecret,
                    (body as? Letter.Ack)?.let { 18L to it.journey.fold(ByteArray(0)) { a, h -> a + h } },
                    (body as? Letter.Text)?.service?.takeIf { it != "chat" }?.let { 19L to it.toByteArray() },
                    (body as? Letter.Ack)?.let { 20L to it.revealed },
                    ((body as? Letter.Moved)?.card ?: senderCard)?.let { 21L to it.encode() },
                    (body as? Letter.Ack)?.validity?.takeIf { it.isNotEmpty() }?.let { 23L to it },
                    (body as? Letter.Media)?.let { 24L to it.encode() }
                )
            )
            val ephBoxSecret = Crypto.randomBytes(32)
            val boxNonce = Crypto.randomBytes(24)
            val payload = Crypto.boxPublicKey(ephBoxSecret) + boxNonce + Crypto.box(inner, boxNonce, to.boxPublic, ephBoxSecret)
            val originTlv = Tlv.encode(listOf(
                2L to Writer().varint(SEALED).bytes(), 4L to byteArrayOf(maxHops.toByte()), 6L to payload,
                8L to Crypto.boxPublicKey(journeySecret), 10L to Crypto.hash(deliverySecret)
            ) + listOfNotNull(to.zone?.let { 12L to it.encode() }, priority.takeIf { it > 0 }?.let { 13L to Tlv.u64(it) }))
            val unsigned = Envelope(src, tagFor(to.tagSecret, nonce), nonce, now, now + ttlMs, originTlv, ByteArray(64), 0)
            val env = Envelope(unsigned.src, unsigned.dstTag, nonce, now, now + ttlMs, originTlv, Crypto.sign(eph, Identity.signingInput("ENV", unsigned.body())), 0)
            return Sealed(env, eph, deliverySecret)
        }

        fun decode(r: Reader): Envelope {
            val src = r.take(32); val dst = r.take(32); val nonce = r.take(16); val ts = r.u64(); val exp = r.u64()
            val origin = r.take(r.varint().toInt()); val sig = r.take(64); val hops = r.u8(); val transit = r.take(r.varint().toInt())
            return Envelope(src, dst, nonce, ts, exp, origin, sig, hops, transit)
        }
    }
}

/* ======================= la plaza: an open test chat for whoever is within earshot ======================= */

class Plaza(val nodeId: ByteArray, val ts: Long, val text: String, val name: String, val nonce: ByteArray, val sig: ByteArray) : Packet {
    val id: String get() = Crypto.hash(nodeId + nonce).toHex()

    private fun body() = nodeId + Tlv.u64(ts) + nonce + text.toByteArray() + 0.toByte() + name.toByteArray()

    fun verify() = Identity.verify(nodeId, "PLAZA", body(), sig)

    override fun encode() = Packet.link(
        Packet.LINK_PLAZA,
        listOf(2L to nodeId, 4L to Tlv.u64(ts), 6L to text.toByteArray(), 7L to name.toByteArray(), 8L to nonce, 10L to sig)
    )

    companion object {
        fun of(id: Identity, text: String, now: Long): Plaza {
            val nonce = Crypto.randomBytes(8)
            val unsigned = Plaza(id.nodeId, now, text, id.name, nonce, ByteArray(64))
            return Plaza(id.nodeId, now, text, id.name, nonce, id.sign("PLAZA", unsigned.body()))
        }

        fun decode(t: Map<Long, ByteArray>): Plaza {
            Tlv.requireKnown(t, setOf(2, 4, 6, 8, 10))
            return Plaza(t.getValue(2), Tlv.readU64(t.getValue(4)), String(t.getValue(6)), String(t[7] ?: ByteArray(0)), t.getValue(8), t.getValue(10))
        }
    }
}

/** "I heard it": the automatic answer to a plaza message, so its author sees who it reached. */
class Heard(val nodeId: ByteArray, val plazaId: String, val name: String, val ts: Long, val sig: ByteArray) : Packet {
    private fun body() = nodeId + hex(plazaId) + Tlv.u64(ts) + name.toByteArray()

    fun verify() = Identity.verify(nodeId, "HEARD", body(), sig)

    override fun encode() = Packet.link(
        Packet.LINK_HEARD,
        listOf(2L to nodeId, 4L to hex(plazaId), 6L to Tlv.u64(ts), 7L to name.toByteArray(), 10L to sig)
    )

    companion object {
        fun of(id: Identity, plazaId: String, now: Long): Heard {
            val unsigned = Heard(id.nodeId, plazaId, id.name, now, ByteArray(64))
            return Heard(id.nodeId, plazaId, id.name, now, id.sign("HEARD", unsigned.body()))
        }

        fun decode(t: Map<Long, ByteArray>): Heard {
            Tlv.requireKnown(t, setOf(2, 4, 6, 10))
            return Heard(t.getValue(2), t.getValue(4).toHex(), String(t[7] ?: ByteArray(0)), Tlv.readU64(t.getValue(6)), t.getValue(10))
        }
    }
}

/* ======================= la carretera: the key of my Wi-Fi road, sealed for one neighbor ======================= */

class Road(val ssid: String, val passphrase: String)

class RoadInvite(val from: ByteArray, val to: ByteArray, val fromBox: ByteArray, val nonce: ByteArray, val sealed: ByteArray) : Packet {
    override fun encode() = Packet.link(Packet.LINK_ROAD_INVITE, listOf(2L to from, 4L to to, 6L to fromBox, 8L to nonce, 10L to sealed))

    /** The road key, if this invite is for [me] and its owner signed it. */
    fun open(me: Identity): Road? {
        if (!to.contentEquals(me.nodeId)) return null
        val plain = Crypto.boxOpen(sealed, nonce, fromBox, me.boxSecret) ?: return null
        return runCatching {
            val t = Tlv.decode(plain)
            val ssid = t.getValue(2); val pass = t.getValue(4); val ts = t.getValue(6)
            if (!Identity.verify(from, "ROAD", ssid + 0.toByte() + pass + ts + to, t.getValue(8))) return null
            Road(String(ssid), String(pass))
        }.getOrNull()
    }

    companion object {
        fun to(me: Identity, nodeId: ByteArray, boxPublic: ByteArray, ssid: String, passphrase: String, now: Long): RoadInvite {
            val ts = Tlv.u64(now)
            val sig = me.sign("ROAD", ssid.toByteArray() + 0.toByte() + passphrase.toByteArray() + ts + nodeId)
            val plain = Tlv.encode(listOf(2L to ssid.toByteArray(), 4L to passphrase.toByteArray(), 6L to ts, 8L to sig))
            val nonce = Crypto.randomBytes(24)
            return RoadInvite(me.nodeId, nodeId, me.boxPublic, nonce, Crypto.box(plain, nonce, boxPublic, me.boxSecret))
        }

        fun decode(t: Map<Long, ByteArray>): RoadInvite {
            Tlv.requireKnown(t, setOf(2, 4, 6, 8, 10))
            return RoadInvite(t.getValue(2), t.getValue(4), t.getValue(6), t.getValue(8), t.getValue(10))
        }
    }
}

/* ======================= the payment: the origin confirms the journey, every carrier checks its own record ======================= */

/**
 * The origin's confirmation travelling to the carriers (Proof of Relay §6, Discovery & Routing §10.7): the sealed
 * [Journey], pointed like a letter toward its zone. Each carrier that finds the hash of its own record earns a candy and
 * can present its [Claim] to the ledger. Nobody needs to see the whole route.
 */
class Payment(val sealed: Journey, val ttl: Int, val zone: Zone? = null, val receivers: List<ByteArray> = emptyList()) : Packet {
    val msgId: ByteArray get() = sealed.msgId
    val src: ByteArray get() = sealed.src
    val journey: List<ByteArray> get() = sealed.hashes
    val revealed: ByteArray get() = sealed.revealed
    val confirm: ByteArray get() = sealed.confirm

    fun journeyHash() = sealed.hash()

    /** True if [deliveryCommit] (from the letter I carried) matches the revealed secret and the origin signed this journey. */
    fun proves(deliveryCommit: ByteArray) =
        Crypto.hash(revealed).contentEquals(deliveryCommit) && Identity.verify(src, "CONF", msgId + journeyHash(), confirm)

    fun withTtl(t: Int) = Payment(sealed, t, zone, receivers)

    /** Pointed like a letter (Discovery & Routing §10.7): toward [zone], to [receivers] (empty: everyone). Not signed. */
    fun routed(zone: Zone?, receivers: List<ByteArray>, ttl: Int) = Payment(sealed, ttl, zone, receivers)

    fun isReceiver(nodeId: ByteArray) = receivers.isEmpty() || receivers.any { it.contentEquals(nodeId.copyOf(Envelope.SHORT_ID)) }

    override fun encode() = Packet.link(Packet.LINK_PAYMENT, listOf(2L to sealed.encode(), 11L to byteArrayOf(ttl.toByte())) +
        listOfNotNull(zone?.let { 13L to it.encode() },
            receivers.takeIf { it.isNotEmpty() }?.let { r -> 15L to r.fold(ByteArray(0)) { a, b -> a + b.copyOf(Envelope.SHORT_ID) } }))

    companion object {
        fun confirm(sealed: Sealed, journey: List<ByteArray>, revealed: ByteArray, validity: ByteArray, ttl: Int = 8): Payment =
            Payment(Journey.confirm(sealed, journey, revealed, validity), ttl, sealed.envelope.destZone)

        fun decode(t: Map<Long, ByteArray>): Payment {
            Tlv.requireKnown(t, setOf(2))
            return Payment(Journey.decode(t.getValue(2)), (t[11]?.firstOrNull()?.toInt() ?: 0) and 0xff, Zone.decodeOrNull(t[13]),
                (t[15] ?: ByteArray(0)).toList().chunked(Envelope.SHORT_ID) { it.toByteArray() })
        }
    }
}

/* ======================= hand to hand: offer and accept (Proof of Relay §5.2) ======================= */

/** "Do you take this letter?": the giver names the hop and the seed so the taker can sign without seeing the letter. */
class Offer(val giver: ByteArray, val taker: ByteArray, val msgId: ByteArray, val i: Int, val seed: ByteArray, val exp: Long) : Packet {
    override fun encode() = Packet.link(Packet.LINK_OFFER, listOf(
        2L to giver, 4L to taker, 6L to msgId, 8L to Tlv.u64(i.toLong()), 10L to seed, 12L to Tlv.u64(exp)
    ))

    /** Accepting is promising to sign the receipt (the Capitán's "papelito"). */
    fun accept(me: Identity, now: Long) =
        Accept(me.nodeId, giver, msgId, i, now, me.sign("ACPT", Envelope.acceptBody(msgId, i, seed, giver, me.nodeId, now)))

    /** "La tengo": another copy is in my pocket. Not a promise, so nothing to sign (Discovery & Routing §10.5). */
    fun already(me: Identity) = Accept(me.nodeId, giver, msgId, i, 0, ByteArray(0), already = true)

    companion object {
        fun decode(t: Map<Long, ByteArray>): Offer {
            Tlv.requireKnown(t, setOf(2, 4, 6, 8, 10, 12))
            return Offer(t.getValue(2), t.getValue(4), t.getValue(6), Tlv.readU64(t.getValue(8)).toInt(), t.getValue(10), Tlv.readU64(t.getValue(12)))
        }
    }
}

class Accept(val taker: ByteArray, val giver: ByteArray, val msgId: ByteArray, val i: Int, val ts: Long, val sig: ByteArray, val already: Boolean = false) : Packet {
    override fun encode() = Packet.link(Packet.LINK_ACCEPT, listOf(
        2L to taker, 4L to giver, 6L to msgId, 8L to Tlv.u64(i.toLong()), 10L to Tlv.u64(ts), 12L to sig
    ) + listOfNotNull(if (already) 13L to byteArrayOf(1) else null))

    companion object {
        fun decode(t: Map<Long, ByteArray>): Accept {
            Tlv.requireKnown(t, setOf(2, 4, 6, 8, 10, 12))
            return Accept(t.getValue(2), t.getValue(4), t.getValue(6), Tlv.readU64(t.getValue(8)).toInt(), Tlv.readU64(t.getValue(10)), t.getValue(12),
                (t[13]?.firstOrNull()?.toInt() ?: 0) == 1)
        }
    }
}

/* ======================= the ledger travelling between phones (Economy & Governance §9) ======================= */

/** An entry, a proposed page, an endorsement, a sealed page, or "I have up to page n". [ttl]: hops left, not signed. */
class LedgerMsg(val kind: Int, val payload: ByteArray, val ttl: Int) : Packet {
    override fun encode() = Packet.link(Packet.LINK_LEDGER, listOf(2L to byteArrayOf(kind.toByte()), 4L to payload, 5L to byteArrayOf(ttl.toByte())))

    companion object {
        const val ENTRY = 1
        const val PROPOSAL = 2
        const val ENDORSE = 3
        const val PAGE = 4
        const val HAVE = 5

        fun decode(t: Map<Long, ByteArray>): LedgerMsg {
            Tlv.requireKnown(t, setOf(2, 4))
            return LedgerMsg(t.getValue(2)[0].toInt(), t.getValue(4), (t[5]?.firstOrNull()?.toInt() ?: 1) and 0xff)
        }
    }
}
