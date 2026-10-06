// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-CHAMULLO-Commercial
// Copyright (C) 2026 Jesús Nicolás Astorga y RESOURCES OPEN DOORS S.A.S
package ar.chamullo.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import ar.chamullo.core.CallMsg
import ar.chamullo.core.CallSession
import ar.chamullo.core.Card
import ar.chamullo.core.Crypto
import ar.chamullo.core.Identity
import ar.chamullo.core.toHex
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Llamadas y videollamadas dentro de la isla (Camino y Carretera §7). Only with contacts (their card's box key seals
 * everything), live, over the island's pipes: the host passes each piece only to the member it is for.
 * [CallSession] keeps the rules; this object moves the voice, the picture, the ringtone and the screen.
 */
object Calls {
    class Call(val session: CallSession, val card: Card, val key: ByteArray) {
        var audio: AudioEngine? = null
        var video: VideoEngine? = null
        var lastPing = 0L
    }

    private val thread = HandlerThread("calls").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private var context: Context? = null
    private var identity: Identity? = null
    private var islands: WifiIslands? = null
    private var ringtone: Ringtone? = null

    @Volatile var current: Call? = null; private set
    /** Screens listening for changes (on the main thread). */
    val listeners = CopyOnWriteArraySet<() -> Unit>()
    /** The service updates what it declares to Android (microphone, camera) while a call talks. */
    var onTalking: (talking: Boolean, video: Boolean) -> Unit = { _, _ -> }

