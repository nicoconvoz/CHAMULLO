package ar.chamullo.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import ar.chamullo.core.Crypto
import ar.chamullo.core.DataPlan
import ar.chamullo.core.DataReceipt
import ar.chamullo.core.Identity
import ar.chamullo.core.LendSession
import ar.chamullo.core.Reader
import ar.chamullo.core.Shop
import ar.chamullo.core.Tunnel
import ar.chamullo.core.TunnelMsg
import ar.chamullo.core.Usage
import ar.chamullo.core.Writer
import ar.chamullo.core.toHex
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * El túnel de datos (Discovery & Routing §11.3): the two sides of browsing through a neighbor's Internet.
 *
 * - **Buyer** (while [Hub.browsing]): asks its island "who lends?", picks the first lender that answers, and opens
 *   streams for [LocalProxy]. It counts the bytes and signs a receipt every [Tunnel.RECEIPT_EVERY].
 * - **Lender** (with "Prestar Internet" on and Internet at hand): answers, opens the sockets to the web for the buyer,
 *   serves on [Tunnel.CREDIT] and sends the best receipt of each session to the pueblo's ledger.
 *
 * Every piece travels as a LINK 18 frame over the island's pipes ([WifiIslands.sendDirect]), sealed for the other end.
 */
class DataTunnel(private val context: Context, private val identity: Identity, private val islands: WifiIslands) {
    @Volatile private var running = true

    fun start() {
        Thread {
            while (running) { runCatching { tick() }; Thread.sleep(1_000) }
        }.apply { isDaemon = true; name = "tunnel" }.start()
    }

    fun stop() {
        running = false
        lender = null
        buyerStreams.values.forEach { it.end(false) }; buyerStreams.clear()
        lent.values.forEach { it.close() }; lent.clear()
        flushReceipts(force = true)
    }

    private fun send(m: TunnelMsg) = islands.sendDirect(m.encode(), m.to)

    fun onMsg(m: TunnelMsg) {
        when (m.op) {
            TunnelMsg.ASK -> onAsk(m)
            TunnelMsg.LEND -> onLend(m)
            else -> {
                val id = m.from.toHex()
                if (lender?.id == id) onFromLender(m) else buyers[id]?.let { onFromBuyer(it, m) }
            }
        }
    }

    private var lastTick = 0L
    private fun tick() {
        val now = System.currentTimeMillis()
        if (Hub.browsing) {
            if (now - packsAt > PACKS_MS) refreshPacks()
            // Bought more since this lender was picked: start a new session with the new packs.
            lender?.let { if (it.usage.left() <= 0 && buyerStreams.isEmpty() && DataPlan.left(packs, Settings.dataUsed(context)) > 0) lender = null }
            val l = lender
            if (l == null || now - l.heardAt > LENDER_SILENT_MS && buyerStreams.isEmpty()) {
                if (l != null) { status = "el que prestaba se fue: busco otro"; lender = null }
                if (now - askedAt > ASK_MS) { askedAt = now; send(TunnelMsg.ask(identity, now)); if (lender == null) status = "busco quién preste Internet en tu isla…" }
            }
        } else if (lender != null) { lender = null; buyerStreams.values.forEach { it.end(true) }; buyerStreams.clear() }
        if (now - lastTick > COLLECT_MS) { lastTick = now; flushReceipts(force = false) }
        // Idle lenders' sessions: collect and forget.
        for ((k, b) in buyers.entries.toList()) if (now - b.heardAt > BUYER_IDLE_MS && lent.values.none { it.buyer === b }) {
            b.sessions.values.forEach { collect(it) }; buyers.remove(k)
        }
    }

    /* ======================= buyer ======================= */

    class Lender internal constructor(val id: String, val nodeId: ByteArray, val key: ByteArray, val session: ByteArray, val usage: Usage) {
        @Volatile var heardAt = System.currentTimeMillis()
    }

    @Volatile private var lender: Lender? = null
    @Volatile var status: String = "apagado"; private set
    private var askedAt = 0L
    private var packsAt = 0L
    @Volatile private var packs: List<DataPlan.Pack> = emptyList()
    private val nextStream = AtomicLong(1)
    private val buyerStreams = ConcurrentHashMap<Long, BuyerStream>()

