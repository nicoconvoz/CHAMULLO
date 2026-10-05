package ar.chamullo.core

import java.io.ByteArrayOutputStream

class WireException(message: String) : Exception(message)

/** Big-endian writer with QUIC varints (Packet Format §2). */
class Writer {
    private val out = ByteArrayOutputStream()

    fun u8(v: Int) = apply { out.write(v and 0xff) }

    fun u64(v: Long) = apply { for (i in 7 downTo 0) out.write((v ushr (8 * i)).toInt() and 0xff) }

    fun raw(b: ByteArray) = apply { out.write(b) }

    fun varint(v: Long) = apply {
        when {
            v < 0 -> throw WireException("negative varint")
            v < (1L shl 6) -> out.write(v.toInt())
            v < (1L shl 14) -> { out.write(0x40 or (v ushr 8).toInt()); out.write(v.toInt() and 0xff) }
            v < (1L shl 30) -> { out.write(0x80 or (v ushr 24).toInt()); for (i in 2 downTo 0) out.write((v ushr (8 * i)).toInt() and 0xff) }
            v < (1L shl 62) -> { out.write(0xc0 or (v ushr 56).toInt()); for (i in 6 downTo 0) out.write((v ushr (8 * i)).toInt() and 0xff) }
            else -> throw WireException("varint too large")
        }
    }

    fun bytes(): ByteArray = out.toByteArray()
}

class Reader(private val b: ByteArray, private var pos: Int = 0) {
    val remaining get() = b.size - pos

    private fun need(n: Int) { if (n < 0 || remaining < n) throw WireException("truncated") }

    fun u8(): Int { need(1); return b[pos++].toInt() and 0xff }

    fun u64(): Long { need(8); var v = 0L; repeat(8) { v = (v shl 8) or (b[pos++].toLong() and 0xff) }; return v }

    fun take(n: Int): ByteArray { need(n); return b.copyOfRange(pos, pos + n).also { pos += n } }

    fun rest(): ByteArray = take(remaining)

    fun varint(): Long {
        val first = u8()
        val len = 1 shl (first ushr 6)
        var v = (first and 0x3f).toLong()
        repeat(len - 1) { v = (v shl 8) or u8().toLong() }
        val min = when (len) { 1 -> 0L; 2 -> 1L shl 6; 4 -> 1L shl 14; else -> 1L shl 30 }
        if (v < min) throw WireException("non-minimal varint")
        return v
    }
}

/** Gestures: type-length-value records in strictly increasing type order (Packet Format §4). */
object Tlv {
    fun encode(records: List<Pair<Long, ByteArray>>): ByteArray {
        val w = Writer()
        var last = -1L
        for ((type, value) in records) {
            if (type <= last) throw WireException("tlv types must strictly increase")
            last = type
            w.varint(type).varint(value.size.toLong()).raw(value)
        }
        return w.bytes()
    }

    fun decode(bytes: ByteArray): Map<Long, ByteArray> {
        val r = Reader(bytes)
        val out = LinkedHashMap<Long, ByteArray>()
        var last = -1L
        while (r.remaining > 0) {
            val type = r.varint()
            if (type <= last) throw WireException("tlv types must strictly increase")
            last = type
            out[type] = r.take(r.varint().toInt())
        }
        return out
    }

    // Unknown odd = courtesy, ignored; unknown even = stop (Packet Format §4.3).
    fun requireKnown(records: Map<Long, ByteArray>, known: Set<Long>) {
        for (t in records.keys) if (t !in known && t % 2 == 0L) throw WireException("unknown required field $t")
    }

    fun u64(v: Long) = Writer().u64(v).bytes()
    fun readU64(b: ByteArray) = Reader(b).u64()
}

/** Letters bigger than one shout are split into FRAGMENT packets and rebuilt by the neighbor (Packet Format §8). */
object Fragments {
    private const val HEADER = 4 + 8 + 2 + 2

    fun split(frame: ByteArray, maxSize: Int): List<ByteArray> {
        if (frame.size <= maxSize) return listOf(frame)
        val chunk = maxSize - HEADER
        require(chunk > 0) { "radio frame too small" }
        val id = Crypto.randomBytes(8)
        val count = (frame.size + chunk - 1) / chunk
        return (0 until count).map { i ->
            Writer().raw(Packet.MAGIC).u8(Packet.VERSION).u8(Packet.KIND_FRAGMENT).raw(id).varint(i.toLong()).varint(count.toLong())
                .raw(frame.copyOfRange(i * chunk, minOf(frame.size, (i + 1) * chunk))).bytes()
        }
    }
}

class Reassembler(private val maxPending: Int = 64) {
    private class Pending(val count: Int, val parts: Array<ByteArray?>)
    private val pending = LinkedHashMap<String, Pending>()

    /** Returns a whole frame: the input itself if it was not a fragment, or the rebuilt frame once complete. */
    fun accept(bytes: ByteArray): ByteArray? {
        if (bytes.size < 4 || !Packet.isChamullo(bytes) || bytes[3].toInt() != Packet.KIND_FRAGMENT) return bytes
        val r = Reader(bytes, 4)
        val id = r.take(8).toHex()
        val index = r.varint().toInt()
        val count = r.varint().toInt()
        if (count !in 1..4096 || index !in 0 until count) return null
        val p = pending.getOrPut(id) { Pending(count, arrayOfNulls(count)) }
        if (p.count != count) return null
        p.parts[index] = r.rest()
        while (pending.size > maxPending) pending.remove(pending.keys.first())
        if (p.parts.any { it == null }) return null
        pending.remove(id)
        return p.parts.fold(ByteArray(0)) { acc, part -> acc + part!! }
    }
}
