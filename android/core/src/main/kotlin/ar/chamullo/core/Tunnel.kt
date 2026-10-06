// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-CHAMULLO-Commercial
// Copyright (C) 2026 Jesús Nicolás Astorga y RESOURCES OPEN DOORS S.A.S
package ar.chamullo.core

import java.net.Inet6Address
import java.net.InetAddress

/**
 * El túnel de datos (Discovery & Routing §11.3, Economy & Governance §13): a phone without Internet browses through a
 * phone of its island that lends it. The bytes travel as LINK 18 frames over the island's pipes, sealed end to end; the
 * buyer signs cumulative receipts and the lender collects them from the buyer's data escrow in the pueblo's ledger.
 */
object Tunnel {
    /** Bytes the lender serves without a fresh receipt. */
    const val CREDIT = 1024L * 1024
    /** The buyer signs a receipt every this many bytes. */
    const val RECEIPT_EVERY = 256L * 1024
    /** Bytes in flight per stream before the sender waits for an acknowledgement. */
    const val WINDOW = 256 * 1024
    /** Largest piece of a stream in one frame. */
    const val CHUNK = 16 * 1024
    /** Only the web goes through: no mail servers, no other services on the lender's account. */
    val PORTS = setOf(80, 443, 8080, 8443)

    /** Whether the lender may open [ip]:[port]: public addresses and web ports only, never its own home network. */
    fun allowed(ip: String, port: Int): Boolean {
        if (port !in PORTS) return false
        val a = runCatching { InetAddress.getByName(ip) }.getOrNull() ?: return false
        if (a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress || a.isAnyLocalAddress || a.isMulticastAddress) return false
        val b = a.address
        if (a is Inet6Address) return (b[0].toInt() and 0xfe) != 0xfc // unique local fc00::/7
        val first = b[0].toInt() and 0xff; val second = b[1].toInt() and 0xff
        return !(first == 100 && second in 64..127) && first != 0 // carrier NAT, "this network"
    }
}

/** The Internet a buyer paid for, pack by pack in the order bought (Economy & Governance §13). */
object DataPlan {
    class Pack(val mb: Int, val price: Long)

    private const val MB = 1024L * 1024

    fun total(packs: List<Pack>): Long = packs.sumOf { it.mb * MB }

    fun left(packs: List<Pack>, used: Long): Long = (total(packs) - used).coerceAtLeast(0)

    /** Lucas that [bytes] of use cost: whole packs at their price, the one being used in proportion. */
    fun owed(packs: List<Pack>, bytes: Long): Long {
        var rest = bytes; var lucas = 0L
        for (p in packs) {
            if (rest <= 0) break
            val size = p.mb * MB
            val take = minOf(rest, size)
            lucas += if (take == size) p.price else p.price * take / size
            rest -= take
        }
        return lucas
    }
}

/** "I used [bytes] through [lender] in [session], worth [lucas]": cumulative, signed by the buyer. */
class DataReceipt(val buyer: ByteArray, val lender: ByteArray, val session: ByteArray, val bytes: Long, val lucas: Long, val ts: Long, val sig: ByteArray) {
    fun body() = buyer + lender + session + Tlv.u64(bytes) + Tlv.u64(lucas) + Tlv.u64(ts)

    fun verify() = buyer.size == 32 && lender.size == 32 && session.size == SESSION && Identity.verify(buyer, "DATA", body(), sig)

    fun encode(): ByteArray = Tlv.encode(listOf(2L to buyer, 4L to lender, 6L to session, 8L to Tlv.u64(bytes), 10L to Tlv.u64(lucas), 12L to Tlv.u64(ts), 14L to sig))

    companion object {
        const val SESSION = 16

        fun of(buyer: Identity, lender: ByteArray, session: ByteArray, bytes: Long, lucas: Long, ts: Long): DataReceipt {
            val unsigned = DataReceipt(buyer.nodeId, lender, session, bytes, lucas, ts, ByteArray(0))
            return DataReceipt(buyer.nodeId, lender, session, bytes, lucas, ts, buyer.sign("DATA", unsigned.body()))
        }

        fun decode(b: ByteArray): DataReceipt = Tlv.decode(b).let { t ->
            DataReceipt(t.getValue(2), t.getValue(4), t.getValue(6), Tlv.readU64(t.getValue(8)), Tlv.readU64(t.getValue(10)), Tlv.readU64(t.getValue(12)), t.getValue(14))
        }

        fun decodeOrNull(b: ByteArray): DataReceipt? = runCatching { decode(b) }.getOrNull()
    }
}

/** The lender's side of one session: what it served and the best receipt it holds. */
class LendSession(val lender: ByteArray, val buyer: ByteArray, val session: ByteArray) {
    var servedBytes = 0L; private set
    var best: DataReceipt? = null; private set

    fun served(n: Long) { servedBytes += n }

