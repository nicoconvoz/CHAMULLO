package ar.chamullo.app

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareSession
import android.os.Handler
import android.os.Looper

/**
 * The Wi-Fi shout: Wi-Fi Aware (NAN) lets phones find each other and exchange short messages directly, without
 * joining any network and without internet. Every phone publishes and subscribes to the "chamullo" service;
 * frames go to each discovered phone as a message (up to 255 bytes; CHAMULLO frames are 240).
 */
@SuppressLint("MissingPermission")
class WifiRadio(private val context: Context, private val onFrame: (ByteArray) -> Unit) : Radio {
    private val manager = context.getSystemService(WifiAwareManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var session: WifiAwareSession? = null
    private var publish: PublishDiscoverySession? = null
    private var subscribe: SubscribeDiscoverySession? = null
    private val found = LinkedHashMap<PeerHandle, Long>()
    private val queue = ArrayDeque<ByteArray>()
    private var sending = false
    private var nextId = 0
    private var started = false

    override val label = "Wi-Fi"
    override val supported = context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE) && manager != null
    override val active get() = supported && manager!!.isAvailable && session != null
    override var shouts = 0L; private set
    override var heard = 0L; private set
    override val peers get() = found.size
    override var lastError: String? = null; private set

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { handler.post { reattach() } }
    }

    override fun start() {
        if (!supported) { lastError = "este celular no tiene Wi-Fi Aware"; return }
        handler.post {
            if (!started) { context.registerReceiver(stateReceiver, IntentFilter(WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED)); started = true }
            reattach()
        }
    }

    override fun stop() {
        handler.post {
            if (started) runCatching { context.unregisterReceiver(stateReceiver) }
            started = false
            close()
        }
    }

    private fun close() {
        runCatching { publish?.close() }; runCatching { subscribe?.close() }; runCatching { session?.close() }
        publish = null; subscribe = null; session = null; found.clear(); queue.clear(); sending = false
    }

    // Wi-Fi Aware drops every session when Wi-Fi or location go off; start over when it comes back.
    private fun reattach() {
        close()
        if (!manager!!.isAvailable) { lastError = "prendé el Wi-Fi (no hace falta conectarse a ninguna red)"; return }
        manager.attach(object : AttachCallback() {
            override fun onAttached(s: WifiAwareSession) {
                session = s
                lastError = null
                s.publish(PublishConfig.Builder().setServiceName(SERVICE).build(), publishCallback, handler)
                s.subscribe(SubscribeConfig.Builder().setServiceName(SERVICE).build(), subscribeCallback, handler)
            }
            override fun onAttachFailed() { lastError = "Wi-Fi Aware no arrancó" }
        }, handler)
    }

    private val publishCallback = object : DiscoverySessionCallback() {
        override fun onPublishStarted(s: PublishDiscoverySession) { publish = s }
        override fun onMessageReceived(peer: PeerHandle, message: ByteArray) = receive(message)
        override fun onSessionTerminated() { publish = null }
    }

    private val subscribeCallback = object : DiscoverySessionCallback() {
        override fun onSubscribeStarted(s: SubscribeDiscoverySession) { subscribe = s }
        override fun onServiceDiscovered(peer: PeerHandle, serviceSpecificInfo: ByteArray?, matchFilter: MutableList<ByteArray>?) {
            found[peer] = System.currentTimeMillis()
        }
        override fun onMessageReceived(peer: PeerHandle, message: ByteArray) = receive(message)
        override fun onMessageSendFailed(messageId: Int) { lastError = "un mensaje no salió (se reintenta con el próximo grito)" }
        override fun onSessionTerminated() { subscribe = null }
    }

    private fun receive(message: ByteArray) { heard++; onFrame(message) }

    override fun shout(frame: ByteArray) {
        handler.post {
            if (subscribe == null || found.isEmpty()) return@post
            queue.addLast(frame)
            while (queue.size > MAX_QUEUE) queue.removeFirst()
            pump()
        }
    }

    // One frame to every phone found, a few milliseconds apart so the Wi-Fi chip's message queue never overflows.
    private fun pump() {
        if (sending || queue.isEmpty()) return
        val s = subscribe ?: return
        val frame = queue.removeFirst()
        sending = true
        for (peer in found.keys) runCatching { s.sendMessage(peer, nextId++, frame) }
        shouts++
        handler.postDelayed({ sending = false; pump() }, PACE_MS)
    }

    companion object {
        const val SERVICE = "chamullo"
        const val PACE_MS = 40L
        const val MAX_QUEUE = 300
    }
}
