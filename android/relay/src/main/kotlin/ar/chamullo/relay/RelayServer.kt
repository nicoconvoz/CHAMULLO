package ar.chamullo.relay

import ar.chamullo.core.Relay
import ar.chamullo.core.Zone
import ar.chamullo.core.hex
import ar.chamullo.core.toHex
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * El relé (Discovery & Routing §11.1): who lends Internet in which manzana, and a mailbox for each of them.
 * Everything lives in memory: a restart only costs a minute, until the bridges register again.
 *
 * - `POST /register` (signed): body is my manzana. Good for [BRIDGE_TTL_MS].
 * - `GET /peers?zone=…` (public): the bridges in that zone, one id per line, a different first one each time; a manzana
 *   with none falls back to its barrio.
 * - `POST /send?to=…` (signed, registered bridges only): a frame for that bridge's mailbox.
 * - `GET /inbox` (signed): waits up to [POLL_MS] for frames and hands them over.
 */
class RelayServer(private val clock: () -> Long = System::currentTimeMillis) {
    private class Registered(val zone: Zone, val at: Long)
    private class Mailbox { val frames = ArrayDeque<Pair<ByteArray, Long>>() }

    private val bridges = ConcurrentHashMap<String, Registered>()
    private val boxes = ConcurrentHashMap<String, Mailbox>()
    private val bytes = ConcurrentHashMap<String, Long>()
    private var turn = 0
    private var http: HttpServer? = null
    val port get() = http?.address?.port ?: 0

    fun start(port: Int) {
        val s = HttpServer.create(InetSocketAddress(port), 0)
        s.executor = Executors.newFixedThreadPool(32)
        s.createContext("/register") { ex -> signed(ex) { id, body -> bridges[id] = Registered(Zone.decode(body), clock()); reply(ex, 200) } }
        s.createContext("/peers") { ex -> peers(ex) }
        s.createContext("/send") { ex -> signed(ex) { id, body -> send(ex, id, body) } }
        s.createContext("/inbox") { ex -> signed(ex) { id, _ -> inbox(ex, id) } }
        s.start()
        http = s
    }

    fun stop() { http?.stop(0); http = null }

    /** How many bridges are registered right now. */
    fun bridges(): Int { forget(); return bridges.size }

    /** Bytes a bridge handed to others: what the economy pays for (Economy & Governance §13). */
    fun bytesFrom(id: ByteArray): Long = bytes[id.toHex()] ?: 0

    private fun forget() {
        val now = clock()
        bridges.values.removeIf { now - it.at > BRIDGE_TTL_MS }
        for (b in boxes.values) synchronized(b) { while (b.frames.isNotEmpty() && now - b.frames.first().second > FRAME_TTL_MS) b.frames.removeFirst() }
    }

    private fun peers(ex: HttpExchange) {
        forget()
        val zone = runCatching { Zone.decode(hex(query(ex)["zone"] ?: "")) }.getOrNull() ?: return reply(ex, 400)
        fun inside(z: Zone) = bridges.entries.filter { z.level >= it.value.zone.level && z.contains(it.value.zone) }.map { it.key }.sorted()
        val found = inside(zone).ifEmpty { if (zone.level < Zone.BARRIO) inside(zone.up(Zone.BARRIO)) else emptyList() }
        val start = if (found.isEmpty()) 0 else synchronized(this) { turn++ } % found.size // spread the load
        reply(ex, 200, (found.drop(start) + found.take(start)).take(MAX_PEERS).joinToString("\n").toByteArray())
    }

    private fun send(ex: HttpExchange, from: String, body: ByteArray) {
        if (!bridges.containsKey(from)) return reply(ex, 403) // only bridges hand frames over: no spam from strangers
        val to = query(ex)["to"]?.takeIf { bridges.containsKey(it) } ?: return reply(ex, 404)
        if (body.isEmpty() || body.size > Relay.MAX_FRAME) return reply(ex, 413)
        bytes.merge(from, body.size.toLong(), Long::plus) // counted before it can be read: the economy never sees less
        val box = boxes.getOrPut(to) { Mailbox() }
        synchronized(box) {
            box.frames.addLast(body to clock())
            while (box.frames.size > MAX_MAILBOX) box.frames.removeFirst()
            (box as Object).notifyAll()
        }
        reply(ex, 200)
    }

    private fun inbox(ex: HttpExchange, id: String) {
        val box = boxes.getOrPut(id) { Mailbox() }
        val frames = synchronized(box) {
            if (box.frames.isEmpty()) (box as Object).wait(POLL_MS)
            buildList { while (box.frames.isNotEmpty()) add(box.frames.removeFirst().first) }
        }
        reply(ex, 200, Relay.pack(frames))
    }

    // Every request that speaks for someone carries that someone's signature over method, path, time and body.
    private fun signed(ex: HttpExchange, then: (id: String, body: ByteArray) -> Unit) {
        val body = ex.requestBody.use { it.readNBytes(Relay.MAX_FRAME + 1) }
        val id = ex.requestHeaders.getFirst(Relay.ID) ?: ""
        val ts = ex.requestHeaders.getFirst(Relay.TS)?.toLongOrNull() ?: 0
        val sig = runCatching { hex(ex.requestHeaders.getFirst(Relay.SIG) ?: "") }.getOrNull() ?: ByteArray(0)
        val ok = id.length == 64 && runCatching {
            Relay.verify(hex(id), ex.requestMethod, ex.requestURI.rawPath + (ex.requestURI.rawQuery?.let { "?$it" } ?: ""), ts, body, sig, clock())
        }.getOrDefault(false)
        if (!ok) return reply(ex, 403)
        runCatching { then(id, body) }.onFailure { reply(ex, 400) }
    }

    private fun query(ex: HttpExchange) = (ex.requestURI.rawQuery ?: "").split('&').filter { '=' in it }
        .associate { it.substringBefore('=') to it.substringAfter('=') }

    private fun reply(ex: HttpExchange, code: Int, body: ByteArray = ByteArray(0)) {
        runCatching {
            ex.sendResponseHeaders(code, if (body.isEmpty()) -1 else body.size.toLong())
            if (body.isNotEmpty()) ex.responseBody.use { it.write(body) } else ex.close()
        }
    }

    companion object {
        const val BRIDGE_TTL_MS = 3 * 60_000L
        const val FRAME_TTL_MS = 60 * 60_000L
        const val POLL_MS = 20_000L
        const val MAX_MAILBOX = 500
        const val MAX_PEERS = 10
    }
}