    /** Megabytes left of the packs bought, after what was used. */
    fun left(): Long = lender?.usage?.left() ?: DataPlan.left(packs, Settings.dataUsed(context))

    fun packs(): List<DataPlan.Pack> = packs

    /** Who I browse through, if anyone. */
    fun lenderName(): String? = lender?.let { l -> Hub.node?.neighbors()?.firstOrNull { it.nodeId.toHex() == l.id }?.beacon?.name?.ifBlank { null } ?: l.id.take(6) }

    private fun refreshPacks() {
        packsAt = System.currentTimeMillis()
        val catalog = Shop.parseOrDefault(Settings.catalog(context))
        Hub.post { n -> n.ledger?.let { packs = Shop.packs(Shop.purchases(it, identity.nodeId, catalog)) } }
    }

    private fun onLend(m: TunnelMsg) {
        // Without my packs read from the ledger yet, wait: the next "who lends?" comes in a few seconds.
        if (!Hub.browsing || lender != null || !m.to.contentEquals(identity.nodeId) || packs.isEmpty()) return
        val key = m.boxKey() ?: return
        val session = Crypto.randomBytes(DataReceipt.SESSION)
        lender = Lender(m.from.toHex(), m.from, Crypto.boxShared(key, identity.boxSecret), session,
            Usage(identity, m.from, session, packs, Settings.dataUsed(context)))
        status = "navegás con el Internet de ${lenderName()}"
        FieldLog.add("TÚNEL", "navego por el Internet de ${lenderName()}")
    }

    /** Opens a stream to [host]:[port] through the lender; null if nobody lends, the data ran out or the lender said no. */
    fun open(host: String, port: Int): BuyerStream? {
        val l = lender ?: return null
        if (l.usage.left() <= 0) { status = "se te acabaron los datos: comprá más en la Tienda"; return null }
        val s = BuyerStream(nextStream.getAndIncrement(), l)
        buyerStreams[s.id] = s
        send(TunnelMsg.sealWith(l.key, identity.nodeId, l.nodeId, TunnelMsg.OPEN, s.id, l.session + "$host:$port".toByteArray()))
        if (!s.opened.await(OPEN_WAIT_S, TimeUnit.SECONDS) || !s.ok) { buyerStreams.remove(s.id); return null }
        return s
    }

    private fun onFromLender(m: TunnelMsg) {
        val l = lender ?: return
        val data = m.openWith(l.key) ?: return
        l.heardAt = System.currentTimeMillis()
        val s = buyerStreams[m.stream] ?: return
        when (m.op) {
            TunnelMsg.OPENED -> { s.ok = data.firstOrNull()?.toInt() == 1; s.opened.countDown() }
            TunnelMsg.DATA -> { s.inbox.offer(data); used(l, data.size) }
            TunnelMsg.ACK -> s.acked(Reader(data).u64())
            TunnelMsg.CLOSE -> s.end(false)
        }
    }

    private fun used(l: Lender, n: Int) {
        val r = l.usage.used(n.toLong(), System.currentTimeMillis())
        Settings.addDataUsed(context, n.toLong())
        if (r != null) send(TunnelMsg.sealWith(l.key, identity.nodeId, l.nodeId, TunnelMsg.RECEIPT, 0, r.encode()))
    }

