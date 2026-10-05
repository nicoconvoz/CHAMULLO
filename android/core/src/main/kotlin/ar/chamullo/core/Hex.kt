package ar.chamullo.core

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun hex(s: String): ByteArray {
    require(s.length % 2 == 0 && s.all { it in "0123456789abcdefABCDEF" }) { "not hex" }
    return ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}
