// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-CHAMULLO-Commercial
// Copyright (C) 2026 Jesús Nicolás Astorga y RESOURCES OPEN DOORS S.A.S
package ar.chamullo.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Nostr for the Internet bridge (Discovery & Routing §11.2): BIP-340 signatures, NIP-01 events. */
class NostrTest {
    // Official BIP-340 test vectors 0 and 1 (github.com/bitcoin/bips/blob/master/bip-0340/test-vectors.csv).
    @Test
    fun `schnorr signatures match the BIP-340 vectors`() {
        val sk0 = hex("0000000000000000000000000000000000000000000000000000000000000003")
        assertEquals("f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9", Schnorr.publicKey(sk0).toHex())
        assertEquals(
            "e907831f80848d1069a5371b402410364bdf1c5f8307b0084c55f1ce2dca821525f66a4a85ea8b71e482a74f382d2ce5ebeee8fdb2172f477df4900d310536c0",
            Schnorr.sign(sk0, ByteArray(32), ByteArray(32)).toHex()
        )
        val sk1 = hex("b7e151628aed2a6abf7158809cf4f3c762e7160f38b4da56a784d9045190cfef")
        val msg1 = hex("243f6a8885a308d313198a2e03707344a4093822299f31d0082efa98ec4e6c89")
        val aux1 = hex("0000000000000000000000000000000000000000000000000000000000000001")
        assertEquals("dff1d77f2a671c5f36183726db2341be58feae1da2deced843240f7b502ba659", Schnorr.publicKey(sk1).toHex())
        val sig1 = Schnorr.sign(sk1, msg1, aux1)
        assertEquals(
            "6896bd60eeae296db48a229ff71dfe071bde413e6d43f917dc8dcf8c78de33418906d11ac976abccb20b091292bff4ea897efcb639ea871cfa95f6de339e4b0a",
            sig1.toHex()
        )
        assertTrue(Schnorr.verify(Schnorr.publicKey(sk1), msg1, sig1))
        assertFalse(Schnorr.verify(Schnorr.publicKey(sk1), msg1.copyOf().also { it[0] = 1 }, sig1))
    }

    @Test
    fun `the Nostr key comes from the same 16 words, and is not the CHAMULLO key`() {
        val id = Identity.generate("Ana")
        assertEquals(Nostr.secretKey(id).toHex(), Nostr.secretKey(Identity.fromSeed(id.seed)).toHex())
        assertEquals(32, Nostr.publicKey(id).size)
        assertFalse(Nostr.publicKey(id).contentEquals(id.nodeId))
    }

    @Test
    fun `an event has the NIP-01 id and a signature anyone can check`() {
        val id = Identity.generate("Ana")
        val e = Nostr.event(id, 4078, listOf(listOf("p", "ab"), listOf("t", "chamullo")), "hola \"Capitán\"\n", 1_700_000_000)
        val serial = "[0,\"${e.pubkey}\",1700000000,4078,[[\"p\",\"ab\"],[\"t\",\"chamullo\"]],\"hola \\\"Capitán\\\"\\n\"]"
        assertEquals(Crypto.sha256(serial.toByteArray()).toHex(), e.id)
        assertTrue(e.verify())
        val back = Nostr.Event.fromJson(Json.parse(e.toJson()) as Map<*, *>)
        assertEquals(e.id, back.id)
        assertTrue(back.verify())
        assertFalse(Nostr.Event(back.id, back.pubkey, back.createdAt, back.kind, back.tags, "otro", back.sig).verify(), "touching the content breaks it")
    }

    @Test
    fun `json reads what relays send`() {
        val v = Json.parse("""["EVENT","sub1",{"a":[1,2.5,-3],"b":"\u00e1\n","c":true,"d":null}]""") as List<*>
        assertEquals("EVENT", v[0])
        val o = v[2] as Map<*, *>
        assertEquals(listOf(1L, 2.5, -3L), o["a"])
        assertEquals("á\n", o["b"])
        assertEquals(true, o["c"])
        assertTrue(o.containsKey("d") && o["d"] == null)
    }
}