    fun attach(c: Context, id: Identity, i: WifiIslands) = handler.post {
        context = c.applicationContext; identity = id; islands = i
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_CALL, "Llamadas", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) })
        handler.post(loop)
    }

    fun detach() = handler.post { current?.let { end(it, sendHangup = true) }; islands = null; handler.removeCallbacks(loop) }

    private fun changed() = main.post { listeners.forEach { it() } }

    /* ---------- what the screen does ---------- */

    /** Calls [card]. False if another call is on or CHAMULLO is not running. */
    fun start(card: Card, video: Boolean): Boolean {
        val me = identity ?: return false
        if (current != null || islands == null) return false
        val call = Call(CallSession(Crypto.randomBytes(8).fold(0L) { a, b -> (a shl 8) or (b.toLong() and 0xff) } and Long.MAX_VALUE,
            card.nodeId, outgoing = true, video = video, now = now()), card, Crypto.boxShared(card.boxPublic, me.boxSecret))
        current = call
        FieldLog.add("LLAMADA", "llamo a ${card.name}${if (video) " (video)" else ""}")
        handler.post { tick() }
        changed()
        return true
    }

    fun answer() = handler.post {
        val c = current ?: return@post
        if (c.session.outgoing || c.session.state != CallSession.State.RINGING) return@post
        c.session.answer(now())
        send(c, CallMsg.ANSWER)
        talk(c)
    }

    fun reject() = handler.post {
        val c = current ?: return@post
        c.session.reject(now())
        send(c, CallMsg.REJECT)
        end(c, sendHangup = false)
    }

    fun hangUp() = handler.post { current?.let { end(it, sendHangup = true) } }

    /* ---------- the wire ---------- */

    private fun send(c: Call, op: Int, data: ByteArray = ByteArray(0)) {
        val me = identity ?: return
        islands?.sendDirect(CallMsg.sealWith(c.key, me.nodeId, c.card.nodeId, op, c.session.id, data).encode(), c.card.nodeId)
    }

    fun onMsg(m: CallMsg) {
        val me = identity ?: return
        val c = current
        if (c != null && c.card.nodeId.contentEquals(m.from) && c.session.id == m.call) {
            val data = m.openWith(c.key) ?: return
            when (m.op) {
                CallMsg.AUDIO -> { c.session.onMedia(now()); c.audio?.onRemote(data) }
                CallMsg.VIDEO -> { c.session.onMedia(now()); c.video?.onRemote(data) }
                CallMsg.RING -> send(c, CallMsg.RINGING) // my "ringing" got lost
                else -> handler.post {
                    val was = c.session.state
                    c.session.onRemote(m.op, now())
                    if (was != CallSession.State.ACTIVE && c.session.state == CallSession.State.ACTIVE) talk(c)
                    if (c.session.state == CallSession.State.ENDED) end(c, sendHangup = false) else changed()
                }
            }
            return
        }
        if (m.op != CallMsg.RING) return
        // A new call: only from a contact, whose card's key opens it.
        handler.post {
            val card = Hub.node?.store?.contact(m.from) ?: return@post
            val key = Crypto.boxShared(card.boxPublic, me.boxSecret)
            val data = m.openWith(key) ?: return@post
            val busy = current
            if (busy != null) {
                if (!(busy.card.nodeId.contentEquals(m.from) && busy.session.id == m.call))
                    islands?.sendDirect(CallMsg.sealWith(key, me.nodeId, m.from, CallMsg.BUSY, m.call, ByteArray(0)).encode(), m.from)
                return@post
            }
            val call = Call(CallSession(m.call, m.from, outgoing = false, video = data.firstOrNull()?.toInt() == 1, now = now()), card, key)
            current = call
            send(call, CallMsg.RINGING)
            FieldLog.add("LLAMADA", "me llama ${card.name}")
            ring(call)
            changed()
        }
    }

    /* ---------- the call's life ---------- */

    private val loop = object : Runnable {
        override fun run() { tick(); handler.postDelayed(this, 200) }
    }

    private fun tick() {
        val c = current ?: return
        val now = now()
        if (c.session.wantsRing(now)) send(c, CallMsg.RING, byteArrayOf(if (c.session.video) 1 else 0))
        if (c.session.state == CallSession.State.ACTIVE && now - c.lastPing >= CallSession.PING_MS) { c.lastPing = now; send(c, CallMsg.PING) }
        c.session.tick(now)
        // Tell the other side too, so its phone stops ringing or calling.
        if (c.session.state == CallSession.State.ENDED) end(c, sendHangup = true)
    }

    private fun talk(c: Call) {
        stopRinging()
        val ctx = context ?: return
        c.audio = AudioEngine(ctx) { piece -> send(c, CallMsg.AUDIO, piece) }.also { it.start(); it.speaker = c.session.video }
        if (c.session.video) c.video = VideoEngine(ctx) { piece -> send(c, CallMsg.VIDEO, piece) }
        main.post { runCatching { onTalking(true, c.session.video) } }
        FieldLog.add("LLAMADA", "hablando con ${c.card.name}")
        changed()
    }

    private fun end(c: Call, sendHangup: Boolean) {
        if (current !== c) return
        if (c.session.state != CallSession.State.ENDED) c.session.hangUp(now())
        if (sendHangup) send(c, CallMsg.HANGUP)
        stopRinging()
        c.audio?.stop(); c.video?.stop()
        current = null
        main.post { runCatching { onTalking(false, false) } }
        val ctx = context
        if (ctx != null) {
            ctx.getSystemService(NotificationManager::class.java).cancel(ID_RING)
            // Missed: it rang here and nobody answered (it timed out, or the caller gave up).
            if (!c.session.outgoing && c.session.startedAt == 0L && c.session.end != CallSession.End.REJECTED) notifyMissed(ctx, c)
        }
        FieldLog.add("LLAMADA", "terminó: ${endText(c.session)}")
        lastEnded = c
        changed()
    }

    /** The last call that ended, so the screen can say how. */
    @Volatile var lastEnded: Call? = null; private set

    fun endText(s: CallSession): String = when (s.end) {
        CallSession.End.HUNG_UP -> if (s.startedAt > 0) "llamada terminada (${clock(s.duration())})" else "llamada cancelada"
        CallSession.End.REJECTED -> "rechazó la llamada"
        CallSession.End.BUSY -> "está en otra llamada"
        CallSession.End.UNREACHABLE -> "no está en tu isla: las llamadas van en vivo, solo con alguien de tu misma isla"
        CallSession.End.NO_ANSWER -> "no contesta"
        CallSession.End.MISSED -> "llamada perdida"
        CallSession.End.LOST -> "se cortó: la isla se separó"
        null -> ""
    }

    fun clock(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

    /* ---------- ringing ---------- */

    private fun ring(c: Call) {
        val ctx = context ?: return
        val open = Intent(ctx, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(ctx, ID_RING, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(ctx, CH_CALL).setSmallIcon(R.drawable.ic_launcher_fg)
            .setContentTitle(c.card.name).setContentText(if (c.session.video) "📹 Videollamada por CHAMULLO" else "📞 Llamada por CHAMULLO")
            .setCategory(Notification.CATEGORY_CALL).setOngoing(true).setFullScreenIntent(pi, true).setContentIntent(pi).build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(ID_RING, n) }
        runCatching { ctx.startActivity(open) } // with the app on screen, straight to the call
        runCatching {
            ringtone = RingtoneManager.getRingtone(ctx, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))?.apply {
                audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build()
                if (android.os.Build.VERSION.SDK_INT >= 28) isLooping = true
                play()
            }
        }
    }

    private fun stopRinging() {
        runCatching { ringtone?.stop() }; ringtone = null
        context?.getSystemService(NotificationManager::class.java)?.cancel(ID_RING)
    }

    private fun notifyMissed(ctx: Context, c: Call) {
        val open = Intent(ctx, ChatActivity::class.java).putExtra(ChatActivity.EXTRA_PEER, c.card.nodeId)
        val pi = PendingIntent.getActivity(ctx, c.card.nodeId.toHex().hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        runCatching {
            ctx.getSystemService(NotificationManager::class.java).notify(c.card.nodeId.toHex().hashCode(), Notification.Builder(ctx, GritoService.CH_MSG)
                .setSmallIcon(R.drawable.ic_launcher_fg).setContentTitle("Llamada perdida de ${c.card.name}").setContentIntent(pi).setAutoCancel(true).build())
        }
    }

    private fun now() = System.currentTimeMillis()

    const val CH_CALL = "call"
    const val ID_RING = 40
}
