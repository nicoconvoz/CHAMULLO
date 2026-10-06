package ar.chamullo.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import ar.chamullo.core.Node
import ar.chamullo.core.Crypto
import ar.chamullo.core.NodeEvent
import ar.chamullo.core.toHex

/** Keeps el grito alive while the app is in the background: listening, shouting and carrying letters. */
class GritoService : Service() {
    private var radios: List<Radio> = emptyList()
    private val recent = LinkedHashMap<String, Long>()
    private var lastHello = 0L
    private var running = false
    private var locator: Locator? = null
    private var relay: ar.chamullo.core.RelayBridge? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_RUN, "El grito encendido", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_MSG, "Cartas nuevas", NotificationManager.IMPORTANCE_HIGH))
        val ongoing = notification(CH_RUN, "CHAMULLO escuchando", "Tu celular es parte de la red.", MainActivity::class.java)
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID_RUN, ongoing, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(ID_RUN, ongoing)

        val identity = Vault(this).identity() ?: run { stopSelf(); return }
        val incoming: (ByteArray) -> Unit = { frame -> Hub.worker.post { handle(frame) } }
        val bluetooth = GritoRadio(this, incoming) { hello -> Hub.worker.post { Hub.node?.onHello(hello) } }
        val islands = WifiIslands(this, identity, incoming)
        // Islas: Wi-Fi Direct is camino and carretera. Bluetooth is an opt-in fallback; Wi-Fi Aware stays off because
        // Google documents it may conflict with Wi-Fi Direct.
        val useBluetooth = Settings.bluetooth(this)
        radios = listOfNotNull(islands, bluetooth.takeIf { useBluetooth })
        // Frames up to 60 KB stay whole: islands carry them in one piece; the Bluetooth fallback splits small ones.
        val node = Node(identity, FileStore(this), ROAD_FRAME, useBluetooth && bluetooth.coded) { System.currentTimeMillis() }
        Hub.radios = radios
        Hub.worker.post { Hub.node = node }
        islands.onHeading = { heading -> Hub.post { it.setHeading(heading) } }
        radios.forEach { it.start() }
        locator = Locator(this) { lat, lon -> Hub.post { it.locate(lat, lon) } }.also { it.start() }
        // The Internet bridge (Discovery & Routing §11): only if the owner lends it and there is a relé to talk to.
        val relayUrl = Settings.relay(this)
        if (Settings.lendInternet(this) && relayUrl.isNotBlank()) {
            relay = ar.chamullo.core.RelayBridge(relayUrl, identity, incoming).also { r -> r.start(); Hub.relay = r; Hub.worker.post { node.bridge = r } }
            FieldLog.add("PUENTE", "presto Internet por el relé $relayUrl")
        }
        running = true
        Hub.worker.post(object : Runnable {
            override fun run() {
                if (!running) return
                Hub.node?.let { n ->
                    n.tick()
                    val now = System.currentTimeMillis()
                    if (useBluetooth && now - lastHello >= HELLO_MS) { lastHello = now; bluetooth.sayHello(n.hello()) }
                    val on = radios.filter { it.active }
                    for (f in n.drainOutbox()) on.forEach { it.shout(f) }
                    islands.myCell = n.cell(); islands.stuck = n.stuckZone()
                    n.cell()?.let { c -> relay?.place(c) }
                }
                Hub.worker.postDelayed(this, TICK_MS)
            }
        })
        Thread { while (running) { checkVersion(); Thread.sleep(WebVersion.CHECK_EVERY_MS) } }.apply { isDaemon = true }.start()
    }

    // The same frame may arrive by Wi-Fi and by Bluetooth: only the first copy reaches the node.
    private fun handle(frame: ByteArray) {
        val node: Node = Hub.node ?: return
        val key = Crypto.hash(frame).toHex()
        val now = System.currentTimeMillis()
        if ((recent[key] ?: 0L) > now - 10_000) return
        recent[key] = now
        while (recent.size > 2048) recent.remove(recent.keys.first())
        for (event in node.onFrame(frame)) {
            when (event) {
                is NodeEvent.CardReceived ->
                    if (node.store.contact(event.card.nodeId) == null) {
                        synchronized(Hub.pendingCards) { if (Hub.pendingCards.none { it.nodeId.contentEquals(event.card.nodeId) }) Hub.pendingCards += event.card } // repeated offers: one dialog
                        notify(ID_CARD, notification(CH_MSG, "${event.card.name} quiere intercambiar tarjetas", "Tocá para aceptar.", MainActivity::class.java))
                    }
                is NodeEvent.LetterReceived ->
                    notify(event.msgId.hashCode(), notification(CH_MSG, event.from.name, event.text, ChatActivity::class.java, event.from.nodeId))
                else -> Unit
            }
            FieldLog.add("NODO", when (event) {
                is NodeEvent.CardReceived -> "tarjeta de ${event.card.name}"
                is NodeEvent.LetterReceived -> "carta de ${event.from.name} (${event.journey.size} carteros en el camino)"
                is NodeEvent.Delivered -> "✓✓ entregada"
                is NodeEvent.PlazaReceived -> "plaza de ${event.name}: ${event.text.take(30)}"
                is NodeEvent.PlazaHeard -> "${event.name} escuchó mi plaza"
                NodeEvent.NeighborsChanged -> "vecino nuevo"
                is NodeEvent.CandyEarned -> "🍬 confirmaron una carta que llevé: ya tengo ${event.total} caramelos"
                is NodeEvent.RoadInvited -> "${event.name.ifBlank { "un vecino" }} me dio la llave de su carretera ${event.ssid}"
            })
            Hub.emit(event)
        }
    }

    private fun checkVersion() {
        val web = WebVersion.fetch() ?: return
        if (!WebVersion.needsUpdate(this)) return
        val prefs = getSharedPreferences("updates", MODE_PRIVATE)
        if (prefs.getString("notified", null) == web) return
        prefs.edit().putString("notified", web).apply()
        val open = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(WebVersion.DOWNLOAD_PAGE))
        val pi = PendingIntent.getActivity(this, ID_UPDATE, open, PendingIntent.FLAG_IMMUTABLE)
        notify(ID_UPDATE, Notification.Builder(this, CH_MSG).setSmallIcon(R.drawable.ic_launcher_fg)
            .setContentTitle("Nueva versión de CHAMULLO: $web").setContentText("Tocá para descargarla.").setContentIntent(pi).setAutoCancel(true).build())
    }

    private fun notify(id: Int, n: Notification) = runCatching { getSystemService(NotificationManager::class.java).notify(id, n) }

    private fun notification(channel: String, title: String, text: String, target: Class<*>, peer: ByteArray? = null): Notification {
        val intent = Intent(this, target).apply { peer?.let { putExtra(ChatActivity.EXTRA_PEER, it) }; flags = Intent.FLAG_ACTIVITY_SINGLE_TOP }
        val pi = PendingIntent.getActivity(this, title.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, channel).setSmallIcon(R.drawable.ic_launcher_fg).setContentTitle(title).setContentText(text)
            .setContentIntent(pi).setAutoCancel(channel == CH_MSG).build()
    }

    override fun onDestroy() {
        running = false
        locator?.stop()
        relay?.stop(); Hub.relay = null
        radios.forEach { it.stop() }
        Hub.node = null
        super.onDestroy()
    }

    companion object {
        const val CH_RUN = "run"
        const val CH_MSG = "msg"
        const val ID_RUN = 1
        const val ID_CARD = 2
        const val ID_UPDATE = 3
        const val TICK_MS = 200L
        const val ROAD_FRAME = 60_000
        const val HELLO_MS = 3_000L
        const val ROAD_IDLE_MS = 2 * 60_000L
    }
}