    /** One connection through the tunnel, seen from the buyer: [input] to read what comes, [write] to send. */
    inner class BuyerStream(val id: Long, private val l: Lender) {
        val opened = CountDownLatch(1)
        @Volatile var ok = false
        val inbox = LinkedBlockingQueue<ByteArray>()
        private val window = Window()
        @Volatile private var closed = false

        val input: InputStream = object : InputStream() {
            private var cur = ByteArray(0); private var pos = 0; private var consumed = 0L; private var ackedAt = 0L
            override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xff }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                while (pos >= cur.size) {
                    cur = inbox.poll(READ_IDLE_S, TimeUnit.SECONDS) ?: return -1
                    pos = 0
                    if (cur.isEmpty()) return -1 // the end
                }
                val n = minOf(len, cur.size - pos)
                System.arraycopy(cur, pos, b, off, n); pos += n; consumed += n
                if (consumed - ackedAt >= Tunnel.WINDOW / 4) { ackedAt = consumed; sendOp(TunnelMsg.ACK, Writer().u64(consumed).bytes()) }
                return n
            }
        }

        fun write(b: ByteArray, off: Int, len: Int) {
            var o = off
            while (o < off + len && !closed) {
                val n = minOf(Tunnel.CHUNK, off + len - o)
                if (!window.reserve(n)) return
                sendOp(TunnelMsg.DATA, b.copyOfRange(o, o + n))
                used(l, n)
                o += n
            }
        }

        fun acked(total: Long) = window.acked(total)

        private fun sendOp(op: Int, data: ByteArray) = send(TunnelMsg.sealWith(l.key, identity.nodeId, l.nodeId, op, id, data))

        /** Closes the stream; [tell] the lender so it closes its socket too. */
        fun end(tell: Boolean) {
            if (closed) return
            closed = true
            buyerStreams.remove(id)
            if (tell) sendOp(TunnelMsg.CLOSE, ByteArray(0))
            inbox.offer(ByteArray(0)); window.close(); opened.countDown()
        }
    }

    /* ======================= lender ======================= */

    private class Buyer(val nodeId: ByteArray, val key: ByteArray) {
        @Volatile var heardAt = System.currentTimeMillis()
        val sessions = ConcurrentHashMap<String, LendSession>()
        val collected = ConcurrentHashMap<String, Long>()
    }

    private val buyers = ConcurrentHashMap<String, Buyer>()
    private val lent = ConcurrentHashMap<String, LentStream>()
    @Volatile var lentBytes = 0L; private set

    private fun lending() = Settings.lendInternet(context) && internet() != null

    private fun onAsk(m: TunnelMsg) {
        if (!lending() || m.from.contentEquals(identity.nodeId)) return
        val key = m.boxKey() ?: return
        buyers.getOrPut(m.from.toHex()) { Buyer(m.from, Crypto.boxShared(key, identity.boxSecret)) }.heardAt = System.currentTimeMillis()
        send(TunnelMsg.lend(identity, m.from, System.currentTimeMillis()))
    }

    private fun onFromBuyer(b: Buyer, m: TunnelMsg) {
        val data = m.openWith(b.key) ?: return
        b.heardAt = System.currentTimeMillis()
        val key = b.nodeId.toHex() + ":" + m.stream
        when (m.op) {
            TunnelMsg.OPEN -> if (data.size > DataReceipt.SESSION) {
                val session = data.copyOfRange(0, DataReceipt.SESSION)
                val target = String(data, DataReceipt.SESSION, data.size - DataReceipt.SESSION)
                val ls = b.sessions.getOrPut(session.toHex()) { LendSession(identity.nodeId, b.nodeId, session) }
                Thread { openForBuyer(b, ls, m.stream, key, target) }.apply { isDaemon = true }.start()
            }
            TunnelMsg.DATA -> lent[key]?.fromBuyer(data)
            TunnelMsg.ACK -> lent[key]?.window?.acked(Reader(data).u64())
            TunnelMsg.CLOSE -> lent.remove(key)?.close()
            TunnelMsg.RECEIPT -> DataReceipt.decodeOrNull(data)?.let { r -> b.sessions[r.session.toHex()]?.let { synchronized(it) { it.onReceipt(r) } } }
        }
    }

    private fun openForBuyer(b: Buyer, ls: LendSession, stream: Long, key: String, target: String) {
        fun answer(ok: Boolean) = send(TunnelMsg.sealWith(b.key, identity.nodeId, b.nodeId, TunnelMsg.OPENED, stream, byteArrayOf(if (ok) 1 else 0)))
        val net = internet()
        val host = target.substringBeforeLast(':'); val port = target.substringAfterLast(':').toIntOrNull()
        if (net == null || port == null || !ls.mayServe()) { answer(false); return }
        val socket = runCatching {
            val address = net.getAllByName(host).firstOrNull { Tunnel.allowed(it.hostAddress ?: "", port) } ?: error("dirección no permitida")
            net.socketFactory.createSocket().apply { connect(InetSocketAddress(address, port), CONNECT_MS); tcpNoDelay = true }
        }.getOrNull()
        if (socket == null) { answer(false); return }
        val s = LentStream(b, ls, stream, socket)
        lent[key] = s
        answer(true)
        s.pump()
        lent.remove(key)
    }

    /** One connection to the web on behalf of a buyer. */
    private inner class LentStream(val buyer: Buyer, private val ls: LendSession, private val id: Long, private val socket: Socket) {
        val window = Window()
        private val out: OutputStream = socket.getOutputStream()
        private var consumed = 0L; private var ackedAt = 0L

        private fun sendOp(op: Int, data: ByteArray) = send(TunnelMsg.sealWith(buyer.key, identity.nodeId, buyer.nodeId, op, id, data))

        private fun served(n: Int) { synchronized(ls) { ls.served(n.toLong()) }; lentBytes += n }

        // Waits for a receipt when the credit is used up; gives up after a while.
        private fun credit(): Boolean {
            val until = System.currentTimeMillis() + RECEIPT_WAIT_MS
            while (!synchronized(ls) { ls.mayServe() }) { if (System.currentTimeMillis() > until || socket.isClosed) return false; Thread.sleep(100) }
            return true
        }

        fun fromBuyer(data: ByteArray) {
            runCatching { out.write(data); out.flush() }.onFailure { close() }
            served(data.size)
            consumed += data.size
            if (consumed - ackedAt >= Tunnel.WINDOW / 4) { ackedAt = consumed; sendOp(TunnelMsg.ACK, Writer().u64(consumed).bytes()) }
        }

        fun pump() {
            val buf = ByteArray(Tunnel.CHUNK)
            runCatching {
                val input = socket.getInputStream()
                while (true) {
                    val n = input.read(buf)
                    if (n < 0 || !credit() || !window.reserve(n)) break
                    sendOp(TunnelMsg.DATA, buf.copyOf(n))
                    served(n)
                }
            }
            sendOp(TunnelMsg.CLOSE, ByteArray(0))
            close()
        }

        fun close() { runCatching { socket.close() }; window.close() }
    }

    private fun collect(ls: LendSession) {
        val best = synchronized(ls) { ls.best } ?: return
        val b = buyers[best.buyer.toHex()] ?: return
        if ((b.collected[best.session.toHex()] ?: -1) >= best.lucas || best.lucas <= 0) return
        b.collected[best.session.toHex()] = best.lucas
        Hub.post { it.submitData(best) }
        FieldLog.add("TÚNEL", "cobro ${best.lucas} Lucas por ${best.bytes / 1024} KB prestados")
    }

    private fun flushReceipts(force: Boolean) {
        for (b in buyers.values) for (ls in b.sessions.values) if (force || lent.values.none { it.buyer === b }) collect(ls)
    }

    /** A network with real Internet that is not my own VPN: what a lender lends. */
    private fun internet(): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        return cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.let {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                    !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            } == true
        }
    }

    /** Bytes in flight one way: the sender waits while the other end has not taken [Tunnel.WINDOW] of them. */
    private class Window {
        private val lock = Object()
        private var sent = 0L; private var acked = 0L; private var closed = false

        fun reserve(n: Int): Boolean = synchronized(lock) {
            val until = System.currentTimeMillis() + ACK_WAIT_MS
            while (!closed && sent - acked + n > Tunnel.WINDOW) {
                val wait = until - System.currentTimeMillis()
                if (wait <= 0) return false
                lock.wait(wait)
            }
            if (closed) return false
            sent += n
            true
        }

        fun acked(total: Long) = synchronized(lock) { if (total > acked) { acked = total; lock.notifyAll() } }

        fun close() = synchronized(lock) { closed = true; lock.notifyAll() }
    }

    companion object {
        const val ASK_MS = 5_000L
        const val PACKS_MS = 10_000L
        const val LENDER_SILENT_MS = 60_000L
        const val BUYER_IDLE_MS = 60_000L
        const val COLLECT_MS = 5 * 60_000L
        const val OPEN_WAIT_S = 20L
        const val READ_IDLE_S = 120L
        const val CONNECT_MS = 10_000
        const val RECEIPT_WAIT_MS = 20_000L
        const val ACK_WAIT_MS = 60_000L
    }
}
