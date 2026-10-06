package ar.chamullo.core

/**
 * The universal shout (Air Interface §4): every phone, old or new, can send and hear classic 31-byte
 * advertisements. A frame travels as micros of at most [MAX] bytes:
 *
 *   MARK(1) | id(2) | index(1) | n(1) | g(1) | piece(16)
 *
 * The frame, prefixed with its 2-byte length, is cut into n data pieces. Then g parity pieces are added
 * ("comodines"): parity j is the XOR of every data piece i with i % g == j. One lost micro per group is rebuilt
 * from its parity. The radio's own CRC already drops damaged packets, so what CHAMULLO has to repair are losses.
 */
object Micro {
    const val MARK = 0xC5.toByte()
    const val MAX = 22
    private const val HEADER = 6
    const val DATA = MAX - HEADER

    fun isMicro(b: ByteArray) = b.size == MAX && b[0] == MARK

    fun groupsFor(n: Int) = ((n + 3) / 4).coerceIn(1, 16)

    fun split(frame: ByteArray): List<ByteArray> {
        val body = byteArrayOf((frame.size ushr 8).toByte(), frame.size.toByte()) + frame
        val n = (body.size + DATA - 1) / DATA
        val g = groupsFor(n)
        require(n + g <= 255) { "frame too large for micros" }
        val pieces = (0 until n).map { i -> body.copyOfRange(i * DATA, minOf(body.size, (i + 1) * DATA)).copyOf(DATA) }
        val parity = (0 until g).map { j -> xor(pieces.filterIndexed { i, _ -> i % g == j }) }
        val id = Crypto.randomBytes(2)
        return (pieces + parity).mapIndexed { idx, piece -> byteArrayOf(MARK, id[0], id[1], idx.toByte(), n.toByte(), g.toByte()) + piece }
    }

    internal fun xor(pieces: List<ByteArray>): ByteArray {
        val out = ByteArray(DATA)
        for (p in pieces) for (k in 0 until DATA) out[k] = (out[k].toInt() xor p[k].toInt()).toByte()
        return out
    }
}

class MicroAssembler(private val maxPending: Int = 128) {
    private class Pending(val n: Int, val g: Int) { val pieces = arrayOfNulls<ByteArray>(n + g) }
    private val pending = LinkedHashMap<String, Pending>()
    private val done = LinkedHashSet<String>()

    /** Returns the whole frame once enough micros arrived to rebuild it; null otherwise (or if [micro] is not one). */
    fun accept(micro: ByteArray): ByteArray? {
        if (!Micro.isMicro(micro)) return null
        val index = micro[3].toInt() and 0xff
        val n = micro[4].toInt() and 0xff
        val g = micro[5].toInt() and 0xff
        if (n == 0 || g == 0 || index >= n + g) return null
        val key = "${micro[1]}:${micro[2]}:$n:$g"
        if (key in done) return null
        val p = pending.getOrPut(key) { Pending(n, g) }
        if (p.n != n || p.g != g) return null
        p.pieces[index] = micro.copyOfRange(6, micro.size)
        while (pending.size > maxPending) pending.remove(pending.keys.first())
        val data = repair(p) ?: return null
        pending.remove(key)
        done += key
        while (done.size > maxPending) done.remove(done.first())
        val body = data.fold(ByteArray(0)) { acc, piece -> acc + piece }
        val len = ((body[0].toInt() and 0xff) shl 8) or (body[1].toInt() and 0xff)
        return if (len <= body.size - 2) body.copyOfRange(2, 2 + len) else null
    }

    // Rebuilds each group's single missing data piece from its parity; null while some group misses more than one.
    private fun repair(p: Pending): List<ByteArray>? {
        val data = p.pieces.copyOfRange(0, p.n)
        for (j in 0 until p.g) {
            val members = (j until p.n step p.g).toList()
            val missing = members.filter { data[it] == null }
            if (missing.isEmpty()) continue
            val parity = p.pieces[p.n + j]
            if (missing.size > 1 || parity == null) return null
            data[missing.single()] = Micro.xor(members.mapNotNull { data[it] } + parity)
        }
        return data.map { it!! }
    }
}
