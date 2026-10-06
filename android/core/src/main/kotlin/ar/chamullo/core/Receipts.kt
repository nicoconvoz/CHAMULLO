// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-CHAMULLO-Commercial
// Copyright (C) 2026 Jesús Nicolás Astorga y RESOURCES OPEN DOORS S.A.S
package ar.chamullo.core

/**
 * What a carrier keeps to cash its hop later (Proof of Relay §4.3, §7): the one-time key it sealed its record with, the
 * sealed record (blob) and the record in clear. Without [eSec] nobody can prove that blob is theirs.
 */
class HopKey(val i: Int, val eSec: ByteArray, val blob: ByteArray, val record: ByteArray)

/**
 * The journey sealed by both ends (Proof of Relay §6): the letter's origin section, the hashes of every hop record (L),
 * which hops the destination found valid, the delivery secret only the reader knew (r), and the origin's confirmation
 * over exactly that. The ledger accepts it when r matches the commitment the origin sealed and the confirmation holds.
 */
class Journey(val origin: ByteArray, val hashes: List<ByteArray>, val revealed: ByteArray, val validity: ByteArray, val confirm: ByteArray) {
    val envelope: Envelope by lazy { Packet.parse(origin) as Envelope }
    val msgId: ByteArray get() = envelope.msgId
    val src: ByteArray get() = envelope.src

    /** j = H("CHAMULLO/1/JRNY" || msg_id || L || validity): what the origin confirms. */
    fun hash() = hashOf(msgId, hashes, validity)

    fun verify(): Boolean = runCatching {
        val env = envelope
        env.verify() && env.hopCount == 0 && Crypto.hash(revealed).contentEquals(env.deliveryCommit ?: return false) &&
            Identity.verify(env.src, "CONF", env.msgId + hash(), confirm)
    }.getOrDefault(false)

    fun encode(): ByteArray = Tlv.encode(listOf(2L to origin, 4L to hashes.fold(ByteArray(0)) { a, h -> a + h }, 6L to revealed, 8L to validity, 10L to confirm))

    companion object {
        fun hashOf(msgId: ByteArray, hashes: List<ByteArray>, validity: ByteArray) =
            Crypto.hash("CHAMULLO/1/JRNY".toByteArray() + 0.toByte() + msgId + hashes.fold(ByteArray(0)) { a, h -> a + h } + validity)

        /** The origin signs with the same one-time key that sealed the letter (Proof of Relay §6.3). */
        fun confirm(sealed: Sealed, hashes: List<ByteArray>, revealed: ByteArray, validity: ByteArray): Journey {
            val env = sealed.envelope
            val sig = Crypto.sign(sealed.ephemeralSeed, Identity.signingInput("CONF", env.msgId + hashOf(env.msgId, hashes, validity)))
            return Journey(env.originSection(), hashes, revealed, validity, sig)
        }

        /** Which hops held, one bit each, as the destination found them (Proof of Relay §6.1). */
        fun validity(hops: List<Hop>): ByteArray = ByteArray((hops.size + 7) / 8).also { b ->
            hops.forEachIndexed { i, h -> if (h.valid) b[i / 8] = (b[i / 8].toInt() or (1 shl (i % 8))).toByte() }
        }

        fun bit(validity: ByteArray, i: Int) = i / 8 < validity.size && (validity[i / 8].toInt() shr (i % 8)) and 1 == 1

        fun decode(bytes: ByteArray): Journey {
            val t = Tlv.decode(bytes).also { Tlv.requireKnown(it, setOf(2, 4, 6, 8, 10)) }
            return Journey(t.getValue(2), t.getValue(4).toList().chunked(32) { it.toByteArray() }, t.getValue(6), t.getValue(8), t.getValue(10))
        }
    }
}

/** What a valid claim proves: who gave it, to whom, how many could have carried it instead, and its place in the journey. */
class ClaimProof(val giver: ByteArray, val taker: ByteArray, val alternatives: Int, val i: Int, val hops: Int)

/**
 * Cobrar (Proof of Relay §7): the carrier presents the letter's origin section, its hop and the key it kept. The ledger
 * re-seals the record with that key and gets exactly the blob the journey lists: so the blob is the claimant's, and the
 * claimant is that hop's giver. What the ledger learns: the claimant and who it handed the letter to. Never the route.
 */
class Claim(val origin: ByteArray, val key: HopKey) : Packet {
    override fun encode() = Packet.link(Packet.LINK_CLAIM, listOf(
        2L to origin, 4L to Tlv.u64(key.i.toLong()), 6L to key.eSec, 8L to key.blob, 10L to key.record
    ))

    fun msgId(): ByteArray = (Packet.parse(origin) as Envelope).msgId

    fun check(j: Journey): ClaimProof? = runCatching {
        val i = key.i
        if (!j.verify() || !j.origin.contentEquals(origin)) return null
        if (i < 1 || i >= j.hashes.size || !Journey.bit(j.validity, i)) return null // the origin does not charge for its own letter
        val blob = key.blob
        if (!Crypto.hash(blob).contentEquals(j.hashes[i])) return null
        val ePub = blob.copyOfRange(0, 32); val n = blob.copyOfRange(32, 56)
        val jPub = j.envelope.journeyKey ?: return null
        if (!Crypto.boxPublicKey(key.eSec).contentEquals(ePub)) return null
        if (!Crypto.box(key.record, n, jPub, key.eSec).contentEquals(blob.copyOfRange(56, blob.size))) return null
        val rec = Tlv.decode(key.record)
        val giver = rec.getValue(2); val ts = Tlv.readU64(rec.getValue(6))
        val taker = rec[10] ?: ByteArray(0); val acceptSig = rec[12] ?: ByteArray(0)
        if (Tlv.readU64(rec.getValue(4)) != i.toLong()) return null
        var seed = Crypto.hash("CHAMULLO/1/SEED".toByteArray() + 0.toByte() + j.msgId + j.envelope.sig)
        for (k in 0 until i) seed = Crypto.hash(seed + j.hashes[k])
        val body = Envelope.acceptBody(j.msgId, i, seed, giver, taker, ts)
        if (!Identity.verify(giver, "HOP", body + acceptSig, rec.getValue(8))) return null
        if (taker.isNotEmpty() && !Identity.verify(taker, "ACPT", body, acceptSig)) return null
        ClaimProof(giver, taker, (rec[14]?.let { Tlv.readU64(it) } ?: 0L).toInt(), i, j.hashes.size)
    }.getOrNull()

    companion object {
        fun decode(t: Map<Long, ByteArray>): Claim {
            Tlv.requireKnown(t, setOf(2, 4, 6, 8, 10))
            return Claim(t.getValue(2), HopKey(Tlv.readU64(t.getValue(4)).toInt(), t.getValue(6), t.getValue(8), t.getValue(10)))
        }
    }
}
