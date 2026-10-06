package ar.chamullo.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import ar.chamullo.app.Ui.avatar
import ar.chamullo.app.Ui.dp
import ar.chamullo.app.Ui.text
import ar.chamullo.core.CallSession

/**
 * The call screen (Camino y Carretera §7), dark like ICEBREAK's: who, how it goes, and round buttons. With video, the
 * other side fills the screen and my camera sits in a corner. Starting a call: [EXTRA_PEER] and [EXTRA_VIDEO].
 */
class CallActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var remote: TextureView
    private lateinit var local: TextureView
    private lateinit var who: LinearLayout
    private lateinit var status: TextView
    private lateinit var buttons: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val listener: () -> Unit = { render() }
    private var cameraStarted = false
    private var pendingStart: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        remote = TextureView(this).apply { visibility = View.GONE }
        local = TextureView(this).apply { visibility = View.GONE }
        status = text("", 15f, 0xFFDDDDDD.toInt()).apply { gravity = Gravity.CENTER }
        who = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(80), 0, 0) }
        buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(dp(16), 0, dp(16), dp(48)) }
        root = FrameLayout(this).apply {
            setBackgroundColor(Ui.INK); fitsSystemWindows = true
            addView(remote, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(who, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
            addView(local, FrameLayout.LayoutParams(dp(110), dp(150), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(16), dp(16), 0) })
            addView(buttons, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        }
        setContentView(root)
        remote.surfaceTextureListener = surfaces(onReady = { Calls.current?.video?.showRemote(remote) }, onSize = { Calls.current?.video?.refit() },
            onGone = { Calls.current?.video?.hideRemote() })
        local.surfaceTextureListener = surfaces(onReady = { startCameraIfTalking() }, onGone = {
            // My preview is gone: the camera stops until the call screen is back.
            if (cameraStarted) { cameraStarted = false; Calls.current?.video?.let { if (it.cameraOn) it.stopCamera() } }
        })
        startFromIntent(intent)
        render()
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); startFromIntent(intent); render() }

    // A call started from a chat: needs the microphone (and the camera for video) before ringing.
    private fun startFromIntent(i: Intent) {
        val peer = i.getByteArrayExtra(EXTRA_PEER) ?: return
        i.removeExtra(EXTRA_PEER)
        val video = i.getBooleanExtra(EXTRA_VIDEO, false)
        withPermissions(video) {
            Hub.ask({ n -> n.store.contact(peer) }) { card ->
                if (card == null || !Calls.start(card, video)) { status.text = "No se pudo llamar: ¿CHAMULLO está encendido?"; handler.postDelayed({ finish() }, 2_000) }
            }
        }
    }

    private fun withPermissions(video: Boolean, then: () -> Unit) {
        val need = listOfNotNull(Manifest.permission.RECORD_AUDIO, if (video) Manifest.permission.CAMERA else null)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isEmpty()) then() else { pendingStart = then; requestPermissions(need.toTypedArray(), PERMS) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != PERMS) return
        val p = pendingStart; pendingStart = null
        if (permissions.indices.all { permissions[it] != Manifest.permission.RECORD_AUDIO || grantResults[it] == PackageManager.PERMISSION_GRANTED }) p?.invoke()
        else { status.text = "Sin permiso de micrófono no se puede hablar."; Calls.current?.let { if (!it.session.outgoing) Calls.reject() } }
    }

    override fun onResume() { super.onResume(); Calls.listeners += listener; handler.post(timer); render() }

    override fun onPause() { Calls.listeners -= listener; handler.removeCallbacks(timer); super.onPause() }

    private val timer = object : Runnable { override fun run() { updateStatus(); handler.postDelayed(this, 1_000) } }

    /* ---------- drawing ---------- */

    private var drawnState: CallSession.State? = null

    private fun render() {
        val c = Calls.current
        if (c == null) {
            val ended = Calls.lastEnded
            status.text = ended?.let { Calls.endText(it.session) } ?: ""
            buttons.removeAllViews(); remote.visibility = View.GONE; local.visibility = View.GONE
            if (cameraStarted) cameraStarted = false
            handler.postDelayed({ if (Calls.current == null) finish() }, 2_500)
            drawnState = null
            return
        }
        if (who.childCount == 0 || who.tag != c.card.name) {
            who.removeAllViews(); who.tag = c.card.name
            who.addView(avatar(c.card.name, 96))
            who.addView(text(c.card.name, 26f, 0xFFFFFFFF.toInt(), bold = true).apply { gravity = Gravity.CENTER; setPadding(0, dp(14), 0, dp(6)) })
            who.addView(status)
        }
        updateStatus()
        val talking = c.session.state == CallSession.State.ACTIVE
        val video = c.session.video
        // With video and the other camera on, the picture fills the screen and the name steps aside.
        remote.visibility = if (video && talking) View.VISIBLE else View.GONE
        local.visibility = if (video && talking && c.video?.cameraOn != false) View.VISIBLE else View.GONE
        who.alpha = if (video && talking && c.video?.remoteCameraOn == true) 0f else 1f
        c.video?.onRemoteState = { render() }
        if (talking && video) { remote.surfaceTexture?.let { c.video?.showRemote(remote) }; startCameraIfTalking() }
        if (drawnState != c.session.state || buttons.childCount == 0) drawButtons(c)
        drawnState = c.session.state
    }

    private fun updateStatus() {
        val c = Calls.current ?: return
        status.text = when (c.session.state) {
            CallSession.State.CALLING -> "Llamando… (buscándolo en tu isla)"
            CallSession.State.RINGING -> if (c.session.outgoing) "Sonando…" else if (c.session.video) "📹 Videollamada entrante" else "📞 Llamada entrante"
            CallSession.State.ACTIVE -> Calls.clock(c.session.duration(System.currentTimeMillis()))
            CallSession.State.ENDED -> Calls.endText(c.session)
        }
    }

    private fun drawButtons(c: Calls.Call) {
        buttons.removeAllViews()
        if (!c.session.outgoing && c.session.state == CallSession.State.RINGING) {
            buttons.addView(round("phone", Ui.RED, "Rechazar") { Calls.reject() })
            buttons.addView(round(if (c.session.video) "video" else "phone", Ui.GREEN, "Atender") { withPermissions(c.session.video) { Calls.answer() } })
            return
        }
        if (c.session.state == CallSession.State.ACTIVE) {
            buttons.addView(emoji(if (c.audio?.muted == true) "🔇" else "🎤", if (c.audio?.muted == true) "Sin sonido" else "Micrófono") {
                c.audio?.let { it.muted = !it.muted }; drawButtons(c)
            })
            buttons.addView(emoji("🔊", if (c.audio?.speaker == true) "Altavoz ✓" else "Altavoz") { c.audio?.let { it.speaker = !it.speaker }; drawButtons(c) })
            if (c.session.video) {
                buttons.addView(emoji(if (c.video?.cameraOn == false) "🚫" else "📷", "Cámara") {
                    val v = c.video ?: return@emoji
                    if (v.cameraOn) v.stopCamera() else v.startCamera(local.surfaceTexture)
                    handler.postDelayed({ render(); drawButtons(c) }, 400)
                })
                buttons.addView(emoji("🔄", "Girar") { c.video?.switchCamera(local.surfaceTexture) })
            }
        }
        buttons.addView(round("phone", Ui.RED, "Colgar") { Calls.hangUp() })
    }

    private fun startCameraIfTalking() {
        val c = Calls.current ?: return
        if (cameraStarted || !c.session.video || c.session.state != CallSession.State.ACTIVE) return
        val tex = local.surfaceTexture ?: return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        cameraStarted = true
        c.video?.startCamera(tex)
    }

    /** A big round button with an ICEBREAK icon and its label under it. */
    private fun round(icon: String, color: Int, label: String, onClick: () -> Unit) = column(label, onClick) {
        ImageView(this).apply {
            setImageDrawable(Icons.of(icon, 0xFFFFFFFF.toInt()))
            val p = dp(18); setPadding(p, p, p, p)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        }
    }

    private fun emoji(e: String, label: String, onClick: () -> Unit) = column(label, onClick) {
        TextView(this).apply {
            text = e; textSize = 24f; gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x33FFFFFF) }
        }
    }

    private fun column(label: String, onClick: () -> Unit, face: () -> View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        addView(face().apply { layoutParams = LinearLayout.LayoutParams(dp(64), dp(64)); setOnClickListener { onClick() } })
        addView(text(label, 12f, 0xFFDDDDDD.toInt()).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) })
    }

    private fun surfaces(onReady: () -> Unit, onSize: () -> Unit = {}, onGone: () -> Unit = {}) = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) { onReady(); onSize() }
        override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) = onSize()
        override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean { onGone(); return true }
        override fun onSurfaceTextureUpdated(s: SurfaceTexture) = Unit
    }

    @Deprecated("Activity API")
    override fun onBackPressed() {
        // Back leaves the call screen but not the call, except while it rings here: that is a no.
        val c = Calls.current
        if (c != null && !c.session.outgoing && c.session.state == CallSession.State.RINGING) Calls.reject()
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    companion object {
        const val EXTRA_PEER = "peer"
        const val EXTRA_VIDEO = "video"
        const val PERMS = 11
    }
}
