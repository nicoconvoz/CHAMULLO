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
import ar.chamullo.core.NodeEvent

/** Keeps el grito alive while the app is in the background: listening, shouting and carrying letters. */
class GritoService : Service() {
    private lateinit var radio: GritoRadio
    private var running = false

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
        radio = GritoRadio(this) { frame -> Hub.worker.post { handle(frame) } }
        val node = Node(identity, FileStore(this), if (radio.canShout) radio.maxFrame else GritoRadio.MAX_FRAME, radio.coded) { System.currentTimeMillis() }
        Hub.radio = radio
        Hub.worker.post { Hub.node = node }
        radio.start()
        running = true
        Hub.worker.post(object : Runnable {
            override fun run() {
                if (!running) return
                Hub.node?.let { n -> n.tick(); for (f in n.drainOutbox()) radio.shout(f) }
                Hub.worker.postDelayed(this, TICK_MS)
            }
        })
    }

    private fun handle(frame: ByteArray) {
        val node: Node = Hub.node ?: return
        for (event in node.onFrame(frame)) {
            when (event) {
                is NodeEvent.CardReceived ->
                    if (node.store.contact(event.card.nodeId) == null) {
                        synchronized(Hub.pendingCards) { Hub.pendingCards += event.card }
                        notify(ID_CARD, notification(CH_MSG, "${event.card.name} quiere intercambiar tarjetas", "Tocá para aceptar.", MainActivity::class.java))
                    }
                is NodeEvent.LetterReceived ->
                    notify(event.msgId.hashCode(), notification(CH_MSG, event.from.name, event.text, ChatActivity::class.java, event.from.nodeId))
                else -> Unit
            }
            Hub.emit(event)
        }
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
        if (::radio.isInitialized) radio.stop()
        Hub.node = null
        super.onDestroy()
    }

    companion object {
        const val CH_RUN = "run"
        const val CH_MSG = "msg"
        const val ID_RUN = 1
        const val ID_CARD = 2
        const val TICK_MS = 200L
    }
}
