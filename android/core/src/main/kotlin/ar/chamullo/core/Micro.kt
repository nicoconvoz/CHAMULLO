package ar.chamullo.core

/**
 * The universal shout (Air Interface §4, v0.1.2): every phone, old or new, can send and hear classic 31-byte
 * advertisements. A frame travels as micros of at most [MAX] bytes:
 *
 *   MARK(1) | id(2) | index(1) | count(1) | data(≤ 17)
 *
 * Phones with long-range radios also shout the whole frame at once; the micros guarantee nobody is left out.
 */
object Micro {
    const val MARK = 0xC4.toByte()
    const val MAX = 22
    private const val HEADER = 5
    const val DATA = MAX - HEADER

    fun isMicro(b: ByteArray) = b.size > HEADER && b[0] == MARK

    fun split(frame: ByteArray): List<ByteArray> {
        val count = (frame.size + DATA - 1) / DATA
        require(count in 1..255) { "frame too large for micros" }
        val id = Crypto.randomBytes(2)
        return (0 until count).map { i ->
            byteArrayOf(MARK, id[0], id[1], i.toByte(), count.toByte()) + frame.copyOfRange(i * DATA, minOf(frame.size, (i + 1) * DATA))
        }
    }
}

class MicroAssembler(private val maxPending: Int = 128) {
    private val pending = LinkedHashMap<String, Array<ByteArray?>>()
    private val done = LinkedHashSet<String>()

    /** Returns the whole frame once its last missing micro arrives; null otherwise (or if [micro] is not one). */
    fun accept(micro: ByteArray): ByteArray? {
        if (!Micro.isMicro(micro)) return null
        val index = micro[3].toInt() and 0xff
        val count = micro[4].toInt() and 0xff
        if (count == 0 || index >= count) return null
        val key = "${micro[1]}:${micro[2]}:$count"
        if (key in done) return null
        val parts = pending.getOrPut(key) { arrayOfNulls(count) }
        parts[index] = micro.copyOfRange(5, micro.size)
        while (pending.size > maxPending) pending.remove(pending.keys.first())
        if (parts.any { it == null }) return null
        pending.remove(key)
        done += key
        while (done.size > maxPending) done.remove(done.first())
        return parts.fold(ByteArray(0)) { acc, p -> acc + p!! }
    }
}
