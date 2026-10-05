package ar.chamullo.app

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import ar.chamullo.app.Ui.card
import ar.chamullo.app.Ui.logo
import ar.chamullo.app.Ui.screen
import ar.chamullo.app.Ui.text
import ar.chamullo.app.Ui.title

/** Numbers for the field tests of Air Interface §10: what the radio can do and what it is doing. */
class DiagActivity : Activity() {
    private lateinit var radioInfo: TextView
    private lateinit var nodeInfo: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { refresh(); handler.postDelayed(this, 1000) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen {
            addView(logo())
            addView(title("Radio"))
            radioInfo = text("", 14f); addView(card { addView(radioInfo) })
            addView(title("Nodo"))
            nodeInfo = text("", 14f); addView(card { addView(nodeInfo) })
            addView(text("Para medir alcance: dos celulares con esta pantalla abierta, alejalos de a 10 m y anotá cuándo deja de aparecer el vecino.", 13f, Ui.MUTED))
        }
    }

    override fun onResume() { super.onResume(); handler.post(tick) }
    override fun onPause() { handler.removeCallbacks(tick); super.onPause() }

    private fun refresh() {
        val r = Hub.radio
        radioInfo.text = if (r == null) "El grito no está encendido." else listOf(
            "Bluetooth encendido: ${yes(r.enabled)}",
            "Gritos largos (extended advertising): ${yes(r.extended)}",
            "Largo alcance (Coded PHY): ${yes(r.coded)}",
            "Máximo por anuncio: ${r.maxAdvLen} bytes",
            "Tamaño de grito: ${r.maxFrame} bytes",
            "Gritos enviados: ${r.shouts}",
            "Gritos escuchados: ${r.heard}",
            "Último error: ${r.lastError ?: "ninguno"}"
        ).joinToString("\n")
        Hub.ask({ n -> Triple(n.neighbors().map { "${it.name.ifBlank { "?" }} (${if (it.coded) "largo" else "normal"}, hace ${(System.currentTimeMillis() - it.lastSeen) / 1000}s)" }, n.pocketCount(), n.shoutsSent) }) { (near, pockets, sent) ->
            nodeInfo.text = (listOf("Vecinos: ${near.size}") + near.map { "  · $it" } + listOf("Cartas en el bolsillo: $pockets", "Tramas encoladas en total: $sent")).joinToString("\n")
        }
    }

    private fun yes(b: Boolean) = if (b) "sí" else "no"
}
