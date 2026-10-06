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

class Card(val nodeId: ByteArray, val boxPublic: ByteArray, val tagSecret: ByteArray, val name: String, val ts: Long, val sig: ByteArray) {
    private fun body() = nodeId + boxPublic + tagSecret + Tlv.u64(ts) + name.toByteArray()

    fun verify() = Identity.verify(nodeId, "CARD", body(), sig)

    fun encode(): ByteArray = Tlv.encode(listOf(2L to nodeId, 4L to boxPublic, 6L to tagSecret, 7L to name.toByteArray(), 8L to Tlv.u64(ts), 10L to sig))

    companion object {
        fun of(id: Identity, now: Long): Card {
            val unsigned = Card(id.nodeId, id.boxPublic, id.tagSecret, id.name, now, ByteArray(64))
            return Card(id.nodeId, id.boxPublic, id.tagSecret, id.name, now, id.sign("CARD", unsigned.body()))
        }

        fun decode(bytes: ByteArray): Card {
            val t = Tlv.decode(bytes).also { Tlv.requireKnown(it, setOf(2, 4, 6, 8, 10)) }
            return Card(t.getValue(2), t.getValue(4), t.getValue(6), String(t[7] ?: ByteArray(0)), Tlv.readU64(t.getValue(8)), t.getValue(10))
        }
    }
}

/* ======================= heartbeat: who is around (Air Interface §5.3) ======================= */

class Beacon(val nodeId: ByteArray, val boxPublic: ByteArray, val ts: Long, val name: String, val coded: Boolean, val sig: ByteArray) : Packet {
    private fun body() = nodeId + boxPublic + Tlv.u64(ts) + name.toByteArray() + (if (coded) 1 else 0).toByte()

    fun verify() = Identity.verify(nodeId, "BEACON", body(), sig)

    override fun encode() = Packet.link(
        Packet.LINK_BEACON,
        listOf(2L to nodeId, 4L to boxPublic, 6L to Tlv.u64(ts), 7L to name.toByteArray(), 9L to byteArrayOf(if (coded) 1 else 0), 10L to sig)
    )

