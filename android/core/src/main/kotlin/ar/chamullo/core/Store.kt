// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-CHAMULLO-Commercial
// Copyright (C) 2026 Jesús Nicolás Astorga y RESOURCES OPEN DOORS S.A.S
package ar.chamullo.core

enum class MessageState { SENT, DELIVERED, RECEIVED }

/** [media]: an attachment, whose bytes the store keeps under [msgId] (see [Store.saveMedia]). */
data class Message(val peer: String, val mine: Boolean, val text: String, val ts: Long, val msgId: String, val state: MessageState, val media: MediaRef? = null)

/** What a node keeps between runs. The Android app persists it; tests use [MemoryStore]. */
interface Store {
    fun saveContact(card: Card)
    fun contacts(): List<Card>
    fun contact(nodeId: ByteArray): Card? = contacts().firstOrNull { it.nodeId.contentEquals(nodeId) }
    fun deleteContact(nodeId: ByteArray) {}
    fun saveMedia(msgId: String, bytes: ByteArray) {}
    fun loadMedia(msgId: String): ByteArray? = null
    fun saveMessage(m: Message)
    fun messages(peer: ByteArray): List<Message>
    fun setState(msgId: String, state: MessageState)
    fun savePlaza(lines: List<PlazaLine>) {}
    fun loadPlaza(): List<PlazaLine> = emptyList()
    fun savePockets(envelopes: List<ByteArray>) {}
    fun loadPockets(): List<ByteArray> = emptyList()
    fun saveCandies(n: Int) {}
    fun loadCandies(): Int = 0
    /** The ledger's pages, in order (Economy & Governance §9): kept so the book survives a restart. */
    fun appendPage(page: ByteArray) {}
    fun loadPages(): List<ByteArray> = emptyList()
    /** Keeps only these pages: a kept book that broke the rules is not carried forward. */
    fun replacePages(pages: List<ByteArray>) {}
}

/** [keepPages]: false in the twin, where a thousand phones share one process and nobody restarts. */
class MemoryStore(private val keepPages: Boolean = true) : Store {
    private val contacts = LinkedHashMap<String, Card>()
    private val messages = mutableListOf<Message>()
    private var plaza = emptyList<PlazaLine>()

    override fun savePlaza(lines: List<PlazaLine>) { plaza = lines }
    override fun loadPlaza() = plaza
    private var pockets = emptyList<ByteArray>()
    override fun savePockets(envelopes: List<ByteArray>) { pockets = envelopes }
    override fun loadPockets() = pockets
    private var candies = 0
    override fun saveCandies(n: Int) { candies = n }
    override fun loadCandies() = candies
    private val pages = ArrayList<ByteArray>()
    override fun appendPage(page: ByteArray) { if (keepPages) pages += page }
    override fun loadPages() = pages.toList()
    override fun replacePages(pages: List<ByteArray>) { this.pages.clear(); if (keepPages) this.pages += pages }
    override fun saveContact(card: Card) { contacts[card.nodeId.toHex()] = card }
    override fun deleteContact(nodeId: ByteArray) { contacts.remove(nodeId.toHex()) }
    private val media = HashMap<String, ByteArray>()
    override fun saveMedia(msgId: String, bytes: ByteArray) { media[msgId] = bytes }
    override fun loadMedia(msgId: String) = media[msgId]
    override fun contacts() = contacts.values.toList()
    override fun saveMessage(m: Message) { messages += m }
    override fun messages(peer: ByteArray) = messages.filter { it.peer == peer.toHex() }
    override fun setState(msgId: String, state: MessageState) {
        val i = messages.indexOfFirst { it.msgId == msgId }
        if (i >= 0) messages[i] = messages[i].copy(state = state)
    }
}
