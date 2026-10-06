// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-CHAMULLO-Commercial
// Copyright (C) 2026 Jesús Nicolás Astorga y RESOURCES OPEN DOORS S.A.S
package ar.chamullo.core

/** A frame for one phone of the island, passed on by the host only to it: the data tunnel and calls. */
sealed interface Addressed : Packet {
    val from: ByteArray
    val to: ByteArray
}

/**
 * Sealing for two ends that share a box key: the operation and the stream or call number go inside, so nobody in the
 * middle can turn one into another (Discovery & Routing §11.3, Camino y Carretera §7).
 */
internal object Direct {
    fun seal(key: ByteArray, op: Int, id: Long, data: ByteArray): Pair<ByteArray, ByteArray> {
        val nonce = Crypto.randomBytes(24)
        return nonce to Crypto.secretbox(Writer().u8(op).u64(id).raw(data).bytes(), nonce, key)
    }

    fun open(key: ByteArray, nonce: ByteArray, payload: ByteArray, op: Int, id: Long): ByteArray? {
        val plain = Crypto.secretboxOpen(payload, nonce, key) ?: return null
        if (plain.size < 9 || (plain[0].toInt() and 0xff) != op || Reader(plain, 1).u64() != id) return null
        return plain.copyOfRange(9, plain.size)
    }
}

/**
 * LINK 19: a piece of a call inside the island (Camino y Carretera §7). Always sealed for the contact with the box keys
 * of both cards: only a contact can ring, and the host that passes it on hears nothing.
 */
class CallMsg(override val from: ByteArray, override val to: ByteArray, val op: Int, val call: Long, val nonce: ByteArray, val payload: ByteArray) : Addressed {
    override fun encode() = Packet.link(Packet.LINK_CALL, listOf(
        2L to from, 4L to to, 6L to byteArrayOf(op.toByte()), 8L to Tlv.u64(call), 10L to nonce, 12L to payload
    ))

    fun open(me: Identity, theirBox: ByteArray): ByteArray? = openWith(Crypto.boxShared(theirBox, me.boxSecret))

    fun openWith(key: ByteArray): ByteArray? = Direct.open(key, nonce, payload, op, call)

    companion object {
        /** Ring: the payload says 1 for a video call. */
        const val RING = 1
        /** "It is ringing here": the caller stops repeating the ring. */
        const val RINGING = 2
        const val ANSWER = 3
        const val REJECT = 4
        const val BUSY = 5
        const val HANGUP = 6
        /** 20 ms of voice: 16 kHz, mono, 16-bit PCM. */
        const val AUDIO = 7
        /** A piece of H.264 video: flags (1 = codec config, 2 = key frame), rotation, then the bytes. */
        const val VIDEO = 8
        /** Still here (every [CallSession.PING_MS]), also when the microphone is muted. */
        const val PING = 9

        fun seal(me: Identity, to: ByteArray, theirBox: ByteArray, op: Int, call: Long, data: ByteArray) =
            sealWith(Crypto.boxShared(theirBox, me.boxSecret), me.nodeId, to, op, call, data)

        fun sealWith(key: ByteArray, from: ByteArray, to: ByteArray, op: Int, call: Long, data: ByteArray): CallMsg {
            val (nonce, payload) = Direct.seal(key, op, call, data)
            return CallMsg(from, to, op, call, nonce, payload)
        }

        fun decode(t: Map<Long, ByteArray>): CallMsg {
            Tlv.requireKnown(t, setOf(2, 4, 6, 8, 10, 12))
            return CallMsg(t.getValue(2), t.getValue(4), t.getValue(6)[0].toInt() and 0xff, Tlv.readU64(t.getValue(8)), t.getValue(10), t.getValue(12))
        }
    }
}

/** One call, from ring to hang-up, with its timeouts (Camino y Carretera §7). Pure: the app feeds it time and messages. */
class CallSession(val id: Long, val peer: ByteArray, val outgoing: Boolean, val video: Boolean, now: Long) {
    enum class State { CALLING, RINGING, ACTIVE, ENDED }
    enum class End { HUNG_UP, REJECTED, BUSY, UNREACHABLE, NO_ANSWER, MISSED, LOST }

    var state = if (outgoing) State.CALLING else State.RINGING; private set
    var end: End? = null; private set
    var startedAt = 0L; private set
    private val createdAt = now
    private var ringingAt = now
    private var lastRing = Long.MIN_VALUE / 2
    private var lastHeard = now
    private var endedAt = 0L

    /** Whether to send the ring again: until the other phone says it is ringing. */
    fun wantsRing(now: Long): Boolean {
        if (!outgoing || state != State.CALLING || now - lastRing < RING_EVERY_MS) return false
        lastRing = now
        return true
    }

    fun onRinging(now: Long) { if (state == State.CALLING) { state = State.RINGING; ringingAt = now } }

    fun onAnswer(now: Long) { if (outgoing && (state == State.CALLING || state == State.RINGING)) activate(now) }