    fun mayServe() = servedBytes - (best?.bytes ?: 0) <= Tunnel.CREDIT

    fun onReceipt(r: DataReceipt): Boolean {
        if (!r.verify() || !r.buyer.contentEquals(buyer) || !r.lender.contentEquals(lender) || !r.session.contentEquals(session)) return false
        val b = best
        if (b != null && (r.bytes <= b.bytes || r.lucas < b.lucas)) return false
        best = r
        return true
    }
}

/** The buyer's side of one session: counts bytes and signs a receipt every [Tunnel.RECEIPT_EVERY]. */
class Usage(private val me: Identity, val lender: ByteArray, val session: ByteArray, private val packs: List<DataPlan.Pack>, private val usedBefore: Long) {
    var bytes = 0L; private set
    private var receipted = 0L

    fun used(n: Long, now: Long): DataReceipt? {
        bytes += n
        return if (bytes - receipted >= Tunnel.RECEIPT_EVERY) receipt(now) else null
    }

    /** A receipt for everything so far, even if short of [Tunnel.RECEIPT_EVERY] (at the end of the session). */
    fun receipt(now: Long): DataReceipt {
        receipted = bytes
        val lucas = DataPlan.owed(packs, usedBefore + bytes) - DataPlan.owed(packs, usedBefore)
        return DataReceipt.of(me, lender, session, bytes, lucas, now)
    }

    fun left(): Long = DataPlan.left(packs, usedBefore + bytes)
}

/**
 * LINK 18: one piece of the tunnel. ASK ("who lends?", to everyone) and LEND carry the sender's box key, signed; every
 * other op is sealed for the other end, header included, so the island's host only sees who talks to whom.
 */
class TunnelMsg(override val from: ByteArray, override val to: ByteArray, val op: Int, val stream: Long, val nonce: ByteArray, val payload: ByteArray) : Addressed {
    override fun encode() = Packet.link(Packet.LINK_TUNNEL, listOf(
        2L to from, 4L to to, 6L to byteArrayOf(op.toByte()), 8L to Tlv.u64(stream), 10L to nonce, 12L to payload
    ))

    /** ASK / LEND: the sender's box key, if its signature holds. */
    fun boxKey(): ByteArray? {
        if ((op != ASK && op != LEND) || payload.size != 32 + 8 + 64) return null
        val key = payload.copyOfRange(0, 32)
        val ts = payload.copyOfRange(32, 40)
        return key.takeIf { Identity.verify(from, "TUNNEL", keyBody(op, to, key, ts), payload.copyOfRange(40, 104)) }
    }

    fun open(me: Identity, theirBox: ByteArray): ByteArray? = openWith(Crypto.boxShared(theirBox, me.boxSecret))

    /** Opens with a shared key already computed ([Crypto.boxShared]), checking the header was not changed on the way. */
    fun openWith(key: ByteArray): ByteArray? = Direct.open(key, nonce, payload, op, stream)

    companion object {
        const val ASK = 1
        const val LEND = 2
        const val OPEN = 3
        const val OPENED = 4
        const val DATA = 5
        const val ACK = 6
        const val CLOSE = 7
        const val RECEIPT = 8

        private fun keyBody(op: Int, to: ByteArray, key: ByteArray, ts: ByteArray) = byteArrayOf(op.toByte()) + to + key + ts

        private fun keyed(me: Identity, op: Int, to: ByteArray, now: Long): TunnelMsg {
            val ts = Tlv.u64(now)
            val sig = me.sign("TUNNEL", keyBody(op, to, me.boxPublic, ts))
            return TunnelMsg(me.nodeId, to, op, 0, ByteArray(0), me.boxPublic + ts + sig)
        }

        fun ask(me: Identity, now: Long) = keyed(me, ASK, ByteArray(0), now)

        fun lend(me: Identity, buyer: ByteArray, now: Long) = keyed(me, LEND, buyer, now)

        fun seal(me: Identity, to: ByteArray, theirBox: ByteArray, op: Int, stream: Long, data: ByteArray) =
            sealWith(Crypto.boxShared(theirBox, me.boxSecret), me.nodeId, to, op, stream, data)

        fun sealWith(key: ByteArray, from: ByteArray, to: ByteArray, op: Int, stream: Long, data: ByteArray): TunnelMsg {
            val (nonce, payload) = Direct.seal(key, op, stream, data)
            return TunnelMsg(from, to, op, stream, nonce, payload)
        }

        fun decode(t: Map<Long, ByteArray>): TunnelMsg {
            Tlv.requireKnown(t, setOf(2, 4, 6, 8, 10, 12))
            return TunnelMsg(t.getValue(2), t.getValue(4), t.getValue(6)[0].toInt() and 0xff, Tlv.readU64(t.getValue(8)), t.getValue(10), t.getValue(12))
        }
    }
}
