package ar.chamullo.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ar.chamullo.app.Ui.button
import ar.chamullo.app.Ui.card
import ar.chamullo.app.Ui.dp
import ar.chamullo.app.Ui.iconButton
import ar.chamullo.app.Ui.text
import ar.chamullo.app.Ui.title
import ar.chamullo.core.Shop
import ar.chamullo.core.hex
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * La Tienda de Lucas (Economy & Governance §13): what the Lucas earned carrying letters or lending Internet buy. The
 * catalog comes from CHAMULLO's page (tienda.json) with the built-in one as fallback; each purchase is a signed spend
 * in the pueblo's ledger, and "Mis compras" is read back from it.
 */
class StoreActivity : Activity() {
    private lateinit var page: LinearLayout
    private var catalog: List<Shop.Category> = Shop.parse(Shop.DEFAULT)
    private var category = "datos"
    private val day = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(24)) }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; fitsSystemWindows = true; setBackgroundColor(Ui.BG)
            addView(LinearLayout(this@StoreActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(Ui.SURFACE); setPadding(dp(8), dp(8), dp(8), dp(8))
                addView(iconButton("back", 44) { finish() })
                addView(text("Tienda de Lucas", 18f, bold = true).apply { setPadding(dp(8), 0, 0, 0) })
            })
            addView(ScrollView(this@StoreActivity).apply { addView(page); layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f) })
        })
        catalog = Shop.parseOrDefault(Settings.catalog(this))
        render()
        Thread { // the page's catalog: new prices and products without a new app
            val fresh = runCatching {
                val c = URL("$CATALOG_URL?t=${System.currentTimeMillis()}").openConnection() as HttpURLConnection
                c.connectTimeout = 8_000; c.readTimeout = 8_000
                try { if (c.responseCode == 200) c.inputStream.bufferedReader().readText() else null } finally { c.disconnect() }
            }.getOrNull()
            if (fresh != null && runCatching { Shop.parse(fresh) }.isSuccess) Settings.setCatalog(this, fresh)
            Shop.parseOrDefault(fresh ?: Settings.catalog(this)).let { runOnUiThread { catalog = it; render() } }
        }.start()
    }

    private fun seller(): ByteArray? = Settings.founder(this).takeIf { it.length == 64 }?.let { hex(it) }

    private fun render() {
        Hub.ask({ n -> Triple(n.available(), n.ledger?.let { Shop.purchases(it, n.identity.nodeId, catalog) } ?: emptyList(), n.ledger != null) }) { (lucas, mine, hasLedger) ->
        page.removeAllViews()
        // The wallet: Lucas to spend and the Internet already bought.
        page.addView(card {
            addView(text("💰 $lucas Lucas para gastar", 22f, Ui.GREEN, bold = true))
            val mb = Shop.dataMb(mine)
            addView(text("🌐 Internet prestado comprado: ${size(mb)}", 15f, bold = true))
            if (mb > 0) {
                val left = Hub.tunnel?.left() ?: 0
                addView(text("Te quedan ${size((left / (1024 * 1024)).toInt())}", 14f, Ui.MUTED))
                addView(button(if (Hub.browsing) "✓ Navegando con mis datos (tocá para parar)" else "Navegar con mis datos", primary = !Hub.browsing) { toggleBrowsing() })
                if (Hub.browsing) addView(text("📶 ${Hub.tunnel?.status ?: "CHAMULLO no está encendido"}. Abrí tu navegador y navegá normal.", 13f, Ui.MUTED))
            }
            addView(text("Las Lucas se ganan llevando cartas y prestando Internet. Cada compra queda en la libreta de tu pueblo.", 13f, Ui.MUTED))
            if (!hasLedger) addView(text("⏳ Tu libreta todavía no arrancó (espera la ubicación). Las compras se habilitan enseguida.", 13f, Ui.RED))
        })
        // Categories, like ICEBREAK's sub-tabs.
        page.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(LinearLayout(this@StoreActivity).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, dp(4))
                for (c in catalog) addView(TextView(this@StoreActivity).apply {
                    text = c.title; textSize = 14f; setPadding(dp(14), dp(8), dp(14), dp(8))
                    val on = c.id == category
                    setTextColor(if (on) 0xFFFFFFFF.toInt() else Ui.INK)
                    background = GradientDrawable().apply { cornerRadius = dp(999).toFloat(); setColor(if (on) Ui.INK else Ui.SURFACE); setStroke(dp(1), Ui.LINE) }
                    layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = dp(8) }
                    setOnClickListener { category = c.id; render() }
                })
            })
        })
        val current = catalog.firstOrNull { it.id == category } ?: catalog.first()
        for (item in current.items) page.addView(card {
            addView(LinearLayout(this@StoreActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(LinearLayout(this@StoreActivity).apply {
                    orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                    addView(text(item.name, 17f, bold = true))
                    addView(text(item.desc, 13f, Ui.MUTED))
                })
                addView(TextView(this@StoreActivity).apply {
                    text = "${item.price} Lucas"; textSize = 14f; setTextColor(0xFFFFFFFF.toInt()); typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                    background = GradientDrawable().apply { cornerRadius = dp(999).toFloat(); setColor(if (lucas >= item.price) Ui.INK else Ui.MUTED) }
                    setOnClickListener { buy(item, lucas) }
                })
            })
        })
        if (category == "datos") page.addView(text("💡 Los datos los prestan los celulares de tu isla que tienen \"Prestar Internet\" prendido: tus Lucas les pagan a ellos, a medida que usás.", 13f, Ui.MUTED).apply { setPadding(0, dp(8), 0, 0) })
        page.addView(title("Mis compras"))
        if (mine.isEmpty()) page.addView(text("Todavía no compraste nada.", 14f, Ui.MUTED))
        for (p in mine.reversed()) page.addView(card {
            addView(text("${p.item.name} · ${p.price} Lucas", 15f, bold = true))
            addView(text(day.format(Date(p.ts)) + " · anotado en la libreta", 12f, Ui.MUTED))
        })
    }

    }

    // Browsing goes through a VPN that only points apps to CHAMULLO's proxy: Android asks its owner once.
    private fun toggleBrowsing() {
        if (Hub.browsing) { startService(Intent(this, TunnelVpn::class.java).setAction(TunnelVpn.STOP)); handler.postDelayed({ render() }, 300); return }
        val ask = VpnService.prepare(this)
        if (ask != null) startActivityForResult(ask, VPN_OK) else onActivityResult(VPN_OK, RESULT_OK, null)
    }

    @Deprecated("Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != VPN_OK) return
        if (resultCode == RESULT_OK) { startService(Intent(this, TunnelVpn::class.java)); handler.postDelayed({ render() }, 500) }
        else toast("Sin ese permiso no se puede navegar con tus datos.")
    }

    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable { override fun run() { if (Hub.browsing) render(); handler.postDelayed(this, 3_000) } }

    override fun onResume() { super.onResume(); handler.postDelayed(refresh, 3_000) }

    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }

    private fun buy(item: Shop.Item, lucas: Long) {
        val seller = seller() ?: return
        if (lucas < item.price) { toast("Te faltan ${item.price - lucas} Lucas. Ganalas llevando cartas o prestando Internet."); return }
        AlertDialog.Builder(this)
            .setTitle("¿Comprar ${item.name}?")
            .setMessage("Cuesta ${item.price} Lucas. Te quedan ${lucas - item.price}.")
            .setPositiveButton("Comprar") { _, _ ->
                Hub.ask({ n -> n.spend(Shop.payee(item, seller), item.price, Shop.PREFIX + item.id) }) { ok ->
                    toast(if (ok) "✓ ${item.name}: queda anotado en la próxima página de la libreta." else "No se pudo: ¿te alcanzan las Lucas?")
                    render()
                }
            }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun size(mb: Int) = if (mb >= 1024) "%.1f GB".format(mb / 1024f) else "$mb MB"

    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show()

    companion object {
        const val CATALOG_URL = "https://nicoconvoz.github.io/chamullo-web/tienda.json"
        const val VPN_OK = 7
    }
}