    /** I answer an incoming call. */
    fun answer(now: Long) { if (!outgoing && state == State.RINGING) activate(now) }

    fun reject(now: Long) { if (!outgoing && state == State.RINGING) finish(End.REJECTED, now) }

    fun hangUp(now: Long) = finish(End.HUNG_UP, now)

    fun onMedia(now: Long) { lastHeard = now }

    fun onRemote(op: Int, now: Long) {
        lastHeard = now
        when (op) {
            CallMsg.RINGING -> onRinging(now)
            CallMsg.ANSWER -> onAnswer(now)
            CallMsg.BUSY -> finish(End.BUSY, now)
            CallMsg.REJECT -> finish(End.REJECTED, now)
            CallMsg.HANGUP -> finish(End.HUNG_UP, now)
        }
    }

    fun tick(now: Long) {
        when (state) {
            State.CALLING -> if (now - createdAt >= REACH_MS) finish(End.UNREACHABLE, now)
            State.RINGING -> if (outgoing && now - ringingAt >= RING_MS) finish(End.NO_ANSWER, now)
                else if (!outgoing && now - createdAt >= RING_MS) finish(End.MISSED, now)
            State.ACTIVE -> if (now - lastHeard >= SILENT_MS) finish(End.LOST, now)
            State.ENDED -> Unit
        }
    }

    /** How long it talked, in milliseconds; 0 if it was never answered. */
    /** Talking, but nothing heard for a few seconds: the island may be moving (§7.2). The screen says "reconectando". */
    fun reconnecting(now: Long) = state == State.ACTIVE && now - lastHeard >= RECONNECTING_MS

    fun duration(now: Long = endedAt): Long = if (startedAt == 0L) 0 else (if (state == State.ENDED) endedAt else now) - startedAt

    private fun activate(now: Long) { state = State.ACTIVE; startedAt = now; lastHeard = now }

    private fun finish(e: End, now: Long) {
        if (state == State.ENDED) return
        state = State.ENDED; end = e; endedAt = now
    }

    companion object {
        /**
         * Without a "ringing" back in this time, the contact is not reachable now. 20 s, not 12: in the field a stalled
         * pipe took longer than that to be replaced (Camino y Carretera §6.10).
         */
        const val REACH_MS = 20_000L
        const val RING_EVERY_MS = 2_000L
        const val RING_MS = 45_000L
        /** 25 s, not 15: a member that falls off when the island moves channel is back in 10 to 20 s (§7.2). */
        const val SILENT_MS = 25_000L
        const val RECONNECTING_MS = 3_000L
        const val PING_MS = 2_000L
    }
}

/**
 * The media lane of a call (Camino y Carretera §7.1): voice and picture go as UDP datagrams straight to the other
 * phone, because a lost piece is better skipped than waited for. The pipe (TCP) carries them too until the other side
 * says, in its ping, that UDP reaches it. A piece that arrives both ways plays once.
 */
class MediaRoute {
    /** The other phone's IPv4 address in the island, from its ring or its answer. */
    @Volatile var peer: ByteArray? = null
    @Volatile private var lastUdpIn = Long.MIN_VALUE / 2
    @Volatile private var peerHearsUdp = false
    private val seen = LinkedHashSet<String>()

    fun useUdp() = peer != null

    fun alsoPipe() = peer == null || !peerHearsUdp

    fun onUdpIn(now: Long) { lastUdpIn = now }

    /** What my ping says: 1 if UDP media reached me in the last [UDP_FRESH_MS], then my current address in the island. */
    fun pingPayload(now: Long, ip: ByteArray? = null) = byteArrayOf(if (now - lastUdpIn <= UDP_FRESH_MS) 1 else 0) + (ip ?: ByteArray(0))

    /** The other side's ping: whether it hears my UDP, and its address now (it may change after it rejoins the island). */
    fun onPing(payload: ByteArray) {
        peerHearsUdp = payload.firstOrNull()?.toInt() == 1
        address(payload, 1)?.let { peer = it }
    }

    /** Whether a piece (known by its nonce) is new: the copy that comes the other way is dropped. */
    @Synchronized fun firstTime(nonce: ByteArray): Boolean {
        if (!seen.add(nonce.toHex())) return false
        while (seen.size > MAX_SEEN) seen.remove(seen.first())
        return true
    }

    companion object {
        const val UDP_FRESH_MS = 3_000L
        const val MAX_SEEN = 512

        fun ring(video: Boolean, ip: ByteArray?) = byteArrayOf(if (video) 1 else 0) + (ip ?: ByteArray(0))

        fun answer(ip: ByteArray?) = ip ?: ByteArray(0)

        fun isVideo(ring: ByteArray) = ring.firstOrNull()?.toInt() == 1

        /** An IPv4 address at [offset], if the piece carries one (phones before 0.9.2 do not). */
        fun address(data: ByteArray, offset: Int): ByteArray? = if (data.size >= offset + 4) data.copyOfRange(offset, offset + 4) else null
    }
}
