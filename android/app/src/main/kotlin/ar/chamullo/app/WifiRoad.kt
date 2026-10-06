package ar.chamullo.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import ar.chamullo.core.Crypto
import ar.chamullo.core.Identity
import ar.chamullo.core.toHex
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue

/**
 * La carretera (Camino y Carretera): every phone owns a Wi-Fi Direct group, its own road, and at the same time rides a
 * neighbor's road as an ordinary Wi-Fi client, using the key that came sealed over the camino. Owning a group while
 * being a client elsewhere is a concurrency most phones support, so roads chain into a live line: A ← B ← C.
 * Frames travel whole over TCP: no 22-byte pieces. Needs Android 10+ (groups with a chosen name and key).
 */
@SuppressLint("MissingPermission")
class WifiRoad(
    private val context: Context,
    identity: Identity,
    private val onFrame: (ByteArray) -> Unit,
    private val onRoadReady: (ssid: String, passphrase: String) -> Unit
) : Radio {
    private val handler = Handler(Looper.getMainLooper())
    private val p2p = context.getSystemService(WifiP2pManager::class.java)
    private val conn = context.getSystemService(ConnectivityManager::class.java)
    private var channel: WifiP2pManager.Channel? = null
    private var server: ServerSocket? = null
    private val links = CopyOnWriteArrayList<Link>()
    private var joinCallback: ConnectivityManager.NetworkCallback? = null
    private var running = false

    // Always the same name and key per phone: a neighbor approves joining it once.
    val ssid = "DIRECT-CH-" + identity.nodeId.toHex().take(6)
    private val passphrase = Crypto.hash("CHAMULLO/1/ROADKEY".toByteArray() + identity.seed).toHex().take(20)

    override val label = "Carretera"
    override val supported = Build.VERSION.SDK_INT >= 29 && p2p != null &&
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)
    @Volatile var roadUp = false; private set
    @Volatile var riding: String? = null; private set
    override val active get() = roadUp || links.isNotEmpty()
    override var shouts = 0L; private set
    override var heard = 0L; private set
    override val peers get() = links.size
    override var lastError: String? = null; private set
    @Volatile var lastSpeed: String? = null; private set

    override fun start() {
        if (!supported) { lastError = "necesita Android 10 o más nuevo con Wi-Fi Direct"; return }
        running = true
        handler.post { openRoad() }
    }

    // My own road: a Wi-Fi Direct group with a fixed name and key, plus a socket server for whoever rides it.
    private fun openRoad() {
        if (!running) return
        val ch = channel ?: p2p!!.initialize(context, Looper.getMainLooper(), null).also { channel = it }
        p2p!!.removeGroup(ch, null)
        val config = WifiP2pConfig.Builder().setNetworkName(ssid).setPassphrase(passphrase).enablePersistentMode(false).build()
        handler.postDelayed({
            p2p.createGroup(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    roadUp = true
                    lastError = null
                    FieldLog.add("RUTA", "carretera propia abierta: $ssid")
                    startServer()
                    onRoadReady(ssid, passphrase)
                }
                override fun onFailure(reason: Int) {
                    roadUp = false
                    lastError = "no se pudo abrir la carretera (código $reason); ¿Wi-Fi prendido?"
                    FieldLog.add("RUTA", "no se pudo abrir la carretera, código $reason; reintento en 15 s")
                    handler.postDelayed({ openRoad() }, 15_000)
                }
            })
        }, 500)
    }

    private fun startServer() {
        if (server != null) return
        Thread {
            runCatching {
                val s = ServerSocket(PORT).also { server = it }
                while (running) {
                    val socket = s.accept()
                    FieldLog.add("RUTA", "alguien entró a mi carretera")
                    Link(socket).start()
                }
            }.onFailure { if (running) FieldLog.add("RUTA", "el servidor de la carretera se cerró: ${it.message}") }
            server = null
        }.apply { isDaemon = true }.start()
    }

    /** Ride a neighbor's road with the key that came over the camino. One ride at a time. */
    fun join(ssid: String, passphrase: String) = handler.post {
        if (!supported || riding != null || ssid == this.ssid) return@post
        val spec = WifiNetworkSpecifier.Builder().setSsid(ssid).setWpa2Passphrase(passphrase).build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec)
            .build()
        riding = ssid
        FieldLog.add("RUTA", "subiendo a la carretera $ssid")
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Thread {
                    runCatching {
                        val socket = network.socketFactory.createSocket(gatewayOf(network), PORT)
                        FieldLog.add("RUTA", "en la carretera $ssid")
                        Link(socket).start()
                    }.onFailure { FieldLog.add("RUTA", "no pude abrir el caño en $ssid: ${it.message}"); leave() }
                }.start()
            }
            override fun onUnavailable() { FieldLog.add("RUTA", "no se pudo subir a $ssid"); riding = null; joinCallback = null }
            override fun onLost(network: Network) { FieldLog.add("RUTA", "se cortó la carretera $ssid"); leave() }
        }
        joinCallback = cb
        runCatching { conn.requestNetwork(request, cb) }.onFailure { lastError = it.message; riding = null; joinCallback = null }
    }

    private fun leave() = handler.post {
        joinCallback?.let { runCatching { conn.unregisterNetworkCallback(it) } }
        joinCallback = null
        riding = null
    }

    // The group owner of the road we ride is the gateway of that network (192.168.49.1 on Android).
    private fun gatewayOf(network: Network): InetAddress =
        conn.getLinkProperties(network)?.routes?.firstNotNullOfOrNull { r -> r.gateway?.takeIf { it is Inet4Address && !it.isAnyLocalAddress } }
            ?: InetAddress.getByName("192.168.49.1")

    override fun shout(frame: ByteArray) {
        for (l in links) l.send(frame)
        if (links.isNotEmpty()) shouts++
    }

    /** Sends [megabytes] of test data to everyone on the roads; the receivers measure and answer. */
    fun speedTest(megabytes: Int = 4) {
        if (links.isEmpty()) { lastSpeed = "no hay nadie en la carretera"; return }
        val chunk = ByteArray(64 * 1024).also { System.arraycopy(SPEED, 0, it, 0, SPEED.size) }
        val total = megabytes * 1024 * 1024
        lastSpeed = "midiendo…"
        FieldLog.add("RUTA", "prueba de velocidad: mandando $megabytes MB")
        for (l in links) { l.send(SPEED_START + intBytes(total)); repeat(total / chunk.size) { l.send(chunk) } }
    }

    override fun stop() {
        running = false
        links.forEach { it.close() }
        links.clear()
        runCatching { server?.close() }; server = null
        leave()
        channel?.let { ch -> runCatching { p2p?.removeGroup(ch, null) } }
        roadUp = false
    }

    /** One TCP pipe: frames go as [length][bytes], each direction on its own thread. */
    private inner class Link(private val socket: Socket) {
        private val out = LinkedBlockingQueue<ByteArray>(4096)
        @Volatile private var open = true
        private var speedExpected = 0; private var speedGot = 0; private var speedStart = 0L

        fun start() {
            links += this
            socket.tcpNoDelay = true
            Thread { readLoop() }.apply { isDaemon = true }.start()
            Thread { writeLoop() }.apply { isDaemon = true }.start()
        }

        fun send(frame: ByteArray) { if (open) out.offer(frame) }

        private fun writeLoop() = runCatching {
            val w = DataOutputStream(socket.getOutputStream().buffered(256 * 1024))
            while (open) {
                val f = out.take()
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
                when {
                    f.startsWith(SPEED_START) -> { speedExpected = intOf(f, SPEED_START.size); speedGot = 0; speedStart = System.nanoTime() }
                    f.startsWith(SPEED) -> {
                        speedGot += f.size
                        if (speedExpected in 1..speedGot) {
                            val secs = (System.nanoTime() - speedStart) / 1e9
                            val result = "%.1f MB en %.2f s = %.1f Mbit/s".format(speedGot / 1048576.0, secs, speedGot * 8 / secs / 1e6)
                            lastSpeed = "recibí $result"
                            FieldLog.add("RUTA", "prueba de velocidad: recibí $result")
                            send(SPEED_RESULT + result.toByteArray())
                            speedExpected = 0
                        }
                    }
                    f.startsWith(SPEED_RESULT) -> String(f, SPEED_RESULT.size, f.size - SPEED_RESULT.size).let {
                        lastSpeed = "el otro recibió $it"; FieldLog.add("RUTA", "prueba de velocidad: el otro recibió $it")
                    }
                    else -> onFrame(f)
                }
            }
        }.also { close() }

        fun close() {
            if (!open) return
            open = false
            links -= this
            runCatching { socket.close() }
            out.offer(ByteArray(0))
            FieldLog.add("RUTA", "se cerró un caño (quedan ${links.size})")
        }
    }

    companion object {
        const val PORT = 47_474
        const val MAX_FRAME = 1_048_576
        private val SPEED = "CHSPD".toByteArray()
        private val SPEED_START = "CHSPS".toByteArray()
        private val SPEED_RESULT = "CHSPR".toByteArray()
        private fun ByteArray.startsWith(p: ByteArray) = size >= p.size && p.indices.all { this[it] == p[it] }
        private fun intBytes(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
        private fun intOf(b: ByteArray, o: Int) = ((b[o].toInt() and 0xff) shl 24) or ((b[o + 1].toInt() and 0xff) shl 16) or ((b[o + 2].toInt() and 0xff) shl 8) or (b[o + 3].toInt() and 0xff)
    }
}
