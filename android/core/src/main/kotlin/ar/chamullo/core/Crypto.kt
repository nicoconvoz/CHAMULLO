package ar.chamullo.core

import org.bouncycastle.crypto.engines.XSalsa20Engine
import org.bouncycastle.crypto.macs.Poly1305
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.math.ec.rfc7748.X25519
import org.bouncycastle.math.ec.rfc8032.Ed25519
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The primitives of Identity §3, byte-compatible with tweetnacl (ICEBREAK's prototype) and pinenacl (ICEBREAK's app):
 * Ed25519 signatures, X25519 + XSalsa20-Poly1305 box and secretbox, and SHA-512 truncated to 32 bytes.
 */
object Crypto {
    private val random = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    /* ---------- signatures ---------- */
    fun signPublicKey(seed: ByteArray): ByteArray = ByteArray(32).also { Ed25519.generatePublicKey(seed, 0, it, 0) }

    fun sign(seed: ByteArray, message: ByteArray): ByteArray =
        ByteArray(64).also { Ed25519.sign(seed, 0, message, 0, message.size, it, 0) }

    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        val memo = memo ?: return checkSignature(publicKey, message, signature)
        val key = hash(publicKey + signature + message).toHex() // the whole triple: a forged message never hits
        return synchronized(memo) { memo[key] } ?: checkSignature(publicKey, message, signature).also { synchronized(memo) { memo[key] = it } }
    }

    private fun checkSignature(publicKey: ByteArray, message: ByteArray, signature: ByteArray) =
        runCatching { Ed25519.verify(signature, 0, publicKey, 0, message, 0, message.size) }.getOrDefault(false)

    /**
     * For the digital twin only: a thousand simulated phones share one process, so the same frame gets its signature
     * checked once per listener. Remembering the answer (keyed by key, signature and message) gives the same result
     * faster. A real phone has nothing to share and leaves this off.
     */
    @Volatile private var memo: LinkedHashMap<String, Boolean>? = null
    private const val MEMO_SIZE = 200_000

    fun rememberSignatures(on: Boolean) {
        memo = if (on) object : LinkedHashMap<String, Boolean>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>) = size > MEMO_SIZE
        } else null
    }

    fun rememberedSignatures(): Int = memo?.let { synchronized(it) { it.size } } ?: 0

    /* ---------- hashing ---------- */
    fun sha512(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").digest(data)

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun hash(data: ByteArray): ByteArray = sha512(data).copyOf(32)

    /* ---------- box ---------- */
    fun boxPublicKey(secret: ByteArray): ByteArray = ByteArray(32).also { X25519.scalarMultBase(secret, 0, it, 0) }

    // The X25519 form of an Ed25519 signing seed, as tweetnacl/pinenacl derive it.
    fun boxSecretFromSignSeed(seed: ByteArray): ByteArray = sha512(seed).copyOf(32).also {
        it[0] = (it[0].toInt() and 248).toByte()
        it[31] = (it[31].toInt() and 127 or 64).toByte()
    }

    /** The key two box key pairs share, to seal many messages without repeating the curve math. */
    fun boxShared(theirPublic: ByteArray, mySecret: ByteArray): ByteArray = boxKey(theirPublic, mySecret)

    private fun boxKey(theirPublic: ByteArray, mySecret: ByteArray): ByteArray {
        val shared = ByteArray(32)
        X25519.scalarMult(mySecret, 0, theirPublic, 0, shared, 0)
        return hsalsa20(shared, ByteArray(16))
    }

    fun box(message: ByteArray, nonce: ByteArray, theirPublic: ByteArray, mySecret: ByteArray): ByteArray =
        secretbox(message, nonce, boxKey(theirPublic, mySecret))

    fun boxOpen(sealed: ByteArray, nonce: ByteArray, theirPublic: ByteArray, mySecret: ByteArray): ByteArray? =
        secretboxOpen(sealed, nonce, boxKey(theirPublic, mySecret))

    /* ---------- secretbox: XSalsa20-Poly1305, output = tag(16) || ciphertext, like tweetnacl ---------- */
    fun secretbox(message: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray {
        val stream = keystream(key, nonce, 32 + message.size)
        val cipher = ByteArray(message.size) { (message[it].toInt() xor stream[32 + it].toInt()).toByte() }
        return poly1305(stream.copyOf(32), cipher) + cipher
    }

    fun secretboxOpen(sealed: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray? {
        if (sealed.size < 16) return null
        val tag = sealed.copyOfRange(0, 16)
        val cipher = sealed.copyOfRange(16, sealed.size)
        val stream = keystream(key, nonce, 32 + cipher.size)
        if (!MessageDigest.isEqual(tag, poly1305(stream.copyOf(32), cipher))) return null
        return ByteArray(cipher.size) { (cipher[it].toInt() xor stream[32 + it].toInt()).toByte() }
    }

    private fun keystream(key: ByteArray, nonce: ByteArray, length: Int): ByteArray {
        val engine = XSalsa20Engine()
        engine.init(true, ParametersWithIV(KeyParameter(key), nonce))
        val out = ByteArray(length)
        engine.processBytes(ByteArray(length), 0, length, out, 0)
        return out
    }

    private fun poly1305(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Poly1305()
        mac.init(KeyParameter(key))
        mac.update(data, 0, data.size)
        return ByteArray(16).also { mac.doFinal(it, 0) }
    }

    // HSalsa20 (the key-derivation step of XSalsa20 and NaCl box), which BouncyCastle does not expose on its own.
    private fun hsalsa20(key: ByteArray, input: ByteArray): ByteArray {
        val c = intArrayOf(0x61707865, 0x3320646e, 0x79622d32, 0x6b206574) // "expand 32-byte k"
        val x = IntArray(16)
        x[0] = c[0]; x[5] = c[1]; x[10] = c[2]; x[15] = c[3]
        for (i in 0 until 4) {
            x[1 + i] = le32(key, 4 * i)
            x[11 + i] = le32(key, 16 + 4 * i)
            x[6 + i] = le32(input, 4 * i)
        }
        fun qr(a: Int, b: Int, cc: Int, d: Int) {
            x[b] = x[b] xor (x[a] + x[d]).rotateLeft(7)
            x[cc] = x[cc] xor (x[b] + x[a]).rotateLeft(9)
            x[d] = x[d] xor (x[cc] + x[b]).rotateLeft(13)
            x[a] = x[a] xor (x[d] + x[cc]).rotateLeft(18)
        }
        repeat(10) {
            qr(0, 4, 8, 12); qr(5, 9, 13, 1); qr(10, 14, 2, 6); qr(15, 3, 7, 11)
            qr(0, 1, 2, 3); qr(5, 6, 7, 4); qr(10, 11, 8, 9); qr(15, 12, 13, 14)
        }
        val out = ByteArray(32)
        intArrayOf(0, 5, 10, 15, 6, 7, 8, 9).forEachIndexed { i, w -> putLe32(out, 4 * i, x[w]) }
        return out
    }

    private fun le32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)

    private fun putLe32(b: ByteArray, o: Int, v: Int) {
        for (i in 0 until 4) b[o + i] = (v ushr (8 * i)).toByte()
    }
}
