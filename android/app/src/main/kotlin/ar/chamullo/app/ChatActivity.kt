package ar.chamullo.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import ar.chamullo.app.Ui.avatar
import ar.chamullo.app.Ui.dp
import ar.chamullo.app.Ui.iconButton
import ar.chamullo.app.Ui.text
import ar.chamullo.core.Card
import ar.chamullo.core.Letter
import ar.chamullo.core.Media
import ar.chamullo.core.Message
import ar.chamullo.core.MessageState
import ar.chamullo.core.NodeEvent
import ar.chamullo.core.toHex
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The private chat, copied from ICEBREAK's conversation screen: a header with the contact and a row of square buttons
 * (call, video call, delete contact), bubbles (mine dark, theirs white with a line, the "tail" corner sharp) and the
 * composer pill (clip, text, camera) with the send button. Attachments travel as sealed letters (Packet Format §5.6).
 */
class ChatActivity : Activity() {
    private lateinit var peer: ByteArray
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var box: EditText
    private lateinit var priority: CheckBox
    private lateinit var actions: LinearLayout
    private var name = "Contacto"
    private var pendingPhoto: Uri? = null
    private val clock = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val listener: (NodeEvent?) -> Unit = { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        peer = intent.getByteArrayExtra(EXTRA_PEER) ?: run { finish(); return }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        scroll = ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(list); layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f) }
        val header = header()
        actions = actionRow().apply { visibility = View.GONE }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; fitsSystemWindows = true; setBackgroundColor(Ui.BG)
            addView(header); addView(actions); addView(scroll); addView(composer())
        })
        Hub.ask({ it.store.contact(peer)?.name ?: "Contacto" }) { n -> name = n; refreshHeader(header) }
    }

    override fun onResume() { super.onResume(); Hub.listeners += listener; refresh() }

    override fun onPause() { Hub.listeners -= listener; super.onPause() }

    /* ---------- header: back, avatar, name, and the chevron that shows the square buttons ---------- */

    private lateinit var nameView: android.widget.TextView

    private fun header() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(8), dp(8), dp(8)); setBackgroundColor(Ui.SURFACE)
        addView(iconButton("back", 44) { finish() })
        addView(avatar(name, 42))
        nameView = text(name, 17f, bold = true).apply {
            setPadding(dp(12), 0, 0, 0); layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        addView(nameView)
        var open = false
        addView(iconButton("down", 44) {}.apply {
            setOnClickListener {
                open = !open
                actions.visibility = if (open) View.VISIBLE else View.GONE
                setImageDrawable(Icons.of(if (open) "up" else "down", Ui.INK))
            }
        })
    }

    private fun refreshHeader(h: LinearLayout) {
        nameView.text = name
        h.removeViewAt(1); h.addView(avatar(name, 42), 1)
    }

    private fun actionRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        setPadding(dp(8), dp(4), dp(8), dp(10)); setBackgroundColor(Ui.SURFACE)
        addView(square("phone", "Llamada") { call(video = false) })
        addView(square("video", "Videollamada") { call(video = true) })
        addView(square("trash", "Eliminar contacto", Ui.RED) { deleteContact() })
    }

    /** ICEBREAK's IbSquare: a 52 dp box with an icon and its label under it. */
    private fun square(icon: String, label: String, color: Int = Ui.INK, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        addView(ImageView(this@ChatActivity).apply {
            setImageDrawable(Icons.of(icon, color))
            val p = dp(12); setPadding(p, p, p, p)
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Ui.SOFT); setStroke(dp(1), Ui.LINE) }
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
        })
        addView(text(label, 12f, color).apply { gravity = Gravity.CENTER })
        setOnClickListener { onClick() }
    }

    private fun call(video: Boolean) {
        AlertDialog.Builder(this)
            .setTitle(if (video) "Videollamada" else "Llamada")
            .setMessage("Las llamadas van en vivo, así que solo funcionan cuando $name está en tu misma isla o al alcance del Wi-Fi: saltar de celular en celular tarda segundos. Llegan en la próxima versión.")
            .setPositiveButton("Entendido", null).show()
    }

    private fun deleteContact() {
        AlertDialog.Builder(this)
            .setTitle("¿Eliminar a $name?")
            .setMessage("Deja de estar en tus contactos. Para volver a escribirse tienen que intercambiar tarjetas de nuevo.")
            .setPositiveButton("Eliminar") { _, _ -> Hub.post { it.store.deleteContact(peer); Hub.emit(null) }; finish() }
            .setNegativeButton("Cancelar", null).show()
    }

    /* ---------- the composer: the pill (clip · text · camera) and the round send button ---------- */

    private fun composer() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(4), dp(10), dp(10)); setBackgroundColor(Ui.BG)
        priority = CheckBox(this@ChatActivity).apply { text = "⚡ Con prioridad: $PRIORITY_LUCAS Lucas para los carteros"; textSize = 13f; setTextColor(Ui.MUTED) }
        addView(priority)
        addView(LinearLayout(this@ChatActivity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(this@ChatActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply { cornerRadius = dp(999).toFloat(); setColor(Ui.SURFACE); setStroke(dp(1) + dp(1) / 2, Ui.LINE) }
                setPadding(dp(4), 0, dp(4), 0)
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                addView(iconButton("clip", 42) { attach() })
                box = EditText(this@ChatActivity).apply {
                    hint = "Escribir mensaje"; textSize = 15f; background = null; maxLines = 5
                    layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                }
                addView(box)
                addView(iconButton("camera", 42) { takePhoto() })
            })
            addView(ImageView(this@ChatActivity).apply {
                setImageDrawable(Icons.of("send", 0xFFFFFFFF.toInt()))
                val p = dp(12); setPadding(p, p, p, p)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.INK) }
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(8) }
                setOnClickListener { sendText() }
            })
        })
    }

    private fun sendText() {
        val msg = box.text.toString().trim()
        if (msg.isEmpty()) return
        box.setText("")
        val paid = if (priority.isChecked) PRIORITY_LUCAS else 0L
        Hub.post { node -> node.store.contact(peer)?.let { node.send(it, msg, priority = paid) }; Hub.emit(null) }
    }

    private fun sendMedia(m: Letter.Media) {
        if (m.data.size > Media.MAX_BYTES) { toast("Es muy grande: hasta 3 MB por carta (este pesa ${m.data.size / 1024 / 1024} MB)."); return }
        Hub.post { node -> node.store.contact(peer)?.let { node.sendMedia(it, m) }; Hub.emit(null) }
    }

    /* ---------- attachments: photo, video, file, my location, a contact ---------- */

    private fun attach() {
        val options = arrayOf("📷 Foto de la galería", "📸 Sacar una foto", "🎬 Video", "📎 Archivo", "📍 Mi ubicación", "👤 Compartir un contacto")
        AlertDialog.Builder(this).setTitle("Adjuntar").setItems(options) { _, i ->
            when (i) {
                0 -> pick("image/*", REQ_IMAGE)
                1 -> takePhoto()
                2 -> pick("video/*", REQ_VIDEO)
                3 -> pick("*/*", REQ_FILE)
                4 -> shareLocation()
                5 -> shareContact()
            }
        }.show()
    }

    private fun pick(type: String, req: Int) =
        startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType(type).addCategory(Intent.CATEGORY_OPENABLE), req)

    private fun takePhoto() {
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (Build.VERSION.SDK_INT >= 29) {
            // The camera writes into a gallery entry we create; no file sharing library needed.
            pendingPhoto = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "chamullo_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CHAMULLO")
            })
            i.putExtra(MediaStore.EXTRA_OUTPUT, pendingPhoto)
        }
        runCatching { startActivityForResult(i, REQ_CAMERA) }.onFailure { toast("Este celular no tiene una app de cámara.") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        Thread {
            runCatching {
                val m: Letter.Media? = when (requestCode) {
                    REQ_IMAGE -> data?.data?.let { photo(it) }
                    REQ_CAMERA -> pendingPhoto?.let { photo(it) } ?: (data?.extras?.get("data") as? Bitmap)?.let { jpeg(it, "foto.jpg") }
                    REQ_VIDEO -> data?.data?.let { raw(it, Media.VIDEO, "video/mp4") }
                    REQ_FILE -> data?.data?.let { raw(it, Media.FILE, "application/octet-stream") }
                    else -> null
                }
                runOnUiThread { m?.let { sendMedia(it) } }
            }.onFailure { runOnUiThread { toast("No pude leer el archivo: ${it.message}") } }
        }.start()
    }

    // Photos are made light before they travel: at most 1280 px on the long side, JPEG 80.
    private fun photo(uri: Uri): Letter.Media {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2560) sample *= 2
        val bmp = contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }!!
        return jpeg(bmp, nameOf(uri) ?: "foto.jpg")
    }

    private fun jpeg(src: Bitmap, fileName: String): Letter.Media {
        val scale = minOf(1f, 1280f / maxOf(src.width, src.height))
        val bmp = if (scale < 1f) Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true) else src
        val out = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return Letter.Media(Media.PHOTO, fileName.substringBeforeLast('.') + ".jpg", "image/jpeg", out.toByteArray())
    }

    private fun raw(uri: Uri, kind: Int, fallbackMime: String): Letter.Media {
        val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        return Letter.Media(kind, nameOf(uri) ?: "archivo", contentResolver.getType(uri) ?: fallbackMime, bytes)
    }

    private fun nameOf(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    @Suppress("MissingPermission")
    private fun shareLocation() {
        val lm = getSystemService(LocationManager::class.java)
        val loc = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).mapNotNull { p -> runCatching { lm?.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }
        if (loc == null) { toast("Todavía no sé dónde estás: prendé la ubicación y esperá un ratito."); return }
        AlertDialog.Builder(this).setTitle("¿Mandar tu ubicación a $name?")
            .setMessage("Va sellada: solo $name la puede abrir.")
            .setPositiveButton("Mandar") { _, _ -> sendMedia(Letter.Media.location(loc.latitude, loc.longitude)) }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun shareContact() = Hub.ask({ it.store.contacts().filter { c -> !c.nodeId.contentEquals(peer) } }) { cards ->
        if (cards.isEmpty()) { toast("No tenés otros contactos para compartir."); return@ask }
        AlertDialog.Builder(this).setTitle("¿Qué contacto le pasás a $name?")
            .setItems(cards.map { it.name.ifBlank { it.nodeId.toHex().take(8) } }.toTypedArray()) { _, i -> sendMedia(Letter.Media.contact(cards[i])) }
            .show()
    }

    /* ---------- the bubbles ---------- */

    private fun refresh() = Hub.ask({ node -> node.store.messages(peer).map { it to it.media?.let { _ -> node.store.loadMedia(it.msgId) } } }) { messages ->
        Settings.markSeen(this, peer.toHex())
        list.removeAllViews()
        if (messages.isEmpty()) list.addView(text("Todavía no se escribieron. La primera carta puede tardar si están lejos.", 14f, Ui.MUTED).apply { setPadding(dp(8), dp(12), dp(8), dp(12)) })
        for ((m, bytes) in messages) list.addView(bubble(m, bytes))
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun bubble(m: Message, bytes: ByteArray?) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = if (m.mine) Gravity.END else Gravity.START
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) }
        val ink = if (m.mine) 0xFFFFFFFF.toInt() else Ui.INK
        addView(LinearLayout(this@ChatActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(10), dp(13), dp(8))
            val r = dp(16).toFloat(); val tail = dp(5).toFloat()
            background = GradientDrawable().apply {
                // corners: top-left, top-right, bottom-right, bottom-left; the tail is the sharp one
                cornerRadii = if (m.mine) floatArrayOf(r, r, r, r, tail, tail, r, r) else floatArrayOf(r, r, r, r, r, r, tail, tail)
                setColor(if (m.mine) Ui.INK else Ui.SURFACE)
                if (!m.mine) setStroke(dp(1), Ui.LINE)
            }
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { width = WRAP_CONTENT }
            val media = m.media
            if (media == null || bytes == null) addView(text(m.text, 15f, ink).apply { setLineSpacing(0f, 1.38f); maxWidth = (resources.displayMetrics.widthPixels * 0.72).toInt() })
            else addView(content(m, bytes, ink))
            val state = when (m.state) { MessageState.SENT -> " · en camino"; MessageState.DELIVERED -> " ✓✓"; MessageState.RECEIVED -> "" }
            addView(text(clock.format(Date(m.ts)) + state, 10.5f, (ink and 0x00FFFFFF) or 0x8C000000.toInt()).apply {
                typeface = android.graphics.Typeface.MONOSPACE; gravity = Gravity.END
            })
        })
    }

    private fun content(m: Message, bytes: ByteArray, ink: Int): View {
        val ref = m.media!!
        val letter = Letter.Media(ref.kind, ref.name, ref.mime, bytes)
        return when (ref.kind) {
            Media.PHOTO -> ImageView(this).apply {
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = 2 })
                setImageBitmap(bmp); adjustViewBounds = true; maxWidth = dp(240); maxHeight = dp(320)
                clipToOutline = true
                setOnClickListener { showPhoto(bytes) }
            }
            Media.LOCATION -> chip("map", "Ubicación", "Tocá para abrir el mapa", ink) {
                letter.latLon()?.let { (lat, lon) -> open(Uri.parse("geo:$lat,$lon?q=$lat,$lon(${Uri.encode(name)})"), null) }
            }
            Media.CONTACT -> LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val card: Card? = letter.card()
                addView(chip("perfil", card?.name ?: ref.name, if (card != null) "Contacto compartido" else "Tarjeta dañada", ink) {})
                if (card != null && !m.mine) addView(text("➕ Agregar a mis contactos", 14f, if (m.mine) ink else Ui.BLUE, bold = true).apply {
                    setPadding(0, dp(6), 0, 0)
                    setOnClickListener { Hub.post { it.store.saveContact(card); Hub.emit(null) }; toast("${card.name} ya está en tus contactos.") }
                })
            }
            Media.VIDEO -> chip("video", "Video", "${size(bytes.size)} · tocá para verlo", ink) { save(ref.name, ref.mime, bytes, video = true) }
            else -> chip("clip", ref.name, "${size(bytes.size)} · tocá para abrirlo", ink) { save(ref.name, ref.mime, bytes, video = false) }
        }
    }

    /** ICEBREAK's file chip: an icon, the name in bold and a line below. */
    private fun chip(icon: String, title: String, sub: String, ink: Int, onTap: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(ImageView(this@ChatActivity).apply { setImageDrawable(Icons.of(icon, ink)); layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)) })
        addView(LinearLayout(this@ChatActivity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0)
            addView(text(title, 15f, ink, bold = true).apply { maxWidth = dp(200); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
            addView(text(sub, 12f, (ink and 0x00FFFFFF) or 0xB3000000.toInt()))
        })
        setOnClickListener { onTap() }
    }

    private fun showPhoto(bytes: ByteArray) {
        val v = ImageView(this).apply { setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)); adjustViewBounds = true; setBackgroundColor(0xFF000000.toInt()) }
        AlertDialog.Builder(this).setView(v).setPositiveButton("Cerrar", null).show()
    }

    // Received videos and files go to the phone's Downloads (or Movies), and open with the app that knows them.
    private fun save(fileName: String, mime: String, bytes: ByteArray, video: Boolean) {
        if (Build.VERSION.SDK_INT < 29) { toast("Abrir adjuntos necesita Android 10 o más nuevo."); return }
        val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val uri = contentResolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (video) "Movies/CHAMULLO" else "Download/CHAMULLO")
        }) ?: run { toast("No pude guardarlo."); return }
        contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        open(uri, mime)
    }

    private fun open(uri: Uri, mime: String?) {
        val i = Intent(Intent.ACTION_VIEW).apply { if (mime != null) setDataAndType(uri, mime) else data = uri; addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        runCatching { startActivity(i) }.onFailure { toast("No hay una app para abrir esto. Quedó guardado en Descargas.") }
    }

    private fun size(n: Int) = if (n >= 1024 * 1024) "%.1f MB".format(n / 1048576f) else "${n / 1024} KB"

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    companion object {
        const val EXTRA_PEER = "peer"
        // What a letter offers its carriers to go first (Economy & Governance §6.1).
        const val PRIORITY_LUCAS = 2L
        private const val REQ_IMAGE = 21
        private const val REQ_CAMERA = 22
        private const val REQ_VIDEO = 23
        private const val REQ_FILE = 24
    }
}
