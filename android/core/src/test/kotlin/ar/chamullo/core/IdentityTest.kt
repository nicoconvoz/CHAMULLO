package ar.chamullo.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IdentityTest {
    // Vector computed with tweetnacl and ICEBREAK's word list (Identity §5.3).
    private val phrase = "alma cabo cueva gaviota joya madera nube pato rama sol vela almendra caballo cristal hormiga leon"

    @Test
    fun `a phrase always rebuilds the same seed and node id`() {
        assertEquals("78190236755fbfc645acbec601851989cf5ea9119e06a4a020b9f743a5443ff6", Phrase.seedOf(phrase).toHex())
        assertEquals("1b016330274f866c1444a53e1f44c79cba8a1328c350f6507db03c3739333f53", Identity.fromPhrase(phrase).nodeId.toHex())
    }

    @Test
    fun `a new phrase has 16 known words and a valid checksum`() {
        val p = Phrase.newPhrase()
        assertEquals(16, Phrase.normalize(p).size)
        assertEquals(Phrase.Check.Ok, Phrase.check(p))
    }

    @Test
    fun `typos are caught - accents are forgiven, unknown words and bad checksums are not`() {
        assertEquals(Phrase.Check.Ok, Phrase.check(phrase.replace("leon", "León")))
        assertEquals(Phrase.Check.UnknownWord("leonn"), Phrase.check(phrase.replace("leon", "leonn")))
        assertEquals(Phrase.Check.BadChecksum, Phrase.check(phrase.replace("alma", "agua")))
        assertEquals(Phrase.Check.WrongLength, Phrase.check("alma cabo"))
    }

    @Test
    fun `signatures carry the CHAMULLO domain prefix and a tag`() {
        val id = Identity.fromPhrase(phrase)
        val sig = id.sign("ENV", "hola".toByteArray())
        assertTrue(Identity.verify(id.nodeId, "ENV", "hola".toByteArray(), sig))
        assertFalse(Identity.verify(id.nodeId, "HOP", "hola".toByteArray(), sig), "the same bytes under another tag must not verify")
        assertTrue(Crypto.verify(id.nodeId, "CHAMULLO/1/ENV".toByteArray() + 0 + "hola".toByteArray(), sig))
    }

    @Test
    fun `the tag secret is derived from the seed, so a recovered phone still recognizes its letters`() {
        assertEquals(Identity.fromPhrase(phrase).tagSecret.toHex(), Identity.fromPhrase(phrase).tagSecret.toHex())
        assertNotEquals(Identity.fromPhrase(phrase).tagSecret.toHex(), Identity.fromPhrase(Phrase.newPhrase()).tagSecret.toHex())
    }
}
