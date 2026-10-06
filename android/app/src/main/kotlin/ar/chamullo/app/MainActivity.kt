package ar.chamullo.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ar.chamullo.app.Ui.avatar
import ar.chamullo.app.Ui.button
import ar.chamullo.app.Ui.card
import ar.chamullo.app.Ui.dp
import ar.chamullo.app.Ui.iconButton
import ar.chamullo.app.Ui.logo
import ar.chamullo.app.Ui.row
import ar.chamullo.app.Ui.text
import ar.chamullo.app.Ui.title
import ar.chamullo.core.Card
import ar.chamullo.core.Message
import ar.chamullo.core.NodeEvent
import ar.chamullo.core.toHex

/**
 * Home, laid out like ICEBREAK: a gray bar on top with five icons (Contactos, Chats, Apps, Guía, Configuración); the
 * selected one is black with a small line under it. Each tab fills the page below.
 */
class MainActivity : Activity() {
    private class Tab(val icon: String, val label: String)
    private val tabs = listOf(Tab("usuarios", "Contactos"), Tab("chat", "Chats"), Tab("apps", "Apps"), Tab("guia", "Guía"), Tab("gear", "Configuración"))
    private lateinit var bar: LinearLayout
    private lateinit var page: LinearLayout
    private lateinit var scroll: ScrollView
    private var current = 0
    private val listener: (NodeEvent?) -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Vault(this).hasIdentity()) { startActivity(Intent(this, OnboardingActivity::class.java)); finish(); return }
        current = Settings.tab(this).coerceIn(0, tabs.size - 1)
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Ui.BAR)
            setPadding(dp(8), dp(4), dp(8), dp(10))
        }
        page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(24)) }
        scroll = ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(page); layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f) }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; fitsSystemWindows = true; setBackgroundColor(Ui.BAR)
            addView(bar); addView(scroll)
        })
        askPermissions()
    }

    override fun onResume() {
        super.onResume()
        Hub.listeners += listener
        render()
        offerPendingCards()
        checkVersion()
    }

    override fun onPause() { Hub.listeners -= listener; super.onPause() }

    /* ---------- the top bar (ICEBREAK shell: 46 dp buttons, 30 dp icons, underline on the selected one) ---------- */

    private fun drawBar(unread: Int) {
        bar.removeAllViews()
        for ((i, t) in tabs.withIndex()) {
            val selected = i == current
            bar.addView(FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f)
                contentDescription = t.label
                setOnClickListener { current = i; Settings.setTab(this@MainActivity, i); scroll.scrollTo(0, 0); render() }
                addView(ImageView(this@MainActivity).apply {
                    setImageDrawable(Icons.of(t.icon, if (selected) Ui.BAR_ACTIVE else 0xFFFFFFFF.toInt()))
                    layoutParams = FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER)
                })
                if (selected) addView(View(this@MainActivity).apply {
                    background = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(Ui.BAR_ACTIVE) }
                    layoutParams = FrameLayout.LayoutParams(dp(18), dp(3), Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM)
                })
                if (i == 1 && unread > 0) addView(TextView(this@MainActivity).apply {
                    text = if (unread > 99) "99+" else unread.toString(); textSize = 10f; setTextColor(0xFFFFFFFF.toInt())
                    typeface = android.graphics.Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setPadding(dp(5), 0, dp(5), 0)
                    background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(Ui.RED) }
                    layoutParams = FrameLayout.LayoutParams(WRAP_CONTENT, dp(16), Gravity.CENTER).apply { leftMargin = dp(32); bottomMargin = dp(14) }
                })
            })
        }
    }

    private fun render() {
        Hub.ask({ node ->
            val cards = node.store.contacts()
            Snapshot(cards, cards.associate { it.nodeId.toHex() to node.store.messages(it.nodeId) }, node.neighbors().map { Triple(it.name, it.nodeId, it.coded) },
                node.nearby().map { it.shortId to it.name }, node.candies, node.available())
        }) { s ->
            val unread = s.chats.entries.sumOf { (id, msgs) -> msgs.count { !it.mine && it.ts > Settings.seenAt(this, id) } }
            drawBar(unread)
            page.removeAllViews()
            when (current) {
                0 -> contactsTab(s)
                1 -> chatsTab(s)
                2 -> appsTab(s)
                3 -> guideTab()
                else -> configTab(s)
            }
        }
    }

    private class Snapshot(
        val cards: List<Card>, val chats: Map<String, List<Message>>, val near: List<Triple<String, ByteArray, Boolean>>,
        val hellos: List<Pair<String, String>>, val candies: Int, val lucas: Long
    )

    private fun openChat(c: Card) = startActivity(Intent(this, ChatActivity::class.java).putExtra(ChatActivity.EXTRA_PEER, c.nodeId))

    /* ---------- 1. Contactos: favorites first, a star to mark them; who is near to swap cards ---------- */

    private fun contactsTab(s: Snapshot) {
        update = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(update); showUpdate()
        val favs = Settings.favorites(this)
        page.addView(title("Contactos"))
        if (s.cards.isEmpty()) page.addView(text("Todavía no tenés contactos. Acercate a otro celular con CHAMULLO e intercambien tarjetas.", 14f, Ui.MUTED))
        for (c in s.cards.sortedWith(compareByDescending<Card> { it.nodeId.toHex() in favs }.thenBy { it.name.lowercase() })) {
            val id = c.nodeId.toHex()
            page.addView(personCard(c.name, "id " + id.take(8), favorite = id in favs, onStar = { Settings.toggleFavorite(this, id); render() }) { openChat(c) })
        }
        page.addView(title("Cerca tuyo"))
        val known = s.cards.map { it.nodeId.toHex() }.toSet()
        val verified = s.near.map { it.second.toHex().take(16) }.toSet()
        if (s.near.isEmpty() && s.hellos.isEmpty()) page.addView(text("Nadie a la vista todavía.", 14f, Ui.MUTED))
        for ((shortId, name) in s.hellos.filter { it.first !in verified }) page.addView(card {
            addView(text(name.ifBlank { "Sin nombre" }, 16f, bold = true))
            addView(text("te escucho saludar · esperando su latido para intercambiar tarjeta", 12f, Ui.MUTED))
        })
        for ((name, nodeId, _) in s.near) page.addView(card {
            addView(text(name.ifBlank { "Sin nombre" }, 16f, bold = true))
            if (nodeId.toHex() in known) addView(text("Ya es tu contacto", 13f, Ui.GREEN))
            else addView(button("Intercambiar tarjeta", primary = false) {
                Hub.post { node -> node.neighbors().firstOrNull { it.nodeId.contentEquals(nodeId) }?.let { node.offerCard(it) } }
                toast("Tarjeta enviada a ${name.ifBlank { "tu vecino" }}. Cuando acepte, aparece en Contactos.")
            })
        })
    }

    /** ICEBREAK's user card: avatar, name, a line below, and a star on the right. */
    private fun personCard(name: String, sub: String, favorite: Boolean, onStar: () -> Unit, onTap: () -> Unit) = card {
        addView(row {
            addView(avatar(name))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(8), 0)
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                addView(text(name.ifBlank { "Sin nombre" }, 16.5f, bold = true))
                addView(text(sub, 13f, Ui.MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            })
            addView(iconButton("star", 40, if (favorite) 0xFFF2C230.toInt() else Ui.MUTED, filled = favorite) { onStar() })
        })
        setOnClickListener { onTap() }
    }

    /* ---------- 2. Chats: the conversations, newest first, favorites on top, unread in bold ---------- */

    private fun chatsTab(s: Snapshot) {
        val favs = Settings.favorites(this)
        page.addView(title("Chats"))
        val talks = s.cards.mapNotNull { c -> s.chats[c.nodeId.toHex()]?.lastOrNull()?.let { c to it } }
            .sortedWith(compareByDescending<Pair<Card, Message>> { it.first.nodeId.toHex() in favs }.thenByDescending { it.second.ts })
        if (talks.isEmpty()) page.addView(text("Todavía no hay conversaciones. Tocá un contacto para escribirle.", 14f, Ui.MUTED))
        for ((c, last) in talks) {
            val id = c.nodeId.toHex()
            val unread = s.chats.getValue(id).count { !it.mine && it.ts > Settings.seenAt(this, id) }
            val preview = (if (last.mine) "Vos: " else "") + last.text + if (unread > 0) "  ·  $unread nuevas" else ""
            page.addView(personCard(c.name, preview, favorite = id in favs, onStar = { Settings.toggleFavorite(this, id); render() }) { openChat(c) })
        }
    }

    /* ---------- 3. Apps: services that travel on CHAMULLO (none of them is CHAMULLO itself) ---------- */

    private fun appsTab(s: Snapshot) {
        page.addView(title("Apps"))
        page.addView(card {
            addView(text("💰 ${s.lucas} Lucas · 🍬 ${s.candies} caramelos", 16f, Ui.GREEN, bold = true))
            addView(button("Tienda de Lucas") { startActivity(Intent(this@MainActivity, StoreActivity::class.java)) })
        })
        page.addView(card {
            addView(text("🌐 Navegar con Internet prestado", 16f, bold = true))
            addView(text("Sin datos ni Wi-Fi: comprá megas con tus Lucas y navegá por un vecino de tu isla que preste Internet.", 13f, Ui.MUTED))
            addView(button(if (Hub.browsing) "✓ Navegando (abrí la Tienda para parar)" else "Navegar con mis datos", primary = false) { startActivity(Intent(this@MainActivity, StoreActivity::class.java)) })
        })
        page.addView(card {
            addView(text("💰 Prestar Internet y ganar Lucas", 16f, bold = true))
            addView(text("Tu Internet lleva cartas donde no llegan los celulares, y cada una te paga Lucas.", 13f, Ui.MUTED))
            addView(button(if (Settings.lendInternet(this@MainActivity)) "✓ Prestando Internet (tocá para dejar de prestar)" else "Prestar Internet y ganar Lucas",
                primary = !Settings.lendInternet(this@MainActivity)) { Settings.toggleLendInternet(this@MainActivity); render() })
        })
        page.addView(card {
            addView(text("La Plaza", 16f, bold = true))
            addView(text("Chat abierto con los que están cerca.", 13f, Ui.MUTED))
            addView(button("Entrar a la Plaza", primary = false) { startActivity(Intent(this@MainActivity, PlazaActivity::class.java)) })
        })
        page.addView(card {
            addView(text("ICEBREAK", 16f, bold = true))
            addView(text("La red social. Por ahora se abre en el navegador y necesita Internet; CHAMULLO no.", 13f, Ui.MUTED))
            addView(button("Abrir ICEBREAK", primary = false) { web(ICEBREAK_URL) })
        })
    }

    /* ---------- 4. Guía: ICEBREAK first (web or APK), then how to use CHAMULLO ---------- */

    private fun guideTab() {
        page.addView(title("ICEBREAK"))
        page.addView(card {
            addView(text("ICEBREAK es la red social que viaja sobre CHAMULLO.", 14f))
            addView(text("💡 La guía completa de ICEBREAK está adentro de ICEBREAK: Configuración → Guía de uso.", 13f, Ui.MUTED))
            addView(button("Abrir ICEBREAK en la web") { web(ICEBREAK_URL) })
            addView(button("Descargar ICEBREAK para Android", primary = false) { web(ICEBREAK_APK) })
        })
        page.addView(title("Guía de uso"))
        val steps = listOf(
            "1. Intercambiá tarjetas" to "En Contactos → Cerca tuyo, tocá \"Intercambiar tarjeta\" con alguien que tenga CHAMULLO. Cuando acepta, ya se pueden escribir aunque estén lejos.",
            "2. Escribí" to "Tocá el contacto y escribí. La carta salta de celular en celular hasta llegar; ✓✓ quiere decir que llegó.",
            "3. Mandá fotos, videos y más" to "En el chat, el clip te deja mandar fotos, videos, archivos, tu ubicación o un contacto (hasta 3 MB).",
            "4. Favoritos" to "Tocá la estrella de un contacto: queda primero en Contactos y en Chats.",
            "5. Ganá Lucas" to "Llevando cartas de otros y prestando Internet ganás Lucas, anotadas en la libreta de tu pueblo. Se gastan en la Tienda o para que una carta pase primero.",
            "6. Islas" to "Los celulares de cerca forman islas Wi-Fi solos. No hace falta conectarse a ninguna red."
        )
        for ((h, b) in steps) page.addView(card { addView(text(h, 16f, bold = true)); addView(text(b, 14f)) })
    }

    /* ---------- 5. Configuración: everything else of CHAMULLO ---------- */

    private fun configTab(s: Snapshot) {
        val vault = Vault(this)
        page.addView(logo())
        page.addView(card {
            addView(text(vault.name(), 18f, bold = true))
            addView(text("id " + (vault.identity()?.nodeId?.toHex()?.take(8) ?: "—"), 13f, Ui.MUTED))
            addView(text(radioStatus(), 14f, Ui.MUTED))
        })
        page.addView(title("Red"))
        page.addView(button(if (Settings.lendInternet(this)) "✓ Prestando Internet (tocá para dejar de prestar)" else "Prestar Internet y ganar Lucas",
            primary = false) { Settings.toggleLendInternet(this); render() })
        page.addView(button("Diagnóstico del grito, libreta y caja negra", primary = false) { startActivity(Intent(this, DiagActivity::class.java)) })
        page.addView(title("Mi identidad"))
        page.addView(button("Ver mis 16 palabras", primary = false) { showPhrase(vault) })
        page.addView(title("Versión"))
        page.addView(text("CHAMULLO ${WebVersion.installed(this)}", 14f, Ui.MUTED))
        page.addView(button("Ver la página de descarga", primary = false) { web(WebVersion.DOWNLOAD_PAGE) })
    }

    private fun radioStatus(): String {
        val radios = Hub.radios
        val locationOff = android.os.Build.VERSION.SDK_INT >= 28 && !(getSystemService(android.location.LocationManager::class.java)?.isLocationEnabled ?: true)
        val warning = if (locationOff) "⚠ Prendé la Ubicación: Android la exige para encontrar islas.\n" else ""
        return warning + if (radios.isEmpty()) "El grito está apagado: falta dar permisos." else radios.joinToString("\n") { r ->
            when {
                !r.supported -> "${r.label}: este celular no lo tiene."
                r is WifiIslands -> if (r.island != null) "Isla Wi-Fi: en la isla ${r.islandName} ✓ (${r.peers} caños)" else "Isla Wi-Fi: buscando islas…"
                r.active -> "${r.label}: gritando ✓"
                else -> "${r.label}: apagado (es el respaldo)."
            }
        }
    }

    /* ---------- the rest: version notice, cards waiting, phrase, links ---------- */

    private var update: LinearLayout? = null

    private fun checkVersion() = Thread { WebVersion.fetch(); runOnUiThread { showUpdate() } }.start()

    private fun showUpdate() {
        val u = update ?: return
        u.removeAllViews()
        if (!WebVersion.needsUpdate(this)) return
        u.addView(card {
            addView(text("Hay una versión nueva: ${WebVersion.latest}", 16f, Ui.GREEN, bold = true))
            addView(text("Tenés la ${WebVersion.installed(this@MainActivity)}. Bajala de la página e instalala encima: tus contactos y cartas se mantienen.", 13f, Ui.MUTED))
            addView(button("Descargar la nueva versión") { web(WebVersion.DOWNLOAD_PAGE) })
        })
    }

    private fun offerPendingCards() {
        val card: Card = synchronized(Hub.pendingCards) { Hub.pendingCards.removeFirstOrNull() } ?: return
        AlertDialog.Builder(this)
            .setTitle("${card.name} quiere intercambiar tarjetas")
            .setMessage("Si aceptás, se pueden escribir aunque estén lejos: las cartas viajan de celular en celular.")
            .setPositiveButton("Aceptar") { _, _ -> Hub.post { it.acceptCard(card); Hub.emit(null) } }
            .setNegativeButton("Ahora no", null)
            .setOnDismissListener { offerPendingCards() }
            .show()
    }

    private fun showPhrase(vault: Vault) {
        AlertDialog.Builder(this).setTitle("Tus 16 palabras").setMessage(vault.phrase()).setPositiveButton("Listo", null).show()
    }

    private fun web(url: String) = startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show()

    /* ---------- permissions and start ---------- */
    private fun needed(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= 31) { add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_ADVERTISE); add(Manifest.permission.BLUETOOTH_CONNECT) }
        add(Manifest.permission.ACCESS_FINE_LOCATION) // the compass: only the ~50 m cell leaves the phone (Discovery & Routing §2)
        if (Build.VERSION.SDK_INT >= 33) { add(Manifest.permission.NEARBY_WIFI_DEVICES); add(Manifest.permission.POST_NOTIFICATIONS) }
    }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()

    private fun askPermissions() {
        val missing = needed()
        if (missing.isEmpty()) startGrito() else requestPermissions(missing, REQ_PERMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // The grito restarts with whatever antenna got permission; Configuración says which ones work.
        stopService(Intent(this, GritoService::class.java))
        startGrito()
    }

    private fun startGrito() {
        startForegroundService(Intent(this, GritoService::class.java))
        page.postDelayed({ render() }, 1500)
    }

    companion object {
        const val ICEBREAK_URL = "https://nicoconvoz.github.io/icebreak-web/"
        const val ICEBREAK_APK = "https://nicoconvoz.github.io/icebreak-web/android.html"
        private const val REQ_PERMS = 7
    }
}
