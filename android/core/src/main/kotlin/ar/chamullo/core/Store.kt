package ar.chamullo.core

enum class MessageState { SENT, DELIVERED, RECEIVED }

data class Message(val peer: String, val mine: Boolean, val text: String, val ts: Long, val msgId: String, val state: MessageState)

/** What a node keeps between runs. The Android app persists it; tests use [MemoryStore]. */
interface Store {
    fun saveContact(card: Card)
    fun contacts(): List<Card>
    fun contact(nodeId: ByteArray): Card? = contacts().firstOrNull { it.nodeId.contentEquals(nodeId) }
    fun saveMessage(m: Message)
    fun messages(peer: ByteArray): List<Message>
    fun setState(msgId: String, state: MessageState)
}

class MemoryStore : Store {
    private val contacts = LinkedHashMap<String, Card>()
    private val messages = mutableListOf<Message>()

    override fun saveContact(card: Card) { contacts[card.nodeId.toHex()] = card }
    override fun contacts() = contacts.values.toList()
    override fun saveMessage(m: Message) { messages += m }
    override fun messages(peer: ByteArray) = messages.filter { it.peer == peer.toHex() }
    override fun setState(msgId: String, state: MessageState) {
        val i = messages.indexOfFirst { it.msgId == msgId }
        if (i >= 0) messages[i] = messages[i].copy(state = state)
    }
}
