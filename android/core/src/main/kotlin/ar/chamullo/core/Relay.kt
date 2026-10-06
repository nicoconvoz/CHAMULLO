package ar.chamullo.core

import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * El relé: the small cloud of the Internet bridge (Discovery & Routing §11.1). A directory of bridges by manzana and a
 * mailbox per bridge. Every request that speaks for someone is signed with that someone's key, so nobody reads another's
 * mailbox or registers in another's name. Frames stay sealed: the relé sees what any carrier sees.
 */
object Relay {
    const val ID = "X-Chamullo-Id"
    const val TS = "X-Chamullo-Ts"
    const val SIG = "X-Chamullo-Sig"
    /** A signed request is good for this long, either way: clocks of phones are not perfect. */
    const val CLOCK_SKEW_MS = 5 * 60_000L
    const val MAX_FRAME = 65_536

    fun signingBody(method: String, path: String, ts: Long, body: ByteArray): ByteArray =
        "$method $path\n$ts\n".toByteArray() + Crypto.hash(body)

    fun sign(me: Identity, method: String, path: String, ts: Long, body: ByteArray): ByteArray =
        me.sign("RELAY", signingBody(method, path, ts, body))

    fun verify(id: ByteArray, method: String, path: String, ts: Long, body: ByteArray, sig: ByteArray, now: Long): Boolean =
        kotlin.math.abs(now - ts) <= CLOCK_SKEW_MS && Identity.verify(id, "RELAY", signingBody(method, path, ts, body), sig)

    /**
     * The relays published on CHAMULLO's page (relays.txt): one address per line, http or https; comments (#), blanks,
     * junk and repeats are skipped. Phones read it on their own: nobody types an address.
     */
    fun parseList(text: String): List<String> = text.lines().map { it.trim().trimEnd('/') }
        .filter { it.startsWith("https://") || it.startsWith("http://") || it.startsWith("wss://") || it.startsWith("ws://") }.distinct()

    /** Mailbox contents: frames one after the other, each with its length first. */
    fun pack(frames: List<ByteArray>): ByteArray = Writer().apply { for (f in frames) varint(f.size.toLong()).raw(f) }.bytes()

    fun unpack(bytes: ByteArray): List<ByteArray> { val r = Reader(bytes); return buildList { while (r.remaining > 0) add(r.take(r.varint().toInt())) } }
}

/**
 * A phone that lends its Internet, talking to a relé at [baseUrl]. The node calls [peersIn] and [send] from its own
 * thread, so nothing here blocks: the directory answers from a cache that refreshes in the background (a miss returns
 * empty now and the node tries again later), and frames leave from a queue. Frames for me arrive through [onFrame].
 */
class RelayBridge(private val baseUrl: String, val identity: Identity, private val onFrame: (ByteArray) -> Unit) : Bridge {
    @Volatile private var running = false
    @Volatile private var zone: Zone? = null
    private val outgoing = LinkedBlockingQueue<Pair<ByteArray, ByteArray>>(500)
    private val directory = ConcurrentHashMap<Zone, Pair<List<ByteArray>, Long>>()
    private val asked = ConcurrentHashMap.newKeySet<Zone>()
    private val threads = mutableListOf<Thread>()
    @Volatile var lastError: String? = null; private set
    @Volatile var sentBytes = 0L; private set

    /** Where I am: the relé only learns my manzana. */
    fun place(cell: Zone) { zone = cell.up(Zone.MANZANA) }

    fun start() {
        running = true
        threads += Thread { while (running) { runCatching { register() }.onFailure { lastError = it.message }; nap(REGISTER_MS) } }
        threads += Thread { while (running) { runCatching { poll() }.onFailure { lastError = it.message; nap(2_000) } } }
        threads += Thread { while (running) { val (to, f) = outgoing.poll(1, TimeUnit.SECONDS) ?: continue; runCatching { post("/send?to=${to.toHex()}", f) }.onFailure { lastError = it.message } } }
        threads.forEach { it.isDaemon = true; it.start() }
    }

    fun stop() { running = false; threads.forEach { it.interrupt() }; threads.clear() }

    override fun peersIn(zone: Zone): List<ByteArray> {
        val hit = directory[zone]
        if (hit == null || System.currentTimeMillis() - hit.second > DIRECTORY_MS) {
            if (asked.add(zone)) Thread { runCatching { fetchPeers(zone) }.onFailure { lastError = it.message }; asked.remove(zone) }.apply { isDaemon = true }.start()
        }
        return hit?.first ?: emptyList()
    }

    override fun send(to: ByteArray, frame: ByteArray) {
        if (frame.size <= Relay.MAX_FRAME && outgoing.offer(to to frame)) sentBytes += frame.size
    }

    private fun register() { zone?.let { post("/register", it.encode()) } }

    private fun fetchPeers(z: Zone) {
        val c = open("GET", "/peers?zone=${z.encode().toHex()}", ByteArray(0), signed = false)
        val ids = c.inputStream.use { String(it.readBytes()) }.lines().filter { it.length == 64 }.map { hex(it) }
        directory[z] = ids to System.currentTimeMillis()
    }

    private fun poll() {
        val c = open("GET", "/inbox", ByteArray(0), signed = true)
        c.readTimeout = 40_000
        if (c.responseCode != 200) { lastError = "buzón: ${c.responseCode}"; nap(5_000); return }
        val bytes = c.inputStream.use { it.readBytes() }
        Relay.unpack(bytes).forEach(onFrame)
    }

    private fun post(path: String, body: ByteArray) {
        val c = open("POST", path, body, signed = true)
        c.doOutput = true
        c.outputStream.use { it.write(body) }
        if (c.responseCode != 200) lastError = "$path: ${c.responseCode}"
        c.inputStream.use { it.readBytes() }
    }

    private fun open(method: String, path: String, body: ByteArray, signed: Boolean): HttpURLConnection {
        val c = URI(baseUrl.trimEnd('/') + path).toURL().openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        if (signed) {
            val ts = System.currentTimeMillis()
            c.setRequestProperty(Relay.ID, identity.nodeId.toHex())
            c.setRequestProperty(Relay.TS, ts.toString())
            c.setRequestProperty(Relay.SIG, Relay.sign(identity, method, path, ts, body).toHex())
        }
        return c
    }

    private fun nap(ms: Long) = runCatching { Thread.sleep(ms) }

    companion object {
        const val REGISTER_MS = 60_000L
        const val DIRECTORY_MS = 60_000L
    }
}

