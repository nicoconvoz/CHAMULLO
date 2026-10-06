package ar.chamullo.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Vectors produced with tweetnacl 1.0.3, the library ICEBREAK's prototype uses: CHAMULLO must be byte-compatible.
class CryptoTest {
    private val seed = hex("0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20")
    private val nonce = hex("000102030405060708090a0b0c0d0e0f1011121314151617")

    @Test
    fun `ed25519 public key from seed matches tweetnacl`() {
        assertEquals("79b5562e8fe654f94078b112e8a98ba7901f853ae695bed7e0e3910bad049664", Crypto.signPublicKey(seed).toHex())
    }

    @Test
    fun `ed25519 detached signature matches tweetnacl and verifies`() {
        val msg = hex("4348414d554c4c4f2f312f454e5600686f6c61206361706974616e")
        val sig = Crypto.sign(seed, msg)
        assertEquals(
            "05f92c07d241e1b6bb7a26b7a85cf778fce051eed8a53a2b27ada57eeade5a65b863d46134da196155208e26f6fa64c8d6198298f60669a6d28c4fe38bf5090f",
            sig.toHex()
        )
        assertTrue(Crypto.verify(Crypto.signPublicKey(seed), msg, sig))
        msg[0] = (msg[0] + 1).toByte()
        assertFalse(Crypto.verify(Crypto.signPublicKey(seed), msg, sig))
    }

    @Test
    fun `x25519 public key matches tweetnacl box keys`() {
        assertEquals("d06a9f82b0d8d3cf74c8f68dee92fd99064de174d8a30cfe63aa19ce304c345a", Crypto.boxPublicKey(hex("000306090c0f1215181b1e2124272a2d303336393c3f4245484b4e5154575a5d")).toHex())
    }

    @Test
    fun `box matches tweetnacl and opens only for the right keys`() {
        val sk1 = hex("000306090c0f1215181b1e2124272a2d303336393c3f4245484b4e5154575a5d")
        val sk2 = hex("fff8f1eae3dcd5cec7c0b9b2aba49d968f88817a736c655e575049423b342d26")
        val box = Crypto.box("carta secreta".toByteArray(), nonce, Crypto.boxPublicKey(sk2), sk1)
        assertEquals("74ebecd93060a11d09501c6a4daa1ccd8f209c13632e6f7d156e522c59", box.toHex())
        assertArrayEquals("carta secreta".toByteArray(), Crypto.boxOpen(box, nonce, Crypto.boxPublicKey(sk1), sk2))
        box[0] = (box[0] + 1).toByte()
        assertNull(Crypto.boxOpen(box, nonce, Crypto.boxPublicKey(sk1), sk2))
    }

    @Test
    fun `secretbox matches tweetnacl`() {
        val key = ByteArray(32) { 7 }
        val sealed = Crypto.secretbox("bolsillo".toByteArray(), nonce, key)
        assertEquals("52b5ab4a2c3f6115c5dba3451310bbe0f871853ce2190eaa", sealed.toHex())
        assertArrayEquals("bolsillo".toByteArray(), Crypto.secretboxOpen(sealed, nonce, key))
    }

    @Test
    fun `hash is sha512 truncated to 32 bytes`() {
        assertEquals("ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a", Crypto.hash("abc".toByteArray()).toHex())
    }

    @Test
    fun `box key derived from a signing seed equals the x25519 form of the ed25519 key`() {
        // tweetnacl/pinenacl convert an Ed25519 secret to X25519 as clamp(sha512(seed)[0..32]); ICEBREAK seals with that key.
        val x = Crypto.boxSecretFromSignSeed(seed)
        assertEquals("70788f1a0cea001a2631dae5d05dbd062008d5b30f50b9e29beb2a7822289044", x.toHex())
        assertEquals("4a3807d064d077181cc070989e76891d20dca5559548dc2c77c1a50273882b38", Crypto.boxPublicKey(x).toHex())
    }

    @Test
    fun `remembered signatures still reject a forged message or signature`() {
        val seed = Crypto.randomBytes(32); val pk = Crypto.signPublicKey(seed)
        val msg = "carta".toByteArray(); val sig = Crypto.sign(seed, msg)
        Crypto.rememberSignatures(true)
        try {
            assertTrue(Crypto.verify(pk, msg, sig))
            assertTrue(Crypto.verify(pk, msg, sig)) // second time comes from memory
            assertFalse(Crypto.verify(pk, "cartas".toByteArray(), sig))
            assertFalse(Crypto.verify(pk, msg, sig.copyOf().also { it[0] = (it[0] + 1).toByte() }))
            assertFalse(Crypto.verify(Crypto.signPublicKey(Crypto.randomBytes(32)), msg, sig))
            assertEquals(4, Crypto.rememberedSignatures())
        } finally {
            Crypto.rememberSignatures(false)
        }
        assertEquals(0, Crypto.rememberedSignatures())
    }
}
