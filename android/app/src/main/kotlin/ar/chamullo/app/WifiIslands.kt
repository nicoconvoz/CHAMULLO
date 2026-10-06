package ar.chamullo.app

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import ar.chamullo.core.Cartel
import ar.chamullo.core.Crypto
import ar.chamullo.core.Handshake
import ar.chamullo.core.Identity
import ar.chamullo.core.IslandAction
import ar.chamullo.core.Zone
import ar.chamullo.core.IslandState
import ar.chamullo.core.Islands
import ar.chamullo.core.toHex
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue

/**
 * Islas (Camino y Carretera §6, Capitán's design): Wi-Fi Direct is both camino and carretera.
 *
 * - Every phone hangs a cartel (a Wi-Fi Direct DNS-SD service record) that others read without connecting.
 * - [Islands.decide] says whether to found an island (be the host of a Wi-Fi Direct group), join one, merge, or take
 *   the ferry turn to a neighboring island.
 * - Members talk to the host over TCP; the host repeats every frame to the other members, so an island is one hop.
 * - The ferry leaves for a neighboring island for [FERRY_STAY_MS]; the node's pockets shout again when it lands there.
 */
@SuppressLint("MissingPermission")
class WifiIslands(
    private val context: Context,
    private val identity: Identity,
    private val onFrame: (ByteArray) -> Unit
) : Radio {
    private val handler = Handler(Looper.getMainLooper())
    private val p2p = context.getSystemService(WifiP2pManager::class.java)
    private var channel: WifiP2pManager.Channel? = null
    private var server: ServerSocket? = null
    private val links = CopyOnWriteArrayList<Link>()
    private var running = false

    private val me = identity.nodeId.toHex().take(16)
    private val myName = identity.name
    val ssid = Islands.ssidOf(me)
    // The island's key comes from its name (Camino y Carretera §6.4): whoever sees DIRECT-CH-xxxxxx in the Wi-Fi list can
    // join. The island is only the air; letters stay sealed and the pipe to the host has its own handshake.
    private val passphrase = Islands.passphraseFor(ssid)
    private val myIsland = Islands.islandId(me)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    @Volatile var scannedSeen = 0; private set

    // What I know about the islands around me.
    private val carteles = LinkedHashMap<String, Pair<Cartel, Long>>()
    @Volatile var island: String? = null; private set
    @Volatile var host = false; private set
    private val members = LinkedHashMap<Link, String>()
    private val ferryLinks = HashSet<Link>()
    @Volatile var ferrying: String? = null; private set
    private var home: IslandAction.Join? = null
    private var busy = false
    private var publishedService: WifiP2pDnsSdServiceInfo? = null

    override val label = "Isla Wi-Fi"
    override val supported = Build.VERSION.SDK_INT >= 29 && p2p != null &&
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)
    override val active get() = links.isNotEmpty()
    override var shouts = 0L; private set
    override var heard = 0L; private set
    override val peers get() = links.size
    override var lastError: String? = null; private set
    @Volatile var lastSpeed: String? = null; private set
    /** From the node, every tick: where I am, and where a letter nobody here gets closer wants to go (§10.6). */
    @Volatile var myCell: Zone? = null
    @Volatile var stuck: Zone? = null
    /** Tells the node a ferry heading (boarding) or that it arrived (null). */
    var onHeading: (Zone?) -> Unit = {}

    // El puente fijo (Camino y Carretera §6.2): besides my Wi-Fi Direct group, a second, local-only Wi-Fi connection to a
    // neighboring island's group. Android 10+ can request it with a network specifier (the phone asks its owner once).
    private val canBridge = Build.VERSION.SDK_INT >= 29
    @Volatile var bridgingTo: String? = null; private set
    private var bridgeCallback: ConnectivityManager.NetworkCallback? = null
    private var bridgeLink: Link? = null
    val cartelesSeen get() = carteles.size
    val memberCount get() = if (host) members.size else 0
    val islandName get() = island?.let { id -> if (id == myIsland) "mía" else hostOf(id)?.name?.ifBlank { id } ?: id }

    // Islands are named by six hex digits everywhere (cartel or Wi-Fi list): the host of one, if I know its cartel.
    private fun hostOf(island: String): Cartel? = carteles.values.map { it.first }.firstOrNull { it.host && it.island == island }

    override fun start() {
        if (!supported) { lastError = "necesita Android 10 o más nuevo con Wi-Fi Direct"; return }
        running = true
        handler.post {
            channel = p2p!!.initialize(context, Looper.getMainLooper(), null)
            context.registerReceiver(connectionReceiver, IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION))
            context.registerReceiver(scanReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
            p2p.setDnsSdResponseListeners(channel, { _, _, _ -> }, { _, record, _ ->
                Cartel.fromTxt(record)?.takeIf { it.nodeId != me }?.let { c ->
                    val fresh = c.nodeId !in carteles
                    carteles[c.nodeId] = c.copy(island = Islands.islandId(c.island), bridgeTo = Islands.islandId(c.bridgeTo)) to System.currentTimeMillis()
                    if (fresh) FieldLog.add("ISLA", "veo el cartel de ${c.name.ifBlank { c.nodeId.take(6) }}${if (c.host) " (anfitrión)" else ""}")
                }
            })
            p2p.removeGroup(channel, null) // start clean: no leftover group from a previous run
            publishCartel()
            handler.postDelayed(discoverLoop, 500)
            handler.postDelayed(decideLoop, DECIDE_MS)
            FieldLog.add("ISLA", "buscando islas por Wi-Fi Direct")
        }
    }

    /* ---------- carteles ---------- */

    private fun cartel() = Cartel(me, myName, island ?: "", if (host) ssid else "", if (host) passphrase else "", host,
        if (host) members.values.toList() else emptyList(), if (host) myCell else null, bridgingTo ?: "")

    private fun publishCartel() {
        val ch = channel ?: return
        publishedService?.let { p2p!!.removeLocalService(ch, it, null) }
        val info = WifiP2pDnsSdServiceInfo.newInstance("chamullo-" + me.take(8), "_chamullo._tcp", cartel().toTxt())
        publishedService = info
        p2p!!.addLocalService(ch, info, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {}
            override fun onFailure(reason: Int) { lastError = "no se pudo colgar el cartel (código $reason)"; FieldLog.add("ISLA", "cartel falló, código $reason") }
        })
    }

    // Wi-Fi Direct does one thing at a time: each step waits for the answer of the one before. Asking all at once, or
    // starting over every 15 s while a search (about a minute) still runs, only got BUSY (code 2) back.
    private val discoverLoop: Runnable = object : Runnable {
        override fun run() {
            if (!running) return
            handler.removeCallbacks(this)
            search(attempt = 0)
            runCatching { @Suppress("DEPRECATION") wifi?.startScan() } // Android may throttle it; results also come from its own scans
            readScan()
            handler.postDelayed(this, DISCOVER_MS)
        }
    }

    // The second way to find islands: the plain Wi-Fi list, where every island shows up as DIRECT-CH-xxxxxx. It does not
    // depend on the cartel search (DNS-SD), which in the field did not work between some phones.
    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = readScan()
    }

    private fun readScan() {
        val now = System.currentTimeMillis()
        val seen = runCatching { wifi?.scanResults.orEmpty().mapNotNull { Islands.fromScan(it.SSID ?: "") } }.getOrDefault(emptyList())
            .filter { it.island != myIsland }
        scannedSeen = seen.size
        for (c in seen) {
            if (hostOf(c.island)?.let { !it.nodeId.startsWith("scan:") } == true) continue // its cartel already says more
            val key = "scan:" + c.island
            if (key !in carteles) FieldLog.add("ISLA", "veo la isla ${c.island} en la lista de Wi-Fi")
            carteles[key] = c.copy(nodeId = key) to now
        }
    }

    private fun search(attempt: Int) {
        val ch = channel ?: return
        val m = p2p ?: return
        fun step(next: () -> Unit) = object : WifiP2pManager.ActionListener {
            override fun onSuccess() = next()
            override fun onFailure(reason: Int) = busy(reason, attempt, next)
        }
        m.clearServiceRequests(ch, step {
            m.addServiceRequest(ch, WifiP2pDnsSdServiceRequest.newInstance(), step {
                m.discoverPeers(ch, step { // some phones only answer service searches while peers are being searched too
                    m.discoverServices(ch, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() { lastError = null }
                        override fun onFailure(reason: Int) = busy(reason, attempt) { }
                    })
                })
            })
        })
    }

    // Busy: stop whatever search is running, wait a little (longer each time) and start the chain again.
    private fun busy(reason: Int, attempt: Int, orElse: () -> Unit) {
        if (reason != WifiP2pManager.BUSY) {
            lastError = when (reason) {
                WifiP2pManager.P2P_UNSUPPORTED -> "este celular no tiene Wi-Fi Direct"
                WifiP2pManager.NO_SERVICE_REQUESTS -> "la búsqueda de islas se cortó; reintento"
                else -> "no se pudo buscar islas (código $reason); ¿Wi-Fi y ubicación prendidos?"
            }
            orElse(); return
        }
        lastError = "el Wi-Fi Direct estaba ocupado; reintento"
        if (attempt >= 4) return
        val ch = channel ?: return
        p2p?.stopPeerDiscovery(ch, null)
        handler.postDelayed({ if (running) search(attempt + 1) }, 3_000L * (attempt + 1))
    }

    // Before founding an island, look around a while: one founds, the other (still looking) joins it. Each phone waits a
    // different time, by its id, so two phones that start together do not found at the same moment.
    private val startedAt = System.currentTimeMillis()
    private val lookFirstMs = FOUND_AFTER_MS + (me.take(4).toLong(16) % FOUND_SPREAD_MS)

    /* ---------- decisions ---------- */

    private val decideLoop: Runnable = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.currentTimeMillis()
            carteles.values.removeAll { now - it.second > CARTEL_TTL_MS }
            if (!busy && ferrying == null) {
                readScan()
                val a = Islands.decide(me, IslandState(island, host, if (host) members.values.toList() else rosterOfMyIsland(), ferriedTurn, bridgingTo, canBridge),
                    carteles.values.map { it.first }, now, myCell, stuck)
                if (a == IslandAction.Host && island == null && now - startedAt < lookFirstMs) Unit // still looking around
                else act(a)
            }
            handler.postDelayed(this, DECIDE_MS)
        }
    }

    private fun rosterOfMyIsland(): List<String> = island?.let { hostOf(it)?.roster } ?: emptyList()

    private fun act(a: IslandAction) {
        when (a) {
            IslandAction.Stay -> Unit
            IslandAction.Host -> found()
            is IslandAction.Join -> join(a)
            is IslandAction.Ferry -> ferry(a)
            is IslandAction.Bridge -> bridge(a)
            IslandAction.Unbridge -> unbridge()
        }
    }

    private fun found() {
        val ch = channel ?: return
        busy = true
        val config = WifiP2pConfig.Builder().setNetworkName(ssid).setPassphrase(passphrase).enablePersistentMode(false).build()
        p2p!!.createGroup(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                busy = false; host = true; island = myIsland
                FieldLog.add("ISLA", "fundé mi isla: $ssid")
                startServer(); publishCartel()
                handler.postDelayed(discoverLoop, 2_000) // forming a group stops the search on many phones: start it again
            }
            override fun onFailure(reason: Int) { busy = false; lastError = "no se pudo fundar la isla (código $reason)"; FieldLog.add("ISLA", "fundar falló, código $reason") }
        })
    }

    private fun join(a: IslandAction.Join, then: (() -> Unit)? = null) {
        val ch = channel ?: return
        busy = true
        FieldLog.add("ISLA", "me sumo a la isla ${a.island.take(6)}")
        p2p!!.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = connect()
            override fun onFailure(reason: Int) = connect()
            private fun connect() {
                closeLinks(); host = false; members.clear()
                val config = WifiP2pConfig.Builder().setNetworkName(a.ssid).setPassphrase(a.passphrase).build()
                p2p.connect(ch, config, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { island = a.island; busy = false; publishCartel(); handler.postDelayed(discoverLoop, 2_000); then?.invoke() }
                    override fun onFailure(reason: Int) { busy = false; island = null; lastError = "no pude sumarme (código $reason)"; FieldLog.add("ISLA", "sumarme falló, código $reason") }
                })
            }
        })
    }

    // A fixed bridge: join the neighbor's group as a plain local Wi-Fi client while staying in mine, and open a pipe there.
    private fun bridge(a: IslandAction.Bridge) {
        if (bridgeCallback != null) return
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val spec = WifiNetworkSpecifier.Builder().setSsid(a.ssid).setWpa2Passphrase(a.passphrase).build()
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(spec).build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Thread {
                    runCatching {
                        val s = network.socketFactory.createSocket()
                        s.connect(InetSocketAddress(GROUP_OWNER, PORT), 4_000)
                        bridgeLink = Link(s).apply { start() }
                        bridgingTo = a.island
                        handler.post { publishCartel() }
                        FieldLog.add("ISLA", "🌉 puente fijo: también estoy en la isla ${a.island.take(6)}")
                    }.onFailure { FieldLog.add("ISLA", "el puente fijo no abrió el caño: ${it.message}"); handler.post { unbridge() } }
                }.apply { isDaemon = true }.start()
            }
            override fun onLost(network: Network) { handler.post { unbridge() } }
            override fun onUnavailable() { bridgeCallback = null; FieldLog.add("ISLA", "el puente fijo no se pudo armar (el celular no lo permite o no se aceptó)") }
        }
        bridgeCallback = cb
        runCatching { cm.requestNetwork(request, cb) }.onFailure { bridgeCallback = null; lastError = "puente fijo: ${it.message}" }
    }

    private fun unbridge() {
        bridgeCallback?.let { cb -> runCatching { context.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } }
        bridgeCallback = null
        bridgeLink?.close(); bridgeLink = null
        if (bridgingTo != null) FieldLog.add("ISLA", "suelto el puente fijo")
        bridgingTo = null
        publishCartel()
    }

    // The ferry (Camino y Carretera §6.1): announce the heading, wait while letters going that way board, sail, hand them
    // out with the compass on arrival, then come home. A lonely bridge host that sails out of need stays there as a member.
    private fun ferry(a: IslandAction.Ferry) {
        val current = island ?: return
        ferriedTurn = Islands.turnOf(System.currentTimeMillis())
        val target = IslandAction.Join(a.island, a.ssid, a.passphrase)
        ferrying = a.island
        onHeading(a.cell)
        if (host) {
            FieldLog.add("ISLA", "soy un puente solo con cartas trabadas: me mudo a la isla ${a.island.take(6)}")
            handler.postDelayed({ join(target) { ferrying = null; onHeading(null) } }, Islands.FERRY_BOARDING_MS)
            return
        }
        val homeSsid = Islands.SSID_PREFIX + current // the key comes from the name: no cartel needed to come back
        home = IslandAction.Join(current, homeSsid, Islands.passphraseFor(homeSsid))
        FieldLog.add("ISLA", "me toca el ferry: embarco cartas y voy a la isla ${a.island.take(6)}")
        handler.postDelayed({
            join(target) {
                onHeading(null) // arrived: hand out with the compass
                handler.postDelayed({
                    FieldLog.add("ISLA", "vuelvo del ferry a mi isla")
                    home?.let { join(it) { ferrying = null } } ?: run { ferrying = null }
                }, FERRY_STAY_MS)
            }
        }, Islands.FERRY_BOARDING_MS)
    }
    private var ferriedTurn = -1L

    // When the group forms as a member, open the pipe to the host.
    private val connectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val ch = channel ?: return
            p2p!!.requestConnectionInfo(ch) { info ->
                if (info == null || !info.groupFormed) {
                    if (!host && links.isNotEmpty()) { FieldLog.add("ISLA", "me quedé sin isla"); closeLinks(); if (ferrying == null) island = null }
                    return@requestConnectionInfo
                }
                if (!info.isGroupOwner && links.isEmpty()) {
                    val owner = info.groupOwnerAddress ?: return@requestConnectionInfo
                    Thread { connectTo(owner) }.start()
                }
            }
        }
    }

    private fun connectTo(owner: InetAddress) {
        repeat(5) { attempt ->
            runCatching {
                val s = Socket()
                s.connect(InetSocketAddress(owner, PORT), 4_000)
                Link(s).apply { start(); if (ferrying != null) send(FERRY) }
                FieldLog.add("ISLA", "en la isla: caño abierto con el anfitrión")
                return
            }
            Thread.sleep(1_000L * (attempt + 1))
        }
        FieldLog.add("ISLA", "no pude abrir el caño con el anfitrión")
    }

    private fun startServer() {
        if (server != null) return
        Thread {
            runCatching {
                val s = ServerSocket(PORT).also { server = it }
                while (running) Link(s.accept()).start()
            }.onFailure { if (running) FieldLog.add("ISLA", "el servidor de la isla se cerró: ${it.message}") }
            server = null
        }.apply { isDaemon = true }.start()
    }

    private fun closeLinks() { links.forEach { it.close() }; links.clear() }

    /* ---------- frames ---------- */

    override fun shout(frame: ByteArray) {
        for (l in links) l.send(frame)
        if (links.isNotEmpty()) shouts++
    }

    /** Sends [megabytes] of test data to everyone connected; the receivers measure and answer. */
    fun speedTest(megabytes: Int = 4) {
        if (links.isEmpty()) { lastSpeed = "no hay nadie en la isla"; return }
        val chunk = ByteArray(64 * 1024).also { System.arraycopy(SPEED, 0, it, 0, SPEED.size) }
        val total = megabytes * 1024 * 1024
        lastSpeed = "midiendo…"
        FieldLog.add("ISLA", "prueba de velocidad: mandando $megabytes MB")
        for (l in links) { l.send(SPEED_START + intBytes(total)); repeat(total / chunk.size) { l.send(chunk) } }
    }

    override fun stop() {
        running = false
        handler.removeCallbacks(discoverLoop); handler.removeCallbacks(decideLoop)
        runCatching { context.unregisterReceiver(connectionReceiver) }
        runCatching { context.unregisterReceiver(scanReceiver) }
        closeLinks()
        runCatching { server?.close() }; server = null
        channel?.let { ch -> runCatching { p2p?.removeGroup(ch, null) }; publishedService?.let { runCatching { p2p?.removeLocalService(ch, it, null) } } }
    }

    /** One TCP pipe: frames go as [length][bytes]. The host repeats what it hears to every other member. */
    private inner class Link(private val socket: Socket) {
        private val out = LinkedBlockingQueue<ByteArray>(4096)
        @Volatile private var open = true
        private var speedExpected = 0; private var speedGot = 0; private var speedStart = 0L
        // The secret greeting (Identity §7): nothing passes until the other side proves who it is.
        private val handshake = Handshake(identity)
        @Volatile private var trusted = false

        fun start() {
            links += this
            socket.tcpNoDelay = true
            out.offer(handshake.hello())
            handler.postDelayed({ if (!trusted) { FieldLog.add("ISLA", "un caño no probó quién era: lo cierro"); close() } }, HANDSHAKE_MS)
            Thread { readLoop() }.apply { isDaemon = true }.start()
            Thread { writeLoop() }.apply { isDaemon = true }.start()
        }

        fun send(frame: ByteArray) { if (open && trusted) out.offer(frame) }

        private fun writeLoop() = runCatching {
            val w = DataOutputStream(socket.getOutputStream().buffered(256 * 1024))
            while (open) {
                val f = out.take()
                if (f.isEmpty()) continue
                w.writeInt(f.size); w.write(f)
                if (out.isEmpty()) w.flush()
            }
        }.also { close() }

        private fun readLoop() = runCatching {
            val r = DataInputStream(socket.getInputStream().buffered(256 * 1024))
            while (open) {
                val size = r.readInt()
                if (size !in 1..MAX_FRAME) error("trama inválida")
                val f = ByteArray(size).also { r.readFully(it) }
                heard++
                if (!trusted) {
                    handshake.onHello(f)?.let { out.offer(it) }
                    if (Handshake.isHandshake(f) && handshake.onProof(f)) {
                        trusted = true
                        val who = handshake.peer!!.toHex()
                        FieldLog.add("ISLA", "caño verificado con ${who.take(6)}")
                        if (host) handler.post {
                            members[this] = who.take(8)
                            FieldLog.add("ISLA", "se sumó un miembro (${members.size})")
                            publishCartel()
                            // The last seat is for ferries: a regular member beyond the ordinary seats is asked to found its own island.
                            handler.postDelayed({ if (!ferryLinks.contains(this) && members.size > Islands.MAX_MEMBERS - Islands.FERRY_SEATS) { send(FULL); handler.postDelayed({ close() }, 500) } }, 1_500)
                        }
                    }
                    continue
                }
                when {
                    f.startsWith(FERRY) -> handler.post { ferryLinks += this }
                    f.startsWith(FULL) -> { FieldLog.add("ISLA", "la isla está llena: fundo la mía"); handler.post { island = null } }
                    f.startsWith(SPEED_START) -> { speedExpected = intOf(f, SPEED_START.size); speedGot = 0; speedStart = System.nanoTime() }
                    f.startsWith(SPEED) -> {
                        speedGot += f.size
                        if (speedExpected in 1..speedGot) {
                            val secs = (System.nanoTime() - speedStart) / 1e9
                            val result = "%.1f MB en %.2f s = %.1f Mbit/s".format(speedGot / 1048576.0, secs, speedGot * 8 / secs / 1e6)
                            lastSpeed = "recibí $result"
                            FieldLog.add("ISLA", "prueba de velocidad: recibí $result")
                            send(SPEED_RESULT + result.toByteArray())
                            speedExpected = 0
                        }
                    }
                    f.startsWith(SPEED_RESULT) -> String(f, SPEED_RESULT.size, f.size - SPEED_RESULT.size).let {
                        lastSpeed = "el otro recibió $it"; FieldLog.add("ISLA", "prueba de velocidad: el otro recibió $it")
                    }
                    else -> {
                        // The host is the island's air (Camino y Carretera §6): it repeats every frame to the others.
                        // It carries a letter only when it is chosen, like anyone (Discovery & Routing §10.6).
                        if (host) for (l in links) if (l !== this) l.send(f)
                        onFrame(f)
                    }
                }
            }
        }.also { close() }

        fun close() {
            if (!open) return
            open = false
            links -= this
            runCatching { socket.close() }
            out.offer(ByteArray(0))
            handler.post { ferryLinks.remove(this); if (members.remove(this) != null) publishCartel() }
            FieldLog.add("ISLA", "se cerró un caño (quedan ${links.size})")
        }
    }

    companion object {
        const val PORT = 47_474
        // A Wi-Fi Direct group owner answers at this address on its own network, also to plain Wi-Fi clients.
        const val GROUP_OWNER = "192.168.49.1"
        const val MAX_FRAME = 1_048_576
        const val DISCOVER_MS = 60_000L
        const val FOUND_AFTER_MS = 30_000L
        const val FOUND_SPREAD_MS = 30_000L
        const val DECIDE_MS = 5_000L
        const val CARTEL_TTL_MS = 60_000L
        const val FERRY_STAY_MS = 20_000L
        const val HANDSHAKE_MS = 10_000L
        private val FERRY = "CHFRY".toByteArray()
        private val FULL = "CHFUL".toByteArray()
        private val SPEED = "CHSPD".toByteArray()
        private val SPEED_START = "CHSPS".toByteArray()
        private val SPEED_RESULT = "CHSPR".toByteArray()
        private fun ByteArray.startsWith(p: ByteArray) = size >= p.size && p.indices.all { this[it] == p[it] }
        private fun intBytes(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
        private fun intOf(b: ByteArray, o: Int) = ((b[o].toInt() and 0xff) shl 24) or ((b[o + 1].toInt() and 0xff) shl 16) or ((b[o + 2].toInt() and 0xff) shl 8) or (b[o + 3].toInt() and 0xff)
    }
}
