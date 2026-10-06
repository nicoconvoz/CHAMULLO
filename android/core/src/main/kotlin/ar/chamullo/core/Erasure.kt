// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-CHAMULLO-Commercial
// Copyright (C) 2026 Jesús Nicolás Astorga y RESOURCES OPEN DOORS S.A.S
package ar.chamullo.core

/**
 * GRITO+ (Air Interface §4.1): Reed-Solomon erasure code over GF(256), the one in CDs and QR codes. To n data pieces it
 * adds r spare pieces (a systematic Cauchy matrix), and **any** n of the n + r rebuild the data, whichever were lost.
 * The radio's CRC drops damaged packets whole, so losses are all there is to repair. n + r must stay within 255.
 */
object Erasure {
    private val exp = IntArray(512)
    private val log = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) { exp[i] = x; log[x] = i; x = x shl 1; if (x and 0x100 != 0) x = x xor 0x11d }
        for (i in 255 until 512) exp[i] = exp[i - 255]
    }

    private fun mul(a: Int, b: Int) = if (a == 0 || b == 0) 0 else exp[log[a] + log[b]]
    private fun inv(a: Int) = exp[255 - log[a]]

    // Row j of the spare part: 1 / (x_j + y_i), with x_j = n + j and y_i = i (distinct, so never 0).
    private fun cauchy(n: Int, j: Int, i: Int) = inv((n + j) xor i)

    /** The r spare pieces for [data] (all of the same length). */
    fun parity(data: List<ByteArray>, r: Int): List<ByteArray> {
        val n = data.size
        require(n + r <= 255) { "at most 255 pieces in all" }
        val len = data.first().size
        return List(r) { j ->
            ByteArray(len).also { out ->
                for (i in 0 until n) {
                    val c = cauchy(n, j, i)
                    val d = data[i]
                    for (k in 0 until len) out[k] = (out[k].toInt() xor mul(c, d[k].toInt() and 0xff)).toByte()
                }
            }
        }
    }

    /** The n data pieces from any n received ones (index → piece; indices ≥ n are spares); null with fewer than n. */
    fun recover(n: Int, got: Map<Int, ByteArray>): List<ByteArray>? {
        if (got.size < n) return null
        val use = (got.keys.filter { it < n } + got.keys.filter { it >= n }).take(n)
        if ((0 until n).all { it in got }) return List(n) { got.getValue(it) }
        val len = got.values.first().size
        // Rows of the code for the pieces we have; invert it with Gauss-Jordan over GF(256).
        val m = Array(n) { r -> IntArray(n) { c -> val k = use[r]; if (k < n) (if (c == k) 1 else 0) else cauchy(n, k - n, c) } }
        val invM = Array(n) { r -> IntArray(n) { c -> if (r == c) 1 else 0 } }
        for (col in 0 until n) {
            val pivot = (col until n).firstOrNull { m[it][col] != 0 } ?: return null
            if (pivot != col) { m[pivot] = m[col].also { m[col] = m[pivot] }; invM[pivot] = invM[col].also { invM[col] = invM[pivot] } }
            val f = inv(m[col][col])
            for (c in 0 until n) { m[col][c] = mul(m[col][c], f); invM[col][c] = mul(invM[col][c], f) }
            for (r in 0 until n) if (r != col && m[r][col] != 0) {
                val g = m[r][col]
                for (c in 0 until n) { m[r][c] = m[r][c] xor mul(g, m[col][c]); invM[r][c] = invM[r][c] xor mul(g, invM[col][c]) }
            }
        }
        return List(n) { i ->
            ByteArray(len).also { out ->
                for (r in 0 until n) {
                    val c = invM[i][r]; if (c == 0) continue
                    val p = got.getValue(use[r])
                    for (k in 0 until len) out[k] = (out[k].toInt() xor mul(c, p[k].toInt() and 0xff)).toByte()
                }
            }
        }
    }
}
