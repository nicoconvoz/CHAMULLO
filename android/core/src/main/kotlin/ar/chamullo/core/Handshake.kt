package ar.chamullo.core

/**
 * The secret greeting (Identity §7) for pipes inside an island: each side sends its id and a fresh nonce, then signs
 * both ids and both nonces. Nothing else passes until the other side's signature checks out, so knowing an island's
 * key from its cartel is not enough to pose as someone else, and an old proof cannot be replayed on a new pipe.
 */
class Handshake(private val me: Identity) {
    private val nonce = Crypto.randomBytes(16)
    private var peerId: ByteArray? = null
    private var peerNonce: ByteArray? = null

    /** The other side's node id, once its proof verified. */
    var peer: ByteArray? = null; private set

    fun hello(): ByteArray = HELLO + me.nodeId + nonce

    /** Takes the other side's hello and returns my proof for it (null if it was not a hello). */
    fun onHello(frame: ByteArray): ByteArray? {
        if (!frame.startsWith(HELLO) || frame.size != HELLO.size + 32 + 16) return null
        val id = frame.copyOfRange(HELLO.size, HELLO.size + 32)
        val n = frame.copyOfRange(HELLO.size + 32, frame.size)
        peerId = id; peerNonce = n
        return proofFrame(me.sign("HS", me.nodeId + id + nonce + n)) // signer | other | signer's nonce | other's nonce
    }

    /** Checks the other side's proof: it must sign both ids and both nonces of this very pipe. */
    fun onProof(frame: ByteArray): Boolean {
        if (!frame.startsWith(PROOF) || frame.size != PROOF.size + 64) return false
        val id = peerId ?: return false
        val n = peerNonce ?: return false
        val ok = Identity.verify(id, "HS", id + me.nodeId + n + nonce, frame.copyOfRange(PROOF.size, frame.size))
        if (ok) peer = id
        return ok
    }

    companion object {
        private val HELLO = "CHHS1".toByteArray()
        private val PROOF = "CHHS2".toByteArray()

        fun proofFrame(signature: ByteArray) = PROOF + signature

        fun isHandshake(f: ByteArray) = f.startsWith(HELLO) || f.startsWith(PROOF)

        private fun ByteArray.startsWith(p: ByteArray) = size >= p.size && p.indices.all { this[it] == p[it] }
    }
}
