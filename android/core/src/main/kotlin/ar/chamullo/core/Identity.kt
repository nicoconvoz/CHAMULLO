// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-CHAMULLO-Commercial
// Copyright (C) 2026 Jesús Nicolás Astorga y RESOURCES OPEN DOORS S.A.S
package ar.chamullo.core

/**
 * A node is its Ed25519 key pair (Identity §2, §4). The box key and the tag secret are derived from the same seed,
 * so the 16 words restore everything: the id, the ability to open sealed letters and to recognize one's own tags.
 */
class Identity private constructor(val seed: ByteArray, val name: String) {
    val nodeId: ByteArray = Crypto.signPublicKey(seed)
    val boxSecret: ByteArray = Crypto.boxSecretFromSignSeed(seed)
    val boxPublic: ByteArray = Crypto.boxPublicKey(boxSecret)
    val tagSecret: ByteArray = Crypto.hash("CHAMULLO/1/TAGSECRET".toByteArray() + 0.toByte() + seed)

    fun sign(tag: String, body: ByteArray): ByteArray = Crypto.sign(seed, signingInput(tag, body))

    fun card(now: Long = 0, zone: Zone? = null): Card = Card.of(this, now, zone)

    fun signer() = Signer(nodeId, seed)

    fun withName(newName: String) = Identity(seed, newName)

    companion object {
        // Identity §6: "CHAMULLO/1/" || tag || 0x00 || body
        fun signingInput(tag: String, body: ByteArray): ByteArray = "CHAMULLO/1/$tag".toByteArray() + 0.toByte() + body

        fun verify(publicKey: ByteArray, tag: String, body: ByteArray, signature: ByteArray): Boolean =
            Crypto.verify(publicKey, signingInput(tag, body), signature)

        fun fromPhrase(phrase: String, name: String = ""): Identity = Identity(Phrase.seedOf(phrase), name)

        fun fromSeed(seed: ByteArray, name: String = ""): Identity = Identity(seed.copyOf(), name)

        fun generate(name: String = ""): Identity = Identity(Crypto.randomBytes(32), name)
    }
}