    companion object {
        fun of(id: Identity, coded: Boolean, now: Long): Beacon {
            val unsigned = Beacon(id.nodeId, id.boxPublic, now, id.name, coded, ByteArray(64))
            return Beacon(id.nodeId, id.boxPublic, now, id.name, coded, id.sign("BEACON", unsigned.body()))
        }

        fun decode(t: Map<Long, ByteArray>): Beacon {
            Tlv.requireKnown(t, setOf(2, 4, 6, 10))
            return Beacon(t.getValue(2), t.getValue(4), Tlv.readU64(t.getValue(6)), String(t[7] ?: ByteArray(0)), (t[9]?.firstOrNull() ?: 0).toInt() == 1, t.getValue(10))
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
        fun to(me: Identity, neighbor: Beacon, now: Long): CardOffer {
            val nonce = Crypto.randomBytes(24)
            return CardOffer(me.nodeId, neighbor.nodeId, me.boxPublic, nonce, Crypto.box(me.card(now).encode(), nonce, neighbor.boxPublic, me.boxSecret))
        }

        fun decode(t: Map<Long, ByteArray>): CardOffer {
            Tlv.requireKnown(t, setOf(2, 4, 6, 8, 10))
            return CardOffer(t.getValue(2), t.getValue(4), t.getValue(6), t.getValue(8), t.getValue(10))
        }
    }
}

/* ======================= the letter inside the envelope ======================= */

sealed interface Letter {
    data class Text(val text: String) : Letter
    class Ack(val msgId: ByteArray) : Letter
}

class Opened(val sender: ByteArray, val ts: Long, val body: Letter)

/* ======================= the envelope (Packet Format §5, Discovery & Routing §7) ======================= */

class Envelope(
    val src: ByteArray, val dstTag: ByteArray, val nonce: ByteArray, val ts: Long, val exp: Long,
    val originTlv: ByteArray, val sig: ByteArray, val hopCount: Int, val transitTlv: ByteArray = ByteArray(0)
) : Packet {
    val msgId: ByteArray get() = Crypto.hash(src + nonce)
    private val origin: Map<Long, ByteArray> by lazy { Tlv.decode(originTlv) }
    val maxHops: Int get() = (origin[4]?.firstOrNull()?.toInt() ?: 0) and 0xff

    private fun body() = Writer().u8(Packet.VERSION).u8(Packet.KIND_ENVELOPE).raw(src).raw(dstTag).raw(nonce).u64(ts).u64(exp)
        .varint(originTlv.size.toLong()).raw(originTlv).bytes()

    fun verify(): Boolean = runCatching { Tlv.requireKnown(origin, setOf(2, 4, 6)) }.isSuccess && Identity.verify(src, "ENV", body(), sig)

    fun expired(now: Long) = now > exp

    fun isFor(me: Identity) = dstTag.contentEquals(tagFor(me.tagSecret, nonce))

    fun withHop() = Envelope(src, dstTag, nonce, ts, exp, originTlv, sig, hopCount + 1, transitTlv)

    override fun encode(): ByteArray = Writer().raw(Packet.MAGIC).u8(Packet.VERSION).u8(Packet.KIND_ENVELOPE)
        .raw(src).raw(dstTag).raw(nonce).u64(ts).u64(exp).varint(originTlv.size.toLong()).raw(originTlv).raw(sig)
        .u8(hopCount).varint(transitTlv.size.toLong()).raw(transitTlv).bytes()

    /** Opens the sealed payload; null if it is not for [me] or anything does not verify. */
    fun open(me: Identity): Opened? = runCatching {
        val r = Reader(origin.getValue(6))
        val ephBox = r.take(32); val boxNonce = r.take(24)
        val inner = Tlv.decode(Crypto.boxOpen(r.rest(), boxNonce, ephBox, me.boxSecret) ?: return null)
        Tlv.requireKnown(inner, setOf(2, 4, 6, 8, 12))
        val sender = inner.getValue(2); val sentTs = Tlv.readU64(inner.getValue(4))
        val body: Letter = inner[6]?.let { Letter.Text(String(it)) } ?: Letter.Ack(inner.getValue(12))
        val content = inner[6] ?: inner.getValue(12)
        if (!Identity.verify(sender, "MSG", msgId + Tlv.u64(sentTs) + content, inner.getValue(8))) return null
        Opened(sender, sentTs, body)
    }.getOrNull()

    companion object {
        private const val SEALED = 1L

        fun tagFor(tagSecret: ByteArray, nonce: ByteArray) = Crypto.hash("CHAMULLO/1/TAG".toByteArray() + 0.toByte() + tagSecret + nonce)

        fun letter(sender: Identity, to: Card, body: Letter, now: Long, ttlMs: Long = 6 * 3600_000L, maxHops: Int = 8): Envelope {
            val eph = Crypto.randomBytes(32)
            val src = Crypto.signPublicKey(eph)
            val nonce = Crypto.randomBytes(16)
            val msgId = Crypto.hash(src + nonce)
            val content = when (body) { is Letter.Text -> body.text.toByteArray(); is Letter.Ack -> body.msgId }
            val inner = Tlv.encode(
                listOfNotNull(
                    2L to sender.nodeId, 4L to Tlv.u64(now),
                    (body as? Letter.Text)?.let { 6L to content },
                    8L to sender.sign("MSG", msgId + Tlv.u64(now) + content),
                    (body as? Letter.Ack)?.let { 12L to content }
                )
            )
            val ephBoxSecret = Crypto.randomBytes(32)
            val boxNonce = Crypto.randomBytes(24)
            val payload = Crypto.boxPublicKey(ephBoxSecret) + boxNonce + Crypto.box(inner, boxNonce, to.boxPublic, ephBoxSecret)
            val originTlv = Tlv.encode(listOf(2L to Writer().varint(SEALED).bytes(), 4L to byteArrayOf(maxHops.toByte()), 6L to payload))
            val unsigned = Envelope(src, tagFor(to.tagSecret, nonce), nonce, now, now + ttlMs, originTlv, ByteArray(64), 0)
            return Envelope(unsigned.src, unsigned.dstTag, nonce, now, now + ttlMs, originTlv, Crypto.sign(eph, Identity.signingInput("ENV", unsigned.body())), 0)
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
