package ar.chamullo.core

private val DIGITS = "0123456789abcdef".toCharArray()

fun ByteArray.toHex(): String {
    val out = CharArray(size * 2)
    for (i in indices) { val v = this[i].toInt() and 0xff; out[2 * i] = DIGITS[v ushr 4]; out[2 * i + 1] = DIGITS[v and 0x0f] }
    return String(out)
}

fun hex(s: String): ByteArray {
    require(s.length % 2 == 0 && s.all { it in "0123456789abcdefABCDEF" }) { "not hex" }
    return ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}
