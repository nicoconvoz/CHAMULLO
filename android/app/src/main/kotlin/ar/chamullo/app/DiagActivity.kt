package ar.chamullo.app

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import android.content.Intent
import ar.chamullo.app.Ui.button
import ar.chamullo.app.Ui.card
import ar.chamullo.app.Ui.logo
import ar.chamullo.app.Ui.screen
import ar.chamullo.app.Ui.text
import ar.chamullo.app.Ui.title
import ar.chamullo.core.toHex

/** Numbers for the field tests of Air Interface §10: what the radio can do and what it is doing. */
class DiagActivity : Activity() {
    private lateinit var radioInfo: TextView
    private lateinit var nodeInfo: TextView
    private lateinit var logView: TextView
    private var helloInfo = ""
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var bridgeInfo: TextView
    private lateinit var ledgerInfo: TextView
    private val tick = object : Runnable { override fun run() { refresh(); handler.postDelayed(this, 1000) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen {
            addView(logo())
            addView(title("Radio"))
            radioInfo = text("", 14f); addView(card { addView(radioInfo) })
            addView(title("Nodo"))
            nodeInfo = text("", 14f); addView(card { addView(nodeInfo) })
            addView(button("Prueba de velocidad en la isla (4 MB)") { (Hub.radios.firstOrNull { it is WifiIslands } as? WifiIslands)?.speedTest() })
            addView(button(if (Settings.bluetooth(this@DiagActivity)) "Apagar el Bluetooth de respaldo" else "Prender el Bluetooth de respaldo", primary = false) {
                Settings.setBluetooth(this@DiagActivity, !Settings.bluetooth(this@DiagActivity))
                stopService(Intent(this@DiagActivity, GritoService::class.java))
                startForegroundService(Intent(this@DiagActivity, GritoService::class.java))
                recreate()
            })
            addView(title("Puente por Internet"))
            addView(text("Donde las islas no llegan (un salto grande), tu Internet puede llevar la carta al otro lado. Usa tus datos: queda apagado si no lo prendés.", 13f, Ui.MUTED))
            addView(button(if (Settings.lendInternet(this@DiagActivity)) "Dejar de prestar Internet" else "Prestar Internet como puente", primary = false) {
                Settings.toggleLendInternet(this@DiagActivity)
                recreate()
            })
            bridgeInfo = text("", 14f); addView(card { addView(bridgeInfo) })
            addView(title("Libreta del pueblo"))
            addView(text("La libreta anota quién llevó cartas y reparte las Lucas. Hay una sola por pueblo, nacida de la firma del fundador de la red.", 13f, Ui.MUTED))
            ledgerInfo = text("", 14f); addView(card { addView(ledgerInfo) })
            addView(title("Caja negra"))
            addView(button("Compartir registro") {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "CHAMULLO ${WebVersion.installed(this@DiagActivity)}\n" + FieldLog.text()), "Mandar el registro"))
            })
            logView = text("", 11f, Ui.MUTED); addView(card { addView(logView) })
            addView(text("Para medir alcance: dos celulares con esta pantalla abierta, alejalos de a 10 m y anotá cuándo deja de aparecer el vecino.", 13f, Ui.MUTED))
        }
    }

    override fun onResume() { super.onResume(); handler.post(tick) }
    override fun onPause() { handler.removeCallbacks(tick); super.onPause() }

    private fun refresh() {
        Hub.ask({ n -> Triple(n.identity.nodeId.toHex(), n.ledger, n.available()) }) { (me, l, lucas) ->
            val founder = "Fundador de la red: ${Settings.FOUNDER.take(8)}${if (me == Settings.FOUNDER) " (sos vos)" else ""}"
            ledgerInfo.text = if (l == null) "Sin libreta todavía: espera la ubicación.\n$founder\nMi clave: $me"
            else "$founder\nPáginas: ${l.pages.size}\nRey: ${l.court().king?.toHex()?.take(8) ?: "—"}${if (l.court().nobles.isEmpty()) " (génesis)" else " · ${l.court().nobles.size} nobles"}\nMis Lucas: $lucas\nMi clave: $me"
        }
        val r = Hub.relay
        bridgeInfo.text = if (r == null) (if (Settings.lendInternet(this)) "Conectando con Nostr…" else "Apagado.") else
            "Prestando Internet por Nostr.\n  Relés conectados: ${r.connected()}\n  Llevé por Internet: ${r.sentBytes / 1024} KB\n  Último aviso: ${r.lastError ?: "ninguno"}"
        val radios = Hub.radios
        radioInfo.text = if (radios.isEmpty()) "El grito no está encendido." else radios.joinToString("\n\n") { r ->
            listOfNotNull(
                r.label,
                "  Lo tiene este celular: ${yes(r.supported)}",
                "  Encendido y gritando: ${yes(r.active)}",
                (r as? WifiRadio)?.let { "  Celulares encontrados: ${it.peers}" },
                (r as? WifiIslands)?.let { "  Isla: ${it.islandName ?: "ninguna todavía"}${if (it.host) " (soy el anfitrión, ${it.memberCount} miembros)" else ""}" },
                (r as? WifiIslands)?.let { "  Carteles vistos: ${it.cartelesSeen} · islas en la lista de Wi-Fi: ${it.scannedSeen}" },
                (r as? WifiIslands)?.let { "  Caños abiertos: ${it.peers}${it.ferrying?.let { f -> " · de ferry a ${f.take(6)}" } ?: ""}" },
                (r as? WifiIslands)?.let { "  Prueba de velocidad: ${it.lastSpeed ?: "sin hacer"}" },
                (r as? GritoRadio)?.let { "  Anuncios extendidos: ${yes(it.extended)} · largo alcance: ${yes(it.coded)}" },
                (r as? GritoRadio)?.takeIf { !it.extended }?.let { "  Megáfonos a la vez: ${it.megaphoneLimit}" },
                (r as? GritoRadio)?.let { "  Escuchando: ${yes(it.listening)} · cartas en cola: ${it.waiting}" },
                (r as? GritoRadio)?.let { "  Cartas completas armadas: ${it.assembled}" },
                (r as? GritoRadio)?.let { "  Último grito escuchado: " + if (it.lastHeardAt == 0L) "nunca" else "hace ${(System.currentTimeMillis() - it.lastHeardAt) / 1000} s" },
                "  Gritos que salieron: ${r.shouts}",
                "  Gritos escuchados: ${r.heard}",
                "  Último aviso: ${r.lastError ?: "ninguno"}"
            ).joinToString("\n")
        }
        Hub.ask({ n -> n.nearby().map { "${it.name.ifBlank { "?" }} (hace ${(System.currentTimeMillis() - it.lastSeen) / 1000}s)" } }) { hellos ->
            helloInfo = (listOf("Saludos escuchados: ${hellos.size}") + hellos.map { "  · $it" }).joinToString("\n")
        }
        Hub.ask({ n -> Triple(n.neighbors().map { "${it.name.ifBlank { "?" }} (${if (it.coded) "largo" else "normal"}, hace ${(System.currentTimeMillis() - it.lastSeen) / 1000}s)" }, n.pocketCount(), n.shoutsSent) }) { (near, pockets, sent) ->
            logView.text = FieldLog.last(25).reversed().joinToString("\n")
            nodeInfo.text = (listOf("Caramelos: ${Hub.node?.candies ?: 0}", helloInfo, "Vecinos con latido firmado: ${near.size}") + near.map { "  · $it" } + listOf("Cartas en el bolsillo: $pockets", "Tramas que preparó el nodo: $sent (salen solo con la radio encendida)")).joinToString("\n")
        }
    }

    private fun yes(b: Boolean) = if (b) "sí" else "no"
}
