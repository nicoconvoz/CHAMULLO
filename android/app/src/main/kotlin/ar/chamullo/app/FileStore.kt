package ar.chamullo.app

import android.content.Context
import ar.chamullo.core.Card
import ar.chamullo.core.Message
import ar.chamullo.core.MessageState
import ar.chamullo.core.PlazaLine
import ar.chamullo.core.Store
import ar.chamullo.core.hex
import ar.chamullo.core.toHex
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Contacts and messages in one small JSON file in the app's private storage. */
class FileStore(context: Context) : Store {
    private val file = File(context.filesDir, "store.json")
    private val book = File(context.filesDir, "ledger.bin") // the ledger's pages, appended one after the other

    @Synchronized override fun appendPage(page: ByteArray) {
        runCatching { book.appendBytes(ar.chamullo.core.Writer().varint(page.size.toLong()).raw(page).bytes()) }
    }

    @Synchronized override fun replacePages(pages: List<ByteArray>) {
        runCatching { book.writeBytes(ar.chamullo.core.Writer().apply { for (p in pages) varint(p.size.toLong()).raw(p) }.bytes()) }
    }

    @Synchronized override fun loadPages(): List<ByteArray> = runCatching {
        val r = ar.chamullo.core.Reader(book.readBytes())
        buildList { while (r.remaining > 0) add(r.take(r.varint().toInt())) }
    }.getOrDefault(emptyList())
    private val contacts = LinkedHashMap<String, Card>()
    private val messages = mutableListOf<Message>()
    private var plaza = mutableListOf<PlazaLine>()
    private var pockets = listOf<ByteArray>()

    init {
        runCatching {
            val j = JSONObject(file.readText())
            val cs = j.getJSONArray("contacts")
            for (i in 0 until cs.length()) Card.decode(hex(cs.getString(i))).let { contacts[it.nodeId.toHex()] = it }
            val ms = j.getJSONArray("messages")
            for (i in 0 until ms.length()) ms.getJSONObject(i).let {
                val media = it.optJSONObject("media")?.let { m -> ar.chamullo.core.MediaRef(m.getInt("kind"), m.getString("name"), m.getString("mime"), m.getInt("size")) }
                messages += Message(it.getString("peer"), it.getBoolean("mine"), it.getString("text"), it.getLong("ts"), it.getString("msgId"), MessageState.valueOf(it.getString("state")), media)
            }
            val pk = j.optJSONArray("pockets") ?: JSONArray()
            pockets = List(pk.length()) { hex(pk.getString(it)) }
            val ps = j.optJSONArray("plaza") ?: JSONArray()
            for (i in 0 until ps.length()) ps.getJSONObject(i).let {
                val heard = it.getJSONArray("heardBy")
                plaza += PlazaLine(it.getString("id"), it.getString("name"), it.getString("text"), it.getLong("ts"), it.getBoolean("mine"), List(heard.length()) { k -> heard.getString(k) })
            }
        }
    }

    @Synchronized override fun savePlaza(lines: List<PlazaLine>) { plaza = lines.toMutableList(); flush() }
    @Synchronized override fun loadPlaza() = plaza.toList()
    @Synchronized override fun savePockets(envelopes: List<ByteArray>) { pockets = envelopes; flush() }
    @Synchronized override fun loadPockets() = pockets
    @Synchronized override fun saveContact(card: Card) { contacts[card.nodeId.toHex()] = card; flush() }
    @Synchronized override fun contacts() = contacts.values.toList()
    @Synchronized override fun deleteContact(nodeId: ByteArray) { contacts.remove(nodeId.toHex()); flush() }

    // Attachments live as files beside the store, one per message id: the chat list stays small.
    private val mediaDir = File(context.filesDir, "media").apply { mkdirs() }
    override fun saveMedia(msgId: String, bytes: ByteArray) { runCatching { File(mediaDir, msgId).writeBytes(bytes) } }
    override fun loadMedia(msgId: String): ByteArray? = runCatching { File(mediaDir, msgId).takeIf { it.exists() }?.readBytes() }.getOrNull()
    @Synchronized override fun saveMessage(m: Message) { messages += m; flush() }
    @Synchronized override fun messages(peer: ByteArray) = messages.filter { it.peer == peer.toHex() }
    @Synchronized override fun setState(msgId: String, state: MessageState) {
        val i = messages.indexOfFirst { it.msgId == msgId }
        if (i >= 0) { messages[i] = messages[i].copy(state = state); flush() }
    }

    private fun flush() {
        val j = JSONObject()
            .put("contacts", JSONArray(contacts.values.map { it.encode().toHex() }))
            .put("messages", JSONArray(messages.map { m ->
                JSONObject().put("peer", m.peer).put("mine", m.mine).put("text", m.text).put("ts", m.ts).put("msgId", m.msgId).put("state", m.state.name)
                    .apply { m.media?.let { put("media", JSONObject().put("kind", it.kind).put("name", it.name).put("mime", it.mime).put("size", it.size)) } }
            }))
            .put("pockets", JSONArray(pockets.map { it.toHex() }))
            .put("plaza", JSONArray(plaza.map { JSONObject().put("id", it.id).put("name", it.name).put("text", it.text).put("ts", it.ts).put("mine", it.mine).put("heardBy", JSONArray(it.heardBy)) }))
        val tmp = File(file.parentFile, "store.json.tmp")
        tmp.writeText(j.toString())
        tmp.renameTo(file)
    }
}
