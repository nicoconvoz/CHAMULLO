package ar.chamullo.core

import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.math.ec.ECPoint
import java.math.BigInteger
import java.security.MessageDigest

/** BIP-340 Schnorr signatures over secp256k1: the signatures Nostr uses (Discovery & Routing §11.2). */
object Schnorr {
    private val curve = CustomNamedCurves.getByName("secp256k1")
    private val G: ECPoint = curve.g
    private val N: BigInteger = curve.n
    private val P: BigInteger = curve.curve.field.characteristic

    private fun tagged(tag: String, vararg parts: ByteArray): ByteArray {
        val t = sha256(tag.toByteArray())
        val md = MessageDigest.getInstance("SHA-256")
        md.update(t); md.update(t); for (p in parts) md.update(p)
        return md.digest()
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
    private fun int(b: ByteArray) = BigInteger(1, b)
    private fun bytes(x: BigInteger): ByteArray = x.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else ByteArray(32 - it.size) + it }
    private fun xOnly(p: ECPoint) = bytes(p.normalize().affineXCoord.toBigInteger())
    private fun evenY(p: ECPoint) = !p.normalize().affineYCoord.toBigInteger().testBit(0)

    /** The x-only public key of a 32-byte secret. */
    fun publicKey(secret: ByteArray): ByteArray = xOnly(G.multiply(int(secret).mod(N)))

    fun sign(secret: ByteArray, msg: ByteArray, aux: ByteArray = Crypto.randomBytes(32)): ByteArray {
        val d0 = int(secret)
        require(d0.signum() > 0 && d0 < N) { "secret out of range" }
        val p = G.multiply(d0).normalize()
        val d = if (evenY(p)) d0 else N.subtract(d0)
        val t = bytes(d).also { val h = tagged("BIP0340/aux", aux); for (i in it.indices) it[i] = (it[i].toInt() xor h[i].toInt()).toByte() }
        val k0 = int(tagged("BIP0340/nonce", t, xOnly(p), msg)).mod(N)
        require(k0.signum() > 0) { "unlucky nonce" }
        val r = G.multiply(k0).normalize()
        val k = if (evenY(r)) k0 else N.subtract(k0)
        val e = int(tagged("BIP0340/challenge", xOnly(r), xOnly(p), msg)).mod(N)
        return xOnly(r) + bytes(k.add(e.multiply(d)).mod(N))
    }

    fun verify(pub: ByteArray, msg: ByteArray, sig: ByteArray): Boolean = runCatching {
        if (pub.size != 32 || sig.size != 64) return false
        val pt = curve.curve.decodePoint(byteArrayOf(2) + pub) // lift_x: the point with even y
        val r = int(sig.copyOfRange(0, 32)); val s = int(sig.copyOfRange(32, 64))
        if (r >= P || s >= N) return false
        val e = int(tagged("BIP0340/challenge", sig.copyOfRange(0, 32), pub, msg)).mod(N)
        val big = G.multiply(s).add(pt.multiply(N.subtract(e))).normalize()
        !big.isInfinity && evenY(big) && big.affineXCoord.toBigInteger() == r
    }.getOrDefault(false)
}

/**
 * Nostr (NIP-01) for the Internet bridge: thousands of public relays nobody owns, where anyone publishes signed events
 * and subscribes to the ones they care about. CHAMULLO's Nostr key comes from the same 16 words, under its own label.
 */
object Nostr {
    /** A Nostr event: id = sha256 of its NIP-01 serialization, signed with BIP-340. */
    class Event(val id: String, val pubkey: String, val createdAt: Long, val kind: Int, val tags: List<List<String>>, val content: String, val sig: String) {
        fun verify(): Boolean = runCatching {
            serialId(pubkey, createdAt, kind, tags, content) == id && Schnorr.verify(hex(pubkey), hex(id), hex(sig))
        }.getOrDefault(false)

        fun tag(name: String): List<String> = tags.filter { it.size >= 2 && it[0] == name }.map { it[1] }

        fun toJson(): String = "{\"id\":${Json.str(id)},\"pubkey\":${Json.str(pubkey)},\"created_at\":$createdAt,\"kind\":$kind," +
            "\"tags\":${Json.tags(tags)},\"content\":${Json.str(content)},\"sig\":${Json.str(sig)}}"

        companion object {
            fun fromJson(o: Map<*, *>) = Event(
                o["id"] as String, o["pubkey"] as String, (o["created_at"] as Number).toLong(), (o["kind"] as Number).toInt(),
                (o["tags"] as List<*>).map { t -> (t as List<*>).map { it.toString() } }, o["content"] as String, o["sig"] as String
            )
        }
    }

    fun secretKey(me: Identity): ByteArray {
        val n = CustomNamedCurves.getByName("secp256k1").n
        val d = BigInteger(1, Crypto.sha256("CHAMULLO/1/NOSTR".toByteArray() + 0.toByte() + me.seed)).mod(n)
        return d.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else ByteArray(32 - it.size) + it }
    }

    fun publicKey(me: Identity): ByteArray = Schnorr.publicKey(secretKey(me))

    fun serialId(pubkey: String, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): String =
        Crypto.sha256("[0,${Json.str(pubkey)},$createdAt,$kind,${Json.tags(tags)},${Json.str(content)}]".toByteArray()).toHex()

    fun event(me: Identity, kind: Int, tags: List<List<String>>, content: String, createdAt: Long = System.currentTimeMillis() / 1000): Event {
        val pub = publicKey(me).toHex()
        val id = serialId(pub, createdAt, kind, tags, content)
        return Event(id, pub, createdAt, kind, tags, content, Schnorr.sign(secretKey(me), hex(id)).toHex())
    }
}

/** Just enough JSON for Nostr: write strings the NIP-01 way, and read what relays send. */
object Json {
    fun str(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r")
            '\t' -> append("\\t"); '\b' -> append("\\b"); '\u000c' -> append("\\f")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
    }

    fun tags(tags: List<List<String>>) = tags.joinToString(",", "[", "]") { t -> t.joinToString(",", "[", "]") { str(it) } }

    fun parse(text: String): Any? = Parser(text).run { value().also { space() } }

    private class Parser(val s: String) {
        var i = 0
        fun space() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            space()
            return when (val c = s[i]) {
                '{' -> obj(); '[' -> arr(); '"' -> string()
                't' -> { i += 4; true }; 'f' -> { i += 5; false }; 'n' -> { i += 4; null }
                else -> if (c == '-' || c.isDigit()) number() else throw IllegalArgumentException("json at $i")
            }
        }
        fun obj(): Map<String, Any?> {
            i++; val m = LinkedHashMap<String, Any?>(); space()
            if (s[i] == '}') { i++; return m }
            while (true) { space(); val k = string(); space(); i++ /* : */; m[k] = value(); space(); if (s[i++] == '}') return m }
        }
        fun arr(): List<Any?> {
            i++; val l = ArrayList<Any?>(); space()
            if (s[i] == ']') { i++; return l }
            while (true) { l += value(); space(); if (s[i++] == ']') return l }
        }
        fun string(): String {
            i++; val b = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return b.toString()
                    '\\' -> when (val e = s[i++]) {
                        'n' -> b.append('\n'); 'r' -> b.append('\r'); 't' -> b.append('\t'); 'b' -> b.append('\b'); 'f' -> b.append('\u000c')
                        'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> b.append(e)
                    }
                    else -> b.append(c)
                }
            }
        }
        fun number(): Any {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            val t = s.substring(start, i)
            return if (t.any { it in ".eE" }) t.toDouble() else t.toLong()
        }
    }
}
